# Gazo

Cryptomator 互換 Vault に画像を暗号化保存し、ギャラリー表示・キャンバス編集などができるデスクトップアプリです。UI は JavaFX です。

## ドキュメント

- [設計書](docs/DESIGN.md) — アーキテクチャ、データモデル、コンポーネント構成
- [ユーザーマニュアル](docs/MANUAL.md) — インストール、操作手順、CLI、トラブルシューティング

## 必要環境

- **JDK 17** 以上（プロジェクトは 17 をターゲットにビルド）
- **JavaFX**: Gradle の `run` や IDE からの実行ではプラグインが依存関係を解決します。**単体 JAR を別 OS で実行する**ときは、その OS 用にビルドした JAR を使うか、その環境で `gradle fatJar` してください（JavaFX のネイティブは OS ごとに異なります）。
- **Gradle**: コマンド例は Gradle CLI（`gradle`）前提です。プロジェクトルートで実行してください。

## ビルド

```bash
gradle build
gradle test           # ユニットテスト
gradle fatJar         # 依存ライブラリ込みの JAR（build/libs/gazo-1.0-SNAPSHOT-all.jar）
gradle jpackageWin    # Windows の app-image（build/jpackage/Gazo/）
```

`jpackageWin` は `packaging/windows/app-icon.ico` をアイコンとして使用します（Windows のタスクバー反映用）。

## GUI の起動

```bash
gradle run
```

または fat JAR:

```bash
java -jar build/libs/gazo-1.0-SNAPSHOT-all.jar
```

引数なしで起動するとウィンドウが開きます。メニュー「ファイル」から画像の追加・Vault 変更などができます。

### GUI での Vault 接続先

- 起動時は **前回接続先**（local / WebDAV）で自動接続します。
- 起動直後の Vault パスワード入力画面にも `接続先切替…` ボタンがあり、ここから接続先を変更できます。
- 接続先の切替は、画面下部の `接続先切替…` ボタン、または `ファイル -> Vault変更…` で行えます。
- 切替ダイアログで、**ローカルフォルダ**または**WebDAV**を選べます。
- WebDAV の場合は以下を入力します。
  - WebDAV URL（例: `https://example.com/remote.php/dav/files/username`）
  - Vault パス（例: `/gazo-vault`）
  - WebDAV ユーザー名
  - WebDAV パスワード（都度入力。設定ファイルへは保存しません）
- WebDAV 接続時も画像/動画/タグ/削除/キャンバス操作を同じ UI で利用できます。
- WebDAV 同期競合が起きた場合は、解決ダイアログが表示されます。
  - ローカルを優先して上書き
  - リモートを優先して取り込み
  - 競合コピー（`(conflict yyyyMMdd-HHmmss)` 付き）として別名保存
  - 画像競合では d値（dHash距離）のしきい値をダイアログ内で調整でき、次回以降も設定が保持されます
- d値のしきい値はメニュー `ファイル -> 競合比較しきい値設定…` からいつでも変更できます。

## コマンドライン（画像の取り込み）

GUI を開かずに Vault へ画像を登録できます。先頭が `import` のときだけ CLI として処理され、終了後にプロセスが終了します（GUI は起動しません）。

```bash
java -jar build/libs/gazo-1.0-SNAPSHOT-all.jar --help
java -jar build/libs/gazo-1.0-SNAPSHOT-all.jar import [オプション] <パス>...
```

### `import` のオプション

| オプション | 説明 |
|-----------|------|
| `--vault <dir>` | Vault のディレクトリ。省略時は `~/.gazo/settings.properties` の `lastVaultPath`（未設定なら `~/.gazo/vault`）。 |
| `-r` / `--recursive` | **ディレクトリ**を指定したとき、サブフォルダを再帰的にたどって画像を取り込む。 |
| `--password <文字列>` | Vault のパスフレーズ（**履歴に残るため非推奨**）。 |
| `--webdav-endpoint <url>` | WebDAV 接続 URL（例: `https://example.com/remote.php/dav/files/username`）。 |
| `--webdav-base-path <path>` | WebDAV 内の Vault パス（例: `/gazo-vault`）。 |
| `--webdav-username <name>` | WebDAV ユーザー名。 |
| `--webdav-password <文字列>` | WebDAV パスワード（**履歴に残るため非推奨**）。 |
| `--` | この後ろをすべてパスとして扱う（`-` で始まるパスを渡すときなど）。 |

### パスフレーズの渡し方（優先順）

1. 環境変数 **`GAZO_PASSPHRASE`**
2. **`--password`**
3. **対話入力**（`System.console()` が使えるときのみ）

### WebDAV パスワードの渡し方（優先順）

1. 環境変数 **`GAZO_WEBDAV_PASSWORD`**
2. **`--webdav-password`**
3. **対話入力**（`System.console()` が使えるときのみ）

### パスの扱い

- **ファイル**: 対応拡張子（`.jpg` `.jpeg` `.png` `.gif` `.bmp` `.webp`）なら 1 枚取り込み。
- **ディレクトリ（`-r` なし）**: その直下の画像ファイルのみ。
- **ディレクトリ（`-r` あり）**: 再帰的にすべてのサブフォルダ内の画像。

### 終了コード

| コード | 意味 |
|--------|------|
| 0 | 成功 |
| 1 | 引数エラーなど |
| 2 | Vault が見つからない、ロック解除失敗 |
| 3 | 一部のファイルの取り込み失敗 |

### 例

```bash
# 環境変数でパスフレーズを渡して再帰取り込み（Linux / macOS の例）
export GAZO_PASSPHRASE='...'
java -jar build/libs/gazo-1.0-SNAPSHOT-all.jar import -r /path/to/photos

# Gradle から引数だけ渡す
gradle run --args="import -r C:\Pictures\album"

# WebDAV 上の Vault へ取り込み（Linux / macOS の例）
export GAZO_PASSPHRASE='...'
export GAZO_WEBDAV_PASSWORD='...'
java -jar build/libs/gazo-1.0-SNAPSHOT-all.jar import \
  --webdav-endpoint https://example.com/remote.php/dav/files/username \
  --webdav-base-path /gazo-vault \
  --webdav-username username \
  -r /path/to/photos
```

## ライセンス・依存

依存関係は `build.gradle` を参照してください（Cryptomator `cryptofs` など）。
