package com.example.gazo;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 画像一覧カードのキャプション行（ファイル名・日付・タグ表示オプション）。 */
public final class ImageGalleryCaption {

    private ImageGalleryCaption() {}

    public static String build(
            Path imagePath, Set<String> tags, boolean showFileName, boolean showDate, boolean showTags) {
        List<String> lines = new ArrayList<>();
        if (showFileName) {
            String name = imagePath.getFileName().toString();
            if (showDate) {
                String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
                lines.add(name + "  -  " + date);
            } else {
                lines.add(name);
            }
        } else if (showDate) {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
            lines.add(date);
        }
        if (showTags && tags != null && !tags.isEmpty()) {
            lines.add("#" + String.join(" #", tags));
        }
        return String.join("\n", lines);
    }
}
