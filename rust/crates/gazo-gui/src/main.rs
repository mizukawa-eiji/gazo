//! Gazo GUI（Slint）。段階移植の最初のスライスとして Vault 解錠画面を提供する。
//! 解錠に成功したら画像枚数を表示する（コアが GUI に正しく繋がっている証明）。
//!
//! Windows ではコンソールを出さないため `windows_subsystem` を指定する。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::cell::RefCell;
use std::path::Path;
use std::rc::Rc;

use gazo_core::{SettingsStore, Vault, VaultConnection};

slint::include_modules!();

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let ui = AppWindow::new()?;

    // 既定のアルバム位置を設定ファイルから復元（WebDAV は未対応なので既定パス）。
    let store = SettingsStore::at_home();
    let default_path = match store.load_initial_vault_connection() {
        VaultConnection::Local(p) => p,
        VaultConnection::WebDav { .. } => store.default_vault_path(),
    };
    ui.set_vault_path(default_path.display().to_string().into());

    // 解錠済み Vault を保持（後続のギャラリー表示などで使う）。UI スレッド単一なので Rc/RefCell。
    let vault: Rc<RefCell<Option<Vault>>> = Rc::new(RefCell::new(None));

    let ui_weak = ui.as_weak();
    let vault_for_open = vault.clone();
    ui.on_open_vault(move || {
        let ui = ui_weak.unwrap();
        let path = ui.get_vault_path().to_string();
        let passphrase = ui.get_passphrase().to_string();
        let dir = Path::new(&path);

        if !Vault::vault_exists(dir) {
            ui.set_status(format!("アルバムが見つかりません: {path}").into());
            return;
        }
        match Vault::open(dir, &passphrase) {
            Ok(v) => {
                let count = v.list_images().map(|i| i.len()).unwrap_or(0) as i32;
                *vault_for_open.borrow_mut() = Some(v);
                ui.set_image_count(count);
                ui.set_status("".into());
                ui.set_unlocked(true);
            }
            Err(e) => {
                ui.set_status(format!("解錠に失敗しました（パスフレーズの誤りなど）: {e}").into());
            }
        }
    });

    ui.run()?;
    Ok(())
}
