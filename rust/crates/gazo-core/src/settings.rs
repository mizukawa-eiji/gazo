//! アプリ設定ファイル `~/.gazo/settings.properties` の読み書き。
//! Java 版 `com.example.gazo.VaultPathStore` の挙動・キー名・既定値に一致させる。
//!
//! ファイル形式は Java `Properties`（[`crate::properties`] で互換実装）。
//! WebDAV パスワードの暗号化（`WebDavPasswordCodec`）は WebDAV フェーズで対応するため、
//! ここでは保存済み暗号文字列の有無のみ扱い、復号はしない。

use std::path::{Path, PathBuf};

use crate::properties::{self, PropMap};

const APP_DIR: &str = ".gazo";
const DEFAULT_VAULT_DIR: &str = "vault";
const CONFIG_FILE: &str = "settings.properties";
const STORE_COMMENT: &str = "gazo settings";

const K_LAST_VAULT_PATH: &str = "lastVaultPath";
const K_LAST_VAULT_TYPE: &str = "lastVaultType";
const K_WEBDAV_ENDPOINT: &str = "lastWebDavEndpoint";
const K_WEBDAV_BASE_PATH: &str = "lastWebDavBasePath";
const K_WEBDAV_USERNAME: &str = "lastWebDavUsername";
const K_WEBDAV_PASSWORD_ENC: &str = "lastWebDavPasswordEnc";

const K_GALLERY_SHOW_FILE_NAME: &str = "gallery.showFileName";
const K_GALLERY_SHOW_DATE: &str = "gallery.showDate";
const K_GALLERY_SHOW_TAGS: &str = "gallery.showTags";
const K_GALLERY_LIST_VIEW_SIZE: &str = "gallery.listViewSize";
const K_GALLERY_IMAGE_NAME_QUERY: &str = "gallery.imageNameQuery";
const K_GALLERY_TAG_FILTERS: &str = "gallery.tagFilters";
const K_CONFLICT_DHASH_SAME_MAX: &str = "conflict.dhash.sameMax";
const K_CONFLICT_DHASH_NEAR_MAX: &str = "conflict.dhash.nearMax";

const VALID_LIST_VIEW_SIZES: [&str; 3] = ["小", "中", "大"];
const DEFAULT_LIST_VIEW_SIZE: &str = "中";

/// 前回の Vault 接続先。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VaultConnection {
    Local(PathBuf),
    WebDav {
        endpoint: String,
        base_path: String,
        username: String,
    },
}

impl VaultConnection {
    pub fn is_local(&self) -> bool {
        matches!(self, VaultConnection::Local(_))
    }
    pub fn is_web_dav(&self) -> bool {
        matches!(self, VaultConnection::WebDav { .. })
    }
}

/// 画像一覧タブの表示・絞り込み設定。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GallerySettings {
    pub show_file_name: bool,
    pub show_date: bool,
    pub show_tags: bool,
    pub list_view_size: String,
    pub image_name_query: String,
    pub tag_filters: Vec<String>,
}

impl Default for GallerySettings {
    fn default() -> Self {
        GallerySettings {
            show_file_name: true,
            show_date: true,
            show_tags: true,
            list_view_size: DEFAULT_LIST_VIEW_SIZE.to_string(),
            image_name_query: String::new(),
            tag_filters: Vec::new(),
        }
    }
}

/// 競合ダイアログで使う dHash 距離しきい値。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ConflictDHashThresholds {
    pub same_max: i32,
    pub near_max: i32,
}

impl Default for ConflictDHashThresholds {
    fn default() -> Self {
        ConflictDHashThresholds {
            same_max: 3,
            near_max: 8,
        }
    }
}

impl ConflictDHashThresholds {
    /// same は 0..=63、near は 1..=64 にクランプし、near >= same を満たすよう正規化する。
    fn normalized(same: i32, near: i32) -> Self {
        let s = same.clamp(0, 63);
        let n = near.clamp(1, 64).max(s);
        ConflictDHashThresholds {
            same_max: s,
            near_max: n,
        }
    }
}

/// 設定ファイルへのアクセス。テストではファイルパスを差し替えられる。
pub struct SettingsStore {
    file: PathBuf,
}

impl SettingsStore {
    /// 既定の `~/.gazo/settings.properties` を対象にする。
    pub fn at_home() -> Self {
        let file = home_dir().join(APP_DIR).join(CONFIG_FILE);
        SettingsStore { file }
    }

    /// 任意のファイルを対象にする（テスト・カスタム用）。
    pub fn at_file(file: impl Into<PathBuf>) -> Self {
        SettingsStore { file: file.into() }
    }

    fn config_dir(&self) -> PathBuf {
        self.file
            .parent()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| PathBuf::from("."))
    }

    /// 既定の Vault 位置（設定ディレクトリ直下の `vault`）。
    pub fn default_vault_path(&self) -> PathBuf {
        self.config_dir().join(DEFAULT_VAULT_DIR)
    }

    fn load_props(&self) -> PropMap {
        match std::fs::read(&self.file) {
            Ok(bytes) => properties::load(&bytes),
            Err(_) => PropMap::new(),
        }
    }

    fn save_props(&self, map: &PropMap) -> std::io::Result<()> {
        std::fs::create_dir_all(self.config_dir())?;
        let text = properties::store(map, Some(STORE_COMMENT));
        std::fs::write(&self.file, text.as_bytes())
    }

    /// 起動時の接続先を復元する（Java `loadInitialVaultConnection` 相当）。
    pub fn load_initial_vault_connection(&self) -> VaultConnection {
        let default = VaultConnection::Local(self.default_vault_path());
        if !self.file.exists() {
            return default;
        }
        let props = self.load_props();
        let get = |k: &str| props.get(k).map(|s| s.trim().to_string()).unwrap_or_default();

        let type_raw = props
            .get(K_LAST_VAULT_TYPE)
            .map(|s| s.trim().to_lowercase())
            .unwrap_or_else(|| "local".to_string());
        let endpoint = get(K_WEBDAV_ENDPOINT);
        let base_path = get(K_WEBDAV_BASE_PATH);
        let username = get(K_WEBDAV_USERNAME);
        let enc_pwd = get(K_WEBDAV_PASSWORD_ENC);
        let webdav_complete =
            !endpoint.is_empty() && !base_path.is_empty() && !username.is_empty();
        // 保存済み WebDAV パスワードがあれば lastVaultType 未更新でも WebDAV とみなす。
        if webdav_complete && (type_raw == "webdav" || !enc_pwd.is_empty()) {
            return VaultConnection::WebDav {
                endpoint,
                base_path,
                username,
            };
        }
        let raw = get(K_LAST_VAULT_PATH);
        if raw.is_empty() {
            default
        } else {
            VaultConnection::Local(PathBuf::from(raw))
        }
    }

    /// 接続先のみ保存する（Java `saveLastVaultConnection(connection)` 相当、
    /// WebDAV パスワードは同一アカウントなら維持、異なれば破棄）。
    pub fn save_last_vault_connection(&self, conn: &VaultConnection) -> std::io::Result<()> {
        let mut props = self.load_props();
        match conn {
            VaultConnection::WebDav {
                endpoint,
                base_path,
                username,
            } => {
                let same_account = props.get(K_WEBDAV_ENDPOINT).map(|s| s.trim()) == Some(endpoint.as_str())
                    && props.get(K_WEBDAV_BASE_PATH).map(|s| s.trim()) == Some(base_path.as_str())
                    && props.get(K_WEBDAV_USERNAME).map(|s| s.trim()) == Some(username.as_str());
                props.insert(K_LAST_VAULT_TYPE.into(), "webdav".into());
                props.remove(K_LAST_VAULT_PATH);
                props.insert(K_WEBDAV_ENDPOINT.into(), endpoint.clone());
                props.insert(K_WEBDAV_BASE_PATH.into(), base_path.clone());
                props.insert(K_WEBDAV_USERNAME.into(), username.clone());
                // UNCHANGED: アカウントが変わったら保存済みパスワードは破棄。
                if !same_account {
                    props.remove(K_WEBDAV_PASSWORD_ENC);
                }
            }
            VaultConnection::Local(path) => {
                props.insert(K_LAST_VAULT_TYPE.into(), "local".into());
                props.insert(K_LAST_VAULT_PATH.into(), absolutize(path).to_string_lossy().to_string());
                props.remove(K_WEBDAV_ENDPOINT);
                props.remove(K_WEBDAV_BASE_PATH);
                props.remove(K_WEBDAV_USERNAME);
                props.remove(K_WEBDAV_PASSWORD_ENC);
            }
        }
        self.save_props(&props)
    }

    pub fn load_conflict_dhash_thresholds(&self) -> ConflictDHashThresholds {
        if !self.file.exists() {
            return ConflictDHashThresholds::default();
        }
        let props = self.load_props();
        let same = parse_int_or(props.get(K_CONFLICT_DHASH_SAME_MAX), 3);
        let near = parse_int_or(props.get(K_CONFLICT_DHASH_NEAR_MAX), 8);
        ConflictDHashThresholds::normalized(same, near)
    }

    pub fn save_conflict_dhash_thresholds(
        &self,
        thresholds: ConflictDHashThresholds,
    ) -> std::io::Result<()> {
        let n = ConflictDHashThresholds::normalized(thresholds.same_max, thresholds.near_max);
        let mut props = self.load_props();
        props.insert(K_CONFLICT_DHASH_SAME_MAX.into(), n.same_max.to_string());
        props.insert(K_CONFLICT_DHASH_NEAR_MAX.into(), n.near_max.to_string());
        self.save_props(&props)
    }

    pub fn load_gallery_settings(&self) -> GallerySettings {
        if !self.file.exists() {
            return GallerySettings::default();
        }
        let props = self.load_props();
        let bool_of = |k: &str| match props.get(k) {
            Some(v) => v.eq_ignore_ascii_case("true"),
            None => true, // 既定 true
        };
        let mut size = props
            .get(K_GALLERY_LIST_VIEW_SIZE)
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| DEFAULT_LIST_VIEW_SIZE.to_string());
        if !VALID_LIST_VIEW_SIZES.contains(&size.as_str()) {
            size = DEFAULT_LIST_VIEW_SIZE.to_string();
        }
        GallerySettings {
            show_file_name: bool_of(K_GALLERY_SHOW_FILE_NAME),
            show_date: bool_of(K_GALLERY_SHOW_DATE),
            show_tags: bool_of(K_GALLERY_SHOW_TAGS),
            list_view_size: size,
            image_name_query: props
                .get(K_GALLERY_IMAGE_NAME_QUERY)
                .cloned()
                .unwrap_or_default(),
            tag_filters: parse_tag_filters(
                props.get(K_GALLERY_TAG_FILTERS).map(String::as_str).unwrap_or(""),
            ),
        }
    }

    pub fn save_gallery_settings(&self, s: &GallerySettings) -> std::io::Result<()> {
        let mut props = self.load_props();
        props.insert(K_GALLERY_SHOW_FILE_NAME.into(), bool_str(s.show_file_name));
        props.insert(K_GALLERY_SHOW_DATE.into(), bool_str(s.show_date));
        props.insert(K_GALLERY_SHOW_TAGS.into(), bool_str(s.show_tags));
        let mut size = s.list_view_size.trim().to_string();
        if !VALID_LIST_VIEW_SIZES.contains(&size.as_str()) {
            size = DEFAULT_LIST_VIEW_SIZE.to_string();
        }
        props.insert(K_GALLERY_LIST_VIEW_SIZE.into(), size);
        props.insert(K_GALLERY_IMAGE_NAME_QUERY.into(), s.image_name_query.clone());
        props.insert(K_GALLERY_TAG_FILTERS.into(), format_tag_filters(&s.tag_filters));
        self.save_props(&props)
    }
}

fn bool_str(b: bool) -> String {
    if b { "true".into() } else { "false".into() }
}

fn parse_int_or(raw: Option<&String>, fallback: i32) -> i32 {
    raw.and_then(|s| s.trim().parse::<i32>().ok()).unwrap_or(fallback)
}

fn parse_tag_filters(raw: &str) -> Vec<String> {
    if raw.trim().is_empty() {
        return Vec::new();
    }
    raw.split('\n')
        .map(|line| match line.find('\r') {
            Some(i) => &line[..i],
            None => line,
        })
        .filter(|l| !l.trim().is_empty())
        .map(|l| l.to_string())
        .collect()
}

fn format_tag_filters(tags: &[String]) -> String {
    tags.iter()
        .map(|t| t.replace('\r', "").replace('\n', " ").trim().to_string())
        .filter(|t| !t.is_empty())
        .collect::<Vec<_>>()
        .join("\n")
}

/// 相対パスを現在のディレクトリ基準で絶対化する（実体の存在は問わない）。
fn absolutize(p: &Path) -> PathBuf {
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|c| c.join(p))
            .unwrap_or_else(|_| p.to_path_buf())
    }
}

/// ユーザーのホームディレクトリ（`USERPROFILE` → `HOME`、無ければカレント）。
pub fn home_dir() -> PathBuf {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn store_in(dir: &Path) -> SettingsStore {
        SettingsStore::at_file(dir.join(".gazo").join("settings.properties"))
    }

    #[test]
    fn missing_file_defaults_to_local_vault() {
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        let conn = store.load_initial_vault_connection();
        match conn {
            VaultConnection::Local(p) => {
                assert!(p.ends_with("vault"));
            }
            _ => panic!("expected local"),
        }
    }

    #[test]
    fn save_and_load_local_connection() {
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        let vault = dir.path().join("my-album");
        store
            .save_last_vault_connection(&VaultConnection::Local(vault.clone()))
            .unwrap();
        let conn = store.load_initial_vault_connection();
        assert_eq!(conn, VaultConnection::Local(absolutize(&vault)));
    }

    #[test]
    fn webdav_detected_by_type() {
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        store
            .save_last_vault_connection(&VaultConnection::WebDav {
                endpoint: "https://example.com/dav".into(),
                base_path: "/gazo".into(),
                username: "alice".into(),
            })
            .unwrap();
        assert_eq!(
            store.load_initial_vault_connection(),
            VaultConnection::WebDav {
                endpoint: "https://example.com/dav".into(),
                base_path: "/gazo".into(),
                username: "alice".into(),
            }
        );
    }

    #[test]
    fn switching_to_local_clears_webdav_keys() {
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        store
            .save_last_vault_connection(&VaultConnection::WebDav {
                endpoint: "https://example.com/dav".into(),
                base_path: "/gazo".into(),
                username: "alice".into(),
            })
            .unwrap();
        let local = dir.path().join("album");
        store
            .save_last_vault_connection(&VaultConnection::Local(local.clone()))
            .unwrap();
        assert_eq!(
            store.load_initial_vault_connection(),
            VaultConnection::Local(absolutize(&local))
        );
    }

    #[test]
    fn dhash_thresholds_roundtrip_and_normalize() {
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        assert_eq!(
            store.load_conflict_dhash_thresholds(),
            ConflictDHashThresholds::default()
        );
        // near < same は near=same に正規化される。
        store
            .save_conflict_dhash_thresholds(ConflictDHashThresholds {
                same_max: 10,
                near_max: 4,
            })
            .unwrap();
        assert_eq!(
            store.load_conflict_dhash_thresholds(),
            ConflictDHashThresholds {
                same_max: 10,
                near_max: 10
            }
        );
    }

    #[test]
    fn gallery_settings_roundtrip() {
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        assert_eq!(store.load_gallery_settings(), GallerySettings::default());
        let s = GallerySettings {
            show_file_name: false,
            show_date: true,
            show_tags: false,
            list_view_size: "大".into(),
            image_name_query: "夏".into(),
            tag_filters: vec!["旅行".into(), "海".into()],
        };
        store.save_gallery_settings(&s).unwrap();
        assert_eq!(store.load_gallery_settings(), s);
    }

    #[test]
    fn reads_java_escaped_windows_path() {
        // Java の Properties.store は Windows パスを `C\:\\dir` のようにエスケープして書く。
        // その実ファイルを正しく復元できること（既存 settings.properties との互換）。
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        std::fs::create_dir_all(store.config_dir()).unwrap();
        std::fs::write(
            &store.file,
            "#gazo settings\nlastVaultType=local\nlastVaultPath=C\\:\\\\Users\\\\me\\\\album\n",
        )
        .unwrap();
        assert_eq!(
            store.load_initial_vault_connection(),
            VaultConnection::Local(PathBuf::from(r"C:\Users\me\album"))
        );
    }

    #[test]
    fn invalid_list_view_size_falls_back() {
        let dir = tempdir().unwrap();
        let store = store_in(dir.path());
        let mut s = GallerySettings::default();
        s.list_view_size = "巨大".into();
        store.save_gallery_settings(&s).unwrap();
        assert_eq!(store.load_gallery_settings().list_view_size, "中");
    }
}
