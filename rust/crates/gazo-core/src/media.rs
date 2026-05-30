//! 取り込み対象ファイルの判定。Java 版 `CliImport`/`GazoVaultService` と一致させる。

/// 対応画像拡張子（小文字・ドット付き）。
pub const IMAGE_EXTENSIONS: &[&str] = &[".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"];

/// 対応動画拡張子（小文字・ドット付き）。将来の動画取り込み用。
pub const VIDEO_EXTENSIONS: &[&str] = &[".mp4", ".webm", ".m4v", ".mov", ".mkv"];

/// 画像ファイル名か（拡張子で判定）。先頭 `.` のファイルは対象外。
pub fn is_image_file_name(name: &str) -> bool {
    if name.starts_with('.') {
        return false;
    }
    let lower = name.to_lowercase();
    IMAGE_EXTENSIONS.iter().any(|e| lower.ends_with(e))
}

/// ユーザー可視のメディア名か（空でなく、先頭が `.` でない）。
/// `._foo.jpg` のような Apple メタデータを弾く。
pub fn is_visible_user_media_name(name: &str) -> bool {
    !name.is_empty() && !name.starts_with('.')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn image_detection() {
        assert!(is_image_file_name("a.JPG"));
        assert!(is_image_file_name("photo.webp"));
        assert!(!is_image_file_name(".hidden.jpg"));
        assert!(!is_image_file_name("note.txt"));
    }

    #[test]
    fn visibility() {
        assert!(is_visible_user_media_name("a.jpg"));
        assert!(!is_visible_user_media_name("._a.jpg"));
        assert!(!is_visible_user_media_name(""));
    }
}
