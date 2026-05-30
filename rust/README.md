# Gazo — Rust 移植

JavaFX 版 Gazo を Rust へ段階的に移植するためのワークスペース。既存の Java 実装
（`../src`）はそのまま残し、UI 非依存のコア機能から順に Rust 化する。

## 方針

- **暗号 (Cryptomator Vault Format 8)**: [`oxcrypt-core`](https://github.com/agucova/oxcrypt)
  に委譲（コミット固定）。Java 版が `cryptofs 2.6.4` で作成した既存 Vault を
  そのまま読み書きできる（`vault.cryptomator` の `kid` からマスターキー位置を解決）。
- **段階移植**: CLI / コア → （将来）GUI（Slint 予定）。
- **WebDAV 同期**: 後続フェーズ。

## クレート構成

| クレート | 役割 |
|----------|------|
| `gazo-core` | Vault 解錠・画像取り込み・サムネイル生成・タグ（Java `Properties` 互換）・アプリ設定（`~/.gazo/settings.properties`）。`VaultOperations` を平文パスベースで利用しマウント不要。 |
| `gazo-cli`  | `gazo init` / `import` / `import-video` / `delete`・`restore`・`list-deleted`（ゴミ箱）/ `delete-video`。ローカル Vault 対象。 |
| `gazo-webdav` | WebDAV クライアント（PROPFIND/GET/PUT/DELETE/MKCOL, Basic 認証）。reqwest(blocking)+quick-xml。差分同期の土台。 |
| `gazo-gui`  | Slint 製デスクトップ GUI（移植中）。現状は Vault 解錠画面。 |

`gazo-core::webdav_mirror` がリモート Vault を `~/.gazo/webdav-cache/<hash>` へ差分ダウンロード
同期する（sync-state による etag/サイズ/Last-Modified 比較、削除反映、ローカル専用ファイル温存）。
アップロード同期（`sync_up`）はローカルの新規/変更を PUT・リモート専用を DELETE し、
双方が変化したファイルは競合として記録する（メタファイルは実バイト一致なら自動解決）。
同期アルゴリズムは `RemoteSource` トレイトで抽象化し、インメモリ偽リモートで検証している。

## ビルド・テスト

```bash
cd rust
cargo test            # 全テスト（Vault 作成→取り込み→再オープンの統合テスト含む）
cargo run -p gazo-cli -- import --help
```

### コマンド（現状）

```bash
gazo init         [--vault <dir>] [--password <str>]            # 新規アルバム作成
gazo import       [--vault <dir>] [-r] [--password <str>] [--] <パス>...  # 画像
gazo import-video [--vault <dir>] [-r] [--password <str>] [--] <パス>...  # 動画
```

`import-video` は `.mp4 .webm .m4v .mov .mkv` を `videos/` に取り込む（サムネイルなし）。

WebDAV 上のアルバムへ取り込むには `--vault` の代わりに WebDAV オプションを使う:

```bash
gazo import --webdav-endpoint https://host/remote.php/dav/files/user \
            --webdav-base-path /gazo-vault --webdav-username user [-r] <パス>...
# パスフレーズ: GAZO_PASSPHRASE / WebDAV パスワード: GAZO_WEBDAV_PASSWORD（推奨）
```

開く前にリモート→ローカルミラーへ差分ダウンロードし、取り込み後に一括アップロードする。
（現状 WebDAV 対応は `import`/`import-video` のみ。`delete`/`restore` は後続で対応予定。）

双方が変化した競合は `--on-conflict {abort|keep-local|keep-remote|copy}`（既定 `abort`）で解決する。
`copy` はローカル版を `(conflict …)` 付きで別名保存しリモートを採用する。画像類似度（dHash）に
よる対話的な判断補助は GUI フェーズで提供予定。

```bash
gazo delete       [--vault <dir>] [--password <str>] <ファイル名>...  # 画像をゴミ箱へ（論理削除）
gazo list-deleted [--vault <dir>] [--password <str>] [--limit N]      # 最近削除した画像
gazo restore      [--vault <dir>] [--password <str>] [--limit N]      # 復元
gazo delete-video [--vault <dir>] [--password <str>] <ファイル名>...  # 動画を完全削除
```

画像削除は `.gazo-trash/` への移動（タグ・表示メタも退避）で、`restore` で元に戻せる。
動画削除はゴミ箱を経由せず完全削除（Java 版と同じ）。

`init` は Java/Cryptomator 標準レイアウト（`masterkey.cryptomator` をルート直下に置き、
`vault.cryptomator` の kid を `masterkeyfile:masterkey.cryptomator`）でアルバムを作る。
oxcrypt の `VaultCreator` は masterkey を `masterkey/` サブフォルダに作るため、
JWT を自前署名して標準配置を実現している。

- パスフレーズ: `--password` → 環境変数 `GAZO_PASSPHRASE` → 対話入力。
- 終了コード: `0`=成功 / `1`=引数エラー / `2`=アルバム未検出・解錠失敗 / `3`=一部失敗。
- ディレクトリ取り込みではフォルダ名がタグとして付与される（`-r` で再帰）。
- `--vault` 省略時は `~/.gazo/settings.properties` の前回接続先（`lastVaultPath`）を使う。
  前回が WebDAV のときは未対応のため `--vault` の指定を促す。

## 移植状況

- [x] oxcrypt-core 実現可否の検証（Vault Format 8 読み書き）
- [x] `gazo-core`: 解錠 / 画像取り込み（重複名回避）/ サムネイル / タグ
- [x] `gazo-cli`: ローカル Vault への `import`
- [x] アプリ設定ファイル（`~/.gazo/settings.properties`）の読み書き
- [x] 新規 Vault 作成（Java/Cryptomator 互換のルート直下マスターキー配置）
- [x] 動画取り込み・一覧
- [x] 削除・ゴミ箱（`.gazo-trash`）と復元
- [x] WebDAV ストレージ・差分同期
  - [x] WebDAV クライアント（PROPFIND/GET/PUT/DELETE/MKCOL, Basic 認証）
  - [x] ミラー＋差分ダウンロード同期
  - [x] アップロード同期（flush）
  - [x] VaultStorage 抽象の導入と配線（`import`/`import-video` が WebDAV 対応）
  - [x] 競合解決（KEEP_LOCAL/KEEP_REMOTE/CONFLICT_COPY、`--on-conflict`）。
        dHash しきい値での対話的判定は GUI フェーズで対応
- [ ] GUI（Slint）
  - [x] アプリ骨組み + Vault 解錠画面
  - [ ] 画像ギャラリー表示（サムネイル grid）
  - [ ] タグ/検索フィルタ・ツールバー
  - [ ] 取り込み・削除・復元の GUI 操作
  - [ ] 動画 / キャンバス / スライドショー / 重複チェック
