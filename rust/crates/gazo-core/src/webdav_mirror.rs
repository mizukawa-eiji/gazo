//! WebDAV リモートとローカルミラーの差分ダウンロード同期。
//!
//! Java 版 `WebDavVaultStorage.syncDownFromRemote` のダウンロード側を移植したもの。
//! 暗号化された Vault 構造（`vault.cryptomator`/`masterkey.cryptomator`/`d/…`）を
//! `~/.gazo/webdav-cache/<hash>` にミラーし、その平文ビューを後段（VaultStorage）で開く。
//!
//! HTTP は [`gazo_webdav::WebDavClient`] に委譲するが、同期アルゴリズム（差分判定・削除反映）
//! の検証のため [`RemoteSource`] で抽象化し、インメモリの偽リモートでテストできるようにする。

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use gazo_webdav::{canonical_etag, normalize_path, RemoteEntry, WebDavClient};

use crate::properties::{self, PropMap};
use crate::settings::home_dir;

/// 差分同期のためにリモートを抽象化する。`list` の `dir` は「リモートルート相対」パス
/// （空文字でルート）。返す [`RemoteEntry`] の `path` はサーバ上の絶対パスでよい
/// （ミラー側でルート相対へ変換する）。
pub trait RemoteSource {
    fn list(&self, request_path: &str) -> std::result::Result<Vec<RemoteEntry>, String>;
    fn get(&self, request_path: &str) -> std::result::Result<Vec<u8>, String>;
    /// アップロード。成功時に新しい ETag があれば返す。
    fn put(&self, request_path: &str, data: &[u8]) -> std::result::Result<Option<String>, String>;
    fn delete(&self, request_path: &str) -> std::result::Result<(), String>;
    /// パス上の各コレクションを作成する（`mkdir -p` 相当）。
    fn ensure_dir(&self, request_path: &str) -> std::result::Result<(), String>;
}

/// 同期結果の要約。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SyncSummary {
    pub downloaded: usize,
    pub uploaded: usize,
    pub deleted: usize,
    /// ローカル・リモート双方が前回同期後に変化したファイル（要解決）。
    /// 競合解決（dHash 等）は後続フェーズで対応するため、ここでは記録のみ。
    pub conflicts: Vec<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum MirrorError {
    #[error("I/O エラー: {0}")]
    Io(#[from] std::io::Error),
    #[error("リモートアクセスに失敗しました: {0}")]
    Remote(String),
}

type Result<T> = std::result::Result<T, MirrorError>;

/// 同期状態の 1 ファイル分。`size`/`mtime_millis` は**ローカルミラー上の**値、
/// `etag` は最後に確認した**リモート**の ETag。Java 版 `SyncStateEntry` と同義。
#[derive(Debug, Clone, PartialEq, Eq)]
struct StateEntry {
    etag: Option<String>,
    size: u64,
    mtime_millis: i64,
}

/// WebDAV ミラー。`R` は差分同期に使うリモート実装。
pub struct WebDavMirror<R: RemoteSource> {
    source: R,
    remote_root: String,
    mirror_dir: PathBuf,
    state_file: PathBuf,
}

impl WebDavMirror<WebDavClientSource> {
    /// 実 WebDAV サーバに対するミラーを構築する。ミラー位置・状態ファイルは
    /// Java 版と同じく接続情報の SHA-256 から決まる（`~/.gazo/webdav-cache/<hash>`）。
    pub fn connect(
        endpoint: &str,
        base_path: &str,
        username: &str,
        password: &str,
    ) -> Result<Self> {
        let client =
            WebDavClient::new(endpoint, username, password).map_err(|e| MirrorError::Remote(e.to_string()))?;
        let remote_root = normalize_path(base_path);
        let key = format!("{endpoint}|{base_path}|{username}");
        let hash = sha256_hex(&key);
        let home = home_dir();
        let mirror_dir = home.join(".gazo").join("webdav-cache").join(&hash);
        let state_file = home
            .join(".gazo")
            .join("webdav-cache-meta")
            .join(&hash)
            .join("sync-state.properties");
        Ok(WebDavMirror {
            source: WebDavClientSource { client },
            remote_root,
            mirror_dir,
            state_file,
        })
    }
}

impl<R: RemoteSource> WebDavMirror<R> {
    /// 任意のリモート実装でミラーを構築する（テスト・カスタム用）。
    pub fn with_source(
        source: R,
        remote_root: &str,
        mirror_dir: PathBuf,
        state_file: PathBuf,
    ) -> Self {
        WebDavMirror {
            source,
            remote_root: normalize_path(remote_root),
            mirror_dir,
            state_file,
        }
    }

    /// ミラーのローカルパス（解錠時に Vault として開く対象）。
    pub fn mirror_dir(&self) -> &Path {
        &self.mirror_dir
    }

    /// リモートとの差分のみをローカルミラーへ反映する。
    ///
    /// - リモートにあってローカルに無い/変わったファイルをダウンロード。
    /// - 状態に残っていてリモートから消えたファイルをローカルから削除。
    /// - 状態に無い（ローカルのみ）ファイルは未アップロードとみなして残す。
    pub fn sync_down(&self) -> Result<SyncSummary> {
        std::fs::create_dir_all(&self.mirror_dir)?;

        // リモート全件を再帰取得（ルート相対パス -> エントリ）。
        let mut remote_files: BTreeMap<String, RemoteEntry> = BTreeMap::new();
        self.walk("", &mut remote_files)?;

        let mut state = self.load_state();
        let mut summary = SyncSummary::default();

        // リモートから消えたものをローカル・状態から削除。
        let removed: Vec<String> = state
            .keys()
            .filter(|rel| !remote_files.contains_key(*rel))
            .cloned()
            .collect();
        for rel in removed {
            let local = self.mirror_dir.join(&rel);
            let _ = std::fs::remove_file(&local); // best effort
            state.remove(&rel);
            summary.deleted += 1;
        }

        // 差分をダウンロード。
        for (rel, entry) in &remote_files {
            if !self.needs_download(rel, entry, state.get(rel)) {
                continue;
            }
            let data = self
                .source
                .get(&join_rel(&self.remote_root, rel))
                .map_err(MirrorError::Remote)?;
            self.write_atomic(rel, &data)?;
            let (size, mtime_millis) = self.local_meta(rel);
            state.insert(
                rel.clone(),
                StateEntry {
                    etag: entry.etag.clone(),
                    size,
                    mtime_millis,
                },
            );
            summary.downloaded += 1;
        }

        self.save_state(&state)?;
        Ok(summary)
    }

    /// リモートを再帰的に走査し、ルート相対パス -> 非ディレクトリエントリを集める。
    fn walk(&self, rel_dir: &str, out: &mut BTreeMap<String, RemoteEntry>) -> Result<()> {
        let request = join_rel(&self.remote_root, rel_dir);
        let entries = self.source.list(&request).map_err(MirrorError::Remote)?;
        for entry in entries {
            let Some(rel) = self.relative_to_root(&entry.path) else {
                continue;
            };
            if rel.is_empty() {
                continue;
            }
            if entry.is_dir {
                self.walk(&rel, out)?;
            } else {
                out.insert(rel, entry);
            }
        }
        Ok(())
    }

    /// ローカルへの反映が必要か判定する。
    fn needs_download(&self, rel: &str, remote: &RemoteEntry, prev: Option<&StateEntry>) -> bool {
        let local = self.mirror_dir.join(rel);
        let Some(prev) = prev else {
            return true;
        };
        if !local.is_file() {
            return true;
        }
        // etag が両端で取れていれば canonical 比較。
        let remote_etag = canonical_etag(remote.etag.as_deref());
        let prev_etag = canonical_etag(prev.etag.as_deref());
        if let (Some(r), Some(p)) = (&remote_etag, &prev_etag) {
            return r != p;
        }
        // フォールバック（etag が取れないサーバ向け）: リモートのサイズが分かるなら比較。
        if remote.content_length >= 0 && remote.content_length as u64 != prev.size {
            return true;
        }
        false
    }

    /// `rel` をサーバ絶対パスから「リモートルート相対」へ変換する。
    /// サーバが endpoint のパス接頭辞を含む href を返しても、ルート位置を探して相対化する。
    fn relative_to_root(&self, full: &str) -> Option<String> {
        let full = normalize_path(full);
        if full == self.remote_root {
            return Some(String::new());
        }
        let needle = format!("{}/", self.remote_root);
        let idx = full.find(&needle)?;
        Some(full[idx + needle.len()..].to_string())
    }

    /// tmp 書き込み + リネームで原子的に置き換える（読み取り中の中途半端な内容を避ける）。
    fn write_atomic(&self, rel: &str, data: &[u8]) -> Result<()> {
        let target = self.mirror_dir.join(rel);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let tmp = target.with_extension(format!("gazo-dl-{}", uuid::Uuid::new_v4().simple()));
        std::fs::write(&tmp, data)?;
        match std::fs::rename(&tmp, &target) {
            Ok(()) => Ok(()),
            Err(e) => {
                let _ = std::fs::remove_file(&tmp);
                Err(MirrorError::Io(e))
            }
        }
    }

    /// ローカルミラー上の (サイズ, mtime millis) を返す。取れなければ (0,0)。
    fn local_meta(&self, rel: &str) -> (u64, i64) {
        let p = self.mirror_dir.join(rel);
        match std::fs::metadata(&p) {
            Ok(m) => {
                let size = m.len();
                let mtime = m
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as i64)
                    .unwrap_or(0);
                (size, mtime)
            }
            Err(_) => (0, 0),
        }
    }

    /// ローカルミラーの変更をリモートへ反映する（Java `syncUpToRemote` 相当）。
    ///
    /// - 新規/変更されたローカルファイルを PUT（必要なフォルダは先に MKCOL）。
    /// - リモートにのみ存在するファイル/フォルダを DELETE。
    /// - ローカル・リモート双方が変化したファイルは競合として記録（解決は後続フェーズ）。
    ///   ただし vault.cryptomator 等の小メタファイルは、実バイトが同一なら状態だけ合わせる。
    pub fn sync_up(&self) -> Result<SyncSummary> {
        std::fs::create_dir_all(&self.mirror_dir)?;
        self.source
            .ensure_dir(&self.remote_root)
            .map_err(MirrorError::Remote)?;

        let mut remote: BTreeMap<String, RemoteEntry> = BTreeMap::new();
        self.walk("", &mut remote)?;
        let (local_dirs, local_files) = self.local_snapshot()?;
        let mut state = self.load_state();
        let mut summary = SyncSummary::default();

        // 不足しているリモートディレクトリを浅い順に作成。
        let mut dirs: Vec<&String> = local_dirs.iter().collect();
        dirs.sort_by_key(|d| path_depth(d));
        for rel in dirs {
            if rel.is_empty() {
                continue;
            }
            let exists_as_dir = remote.get(rel).map(|e| e.is_dir).unwrap_or(false);
            if !exists_as_dir {
                self.source
                    .ensure_dir(&join_rel(&self.remote_root, rel))
                    .map_err(MirrorError::Remote)?;
            }
        }

        // ローカルファイルをアップロード。
        for (rel, (size, mtime)) in &local_files {
            let prev = state.get(rel);
            let remote_entry = remote.get(rel);
            let local_changed = local_changed(*size, *mtime, prev);
            let remote_changed = remote_changed(remote_entry, prev);

            // 双方未変更でリモートにファイルがある → 同期済み。
            if !local_changed && !remote_changed && remote_entry.map(|e| !e.is_dir).unwrap_or(false) {
                continue;
            }
            if local_changed && remote_changed {
                if self.try_resolve_twin(rel, *size, *mtime, remote_entry, &mut state)? {
                    continue;
                }
                summary.conflicts.push(rel.clone());
                continue; // 非破壊: 解決は後続フェーズ
            }
            if !local_changed && remote_changed {
                continue; // リモートが新しい。下り同期に任せる。
            }
            // リモートが同名ディレクトリなら消してからアップロード。
            if remote_entry.map(|e| e.is_dir).unwrap_or(false) {
                self.source
                    .delete(&join_rel(&self.remote_root, rel))
                    .map_err(MirrorError::Remote)?;
            }
            let data = std::fs::read(self.mirror_dir.join(rel))?;
            let new_etag = self
                .source
                .put(&join_rel(&self.remote_root, rel), &data)
                .map_err(MirrorError::Remote)?;
            state.insert(
                rel.clone(),
                StateEntry {
                    etag: new_etag,
                    size: *size,
                    mtime_millis: *mtime,
                },
            );
            summary.uploaded += 1;
        }

        // リモートのみのファイルを削除。
        for (rel, entry) in &remote {
            if entry.is_dir || rel.is_empty() {
                continue;
            }
            if !local_files.contains_key(rel) {
                self.source
                    .delete(&join_rel(&self.remote_root, rel))
                    .map_err(MirrorError::Remote)?;
                state.remove(rel);
                summary.deleted += 1;
            }
        }
        // リモートのみのディレクトリを深い順に削除。
        let mut remote_only_dirs: Vec<&String> = remote
            .iter()
            .filter(|(rel, e)| e.is_dir && !rel.is_empty() && !local_dirs.contains(*rel))
            .map(|(rel, _)| rel)
            .collect();
        remote_only_dirs.sort_by_key(|d| std::cmp::Reverse(path_depth(d)));
        for rel in remote_only_dirs {
            self.source
                .delete(&join_rel(&self.remote_root, rel))
                .map_err(MirrorError::Remote)?;
            summary.deleted += 1;
        }

        // ローカルに無い状態エントリを掃除。
        state.retain(|rel, _| local_files.contains_key(rel));
        self.save_state(&state)?;
        Ok(summary)
    }

    /// ローカルミラーを走査し (ディレクトリ rel 集合, ファイル rel -> (size,mtime)) を返す。
    fn local_snapshot(
        &self,
    ) -> Result<(std::collections::BTreeSet<String>, BTreeMap<String, (u64, i64)>)> {
        let mut dirs = std::collections::BTreeSet::new();
        let mut files = BTreeMap::new();
        walk_local(&self.mirror_dir, &self.mirror_dir, &mut dirs, &mut files)?;
        Ok((dirs, files))
    }

    /// vault.cryptomator 等の小メタファイルで「両方更新」のとき、実バイトが同一なら
    /// 競合にせず状態だけ合わせる（Java `tryResolveTwinChangeByContentEquality` 相当）。
    fn try_resolve_twin(
        &self,
        rel: &str,
        size: u64,
        mtime: i64,
        remote: Option<&RemoteEntry>,
        state: &mut BTreeMap<String, StateEntry>,
    ) -> Result<bool> {
        let Some(remote) = remote else {
            return Ok(false);
        };
        if remote.is_dir || !should_compare_bytes(rel, size) {
            return Ok(false);
        }
        let local_bytes = std::fs::read(self.mirror_dir.join(rel))?;
        let remote_bytes = match self.source.get(&join_rel(&self.remote_root, rel)) {
            Ok(b) => b,
            Err(_) => return Ok(false),
        };
        if local_bytes != remote_bytes {
            return Ok(false);
        }
        state.insert(
            rel.to_string(),
            StateEntry {
                etag: remote.etag.clone(),
                size,
                mtime_millis: mtime,
            },
        );
        Ok(true)
    }

    fn load_state(&self) -> BTreeMap<String, StateEntry> {
        let mut out = BTreeMap::new();
        let Ok(bytes) = std::fs::read(&self.state_file) else {
            return out;
        };
        let props = properties::load(&bytes);
        for (key, value) in &props {
            let Some(rel) = key.strip_prefix("file.") else {
                continue;
            };
            let parts: Vec<&str> = value.splitn(3, '|').collect();
            if parts.len() != 3 {
                continue;
            }
            let etag = if parts[0].is_empty() {
                None
            } else {
                Some(parts[0].to_string())
            };
            let size = parts[1].parse::<u64>().unwrap_or(0);
            let mtime_millis = parts[2].parse::<i64>().unwrap_or(0);
            out.insert(
                rel.to_string(),
                StateEntry {
                    etag,
                    size,
                    mtime_millis,
                },
            );
        }
        out
    }

    fn save_state(&self, state: &BTreeMap<String, StateEntry>) -> Result<()> {
        if let Some(parent) = self.state_file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut props = PropMap::new();
        for (rel, s) in state {
            let value = format!(
                "{}|{}|{}",
                s.etag.clone().unwrap_or_default(),
                s.size,
                s.mtime_millis
            );
            props.insert(format!("file.{rel}"), value);
        }
        let text = properties::store(&props, Some("webdav sync state"));
        std::fs::write(&self.state_file, text.as_bytes())?;
        Ok(())
    }
}

/// `remote_root` とルート相対パスを結合する。
fn join_rel(remote_root: &str, rel: &str) -> String {
    if rel.is_empty() {
        remote_root.to_string()
    } else {
        format!("{remote_root}/{rel}")
    }
}

/// パスの深さ（`/` の数 + 1）。ディレクトリ作成/削除の順序付け用。
fn path_depth(rel: &str) -> usize {
    if rel.is_empty() {
        0
    } else {
        rel.matches('/').count() + 1
    }
}

/// ローカルファイルが前回同期後に変わったか（新規 or サイズ/mtime 差）。
fn local_changed(size: u64, mtime: i64, prev: Option<&StateEntry>) -> bool {
    match prev {
        None => true,
        Some(p) => size != p.size || mtime != p.mtime_millis,
    }
}

/// リモートが前回同期後に変わったか。前回記録が無いときは「変わった」とみなさない
/// （初回 push で誤競合にしないため）。判定は etag 優先、取れなければ変化なし扱い。
fn remote_changed(remote: Option<&RemoteEntry>, prev: Option<&StateEntry>) -> bool {
    let Some(remote) = remote else {
        return prev.is_some();
    };
    if remote.is_dir {
        return false;
    }
    let Some(prev) = prev else {
        return false;
    };
    match (canonical_etag(remote.etag.as_deref()), canonical_etag(prev.etag.as_deref())) {
        (Some(r), Some(p)) => r != p,
        _ => false,
    }
}

/// バイト比較で競合回避を試みる対象か（5MB 以下の Cryptomator メタファイル）。
fn should_compare_bytes(rel: &str, size: u64) -> bool {
    if size > 5_000_000 {
        return false;
    }
    let name = rel.rsplit('/').next().unwrap_or(rel);
    name.ends_with(".bkup") || name == "vault.cryptomator" || name == "masterkey.cryptomator"
}

/// ローカルディレクトリを再帰走査し、相対パスのディレクトリ集合とファイル情報を集める。
fn walk_local(
    root: &Path,
    dir: &Path,
    dirs: &mut std::collections::BTreeSet<String>,
    files: &mut BTreeMap<String, (u64, i64)>,
) -> Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        let rel = path
            .strip_prefix(root)
            .map(|p| p.to_string_lossy().replace('\\', "/"))
            .unwrap_or_default();
        let ft = entry.file_type()?;
        if ft.is_dir() {
            dirs.insert(rel.clone());
            walk_local(root, &path, dirs, files)?;
        } else if ft.is_file() {
            let meta = entry.metadata()?;
            let mtime = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);
            files.insert(rel, (meta.len(), mtime));
        }
    }
    Ok(())
}

fn sha256_hex(value: &str) -> String {
    let mut h = Sha256::new();
    h.update(value.as_bytes());
    let digest = h.finalize();
    let mut s = String::with_capacity(digest.len() * 2);
    for b in digest {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// 実 WebDAV クライアントを [`RemoteSource`] として使うアダプタ。
/// 渡されるパスは endpoint 相対（= リモートルート起点）のサーバパス。
pub struct WebDavClientSource {
    client: WebDavClient,
}

impl RemoteSource for WebDavClientSource {
    fn list(&self, request_path: &str) -> std::result::Result<Vec<RemoteEntry>, String> {
        self.client.propfind(request_path, 1).map_err(|e| e.to_string())
    }
    fn get(&self, request_path: &str) -> std::result::Result<Vec<u8>, String> {
        self.client.get(request_path).map_err(|e| e.to_string())
    }
    fn put(&self, request_path: &str, data: &[u8]) -> std::result::Result<Option<String>, String> {
        self.client.put(request_path, data).map_err(|e| e.to_string())
    }
    fn delete(&self, request_path: &str) -> std::result::Result<(), String> {
        self.client.delete(request_path).map_err(|e| e.to_string())
    }
    fn ensure_dir(&self, request_path: &str) -> std::result::Result<(), String> {
        self.client.mkcol_all(request_path).map_err(|e| e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::collections::HashMap;

    /// インメモリの偽リモート。パスはルート `/v` 配下のサーバ絶対パスを模す。
    struct FakeRemote {
        // path -> (is_dir, etag, content)
        nodes: RefCell<HashMap<String, FakeNode>>,
        get_count: RefCell<usize>,
        put_seq: RefCell<u64>,
    }
    #[derive(Clone)]
    struct FakeNode {
        is_dir: bool,
        etag: String,
        content: Vec<u8>,
        last_modified: String,
    }

    impl FakeRemote {
        fn new() -> Self {
            FakeRemote {
                nodes: RefCell::new(HashMap::new()),
                get_count: RefCell::new(0),
                put_seq: RefCell::new(0),
            }
        }
        fn dir(&self, path: &str) {
            self.nodes.borrow_mut().insert(
                normalize_path(path),
                FakeNode { is_dir: true, etag: String::new(), content: vec![], last_modified: String::new() },
            );
        }
        fn file(&self, path: &str, etag: &str, content: &[u8]) {
            self.nodes.borrow_mut().insert(
                normalize_path(path),
                FakeNode {
                    is_dir: false,
                    etag: etag.to_string(),
                    content: content.to_vec(),
                    last_modified: "Tue, 01 Jan 2030 00:00:00 GMT".to_string(),
                },
            );
        }
    }

    impl RemoteSource for FakeRemote {
        fn list(&self, rel_dir: &str) -> std::result::Result<Vec<RemoteEntry>, String> {
            let dir = normalize_path(rel_dir);
            let prefix = if dir == "/" { "/".to_string() } else { format!("{dir}/") };
            let mut out = Vec::new();
            for (path, node) in self.nodes.borrow().iter() {
                if path == &dir {
                    continue;
                }
                if let Some(rest) = path.strip_prefix(&prefix) {
                    // 直下のみ（さらに / を含まない）。
                    if !rest.contains('/') {
                        out.push(RemoteEntry {
                            path: path.clone(),
                            is_dir: node.is_dir,
                            content_length: node.content.len() as i64,
                            last_modified: Some(node.last_modified.clone()),
                            etag: Some(node.etag.clone()),
                        });
                    }
                }
            }
            Ok(out)
        }
        fn get(&self, rel_path: &str) -> std::result::Result<Vec<u8>, String> {
            *self.get_count.borrow_mut() += 1;
            self.nodes
                .borrow()
                .get(&normalize_path(rel_path))
                .map(|n| n.content.clone())
                .ok_or_else(|| format!("not found: {rel_path}"))
        }
        fn put(&self, rel_path: &str, data: &[u8]) -> std::result::Result<Option<String>, String> {
            let mut seq = self.put_seq.borrow_mut();
            *seq += 1;
            let etag = format!("\"put{}\"", *seq);
            self.nodes.borrow_mut().insert(
                normalize_path(rel_path),
                FakeNode {
                    is_dir: false,
                    etag: etag.clone(),
                    content: data.to_vec(),
                    last_modified: "Tue, 01 Jan 2030 00:00:00 GMT".to_string(),
                },
            );
            Ok(Some(etag))
        }
        fn delete(&self, rel_path: &str) -> std::result::Result<(), String> {
            let p = normalize_path(rel_path);
            let prefix = format!("{p}/");
            self.nodes
                .borrow_mut()
                .retain(|k, _| k != &p && !k.starts_with(&prefix));
            Ok(())
        }
        fn ensure_dir(&self, rel_path: &str) -> std::result::Result<(), String> {
            self.nodes.borrow_mut().entry(normalize_path(rel_path)).or_insert(FakeNode {
                is_dir: true,
                etag: String::new(),
                content: vec![],
                last_modified: String::new(),
            });
            Ok(())
        }
    }

    fn mirror(
        remote: FakeRemote,
        dir: &Path,
    ) -> WebDavMirror<FakeRemote> {
        WebDavMirror::with_source(
            remote,
            "/v",
            dir.join("mirror"),
            dir.join("meta").join("sync-state.properties"),
        )
    }

    #[test]
    fn full_then_incremental_sync() {
        let tmp = tempfile::tempdir().unwrap();
        let remote = FakeRemote::new();
        remote.dir("/v");
        remote.dir("/v/d");
        remote.file("/v/vault.cryptomator", "e-vault", b"VAULT");
        remote.file("/v/d/a.c9r", "e-a", b"AAAA");

        let m = mirror(remote, tmp.path());

        // 初回: 2 ファイルをダウンロード。
        let s1 = m.sync_down().unwrap();
        assert_eq!(s1.downloaded, 2);
        assert_eq!(s1.deleted, 0);
        assert_eq!(
            std::fs::read(m.mirror_dir().join("d/a.c9r")).unwrap(),
            b"AAAA"
        );
        assert_eq!(
            std::fs::read(m.mirror_dir().join("vault.cryptomator")).unwrap(),
            b"VAULT"
        );

        // 2 回目: etag 不変 → 何もダウンロードしない。
        let before = *m.source.get_count.borrow();
        let s2 = m.sync_down().unwrap();
        assert_eq!(s2.downloaded, 0);
        assert_eq!(*m.source.get_count.borrow(), before);

        // a.c9r が変更された → それだけ再ダウンロード。
        m.source.file("/v/d/a.c9r", "e-a2", b"BBBB");
        let s3 = m.sync_down().unwrap();
        assert_eq!(s3.downloaded, 1);
        assert_eq!(std::fs::read(m.mirror_dir().join("d/a.c9r")).unwrap(), b"BBBB");

        // リモートから a.c9r が消えた → ローカルからも削除。
        m.source.nodes.borrow_mut().remove("/v/d/a.c9r");
        let s4 = m.sync_down().unwrap();
        assert_eq!(s4.deleted, 1);
        assert!(!m.mirror_dir().join("d/a.c9r").exists());
        assert!(m.mirror_dir().join("vault.cryptomator").exists());
    }

    #[test]
    fn upload_new_and_changed_then_delete_remote_only() {
        let tmp = tempfile::tempdir().unwrap();
        let remote = FakeRemote::new();
        remote.dir("/v"); // 空の Vault（ルートのみ）
        let m = mirror(remote, tmp.path());

        // ローカルミラーにファイルを用意（新規作成を模す）。
        std::fs::create_dir_all(m.mirror_dir().join("d")).unwrap();
        std::fs::write(m.mirror_dir().join("vault.cryptomator"), b"VAULT").unwrap();
        std::fs::write(m.mirror_dir().join("d/a.c9r"), b"AAAA").unwrap();

        // 初回アップロード: dir 作成 + 2 ファイル PUT。
        let s1 = m.sync_up().unwrap();
        assert_eq!(s1.uploaded, 2);
        assert!(s1.conflicts.is_empty());
        assert_eq!(m.source.get(&"/v/d/a.c9r".to_string()).unwrap(), b"AAAA");

        // 2 回目: 変更なし → アップロードなし。
        let s2 = m.sync_up().unwrap();
        assert_eq!(s2.uploaded, 0);
        assert_eq!(s2.deleted, 0);

        // ローカルを変更 → 1 件だけ再アップロード。
        std::fs::write(m.mirror_dir().join("d/a.c9r"), b"BBBBBB").unwrap();
        let s3 = m.sync_up().unwrap();
        assert_eq!(s3.uploaded, 1);
        assert_eq!(m.source.get(&"/v/d/a.c9r".to_string()).unwrap(), b"BBBBBB");

        // ローカルから削除 → リモートからも削除。
        std::fs::remove_file(m.mirror_dir().join("d/a.c9r")).unwrap();
        let s4 = m.sync_up().unwrap();
        assert_eq!(s4.deleted, 1);
        assert!(m.source.nodes.borrow().get("/v/d/a.c9r").is_none());
    }

    #[test]
    fn twin_change_on_metafile_resolved_by_content_equality() {
        let tmp = tempfile::tempdir().unwrap();
        let remote = FakeRemote::new();
        remote.dir("/v");
        let m = mirror(remote, tmp.path());
        std::fs::create_dir_all(m.mirror_dir()).unwrap();
        std::fs::write(m.mirror_dir().join("vault.cryptomator"), b"SAME").unwrap();
        m.sync_up().unwrap();

        // リモート etag だけ外部要因で変わったと仮定（中身は同一 SAME）。
        m.source.file("/v/vault.cryptomator", "\"changed-remote\"", b"SAME");
        // ローカルも mtime を更新（内容は同じ）。
        std::fs::write(m.mirror_dir().join("vault.cryptomator"), b"SAME").unwrap();

        let s = m.sync_up().unwrap();
        // 実バイト同一なので競合にならず、アップロードもしない。
        assert!(s.conflicts.is_empty(), "should not conflict: {:?}", s.conflicts);
        assert_eq!(s.uploaded, 0);
    }

    #[test]
    fn twin_change_on_data_file_is_recorded_as_conflict() {
        let tmp = tempfile::tempdir().unwrap();
        let remote = FakeRemote::new();
        remote.dir("/v");
        let m = mirror(remote, tmp.path());
        std::fs::create_dir_all(m.mirror_dir()).unwrap();
        std::fs::write(m.mirror_dir().join("d-data.c9r"), b"LOCAL1").unwrap();
        m.sync_up().unwrap();

        // リモートが外部で変化（中身も etag も別物）。
        m.source.file("/v/d-data.c9r", "\"remote2\"", b"REMOTE2");
        // ローカルも変化。
        std::fs::write(m.mirror_dir().join("d-data.c9r"), b"LOCAL3xx").unwrap();

        let s = m.sync_up().unwrap();
        assert_eq!(s.conflicts, vec!["d-data.c9r".to_string()]);
        assert_eq!(s.uploaded, 0);
        // 非破壊: リモートは上書きされていない。
        assert_eq!(m.source.get(&"/v/d-data.c9r".to_string()).unwrap(), b"REMOTE2");
    }

    #[test]
    fn local_only_file_is_preserved() {
        let tmp = tempfile::tempdir().unwrap();
        let remote = FakeRemote::new();
        remote.dir("/v");
        remote.file("/v/vault.cryptomator", "e1", b"V");
        let m = mirror(remote, tmp.path());
        m.sync_down().unwrap();

        // 状態に無いローカルファイル（未アップロードの新規）を置く。
        std::fs::write(m.mirror_dir().join("local-new.txt"), b"x").unwrap();
        let s = m.sync_down().unwrap();
        // リモートに無いが状態にも無いので削除されない。
        assert_eq!(s.deleted, 0);
        assert!(m.mirror_dir().join("local-new.txt").exists());
    }
}
