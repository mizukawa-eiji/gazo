# Gazo

Cryptomator 互換 Vault に画像を暗号化保存し、ギャラリー表示・キャンバス編集などができるデスクトップアプリです。UI は JavaFX です。

## 必要環境

- **JDK 17** 以上（プロジェクトは 17 をターゲットにビルド）
- **JavaFX**: Gradle の `run` や IDE からの実行ではプラグインが依存関係を解決します。**単体 JAR を別 OS で実行する**ときは、その OS 用にビルドした JAR を使うか、その環境で `./gradlew fatJar` してください（JavaFX のネイティブは OS ごとに異なります）。

## ビルド

```bash
./gradlew build          # Windows: gradlew.bat build
./gradlew fatJar         # 依存ライブラリ込みの JAR（build/libs/gazo-1.0-SNAPSHOT-all.jar）
./gradlew jpackageWin    # Windows の app-image（build/jpackage/Gazo/）
```

`jpackageWin` は `packaging/windows/app-icon.ico` をアイコンとして使用します（Windows のタスクバー反映用）。

## GUI の起動

```bash
./gradlew run
```

または fat JAR:

```bash
java -jar build/libs/gazo-1.0-SNAPSHOT-all.jar
```

引数なしで起動するとウィンドウが開きます。メニュー「操作」から画像の追加・Vault 変更などができます。

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
| `--` | この後ろをすべてパスとして扱う（`-` で始まるパスを渡すときなど）。 |

### パスフレーズの渡し方（優先順）

1. 環境変数 **`GAZO_PASSPHRASE`**
2. **`--password`**
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
./gradlew run --args="import -r C:\Pictures\album"
```

## ライセンス・依存

依存関係は `build.gradle` を参照してください（Cryptomator `cryptofs` など）。
