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
| `gazo-core` | Vault 解錠・画像取り込み・サムネイル生成・タグ（Java `Properties` 互換）。`VaultOperations` を平文パスベースで利用しマウント不要。 |
| `gazo-cli`  | `gazo import` コマンド（Java `CliImport` 相当、ローカル Vault 対象）。 |

## ビルド・テスト

```bash
cd rust
cargo test            # 全テスト（Vault 作成→取り込み→再オープンの統合テスト含む）
cargo run -p gazo-cli -- import --help
```

### import コマンド（現状）

```bash
gazo import [--vault <dir>] [-r] [--password <str>] [--] <パス>...
```

- パスフレーズ: `--password` → 環境変数 `GAZO_PASSPHRASE` → 対話入力。
- 終了コード: `0`=成功 / `1`=引数エラー / `2`=アルバム未検出・解錠失敗 / `3`=一部失敗。
- ディレクトリ取り込みではフォルダ名がタグとして付与される（`-r` で再帰）。

## 移植状況

- [x] oxcrypt-core 実現可否の検証（Vault Format 8 読み書き）
- [x] `gazo-core`: 解錠 / 画像取り込み（重複名回避）/ サムネイル / タグ
- [x] `gazo-cli`: ローカル Vault への `import`
- [ ] WebDAV ストレージ・差分同期
- [ ] 動画取り込み・一覧
- [ ] GUI（Slint）
