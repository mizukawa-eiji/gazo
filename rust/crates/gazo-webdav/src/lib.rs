//! 最小限の WebDAV クライアント（PROPFIND/GET/PUT/DELETE/MKCOL, Basic 認証）。
//!
//! Java 版 `com.example.gazo.vault.WebDavClient` のうち、標準的な WebDAV サーバー
//! （Nextcloud/ownCloud, Apache mod_dav 等）と通信する中核機能を移植したもの。
//! Java 版にある多数のサーバー固有ワークアラウンドは必要に応じて段階的に足す。

use std::time::Duration;

use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use quick_xml::events::Event;
use quick_xml::Reader;
use reqwest::blocking::Client;
use reqwest::Method;

/// パスセグメントで percent エンコードしない文字（RFC3986 unreserved）。
const SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'_')
    .remove(b'.')
    .remove(b'~');

const PROPFIND_BODY: &str = r#"<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
    <d:getetag/>
  </d:prop>
</d:propfind>"#;

#[derive(Debug, thiserror::Error)]
pub enum WebDavError {
    #[error("HTTP エラー: {0}")]
    Http(#[from] reqwest::Error),
    #[error("WebDAV {method} 失敗: status={status} path={path}")]
    Status {
        method: String,
        status: u16,
        path: String,
    },
    #[error("PROPFIND 応答の解析に失敗しました: {0}")]
    Parse(String),
}

pub type Result<T> = std::result::Result<T, WebDavError>;

/// PROPFIND で得られるリモートのエントリ。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoteEntry {
    /// サーバー上の正規化済みパス（先頭 `/`、末尾 `/` なし）。
    pub path: String,
    pub is_dir: bool,
    /// 不明なら -1。
    pub content_length: i64,
    /// `Last-Modified`（RFC1123 文字列）。無ければ `None`。
    pub last_modified: Option<String>,
    /// ETag（生文字列）。比較時は [`canonical_etag`] で正規化する。
    pub etag: Option<String>,
}

/// WebDAV サーバーへの接続。
pub struct WebDavClient {
    http: Client,
    endpoint: String,
    username: String,
    password: String,
}

impl WebDavClient {
    /// `endpoint` は WebDAV のベース URL（例: `https://host/remote.php/dav/files/user`）。
    pub fn new(endpoint: &str, username: &str, password: &str) -> Result<Self> {
        let http = Client::builder()
            .timeout(Duration::from_secs(300))
            .build()?;
        Ok(WebDavClient {
            http,
            endpoint: normalize_endpoint(endpoint),
            username: username.to_string(),
            password: password.to_string(),
        })
    }

    fn absolute_url(&self, path: &str) -> String {
        format!("{}{}", self.endpoint, encode_path(path))
    }

    fn method(&self, name: &str, path: &str) -> reqwest::blocking::RequestBuilder {
        let m = Method::from_bytes(name.as_bytes()).expect("valid method");
        self.http
            .request(m, self.absolute_url(path))
            .basic_auth(&self.username, Some(&self.password))
    }

    /// 指定パス配下を PROPFIND する。`depth` は 0（自身）または 1（直下の子）。
    /// depth=1 では問い合わせたパス自身は結果から除外する。
    pub fn propfind(&self, path: &str, depth: u8) -> Result<Vec<RemoteEntry>> {
        let resp = self
            .method("PROPFIND", path)
            .header("Depth", depth.to_string())
            .header("Content-Type", "application/xml; charset=utf-8")
            .body(PROPFIND_BODY)
            .send()?;
        let status = resp.status();
        if status.as_u16() != 207 {
            return Err(WebDavError::Status {
                method: "PROPFIND".into(),
                status: status.as_u16(),
                path: normalize_path(path),
            });
        }
        let body = resp.bytes()?;
        let query = normalize_path(path);
        let mut out = Vec::new();
        for raw in parse_propfind(&body).map_err(WebDavError::Parse)? {
            let p = href_to_path(&raw.href);
            if depth == 1 && p == query {
                continue; // 自身は除外
            }
            out.push(RemoteEntry {
                path: p,
                is_dir: raw.is_dir,
                content_length: raw.content_length,
                last_modified: raw.last_modified,
                etag: raw.etag,
            });
        }
        Ok(out)
    }

    /// リソースの有無（PROPFIND depth 0）。
    pub fn exists(&self, path: &str) -> Result<bool> {
        match self
            .method("PROPFIND", path)
            .header("Depth", "0")
            .header("Content-Type", "application/xml; charset=utf-8")
            .body(PROPFIND_BODY)
            .send()
        {
            Ok(resp) => {
                let code = resp.status().as_u16();
                if code == 207 {
                    Ok(true)
                } else if code == 404 {
                    Ok(false)
                } else {
                    Err(WebDavError::Status {
                        method: "PROPFIND".into(),
                        status: code,
                        path: normalize_path(path),
                    })
                }
            }
            Err(e) => Err(WebDavError::Http(e)),
        }
    }

    pub fn get(&self, path: &str) -> Result<Vec<u8>> {
        let resp = self.method("GET", path).send()?;
        let code = resp.status().as_u16();
        if !(200..300).contains(&code) {
            return Err(WebDavError::Status {
                method: "GET".into(),
                status: code,
                path: normalize_path(path),
            });
        }
        Ok(resp.bytes()?.to_vec())
    }

    pub fn put(&self, path: &str, data: &[u8]) -> Result<()> {
        let resp = self.method("PUT", path).body(data.to_vec()).send()?;
        let code = resp.status().as_u16();
        if (200..300).contains(&code) {
            Ok(())
        } else {
            Err(WebDavError::Status {
                method: "PUT".into(),
                status: code,
                path: normalize_path(path),
            })
        }
    }

    pub fn delete(&self, path: &str) -> Result<()> {
        let resp = self.method("DELETE", path).send()?;
        let code = resp.status().as_u16();
        // 既に無い(404)も成功扱い。
        if (200..300).contains(&code) || code == 404 {
            Ok(())
        } else {
            Err(WebDavError::Status {
                method: "DELETE".into(),
                status: code,
                path: normalize_path(path),
            })
        }
    }

    /// 単一コレクションを作成する。既存(405/409)は成功扱い。
    pub fn mkcol(&self, path: &str) -> Result<()> {
        let resp = self.method("MKCOL", path).send()?;
        let code = resp.status().as_u16();
        // 201=作成, 405=既に存在(Method Not Allowed), 409 は「既に存在」で返すサーバーもある。
        if (200..300).contains(&code) || code == 405 || code == 409 {
            Ok(())
        } else {
            Err(WebDavError::Status {
                method: "MKCOL".into(),
                status: code,
                path: normalize_path(path),
            })
        }
    }

    /// パス上の各セグメントを順に MKCOL する（`mkdir -p` 相当）。
    pub fn mkcol_all(&self, path: &str) -> Result<()> {
        let norm = normalize_path(path);
        let mut acc = String::new();
        for seg in norm.split('/').filter(|s| !s.is_empty()) {
            acc.push('/');
            acc.push_str(seg);
            self.mkcol(&acc)?;
        }
        Ok(())
    }
}

/// PROPFIND の生エントリ（href はサーバーが返した未デコード文字列）。
struct RawEntry {
    href: String,
    is_dir: bool,
    content_length: i64,
    last_modified: Option<String>,
    etag: Option<String>,
}

/// DAV:multistatus を解析する。名前空間プレフィックス（d:/D: 等）は無視し localname で照合。
fn parse_propfind(body: &[u8]) -> std::result::Result<Vec<RawEntry>, String> {
    let mut reader = Reader::from_reader(body);
    reader.config_mut().trim_text(true);
    let mut buf = Vec::new();
    let mut entries = Vec::new();

    let mut stack: Vec<String> = Vec::new();
    let mut in_response = false;
    let mut in_propstat = false;
    let mut href = String::new();

    // propstat 単位の一時値。
    let mut ps_status = String::new();
    let mut ps_is_dir = false;
    let mut ps_len: i64 = -1;
    let mut ps_lastmod: Option<String> = None;
    let mut ps_etag: Option<String> = None;

    // 200 の propstat から確定した値。
    let mut c_is_dir = false;
    let mut c_len: i64 = -1;
    let mut c_lastmod: Option<String> = None;
    let mut c_etag: Option<String> = None;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) => {
                let ln = local_name(e.name().as_ref());
                match ln.as_str() {
                    "response" => {
                        in_response = true;
                        href.clear();
                        c_is_dir = false;
                        c_len = -1;
                        c_lastmod = None;
                        c_etag = None;
                    }
                    "propstat" => {
                        in_propstat = true;
                        ps_status.clear();
                        ps_is_dir = false;
                        ps_len = -1;
                        ps_lastmod = None;
                        ps_etag = None;
                    }
                    _ => {}
                }
                stack.push(ln);
            }
            Ok(Event::Empty(e)) => {
                let ln = local_name(e.name().as_ref());
                if ln == "collection" && in_propstat {
                    ps_is_dir = true;
                }
            }
            Ok(Event::Text(t)) => {
                let text = t.unescape().unwrap_or_default().trim().to_string();
                if text.is_empty() {
                    continue;
                }
                match stack.last().map(String::as_str) {
                    Some("href") if in_response && !in_propstat => href.push_str(&text),
                    Some("status") if in_propstat => ps_status.push_str(&text),
                    Some("getcontentlength") if in_propstat => {
                        ps_len = text.parse::<i64>().unwrap_or(-1)
                    }
                    Some("getlastmodified") if in_propstat => ps_lastmod = Some(text),
                    Some("getetag") if in_propstat => ps_etag = Some(text),
                    _ => {}
                }
            }
            Ok(Event::End(e)) => {
                let ln = local_name(e.name().as_ref());
                if ln == "propstat" {
                    if ps_status.contains("200") {
                        c_is_dir = c_is_dir || ps_is_dir;
                        if ps_len >= 0 {
                            c_len = ps_len;
                        }
                        if ps_lastmod.is_some() {
                            c_lastmod = ps_lastmod.take();
                        }
                        if ps_etag.is_some() {
                            c_etag = ps_etag.take();
                        }
                    }
                    in_propstat = false;
                } else if ln == "response" {
                    if !href.is_empty() {
                        entries.push(RawEntry {
                            href: href.clone(),
                            is_dir: c_is_dir,
                            content_length: c_len,
                            last_modified: c_lastmod.clone(),
                            etag: c_etag.clone(),
                        });
                    }
                    in_response = false;
                }
                stack.pop();
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(e.to_string()),
            _ => {}
        }
        buf.clear();
    }
    Ok(entries)
}

/// XML タグ名から名前空間プレフィックスを除いた localname を返す。
fn local_name(qname: &[u8]) -> String {
    let s = String::from_utf8_lossy(qname);
    match s.rfind(':') {
        Some(i) => s[i + 1..].to_string(),
        None => s.to_string(),
    }
}

/// 末尾スラッシュを除いたエンドポイント。
fn normalize_endpoint(endpoint: &str) -> String {
    endpoint.trim().trim_end_matches('/').to_string()
}

/// パスを正規化する（`\`→`/`、先頭 `/` 付与、末尾 `/` 除去、ただしルートは `/`）。
pub fn normalize_path(path: &str) -> String {
    let mut p = path.trim().replace('\\', "/");
    if p.is_empty() {
        p = "/".to_string();
    }
    if !p.starts_with('/') {
        p = format!("/{p}");
    }
    while p.len() > 1 && p.ends_with('/') {
        p.pop();
    }
    p
}

/// 正規化したパスをセグメントごとに percent エンコードする。
fn encode_path(path: &str) -> String {
    let norm = normalize_path(path);
    let mut out = String::new();
    for seg in norm.split('/') {
        if seg.is_empty() {
            continue;
        }
        out.push('/');
        out.push_str(&utf8_percent_encode(seg, SEGMENT).to_string());
    }
    if out.is_empty() {
        out.push('/');
    }
    out
}

/// href（絶対 URL またはパス、percent エンコード）を正規化済みパスへ変換する。
fn href_to_path(href: &str) -> String {
    // scheme://host を除去してパス部分を取り出す。
    let path_part = match href.find("://") {
        Some(i) => {
            let rest = &href[i + 3..];
            match rest.find('/') {
                Some(j) => &rest[j..],
                None => "/",
            }
        }
        None => href,
    };
    // クエリ/フラグメントは捨てる。
    let path_part = path_part
        .split(['?', '#'])
        .next()
        .unwrap_or(path_part);
    let decoded = percent_decode_str(path_part).decode_utf8_lossy();
    normalize_path(&decoded)
}

/// sync-state と PROPFIND/PUT の ETag を比較するための正規化（`W/` と引用符を除去）。
pub fn canonical_etag(raw: Option<&str>) -> Option<String> {
    let raw = raw?;
    let mut t = raw.trim();
    if t.len() >= 2 && t[..2].eq_ignore_ascii_case("W/") {
        t = t[2..].trim();
    }
    while t.len() >= 2 && t.starts_with('"') && t.ends_with('"') {
        t = t[1..t.len() - 1].trim();
    }
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn path_normalization() {
        assert_eq!(normalize_path(""), "/");
        assert_eq!(normalize_path("gazo/"), "/gazo");
        assert_eq!(normalize_path("\\a\\b\\"), "/a/b");
        assert_eq!(normalize_path("/"), "/");
        assert_eq!(normalize_path("/a/b/"), "/a/b");
    }

    #[test]
    fn segment_encoding() {
        assert_eq!(encode_path("/gazo/a b.jpg"), "/gazo/a%20b.jpg");
        assert_eq!(encode_path("/dav/files/u/写真"), "/dav/files/u/%E5%86%99%E7%9C%9F");
        // unreserved はそのまま。
        assert_eq!(encode_path("/a-b_c.d~e"), "/a-b_c.d~e");
    }

    #[test]
    fn href_decoding() {
        assert_eq!(href_to_path("https://h/dav/a%20b"), "/dav/a b");
        assert_eq!(href_to_path("/dav/files/u/"), "/dav/files/u");
        assert_eq!(href_to_path("https://h/dav/x?y=1"), "/dav/x");
    }

    #[test]
    fn etag_canonicalization() {
        assert_eq!(canonical_etag(Some("W/\"abc\"")).as_deref(), Some("abc"));
        assert_eq!(canonical_etag(Some("\"abc\"")).as_deref(), Some("abc"));
        assert_eq!(canonical_etag(Some("  ")), None);
        assert_eq!(canonical_etag(None), None);
    }

    #[test]
    fn parse_nextcloud_style_multistatus() {
        // Nextcloud 風（D: プレフィックス、子 1 件）。Depth:1 で親自身＋子 1。
        let xml = br#"<?xml version="1.0"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/remote.php/dav/files/u/gazo/</D:href>
            <D:propstat>
              <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/remote.php/dav/files/u/gazo/vault.cryptomator</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength>123</D:getcontentlength>
                <D:getlastmodified>Tue, 01 Jan 2030 00:00:00 GMT</D:getlastmodified>
                <D:getetag>"abc123"</D:getetag>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>"#;
        let entries = parse_propfind(xml).unwrap();
        assert_eq!(entries.len(), 2);
        let dir = &entries[0];
        assert!(dir.is_dir);
        let file = &entries[1];
        assert!(!file.is_dir);
        assert_eq!(file.content_length, 123);
        assert_eq!(file.etag.as_deref(), Some("\"abc123\""));
        assert_eq!(canonical_etag(file.etag.as_deref()).as_deref(), Some("abc123"));
        assert_eq!(href_to_path(&file.href), "/remote.php/dav/files/u/gazo/vault.cryptomator");
    }

    #[test]
    fn parse_skips_404_propstat() {
        // 一部サーバーは未取得プロパティを 404 propstat で返す。200 側のみ採用。
        let xml = br#"<multistatus xmlns="DAV:">
          <response>
            <href>/dav/x.txt</href>
            <propstat>
              <prop><getcontentlength>5</getcontentlength></prop>
              <status>HTTP/1.1 200 OK</status>
            </propstat>
            <propstat>
              <prop><getetag/></prop>
              <status>HTTP/1.1 404 Not Found</status>
            </propstat>
          </response>
        </multistatus>"#;
        let entries = parse_propfind(xml).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].content_length, 5);
        assert_eq!(entries[0].etag, None);
    }
}
