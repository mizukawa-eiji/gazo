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
    fn list(&self, rel_dir: &str) -> std::result::Result<Vec<RemoteEntry>, String>;
    fn get(&self, rel_path: &str) -> std::result::Result<Vec<u8>, String>;
}

/// 同期結果の要約。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SyncSummary {
    pub downloaded: usize,
    pub deleted: usize,
}

#[derive(Debug, thiserror::Error)]
pub enum MirrorError {
    #[error("I/O エラー: {0}")]
    Io(#[from] std::io::Error),
    #[error("リモートアクセスに失敗しました: {0}")]
    Remote(String),
}

type Result<T> = std::result::Result<T, MirrorError>;

/// 同期状態の 1 ファイル分（etag / サイズ / Last-Modified 生文字列）。
#[derive(Debug, Clone, PartialEq, Eq)]
struct StateEntry {
    etag: Option<String>,
    size: u64,
    last_modified: String,
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
            state.insert(
                rel.clone(),
                StateEntry {
                    etag: entry.etag.clone(),
                    size: data.len() as u64,
                    last_modified: entry.last_modified.clone().unwrap_or_default(),
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
        // フォールバック: サイズ → Last-Modified。
        match std::fs::metadata(&local) {
            Ok(m) if m.len() != prev.size => return true,
            Err(_) => return true,
            _ => {}
        }
        let lm = remote.last_modified.clone().unwrap_or_default();
        if !lm.is_empty() && lm != prev.last_modified {
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
            out.insert(
                rel.to_string(),
                StateEntry {
                    etag,
                    size,
                    last_modified: parts[2].to_string(),
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
                s.last_modified
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
