package com.example.gazo;

import java.nio.file.Path;

/**
 * DnD インポートで画像・動画として扱う拡張子の判定（{@link VaultDnDImport} と同じ規則）。
 */
public final class ImportFileType {
    private ImportFileType() {}

    public static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (!name.isEmpty() && name.charAt(0) == '.') {
            return false;
        }
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".gif")
                || name.endsWith(".bmp")
                || name.endsWith(".webp");
    }

    public static boolean isVideo(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".mp4")
                || name.endsWith(".webm")
                || name.endsWith(".m4v")
                || name.endsWith(".mov")
                || name.endsWith(".mkv");
    }
}
