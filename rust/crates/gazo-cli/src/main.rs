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

    /// WebDAV 接続 URL（未対応 — 後続フェーズ）
    #[arg(long = "webdav-endpoint")]
    webdav_endpoint: Option<String>,

    /// 取り込むパス（`--` 以降はすべてパスとして扱う）
    #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
    paths: Vec<String>,
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    match cli.command {
        Command::Init(args) => run_init(args),
        Command::Import(args) => run_import(args, MediaKind::Image),
        Command::ImportVideo(args) => run_import(args, MediaKind::Video),
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
    if args.webdav_endpoint.is_some() {
        eprintln!("WebDAV 取り込みはこのバージョンでは未対応です（ローカルアルバムのみ）。");
        return ExitCode::from(1);
    }
    if args.paths.is_empty() {
        eprintln!("取り込むパスを 1 つ以上指定してください。");
        eprintln!("使用例: gazo import -r C:\\Photos\\trip");
        return ExitCode::from(1);
    }

    // --vault 省略時は設定ファイル（~/.gazo/settings.properties）の前回接続先を使う。
    let vault_dir = match args.vault {
        Some(p) => p,
        None => match SettingsStore::at_home().load_initial_vault_connection() {
            VaultConnection::Local(p) => p,
            VaultConnection::WebDav { .. } => {
                eprintln!(
                    "前回の接続先は WebDAV ですが、このバージョンでは未対応です。--vault でローカルアルバムを指定してください。"
                );
                return ExitCode::from(1);
            }
        },
    };

    if !Vault::vault_exists(&vault_dir) {
        eprintln!("アルバムが見つかりません: {}", vault_dir.display());
        return ExitCode::from(2);
    }

    let passphrase = match resolve_passphrase(args.password.as_deref()) {
        Some(p) => p,
        None => {
            eprintln!(
                "パスフレーズを取得できませんでした（GAZO_PASSPHRASE、--password、または対話入力）。"
            );
            return ExitCode::from(1);
        }
    };

    let vault = match Vault::open(&vault_dir, &passphrase) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("アルバムのロック解除に失敗しました（パスフレーズの誤りなど）: {e}");
            return ExitCode::from(2);
        }
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

    if failures > 0 {
        ExitCode::from(3)
    } else {
        ExitCode::SUCCESS
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
