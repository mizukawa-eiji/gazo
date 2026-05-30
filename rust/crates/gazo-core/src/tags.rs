//! タグの正規化・パース・フォルダ由来タグ算出。Java 版 `GazoVaultService` /
//! `ImportFolderTagging` の挙動に一致させる。

use std::collections::BTreeSet;
use std::path::Path;

/// カンマ区切り文字列をタグ集合へ。各要素は trim + 小文字化、空要素は除外。
pub fn parse_tags(raw: &str) -> BTreeSet<String> {
    let mut set = BTreeSet::new();
    if raw.trim().is_empty() {
        return set;
    }
    for part in raw.split(',') {
        let t = part.trim().to_lowercase();
        if !t.is_empty() {
            set.insert(t);
        }
    }
    set
}

/// タグ集合を正規化（trim + 小文字 + 空除外）し、昇順のカンマ区切りへ。
/// Java の `normalizeTags` は `String.compareTo`（UTF-16 コードポイント順）で
/// ソートするが、ここでは `BTreeSet`/`sort` のバイト/スカラー順で十分実用的。
pub fn join_normalized<'a, I>(tags: I) -> String
where
    I: IntoIterator<Item = &'a str>,
{
    let mut v: Vec<String> = tags
        .into_iter()
        .map(|t| t.trim().to_lowercase())
        .filter(|t| !t.is_empty())
        .collect();
    v.sort();
    v.dedup();
    v.join(",")
}

/// ディレクトリ名（最終要素）を trim + 小文字化したタグ。
pub fn root_folder_tag(directory: &Path) -> String {
    directory
        .file_name()
        .map(|n| n.to_string_lossy().trim().to_lowercase())
        .unwrap_or_default()
}

/// 取り込みルートから見た `source_file` の親パス上のフォルダ名をタグにする。
/// 例: ルート `trip`、ファイル `trip/sub/a.jpg` → {`trip`, `sub`}。
pub fn folder_tags_for_path_under_root(root: &Path, source_file: &Path) -> BTreeSet<String> {
    let root = normalize_abs(root);
    let mut tags = BTreeSet::new();
    let root_tag = root_folder_tag(&root);
    if !root_tag.trim().is_empty() {
        tags.insert(root_tag);
    }
    let Some(parent) = source_file.parent() else {
        return tags;
    };
    let parent = normalize_abs(parent);
    let Ok(rel) = parent.strip_prefix(&root) else {
        return tags;
    };
    for seg in rel.components() {
        let s = seg.as_os_str().to_string_lossy().trim().to_lowercase();
        if !s.is_empty() {
            tags.insert(s);
        }
    }
    tags
}

/// 絶対パス化 + `.`/`..` の簡易正規化（実ファイル存在に依存しない）。
fn normalize_abs(p: &Path) -> std::path::PathBuf {
    use std::path::Component;
    let abs = if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|c| c.join(p))
            .unwrap_or_else(|_| p.to_path_buf())
    };
    let mut out = std::path::PathBuf::new();
    for c in abs.components() {
        match c {
            Component::ParentDir => {
                out.pop();
            }
            Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn parse_and_join() {
        let set = parse_tags(" A, b ,, B ,c");
        assert!(set.contains("a") && set.contains("b") && set.contains("c"));
        assert_eq!(join_normalized(["B", "a", "a", " c "]), "a,b,c");
    }

    #[test]
    fn folder_tags() {
        let root = PathBuf::from("/photos/trip");
        let file = PathBuf::from("/photos/trip/sub/a.jpg");
        let tags = folder_tags_for_path_under_root(&root, &file);
        assert!(tags.contains("trip"));
        assert!(tags.contains("sub"));
    }
}
