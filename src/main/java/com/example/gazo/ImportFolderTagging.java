package com.example.gazo;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * フォルダーから取り込むとき、ルート名およびルート以下の親フォルダ名をタグにする（CLI / GUI 共通）。
 */
public final class ImportFolderTagging {

    private ImportFolderTagging() {
    }

    /** 指定ディレクトリ名（最終要素）を trim・小文字化したタグ。 */
    public static String rootFolderTag(Path directory) {
        if (directory.getFileName() == null) {
            return "";
        }
        return directory.getFileName().toString().trim().toLowerCase();
    }

    /**
     * 取り込みルート {@code rootDirectory} から見た、{@code sourceFile} の親パス上のフォルダ名をタグにする。
     * 例: ルート {@code trip}、ファイル {@code trip/sub/a.jpg} → {@code trip}, {@code sub}。
     */
    public static Set<String> folderTagsForPathUnderRoot(Path rootDirectory, Path sourceFile) {
        Path root = rootDirectory.toAbsolutePath().normalize();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        String rootTag = rootFolderTag(root);
        if (!rootTag.isBlank()) {
            tags.add(rootTag);
        }
        Path parent = sourceFile.getParent();
        if (parent == null) {
            return tags.isEmpty() ? Collections.emptySet() : tags;
        }
        Path parentPath = parent.toAbsolutePath().normalize();
        if (!parentPath.startsWith(root)) {
            return tags.isEmpty() ? Collections.emptySet() : tags;
        }
        Path rel = root.relativize(parentPath);
        for (int i = 0; i < rel.getNameCount(); i++) {
            String seg = rel.getName(i).toString().trim().toLowerCase();
            if (!seg.isEmpty()) {
                tags.add(seg);
            }
        }
        return tags.isEmpty() ? Collections.emptySet() : tags;
    }
}
