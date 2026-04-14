# Cryptomator WebDAV Bridge

Cryptomator の暗号化 Vault を WebDAV で公開するブリッジサーバーです。
[Milton](https://milton.io/) の WebDAV サーバーと [CryptoFS](https://github.com/cryptomator/cryptofs) の `java.nio.file.FileSystem` プロバイダーを組み合わせ、暗号化されたファイルを透過的に復号して WebDAV 経由でアクセスできるようにします。

## 仕組み

```
WebDAV クライアント
    ↕  (HTTP/WebDAV)
Milton HttpManager  ←  WebDavServlet (Jetty)
    ↕
NioResourceFactory  ←  java.nio.file.Path ベースの ResourceFactory
    ↕
CryptoFS FileSystem  ←  暗号化/復号を透過的に処理
    ↕
ローカルファイルシステム上の Vault ディレクトリ
```

- **Milton** が WebDAV プロトコル（PROPFIND, GET, PUT, MKCOL, DELETE, MOVE, COPY など）を処理
- **NioResourceFactory** が `java.nio.file.Path` API を Milton の `Resource` インターフェースにブリッジ
- **CryptoFS** が NIO FileSystem として動作し、暗号化/復号を透過的に行う

## 必要環境

- Java 21 以上
- Maven 3.8 以上（ビルド時）

## ビルド

```bash
mvn clean package -DskipTests
```

`target/cryptomator-webdav-bridge-1.0.0-SNAPSHOT.jar` に全依存関係を含む fat JAR が生成されます。

## 使い方

```bash
java -jar target/cryptomator-webdav-bridge-1.0.0-SNAPSHOT.jar \
  --vault /path/to/your/vault \
  --port 8080
```

パスワードは対話的にプロンプトされます。非対話環境では `--password` オプションで指定できます。

### オプション

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--vault <path>` | Cryptomator Vault ディレクトリのパス（必須） | - |
| `--port <number>` | WebDAV サーバーのポート番号 | `8080` |
| `--host <address>` | バインドアドレス | `0.0.0.0` |
| `--readonly` | 読み取り専用モードでマウント | `false` |
| `--password <pass>` | Vault のパスワード（省略時はプロンプト） | - |

### WebDAV クライアントからの接続

サーバー起動後、以下の URL で WebDAV クライアントから接続できます。

```
http://localhost:8080/
```

#### macOS Finder
Finder → 移動 → サーバへ接続 → `http://localhost:8080/`

#### Windows Explorer
エクスプローラーのアドレスバーに `\\localhost@8080\DavWWWRoot` を入力

#### Linux (GNOME Files)
ファイル → 他の場所 → サーバーへ接続 → `dav://localhost:8080/`

#### cadaver (CLI)
```bash
cadaver http://localhost:8080/
```

## アーキテクチャ

### 主要クラス

| クラス | 役割 |
|--------|------|
| `CryptomatorWebDavServer` | メインエントリポイント。Vault のオープン、Milton/Jetty の起動を行う |
| `VaultKeyLoader` | `masterkey.cryptomator` ファイルからパスフレーズで masterkey を復号 |
| `NioResourceFactory` | `java.nio.file.Path` ベースの Milton `ResourceFactory` 実装 |
| `NioResource` | NIO Path ベースの Milton Resource 抽象基底クラス |
| `NioFileResource` | ファイルリソース（GET/PUT/COPY/MOVE/DELETE） |
| `NioDirectoryResource` | ディレクトリリソース（PROPFIND/MKCOL/PUT/DELETE） |
| `WebDavServlet` | Milton HttpManager をラップする薄い Servlet |

### NioResourceFactory の汎用性

`NioResourceFactory` は CryptoFS に限らず、任意の `java.nio.file.FileSystem` と組み合わせて使えます。
例えば、ZipFileSystem や JimFS などの in-memory ファイルシステムでも動作します。

## ライセンス

- Milton Community Edition: Apache License 2.0
- CryptoFS: AGPL v3.0（商用ライセンスあり）

CryptoFS が AGPL であるため、このプロジェクト全体も AGPL v3.0 の条件に従います。
