# Cryptomator WebDAV Bridge

Cryptomator の暗号化 Vault を WebDAV 経由で公開するブリッジサーバーです。

[Milton](https://milton.io/) WebDAV サーバーライブラリと、
[CryptoFS](https://github.com/cryptomator/cryptofs) の `java.nio.file.FileSystem` 実装を組み合わせることで、
Vault のパスフレーズさえあれば復号済みファイルを任意の WebDAV クライアントからアクセスできます。

## 仕組み

```
┌──────────────┐      HTTP/WebDAV       ┌─────────────────────┐
│  WebDAV      │ ───────────────────▶  │  Milton HttpManager  │
│  クライアント │ ◀───────────────────  │  (Jetty embedded)    │
└──────────────┘                        └──────────┬──────────┘
                                                   │
                                        NioResourceFactory
                                    (java.nio.file.Path ↔ Milton Resource)
                                                   │
                                        ┌──────────▼──────────┐
                                        │  CryptoFS           │
                                        │  (java.nio.file.    │
                                        │   FileSystem)       │
                                        └──────────┬──────────┘
                                                   │
                                          暗号化/復号 (透過的)
                                                   │
                                        ┌──────────▼──────────┐
                                        │  Vault ディレクトリ  │
                                        │  (masterkey.crypto-  │
                                        │   mator + d/ + ...)  │
                                        └─────────────────────┘
```

CryptoFS は標準の `java.nio.file.FileSystem` を実装しているため、
`NioResourceFactory` は任意の NIO FileSystem を WebDAV にマッピングする汎用アダプタとして動作します。

## 前提条件

- Java 21 以上
- Maven 3.8+
- 既存の Cryptomator Vault（`masterkey.cryptomator` が存在するディレクトリ）

## ビルド

```bash
mvn clean package -DskipTests
```

`target/cryptomator-webdav-bridge-1.0.0-SNAPSHOT.jar` に uber-jar が生成されます。

## 使い方

```bash
java -jar target/cryptomator-webdav-bridge-1.0.0-SNAPSHOT.jar /path/to/vault
```

起動すると Vault のパスフレーズを対話的に聞かれます。
復号に成功すると `http://127.0.0.1:8080/` で WebDAV サーバーが起動します。

### オプション

| オプション | 説明 | デフォルト |
|---|---|---|
| `--port <port>` | 待ち受けポート | `8080` |
| `--host <addr>` | バインドアドレス | `127.0.0.1` |
| `--user <user>` | HTTP Basic 認証ユーザー名 | なし (認証無効) |
| `--password <pass>` | HTTP Basic 認証パスワード | なし |
| `--passphrase <phrase>` | Vault パスフレーズ (非対話モード) | 対話入力 |
| `--readonly` | 読み取り専用モード | 無効 |

### 使用例

```bash
# ローカルのみ、認証なし
java -jar cryptomator-webdav-bridge.jar ~/my-vault

# LAN 公開 + Basic 認証付き
java -jar cryptomator-webdav-bridge.jar ~/my-vault \
    --host 0.0.0.0 --port 8443 \
    --user alice --password s3cret

# 読み取り専用 + パスフレーズ指定（スクリプト向け）
java -jar cryptomator-webdav-bridge.jar ~/my-vault \
    --readonly --passphrase "my vault passphrase"
```

### WebDAV クライアントからの接続

- **Windows Explorer**: `\\127.0.0.1@8080\DavWWWRoot` をアドレスバーに入力
- **macOS Finder**: Finder → 移動 → サーバへ接続 → `http://127.0.0.1:8080/`
- **Linux (GVFS)**: `davs://127.0.0.1:8080/` (Nautilus のサーバー接続)
- **rclone**: `rclone config` で WebDAV タイプ、URL `http://127.0.0.1:8080/` を設定

## アーキテクチャ

### 主要コンポーネント

- **`CryptoVaultWebDavServer`** — メインクラス。CLI パース、CryptoFS によるVault アンロック、Milton + Jetty の起動を行う。
- **`NioResourceFactory`** — Milton の `ResourceFactory` 実装。WebDAV パスを NIO `Path` に変換し、ファイル/ディレクトリに応じた Milton Resource を返す。
- **`NioFileResource`** — 通常ファイル用 Milton リソース。GET / PUT (replace) / DELETE / MOVE / COPY をサポート。
- **`NioDirectoryResource`** — ディレクトリ用 Milton リソース。PROPFIND / MKCOL / PUT (create) / DELETE / MOVE / COPY をサポート。

### NioResourceFactory の汎用性

`NioResourceFactory` は CryptoFS に限らず、任意の `java.nio.file.FileSystem`
（ZIP ファイルシステム、メモリ内ファイルシステムなど）を WebDAV として公開できます。

## ライセンスについて

- CryptoFS / CryptoLib は **AGPL-3.0** (商用利用は Cryptomator Enterprise ライセンスが必要)
- Milton Community Edition は **Apache-2.0**

このプロジェクトのコード自体はこれらを組み合わせて使用するため、
配布時には AGPL-3.0 の条件に従う必要があります。

## セキュリティに関する注意

- `--passphrase` オプションはプロセス一覧にパスフレーズが表示されるため、
  運用環境では対話入力か環境変数経由での受け渡しを推奨します。
- `--host 0.0.0.0` で外部公開する場合は必ず `--user` / `--password` を設定してください。
- TLS 終端が必要な場合はリバースプロキシ（nginx, Caddy 等）を前段に置いてください。
