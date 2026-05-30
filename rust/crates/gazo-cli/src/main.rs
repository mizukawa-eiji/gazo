//! Gazo CLI — コマンドラインからアルバム（Cryptomator 互換 Vault）へ画像を取り込む。
//!
//! Java 版 `com.example.gazo.cli.CliImport` のローカル Vault 取り込みを移植したもの。
//! WebDAV 取り込みは後続フェーズで対応する。
//!
//! 終了コード: 0=成功 / 1=引数エラー / 2=アルバム未検出・解錠失敗 / 3=一部取り込み失敗

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand};
use gazo_core::media::{is_image_file_name, is_video_file_name};
use gazo_core::tags::folder_tags_for_path_under_root;
use gazo_core::settings::{SettingsStore, VaultConnection};
use gazo_core::Vault;

#[derive(Parser)]
#[command(name = "gazo", about = "暗号化アルバムへの画像取り込み（CLI）", version)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// 新しいアルバム（Cryptomator 互換 Vault）を作成する
    Init(InitArgs),
    /// 画像をアルバムに取り込む
    Import(ImportArgs),
    /// 動画をアルバムに取り込む
    ImportVideo(ImportArgs),
    /// 画像をゴミ箱へ移動（論理削除）する
    Delete(DeleteArgs),
    /// 動画を完全削除する（ゴミ箱を経由しない）
    DeleteVideo(DeleteArgs),
    /// 最近削除した画像を一覧する
    ListDeleted(TrashArgs),
    /// 最近削除した画像を復元する
    Restore(TrashArgs),
}

#[derive(Args)]
struct DeleteArgs {
    /// アルバムのディレクトリ（省略時は設定の前回接続先）
    #[arg(long)]
    vault: Option<PathBuf>,
    /// パスフレーズ（非推奨。環境変数 GAZO_PASSPHRASE を推奨）
    #[arg(long)]
    password: Option<String>,
    /// 対象の Vault 内ファイル名（複数可）
    #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
    names: Vec<String>,
}

#[derive(Args)]
struct TrashArgs {
    /// アルバムのディレクトリ（省略時は設定の前回接続先）
    #[arg(long)]
    vault: Option<PathBuf>,
    /// パスフレーズ（非推奨。環境変数 GAZO_PASSPHRASE を推奨）
    #[arg(long)]
    password: Option<String>,
    /// 件数の上限（0 で無制限）
    #[arg(long, default_value_t = 0)]
    limit: usize,
}

/// 取り込むメディアの種類。画像と動画で対象拡張子と取り込み先が異なる。
#[derive(Clone, Copy)]
enum MediaKind {
    Image,
    Video,
}

impl MediaKind {
    fn matches_name(self, name: &str) -> bool {
        match self {
            MediaKind::Image => is_image_file_name(name),
            MediaKind::Video => is_video_file_name(name),
        }
    }

    fn import(self, vault: &Vault, source: &Path) -> gazo_core::Result<String> {
        match self {
            MediaKind::Image => vault.import_image_from_path(source),
            MediaKind::Video => vault.import_video_from_path(source),
        }
    }

    fn noun(self) -> &'static str {
        match self {
            MediaKind::Image => "画像",
            MediaKind::Video => "動画",
        }
    }
}

#[derive(Args)]
struct InitArgs {
    /// 作成先ディレクトリ（省略時は ~/.gazo/vault）
    #[arg(long)]
    vault: Option<PathBuf>,

    /// パスフレーズ（非推奨。環境変数 GAZO_PASSPHRASE を推奨）
    #[arg(long)]
    password: Option<String>,
}

#[derive(Args)]
struct ImportArgs {
    /// アルバムのディレクトリ（省略時は ~/.gazo/vault）
    #[arg(long)]
    vault: Option<PathBuf>,

    /// ディレクトリ指定時、サブフォルダも再帰的に取り込む
    #[arg(short = 'r', long = "recursive")]
    recursive: bool,

    /// パスフレーズ（非推奨。環境変数 GAZO_PASSPHRASE を推奨）
    #[arg(long)]
    password: Option<String>,

    #[command(flatten)]
    webdav: WebDavOpts,

    /// WebDAV 同期で双方が変化したときの解決方針
    #[arg(long = "on-conflict", value_enum, default_value_t = ConflictArg::Abort)]
    on_conflict: ConflictArg,

    /// 取り込むパス（`--` 以降はすべてパスとして扱う）
    #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
    paths: Vec<String>,
}

/// `--on-conflict` の選択肢（gazo_core::ConflictPolicy に対応）。
#[derive(Clone, Copy, clap::ValueEnum)]
enum ConflictArg {
    /// 解決せず中止（既定）
    Abort,
    /// ローカルを採用
    KeepLocal,
    /// リモートを採用
    KeepRemote,
    /// ローカルを別名保存しリモートを採用
    Copy,
}

impl From<ConflictArg> for gazo_core::ConflictPolicy {
    fn from(a: ConflictArg) -> Self {
        match a {
            ConflictArg::Abort => gazo_core::ConflictPolicy::Abort,
            ConflictArg::KeepLocal => gazo_core::ConflictPolicy::KeepLocal,
            ConflictArg::KeepRemote => gazo_core::ConflictPolicy::KeepRemote,
            ConflictArg::Copy => gazo_core::ConflictPolicy::ConflictCopy,
        }
    }
}

/// WebDAV 接続オプション（指定時はローカル `--vault` の代わりにリモートを使う）。
#[derive(Args)]
struct WebDavOpts {
    /// WebDAV 接続 URL（例: https://host/remote.php/dav/files/user）
    #[arg(long = "webdav-endpoint")]
    endpoint: Option<String>,
    /// WebDAV 内の Vault パス（例: /gazo-vault）
    #[arg(long = "webdav-base-path")]
    base_path: Option<String>,
    /// WebDAV ユーザー名
    #[arg(long = "webdav-username")]
    username: Option<String>,
    /// WebDAV パスワード（非推奨。環境変数 GAZO_WEBDAV_PASSWORD を推奨）
    #[arg(long = "webdav-password", id = "webdav_password")]
    password: Option<String>,
}

impl WebDavOpts {
    fn is_specified(&self) -> bool {
        self.endpoint.is_some() || self.base_path.is_some() || self.username.is_some()
    }
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    match cli.command {
        Command::Init(args) => run_init(args),
        Command::Import(args) => run_import(args, MediaKind::Image),
        Command::ImportVideo(args) => run_import(args, MediaKind::Video),
        Command::Delete(args) => run_delete(args, MediaKind::Image),
        Command::DeleteVideo(args) => run_delete(args, MediaKind::Video),
        Command::ListDeleted(args) => run_list_deleted(args),
        Command::Restore(args) => run_restore(args),
    }
}

fn run_init(args: InitArgs) -> ExitCode {
    let vault_dir = args
        .vault
        .unwrap_or_else(|| SettingsStore::at_home().default_vault_path());

    if Vault::vault_exists(&vault_dir) {
        eprintln!("その場所には既にアルバムがあります: {}", vault_dir.display());
        return ExitCode::from(1);
    }

    let passphrase = match resolve_passphrase_with_confirm(args.password.as_deref()) {
        Some(p) => p,
        None => {
            eprintln!("パスフレーズを取得できませんでした。");
            return ExitCode::from(1);
        }
    };

    match Vault::create(&vault_dir, &passphrase) {
        Ok(_) => {
            println!("アルバムを作成しました: {}", vault_dir.display());
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("アルバムの作成に失敗しました: {e}");
            ExitCode::from(2)
        }
    }
}

fn run_import(args: ImportArgs, kind: MediaKind) -> ExitCode {
    if args.paths.is_empty() {
        eprintln!("取り込むパスを 1 つ以上指定してください。");
        eprintln!("使用例: gazo import -r C:\\Photos\\trip");
        return ExitCode::from(1);
    }

    let vault = match open_target_vault(args.vault, &args.webdav, args.password.as_deref()) {
        Ok(v) => v,
        Err(code) => return code,
    };

    let mut failures = 0u32;
    for ps in &args.paths {
        let p = Path::new(ps);
        if !p.exists() {
            eprintln!("存在しません: {}", p.display());
            failures += 1;
            continue;
        }
        if p.is_file() {
            if is_media_path(p, kind) {
                failures += import_one(&vault, p, &BTreeSet::new(), kind);
            } else {
                eprintln!("{}ではありません（スキップ）: {}", kind.noun(), p.display());
                failures += 1;
            }
        } else if p.is_dir() {
            failures += import_directory(&vault, p, args.recursive, kind);
        } else {
            eprintln!("ファイルでもディレクトリでもありません: {}", p.display());
            failures += 1;
        }
    }

    // WebDAV の場合はここで一括アップロード（ローカルは no-op）。
    if let Err(code) = flush_and_report(&vault, args.on_conflict.into()) {
        return code;
    }

    if failures > 0 {
        ExitCode::from(3)
    } else {
        ExitCode::SUCCESS
    }
}

/// 変更をリモートへ反映し、結果を表示する。未解決の競合があれば警告し終了コード 3 を返す。
fn flush_and_report(
    vault: &Vault,
    policy: gazo_core::ConflictPolicy,
) -> std::result::Result<(), ExitCode> {
    if !vault.is_remote() {
        return Ok(());
    }
    match vault.flush_with(policy) {
        Ok(result) => {
            println!(
                "リモート同期: アップロード {} 件 / ダウンロード {} 件 / 削除 {} 件",
                result.uploaded, result.downloaded, result.deleted
            );
            if !result.conflicts.is_empty() {
                eprintln!(
                    "競合のため未反映のファイルがあります（{} 件）。--on-conflict で解決方針を指定できます:",
                    result.conflicts.len()
                );
                for c in &result.conflicts {
                    eprintln!("  競合: {c}");
                }
                return Err(ExitCode::from(3));
            }
            Ok(())
        }
        Err(e) => {
            eprintln!("リモートへの同期に失敗しました: {e}");
            Err(ExitCode::from(2))
        }
    }
}

/// 1 ファイルを取り込み、必要ならフォルダ由来タグを付与する。失敗で 1 を返す。
fn import_one(vault: &Vault, source: &Path, tags_to_add: &BTreeSet<String>, kind: MediaKind) -> u32 {
    match kind.import(vault, source) {
        Ok(dest_name) => {
            if !tags_to_add.is_empty() {
                if let Err(e) = vault.add_tags(&dest_name, tags_to_add) {
                    eprintln!("タグ付与に失敗: {} — {e}", source.display());
                }
            }
            println!("取り込み: {} -> {dest_name}", source.display());
            0
        }
        Err(e) => {
            eprintln!("失敗: {} — {e}", source.display());
            1
        }
    }
}

fn import_directory(vault: &Vault, directory: &Path, recursive: bool, kind: MediaKind) -> u32 {
    let mut files = Vec::new();
    if let Err(e) = collect_media(directory, recursive, kind, &mut files) {
        eprintln!("ディレクトリの読み込みに失敗しました: {} — {e}", directory.display());
        return 1;
    }
    files.sort();
    let mut failures = 0u32;
    for file in &files {
        let tags = folder_tags_for_path_under_root(directory, file);
        failures += import_one(vault, file, &tags, kind);
    }
    failures
}

/// ディレクトリ内の対象メディアを収集する。`recursive` ならサブフォルダもたどる。
fn collect_media(
    dir: &Path,
    recursive: bool,
    kind: MediaKind,
    out: &mut Vec<PathBuf>,
) -> std::io::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        let file_type = entry.file_type()?;
        if file_type.is_dir() {
            if recursive {
                collect_media(&path, recursive, kind, out)?;
            }
        } else if file_type.is_file() && is_media_path(&path, kind) {
            out.push(path);
        }
    }
    Ok(())
}

fn is_media_path(p: &Path, kind: MediaKind) -> bool {
    p.file_name()
        .map(|n| kind.matches_name(&n.to_string_lossy()))
        .unwrap_or(false)
}

fn run_delete(args: DeleteArgs, kind: MediaKind) -> ExitCode {
    if args.names.is_empty() {
        eprintln!("削除対象のファイル名を 1 つ以上指定してください。");
        return ExitCode::from(1);
    }
    let vault = match open_existing_vault(args.vault, args.password.as_deref()) {
        Ok(v) => v,
        Err(code) => return code,
    };
    let mut failures = 0u32;
    for name in &args.names {
        let result = match kind {
            MediaKind::Image => vault.delete_image(name),
            MediaKind::Video => vault.delete_video(name),
        };
        match result {
            Ok(()) => {
                let where_to = match kind {
                    MediaKind::Image => "ゴミ箱へ移動",
                    MediaKind::Video => "削除",
                };
                println!("{where_to}: {name}");
            }
            Err(e) => {
                eprintln!("失敗: {name} — {e}");
                failures += 1;
            }
        }
    }
    if failures > 0 {
        ExitCode::from(3)
    } else {
        ExitCode::SUCCESS
    }
}

fn run_list_deleted(args: TrashArgs) -> ExitCode {
    let vault = match open_existing_vault(args.vault, args.password.as_deref()) {
        Ok(v) => v,
        Err(code) => return code,
    };
    match vault.list_recently_deleted(args.limit) {
        Ok(items) => {
            if items.is_empty() {
                println!("削除済みの画像はありません。");
            }
            for it in items {
                println!("{}\t(deletedAt={})", it.original_file_name, it.deleted_at_millis);
            }
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("一覧の取得に失敗しました: {e}");
            ExitCode::from(2)
        }
    }
}

fn run_restore(args: TrashArgs) -> ExitCode {
    let vault = match open_existing_vault(args.vault, args.password.as_deref()) {
        Ok(v) => v,
        Err(code) => return code,
    };
    match vault.restore_recently_deleted(args.limit) {
        Ok(result) => {
            println!("{} 件の画像を復元しました。", result.restored);
            for f in &result.failures {
                eprintln!("復元できず: {f}");
            }
            if result.failures.is_empty() {
                ExitCode::SUCCESS
            } else {
                ExitCode::from(3)
            }
        }
        Err(e) => {
            eprintln!("復元に失敗しました: {e}");
            ExitCode::from(2)
        }
    }
}

/// `--webdav-*` 指定があれば WebDAV を、なければローカルを開く。
fn open_target_vault(
    vault: Option<PathBuf>,
    webdav: &WebDavOpts,
    password: Option<&str>,
) -> std::result::Result<Vault, ExitCode> {
    if !webdav.is_specified() {
        return open_existing_vault(vault, password);
    }
    if vault.is_some() {
        eprintln!("--vault と --webdav-* は同時に指定できません。");
        return Err(ExitCode::from(1));
    }
    let (Some(endpoint), Some(base_path), Some(username)) =
        (&webdav.endpoint, &webdav.base_path, &webdav.username)
    else {
        eprintln!("--webdav-endpoint / --webdav-base-path / --webdav-username をすべて指定してください。");
        return Err(ExitCode::from(1));
    };
    let webdav_password = match resolve_webdav_password(webdav.password.as_deref()) {
        Some(p) => p,
        None => {
            eprintln!(
                "WebDAV パスワードを取得できませんでした（GAZO_WEBDAV_PASSWORD、--webdav-password、または対話入力）。"
            );
            return Err(ExitCode::from(1));
        }
    };
    let passphrase = match resolve_passphrase(password) {
        Some(p) => p,
        None => {
            eprintln!("パスフレーズを取得できませんでした（GAZO_PASSPHRASE、--password、または対話入力）。");
            return Err(ExitCode::from(1));
        }
    };
    match Vault::open_webdav(endpoint, base_path, username, &webdav_password, &passphrase) {
        Ok(v) => Ok(v),
        Err(e) => {
            eprintln!("WebDAV アルバムのオープンに失敗しました: {e}");
            Err(ExitCode::from(2))
        }
    }
}

/// WebDAV パスワード取得（Java 版と同順）: --webdav-password → 環境変数 → 対話入力。
fn resolve_webdav_password(opt: Option<&str>) -> Option<String> {
    if let Some(p) = opt {
        if !p.is_empty() {
            return Some(p.to_string());
        }
    }
    if let Ok(env) = std::env::var("GAZO_WEBDAV_PASSWORD") {
        if !env.is_empty() {
            return Some(env);
        }
    }
    rpassword::prompt_password("WebDAV パスワード: ").ok()
}

/// 既存アルバムを解決して解錠する共通処理。失敗時は適切な終了コードを返す。
fn open_existing_vault(
    vault: Option<PathBuf>,
    password: Option<&str>,
) -> std::result::Result<Vault, ExitCode> {
    let vault_dir = match vault {
        Some(p) => p,
        None => match SettingsStore::at_home().load_initial_vault_connection() {
            VaultConnection::Local(p) => p,
            VaultConnection::WebDav { .. } => {
                eprintln!(
                    "前回の接続先は WebDAV ですが、このバージョンでは未対応です。--vault でローカルアルバムを指定してください。"
                );
                return Err(ExitCode::from(1));
            }
        },
    };
    if !Vault::vault_exists(&vault_dir) {
        eprintln!("アルバムが見つかりません: {}", vault_dir.display());
        return Err(ExitCode::from(2));
    }
    let passphrase = match resolve_passphrase(password) {
        Some(p) => p,
        None => {
            eprintln!(
                "パスフレーズを取得できませんでした（GAZO_PASSPHRASE、--password、または対話入力）。"
            );
            return Err(ExitCode::from(1));
        }
    };
    match Vault::open(&vault_dir, &passphrase) {
        Ok(v) => Ok(v),
        Err(e) => {
            eprintln!("アルバムのロック解除に失敗しました（パスフレーズの誤りなど）: {e}");
            Err(ExitCode::from(2))
        }
    }
}

/// パスフレーズ取得（Java 版と同順）: --password → 環境変数 → 対話入力。
fn resolve_passphrase(password_opt: Option<&str>) -> Option<String> {
    if let Some(p) = password_opt {
        if !p.is_empty() {
            return Some(p.to_string());
        }
    }
    if let Ok(env) = std::env::var("GAZO_PASSPHRASE") {
        if !env.is_empty() {
            return Some(env);
        }
    }
    rpassword::prompt_password("アルバム パスフレーズ: ").ok()
}

/// 作成時のパスフレーズ取得。対話入力では確認のため 2 回入力させ一致を確認する。
fn resolve_passphrase_with_confirm(password_opt: Option<&str>) -> Option<String> {
    if let Some(p) = password_opt {
        if !p.is_empty() {
            return Some(p.to_string());
        }
    }
    if let Ok(env) = std::env::var("GAZO_PASSPHRASE") {
        if !env.is_empty() {
            return Some(env);
        }
    }
    let first = rpassword::prompt_password("新しいアルバムのパスフレーズ: ").ok()?;
    if first.is_empty() {
        eprintln!("空のパスフレーズは使用できません。");
        return None;
    }
    let again = rpassword::prompt_password("もう一度入力してください: ").ok()?;
    if first != again {
        eprintln!("パスフレーズが一致しませんでした。");
        return None;
    }
    Some(first)
}
