//! Java `java.util.Properties` 互換のテキスト形式の読み書き。
//!
//! Gazo の Vault 内メタデータ（`.gazo-tags.properties` など）は Java 版が
//! `Properties.store`/`load` で書き出しているため、既存 Vault と相互運用するには
//! 同じエスケープ規則・行解釈を再現する必要がある。
//!
//! 仕様は OpenJDK の `java.util.Properties#saveConvert` / `#load0` に準拠する。
//! - 出力は ASCII（非 ASCII は `\uXXXX`、UTF-16 コードユニット単位）。
//! - キーは先頭/全空白と `= : # !` をエスケープ、値は先頭空白のみエスケープ。

use std::collections::BTreeMap;

/// 挿入順を問わないキー/値の集合。Java の `store` はソート順を保証しないが、
/// 安定した出力のためキー昇順（`BTreeMap`）で書き出す。
pub type PropMap = BTreeMap<String, String>;

/// Java `Properties.load` 互換のパース。バイト列（ISO-8859-1 とみなす）を受け取る。
pub fn load(bytes: &[u8]) -> PropMap {
    // Java の load は入力を ISO-8859-1（= 各バイト 1 文字）として読む。
    let chars: Vec<char> = bytes.iter().map(|&b| b as char).collect();
    let mut map = PropMap::new();

    let mut i = 0;
    let n = chars.len();
    while i < n {
        // 1 論理行を読む（行末 `\` は次行へ継続）。
        let (logical, next) = read_logical_line(&chars, i);
        i = next;
        if logical.is_empty() {
            continue;
        }
        // 先頭の空白を飛ばし、コメント行（# または !）を判定。
        let first = logical.iter().position(|c| !is_line_ws(*c));
        let Some(first) = first else { continue };
        let fc = logical[first];
        if fc == '#' || fc == '!' {
            continue;
        }
        // キーと値の境界を探す（未エスケープの空白 or `=` `:`）。
        let (key_raw, val_raw) = split_key_value(&logical[first..]);
        let key = load_convert(&key_raw);
        let value = load_convert(&val_raw);
        map.insert(key, value);
    }
    map
}

/// Java `Properties.store` 互換の出力。`comment` は先頭にコメント行として書く。
pub fn store(map: &PropMap, comment: Option<&str>) -> String {
    let mut out = String::new();
    if let Some(c) = comment {
        out.push('#');
        out.push_str(c);
        out.push('\n');
    }
    for (k, v) in map {
        out.push_str(&save_convert(k, true));
        out.push('=');
        out.push_str(&save_convert(v, false));
        out.push('\n');
    }
    out
}

fn is_line_ws(c: char) -> bool {
    c == ' ' || c == '\t' || c == '\u{0c}'
}

/// `start` から 1 論理行を読み、(行の文字列, 次の開始位置) を返す。
/// 行末の単一 `\` は継続とみなし次の物理行へ繋ぐ。先頭空白は呼び出し側で処理。
fn read_logical_line(chars: &[char], start: usize) -> (Vec<char>, usize) {
    let n = chars.len();
    let mut i = start;
    let mut line: Vec<char> = Vec::new();
    // 物理行の先頭空白は最初の行のみ意味を持つが、継続行の先頭空白は捨てる。
    let mut skip_leading_ws = false;
    loop {
        // 物理行を 1 本読む。
        let mut j = i;
        let mut seg: Vec<char> = Vec::new();
        while j < n && chars[j] != '\n' && chars[j] != '\r' {
            seg.push(chars[j]);
            j += 1;
        }
        // 改行をまたぐ（\r\n を 1 つに）。
        let mut next = j;
        if next < n {
            if chars[next] == '\r' && next + 1 < n && chars[next + 1] == '\n' {
                next += 2;
            } else {
                next += 1;
            }
        }

        let mut s = &seg[..];
        if skip_leading_ws {
            let p = s.iter().position(|c| !is_line_ws(*c)).unwrap_or(s.len());
            s = &s[p..];
        }

        // 行末の連続するバックスラッシュ数で継続かを判定（奇数なら継続）。
        let backslashes = s.iter().rev().take_while(|c| **c == '\\').count();
        let continued = backslashes % 2 == 1;
        if continued {
            line.extend_from_slice(&s[..s.len() - 1]); // 末尾の `\` を除く
            skip_leading_ws = true;
            i = next;
            if next >= n {
                break;
            }
            continue;
        } else {
            line.extend_from_slice(s);
            i = next;
            break;
        }
    }
    (line, i)
}

/// 先頭空白除去済みの論理行をキー/値に分割する。
fn split_key_value(s: &[char]) -> (Vec<char>, Vec<char>) {
    let n = s.len();
    let mut i = 0;
    // キー終端: 未エスケープの空白 / `=` / `:`
    while i < n {
        let c = s[i];
        if c == '\\' {
            i += 2; // エスケープされた文字はスキップ
            continue;
        }
        if is_line_ws(c) || c == '=' || c == ':' {
            break;
        }
        i += 1;
    }
    let key: Vec<char> = s[..i.min(n)].to_vec();
    // セパレータと前後の空白を飛ばす。
    let mut j = i;
    while j < n && is_line_ws(s[j]) {
        j += 1;
    }
    if j < n && (s[j] == '=' || s[j] == ':') {
        j += 1;
        while j < n && is_line_ws(s[j]) {
            j += 1;
        }
    }
    let value: Vec<char> = if j < n { s[j..].to_vec() } else { Vec::new() };
    (key, value)
}

/// `\t \n \r \f \uXXXX \\` 等のエスケープを解決する（Java `loadConvert` 相当）。
fn load_convert(chars: &[char]) -> String {
    let mut out = String::new();
    let mut i = 0;
    let n = chars.len();
    while i < n {
        let c = chars[i];
        if c == '\\' && i + 1 < n {
            i += 1;
            let e = chars[i];
            match e {
                't' => out.push('\t'),
                'n' => out.push('\n'),
                'r' => out.push('\r'),
                'f' => out.push('\u{0c}'),
                'u' => {
                    // 続く 4 桁を 16 進として読む。
                    let mut val: u32 = 0;
                    let mut k = 0;
                    while k < 4 && i + 1 < n {
                        i += 1;
                        let h = chars[i];
                        let d = h.to_digit(16);
                        match d {
                            Some(d) => val = (val << 4) | d,
                            None => break,
                        }
                        k += 1;
                    }
                    if let Some(ch) = char::from_u32(val) {
                        out.push(ch);
                    }
                }
                other => out.push(other),
            }
            i += 1;
        } else {
            out.push(c);
            i += 1;
        }
    }
    out
}

/// Java `saveConvert` 相当。`escape_space` が真ならすべての空白を、偽なら先頭空白のみエスケープ。
/// 非 ASCII は UTF-16 コードユニット単位で `\uXXXX` にする（escapeUnicode=true 固定）。
fn save_convert(s: &str, escape_space: bool) -> String {
    let mut out = String::new();
    let mut first = true;
    for c in s.chars() {
        let u = c as u32;
        // Java の高速経路: 0x3d('=') < c < 0x7f
        if u > 61 && u < 127 {
            if c == '\\' {
                out.push_str("\\\\");
            } else {
                out.push(c);
            }
            first = false;
            continue;
        }
        match c {
            ' ' => {
                if first || escape_space {
                    out.push('\\');
                }
                out.push(' ');
            }
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{0c}' => out.push_str("\\f"),
            '=' | ':' | '#' | '!' => {
                out.push('\\');
                out.push(c);
            }
            _ => {
                if u < 0x20 || u > 0x7e {
                    let mut buf = [0u16; 2];
                    for unit in c.encode_utf16(&mut buf) {
                        out.push_str(&format!("\\u{:04x}", unit));
                    }
                } else {
                    out.push(c);
                }
            }
        }
        first = false;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_ascii() {
        let mut m = PropMap::new();
        m.insert("foo.jpg".into(), "a,b,c".into());
        m.insert("with space.png".into(), "tag one,tag two".into());
        let text = store(&m, Some("gazo tags"));
        let back = load(text.as_bytes());
        assert_eq!(m, back);
    }

    #[test]
    fn round_trip_unicode() {
        let mut m = PropMap::new();
        m.insert("写真.jpg".into(), "旅行,夏".into());
        let text = store(&m, Some("gazo tags"));
        // 出力は ASCII（\u エスケープ）であること。
        assert!(text.is_ascii(), "output must be ASCII-escaped: {text}");
        let back = load(text.as_bytes());
        assert_eq!(m, back);
    }

    #[test]
    fn parses_java_style_lines() {
        // Java が書きそうな形（コメント行、= 区切り、\u エスケープ、空白エスケープ）。
        let input = "#gazo tags\nphoto\\ 1.jpg=\\u65c5\\u884c,beach\n";
        let m = load(input.as_bytes());
        assert_eq!(m.get("photo 1.jpg").map(String::as_str), Some("旅行,beach"));
    }

    #[test]
    fn key_value_colon_separator() {
        let m = load(b"key:value\n");
        assert_eq!(m.get("key").map(String::as_str), Some("value"));
    }
}
