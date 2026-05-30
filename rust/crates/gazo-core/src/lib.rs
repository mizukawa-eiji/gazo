//! gazo-core — Gazo の Vault コア（暗号化アルバムの取り込み・タグ・サムネイル）。
//!
//! Cryptomator Vault Format 8 の暗号処理は [`oxcrypt_core`] に委譲する。
//! Java 版 `com.example.gazo.vault.GazoVaultService` のうち、UI に依存しない
//! コア機能を段階的に移植したもの。

pub mod media;
pub mod properties;
pub mod settings;
pub mod tags;
pub mod thumbnail;
pub mod vault;
pub mod webdav_mirror;

pub use settings::{ConflictDHashThresholds, GallerySettings, SettingsStore, VaultConnection};
pub use vault::{DeletedImage, FlushResult, GazoError, RestoreResult, Result, Vault};
