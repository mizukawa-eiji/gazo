//! 実 HTTP 往復の検証。外部依存なしで、std の TcpListener による最小モックサーバを立て、
//! reqwest が正しいメソッド・パス・認証ヘッダ・本文を送り、応答を解釈できることを確認する。

use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::mpsc;
use std::thread;

use gazo_webdav::WebDavClient;

struct CapturedRequest {
    method: String,
    target: String,
    authorization: Option<String>,
    body: Vec<u8>,
}

/// 1 リクエストを読み取り、(captured, raw response to send) を決める。
/// `responder` は受信リクエストを見て応答バイト列を返す。
fn read_request(stream: &mut std::net::TcpStream) -> CapturedRequest {
    // ヘッダ終端 \r\n\r\n まで読む。
    let mut buf = Vec::new();
    let mut tmp = [0u8; 1024];
    let header_end;
    loop {
        let n = stream.read(&mut tmp).expect("read");
        if n == 0 {
            header_end = buf.len();
            break;
        }
        buf.extend_from_slice(&tmp[..n]);
        if let Some(pos) = find_subslice(&buf, b"\r\n\r\n") {
            header_end = pos + 4;
            break;
        }
    }
    let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
    let mut lines = head.split("\r\n");
    let request_line = lines.next().unwrap_or("");
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("").to_string();

    let mut authorization = None;
    let mut content_length = 0usize;
    for line in lines {
        // ヘッダ名は大小無視（hyper は HTTP/1.1 で小文字名を送る）。
        let Some((name, value)) = line.split_once(": ") else {
            continue;
        };
        match name.to_ascii_lowercase().as_str() {
            "authorization" => authorization = Some(value.to_string()),
            "content-length" => content_length = value.trim().parse().unwrap_or(0),
            _ => {}
        }
    }

    // 既に読み込んだ本文分 + 残りを読む。
    let mut body = buf[header_end..].to_vec();
    while body.len() < content_length {
        let n = stream.read(&mut tmp).expect("read body");
        if n == 0 {
            break;
        }
        body.extend_from_slice(&tmp[..n]);
    }

    CapturedRequest {
        method,
        target,
        authorization,
        body,
    }
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|w| w == needle)
}

fn http_response(status_line: &str, body: &[u8]) -> Vec<u8> {
    let mut out = format!(
        "HTTP/1.1 {status_line}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    )
    .into_bytes();
    out.extend_from_slice(body);
    out
}

#[test]
fn put_get_propfind_delete_mkcol_roundtrip() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (tx, rx) = mpsc::channel::<CapturedRequest>();

    // 5 リクエストを順に処理するサーバスレッド。
    let server = thread::spawn(move || {
        for _ in 0..5 {
            let (mut stream, _) = listener.accept().unwrap();
            let req = read_request(&mut stream);
            let resp = match req.method.as_str() {
                "PUT" => http_response("201 Created", b""),
                "GET" => http_response("200 OK", b"hello world"),
                "PROPFIND" => http_response(
                    "207 Multi-Status",
                    br#"<multistatus xmlns="DAV:">
                      <response><href>/gazo</href>
                        <propstat><prop><resourcetype><collection/></resourcetype></prop>
                        <status>HTTP/1.1 200 OK</status></propstat></response>
                      <response><href>/gazo/a.txt</href>
                        <propstat><prop><getcontentlength>11</getcontentlength><getetag>"e1"</getetag></prop>
                        <status>HTTP/1.1 200 OK</status></propstat></response>
                    </multistatus>"#,
                ),
                "DELETE" => http_response("204 No Content", b""),
                "MKCOL" => http_response("201 Created", b""),
                _ => http_response("500 Internal Server Error", b""),
            };
            stream.write_all(&resp).unwrap();
            stream.flush().ok();
            tx.send(req).unwrap();
        }
    });

    let endpoint = format!("http://127.0.0.1:{port}");
    let client = WebDavClient::new(&endpoint, "alice", "s3cret").unwrap();

    // PUT
    client.put("/gazo/a.txt", b"hello world").unwrap();
    // GET
    let got = client.get("/gazo/a.txt").unwrap();
    assert_eq!(got, b"hello world");
    // PROPFIND depth 1 — 親 /gazo は除外され a.txt のみ。
    let entries = client.propfind("/gazo", 1).unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].path, "/gazo/a.txt");
    assert_eq!(entries[0].content_length, 11);
    assert_eq!(entries[0].etag.as_deref(), Some("\"e1\""));
    // DELETE
    client.delete("/gazo/a.txt").unwrap();
    // MKCOL
    client.mkcol("/gazo/sub").unwrap();

    server.join().unwrap();

    // サーバが受け取ったリクエストを検証。
    let reqs: Vec<CapturedRequest> = rx.try_iter().collect();
    assert_eq!(reqs.len(), 5);

    let put = &reqs[0];
    assert_eq!(put.method, "PUT");
    assert_eq!(put.target, "/gazo/a.txt");
    assert_eq!(put.body, b"hello world");
    // Basic 認証ヘッダ（base64("alice:s3cret")）。
    assert_eq!(put.authorization.as_deref(), Some("Basic YWxpY2U6czNjcmV0"));

    assert_eq!(reqs[1].method, "GET");
    assert_eq!(reqs[2].method, "PROPFIND");
    assert_eq!(reqs[3].method, "DELETE");
    assert_eq!(reqs[4].method, "MKCOL");
    assert_eq!(reqs[4].target, "/gazo/sub");
}
