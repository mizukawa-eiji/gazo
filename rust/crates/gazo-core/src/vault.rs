//! `oxcrypt-core` の `VaultOperations` をラップし、Gazo のドメイン操作
//! （画像取り込み・サムネイル・タグ）を平文パスベースで提供する。
//!
//! Java 版は Cryptomator CryptoFS を OS にマウントして `java.nio` で操作するが、
//! こちらはマウント不要の `VaultOperations` API（`write_by_path` 等）を直接使う。

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use oxcrypt_core::crypto::keys::MasterKey;
use oxcrypt_core::vault::{create_masterkey_file, DirId, VaultOperations};
use serde::Serialize;

use crate::media::is_visible_user_media_name;
use crate::properties::{self, PropMap};
use crate::tags;
use crate::thumbnail::make_thumbnail_jpeg;

/// Vault 内のディレクトリ・メタファイル名（Java 版 `GazoVaultService` と一致）。
const IMAGES_DIR: &str = "images";
const VIDEOS_DIR: &str = "videos";
const THUMBNAILS_DIR: &str = "thumbnails";
const TAGS_FILE: &str = ".gazo-tags.properties";
const THUMBNAIL_MAX_EDGE: u32 = 640;

/// Java/Cryptomator 標準のマスターキー配置（Vault ルート直下）と、それを指す JWT の kid。
const MASTERKEY_FILE: &str = "masterkey.cryptomator";
const VAULT_CONFIG_FILE: &str = "vault.cryptomator";
const MASTERKEY_KID: &str = "masterkeyfile:masterkey.cryptomator";
/// Cryptomator 既定のファイル名短縮しきい値（oxcrypt の DEFAULT_SHORTENING_THRESHOLD と同値）。
const SHORTENING_THRESHOLD: i32 = 220;

/// vault.cryptomator(JWT) の claims。Cryptomator 仕様に合わせ camelCase で出力する。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VaultClaims {
    format: i32,
    shortening_threshold: i32,
    jti: String,
    cipher_combo: String,
}

/// gazo-core のエラー型。
#[derive(Debug, thiserror::Error)]
pub enum GazoError {
    #[error("I/O エラー: {0}")]
    Io(#[from] std::io::Error),
    #[error("登録できないファイル名です（先頭が「.」のファイルは不可）: {0}")]
    InvalidName(String),
    #[error("アルバム操作に失敗しました: {0}")]
    Vault(String),
}

pub type Result<T> = std::result::Result<T, GazoError>;

/// 解錠済みの Vault に対する操作ハンドル。
pub struct Vault {
    ops: VaultOperations,
    #[allow(dead_code)]
    root: PathBuf,
}

impl Vault {
    /// 指定パスに Cryptomator Vault（`vault.cryptomator`）が存在するか。
    pub fn vault_exists(vault_path: &Path) -> bool {
        vault_path.join("vault.cryptomator").is_file()
    }

    /// 新規 Vault を作成する（Java/Cryptomator 互換のルート直下マスターキー配置）。
    ///
    /// 公式 Cryptomator と同じく `masterkey.cryptomator` を Vault ルートに置き、
    /// `vault.cryptomator`(JWT) の kid を `masterkeyfile:masterkey.cryptomator` にする。
    /// oxcrypt の `VaultCreator` は masterkey を `masterkey/` サブフォルダに作るため、
    /// ここでは JWT を自前で署名して標準レイアウトを実現する。
    ///
    /// 作成後そのまま解錠した [`Vault`] を返す（同時に書いた構成の妥当性も検証される）。
    pub fn create(vault_path: &Path, passphrase: &str) -> Result<Self> {
        if vault_path.join(VAULT_CONFIG_FILE).exists() {
            return Err(GazoError::Vault(format!(
                "既にアルバムが存在します: {}",
                vault_path.display()
            )));
        }
        std::fs::create_dir_all(vault_path)?;

        let master_key = MasterKey::random().map_err(|e| GazoError::Vault(e.to_string()))?;

        // vault.cryptomator（ルートの masterkey を指す kid で署名）。
        let claims = VaultClaims {
            format: 8,
            shortening_threshold: SHORTENING_THRESHOLD,
            jti: uuid::Uuid::new_v4().to_string(),
            cipher_combo: "SIV_GCM".to_string(),
        };
        let mut header = jsonwebtoken::Header::new(jsonwebtoken::Algorithm::HS256);
        header.kid = Some(MASTERKEY_KID.to_string());
        let encoding_key = master_key
            .create_jwt_encoding_key()
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        let jwt = jsonwebtoken::encode(&header, &claims, &encoding_key)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        std::fs::write(vault_path.join(VAULT_CONFIG_FILE), jwt)?;

        // masterkey.cryptomator をルート直下に書く（Java/Cryptomator 標準）。
        let masterkey_content =
            create_masterkey_file(&master_key, passphrase).map_err(|e| GazoError::Vault(e.to_string()))?;
        std::fs::write(vault_path.join(MASTERKEY_FILE), masterkey_content)?;

        // ルートディレクトリの暗号ストレージパス（d/<2文字>/<30文字>）を用意する。
        let ops = VaultOperations::new(vault_path, master_key);
        let root_storage = ops
            .calculate_directory_storage_path(&DirId::root())
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        std::fs::create_dir_all(&root_storage)?;
        drop(ops);

        // 解錠し直して images/thumbnails を作成（書いた構成の検証も兼ねる）。
        Self::open(vault_path, passphrase)
    }

    /// パスフレーズで Vault を解錠し、必要なディレクトリを用意する。
    pub fn open(vault_path: &Path, passphrase: &str) -> Result<Self> {
        let ops = VaultOperations::open(vault_path, passphrase)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        let vault = Self {
            ops,
            root: vault_path.to_path_buf(),
        };
        vault.ensure_dir(IMAGES_DIR)?;
        vault.ensure_dir(THUMBNAILS_DIR)?;
        Ok(vault)
    }

    fn ensure_dir(&self, path: &str) -> Result<()> {
        self.ops
            .create_directory_all(path)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        Ok(())
    }

    /// ローカルのファイルパスから画像 1 枚を取り込む。取り込み後の Vault 内ファイル名を返す。
    pub fn import_image_from_path(&self, source: &Path) -> Result<String> {
        let name = source
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        if !is_visible_user_media_name(&name) {
            return Err(GazoError::InvalidName(name));
        }
        let bytes = std::fs::read(source)?;
        self.import_image_bytes(&bytes, &name)
    }

    /// バイト列から画像を取り込む。重複名は ` (n)` を付けて回避する。
    pub fn import_image_bytes(&self, bytes: &[u8], file_name: &str) -> Result<String> {
        if !is_visible_user_media_name(file_name) {
            return Err(GazoError::InvalidName(file_name.to_string()));
        }
        let dest_name = self.resolve_unique_image_name(file_name)?;
        self.ops
            .write_by_path(format!("{IMAGES_DIR}/{dest_name}"), bytes)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        // サムネイル生成は失敗しても取り込み自体は継続（Java 版と同様）。
        if let Some(thumb) = make_thumbnail_jpeg(bytes, THUMBNAIL_MAX_EDGE) {
            let _ = self
                .ops
                .write_by_path(format!("{THUMBNAILS_DIR}/{dest_name}.jpg"), &thumb);
        }
        Ok(dest_name)
    }

    /// images/ 配下で未使用のファイル名を求める（`foo.jpg` → `foo (1).jpg` …）。
    fn resolve_unique_image_name(&self, original: &str) -> Result<String> {
        self.resolve_unique_name(IMAGES_DIR, original)
    }

    /// 指定ディレクトリ配下で未使用のファイル名を求める（`foo.x` → `foo (1).x` …）。
    fn resolve_unique_name(&self, dir: &str, original: &str) -> Result<String> {
        let (base, ext) = split_base_ext(original);
        let mut candidate = original.to_string();
        let mut counter = 1;
        while self
            .ops
            .entry_type(format!("{dir}/{candidate}"))
            .is_some()
        {
            candidate = format!("{base} ({counter}){ext}");
            counter += 1;
        }
        Ok(candidate)
    }

    /// images/ 配下の画像ファイル名一覧（可視のもののみ）。
    pub fn list_images(&self) -> Result<Vec<String>> {
        let entries = self
            .ops
            .list_by_path(IMAGES_DIR)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        let mut names: Vec<String> = entries
            .into_iter()
            .filter(|e| e.is_file())
            .map(|e| e.name().to_string())
            .filter(|n| is_visible_user_media_name(n))
            .collect();
        names.sort();
        Ok(names)
    }

    /// ローカルのファイルパスから動画 1 本を取り込む。取り込み後の Vault 内ファイル名を返す。
    pub fn import_video_from_path(&self, source: &Path) -> Result<String> {
        let name = source
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        if !is_visible_user_media_name(&name) {
            return Err(GazoError::InvalidName(name));
        }
        let bytes = std::fs::read(source)?;
        self.import_video_bytes(&bytes, &name)
    }

    /// バイト列から動画を取り込む（サムネイルは生成しない）。重複名は ` (n)` で回避する。
    pub fn import_video_bytes(&self, bytes: &[u8], file_name: &str) -> Result<String> {
        if !is_visible_user_media_name(file_name) {
            return Err(GazoError::InvalidName(file_name.to_string()));
        }
        // videos/ は遅延作成（既存 Vault を開いただけでは作らない）。
        self.ensure_dir(VIDEOS_DIR)?;
        let dest_name = self.resolve_unique_name(VIDEOS_DIR, file_name)?;
        self.ops
            .write_by_path(format!("{VIDEOS_DIR}/{dest_name}"), bytes)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        Ok(dest_name)
    }

    /// videos/ 配下の動画ファイル名一覧（可視のもののみ）。
    pub fn list_videos(&self) -> Result<Vec<String>> {
        if self.ops.entry_type(VIDEOS_DIR).is_none() {
            return Ok(Vec::new());
        }
        let entries = self
            .ops
            .list_by_path(VIDEOS_DIR)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        let mut names: Vec<String> = entries
            .into_iter()
            .filter(|e| e.is_file())
            .map(|e| e.name().to_string())
            .filter(|n| is_visible_user_media_name(n))
            .collect();
        names.sort();
        Ok(names)
    }

    /// 画像のタグ集合を取得する。
    pub fn get_tags(&self, image_name: &str) -> Result<BTreeSet<String>> {
        let map = self.load_tag_props()?;
        Ok(tags::parse_tags(map.get(image_name).map(String::as_str).unwrap_or("")))
    }

    /// 画像のタグ集合を置き換える（空なら削除）。
    pub fn set_tags(&self, image_name: &str, new_tags: &BTreeSet<String>) -> Result<()> {
        let mut map = self.load_tag_props()?;
        let value = tags::join_normalized(new_tags.iter().map(String::as_str));
        if value.is_empty() {
            map.remove(image_name);
        } else {
            map.insert(image_name.to_string(), value);
        }
        self.save_tag_props(&map)
    }

    /// 既存タグに `add` をマージする（取り込み時のフォルダタグ付与に使用）。
    pub fn add_tags(&self, image_name: &str, add: &BTreeSet<String>) -> Result<()> {
        if add.is_empty() {
            return Ok(());
        }
        let mut current = self.get_tags(image_name)?;
        current.extend(add.iter().cloned());
        self.set_tags(image_name, &current)
    }

    fn load_tag_props(&self) -> Result<PropMap> {
        if self.ops.entry_type(TAGS_FILE).is_none() {
            return Ok(PropMap::new());
        }
        let df = self
            .ops
            .read_by_path(TAGS_FILE)
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        Ok(properties::load(&df.content))
    }

    fn save_tag_props(&self, map: &PropMap) -> Result<()> {
        let text = properties::store(map, Some("gazo tags"));
        self.ops
            .write_by_path(TAGS_FILE, text.as_bytes())
            .map_err(|e| GazoError::Vault(e.to_string()))?;
        Ok(())
    }
}

/// ファイル名を (ベース, 拡張子) に分割する。Java の `resolveUniqueImagePath` と同じ規則。
/// 先頭以外の最後の `.` で分割。末尾が `.` の場合や先頭ドットのみは分割しない。
fn split_base_ext(name: &str) -> (String, String) {
    if let Some(dot) = name.rfind('.') {
        if dot > 0 && dot < name.len() - 1 {
            return (name[..dot].to_string(), name[dot..].to_string());
        }
    }
    (name.to_string(), String::new())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base_ext_split() {
        assert_eq!(split_base_ext("a.jpg"), ("a".into(), ".jpg".into()));
        assert_eq!(split_base_ext("a.b.png"), ("a.b".into(), ".png".into()));
        assert_eq!(split_base_ext("noext"), ("noext".into(), "".into()));
        assert_eq!(split_base_ext(".hidden"), (".hidden".into(), "".into()));
    }

    fn make_test_png(w: u32, h: u32) -> Vec<u8> {
        use std::io::Cursor;
        let mut img = image::RgbImage::new(w, h);
        for p in img.pixels_mut() {
            *p = image::Rgb([120, 60, 30]);
        }
        let mut png = Vec::new();
        image::DynamicImage::ImageRgb8(img)
            .write_to(&mut Cursor::new(&mut png), image::ImageFormat::Png)
            .unwrap();
        png
    }

    #[test]
    fn create_produces_java_compatible_layout() {
        let dir = tempfile::tempdir().unwrap();
        let vault_path = dir.path();

        let vault = Vault::create(vault_path, "pw123").unwrap();

        // マスターキーはルート直下（Cryptomator 標準）。oxcrypt 既定の masterkey/ は作らない。
        assert!(vault_path.join("masterkey.cryptomator").is_file());
        assert!(!vault_path.join("masterkey").exists());
        assert!(vault_path.join("vault.cryptomator").is_file());

        // JWT の kid がルートの masterkey を指す。
        let jwt = std::fs::read_to_string(vault_path.join("vault.cryptomator")).unwrap();
        let header = jsonwebtoken::decode_header(&jwt).unwrap();
        assert_eq!(header.kid.as_deref(), Some("masterkeyfile:masterkey.cryptomator"));

        // 作成直後に取り込み・解錠ができる。
        let png = make_test_png(64, 64);
        let name = vault.import_image_bytes(&png, "a.png").unwrap();
        drop(vault);
        let reopened = Vault::open(vault_path, "pw123").unwrap();
        assert!(reopened.list_images().unwrap().contains(&name));

        // 二重作成は拒否。
        assert!(matches!(
            Vault::create(vault_path, "pw123"),
            Err(GazoError::Vault(_))
        ));
    }

    #[test]
    fn video_import_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let vault = Vault::create(dir.path(), "pw").unwrap();

        // 開いただけでは videos/ は作られない。
        assert!(vault.list_videos().unwrap().is_empty());

        let fake_mp4 = b"\x00\x00\x00\x18ftypmp42 dummy video bytes".to_vec();
        let n1 = vault.import_video_bytes(&fake_mp4, "clip.mp4").unwrap();
        assert_eq!(n1, "clip.mp4");
        let n2 = vault.import_video_bytes(&fake_mp4, "clip.mp4").unwrap();
        assert_eq!(n2, "clip (1).mp4");

        // 先頭ドットは拒否。
        assert!(matches!(
            vault.import_video_bytes(&fake_mp4, "._x.mp4"),
            Err(GazoError::InvalidName(_))
        ));

        drop(vault);
        let vault = Vault::open(dir.path(), "pw").unwrap();
        let vids = vault.list_videos().unwrap();
        assert!(vids.contains(&"clip.mp4".to_string()));
        assert!(vids.contains(&"clip (1).mp4".to_string()));
        // 中身が一致。
        let back = vault.ops.read_by_path("videos/clip.mp4").unwrap();
        assert_eq!(back.content, fake_mp4);
    }

    #[test]
    fn wrong_passphrase_fails_to_open() {
        let dir = tempfile::tempdir().unwrap();
        Vault::create(dir.path(), "right").unwrap();
        assert!(Vault::open(dir.path(), "wrong").is_err());
    }

    #[test]
    fn import_tags_thumbnail_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let vault_path = dir.path();
        let vault = Vault::create(vault_path, "correct horse").unwrap();
        let png = make_test_png(800, 400);

        // 取り込み + 重複名回避。
        let n1 = vault.import_image_bytes(&png, "photo.png").unwrap();
        assert_eq!(n1, "photo.png");
        let n2 = vault.import_image_bytes(&png, "photo.png").unwrap();
        assert_eq!(n2, "photo (1).png");

        // 先頭ドットのファイルは拒否。
        assert!(matches!(
            vault.import_image_bytes(&png, "._meta.png"),
            Err(GazoError::InvalidName(_))
        ));

        // タグのマージ。
        let mut add = BTreeSet::new();
        add.insert("trip".to_string());
        add.insert("Beach".to_string());
        vault.add_tags(&n1, &add).unwrap();

        // 再オープンしても画像・タグ・サムネイルが永続化されている。
        drop(vault);
        let vault = Vault::open(vault_path, "correct horse").unwrap();

        let imgs = vault.list_images().unwrap();
        assert!(imgs.contains(&"photo.png".to_string()));
        assert!(imgs.contains(&"photo (1).png".to_string()));

        let got = vault.get_tags("photo.png").unwrap();
        assert!(got.contains("trip"));
        assert!(got.contains("beach")); // 小文字化される

        assert!(
            vault.ops.entry_type("thumbnails/photo.png.jpg").is_some(),
            "thumbnail should be written"
        );

        // 読み戻したバイトが元と一致。
        let read_back = vault.ops.read_by_path("images/photo.png").unwrap();
        assert_eq!(read_back.content, png);
    }
}
