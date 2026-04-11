package com.example.gazo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 画像一覧のパス列（タグ・検索・削除保留のフィルタ）。
 */
public final class ImageGalleryListing {

    private ImageGalleryListing() {}

    public static List<Path> filterPaths(
            List<Path> allImages,
            Map<String, Set<String>> tagsByFile,
            Set<String> activeTagFilters,
            String imageNameQuery,
            Predicate<Path> isPendingDelete) {
        List<Path> filtered = new ArrayList<>();
        String q = imageNameQuery == null ? "" : imageNameQuery.trim().toLowerCase();
        for (Path p : allImages) {
            if (isPendingDelete.test(p)) {
                continue;
            }
            Set<String> tags = tagsByFile.getOrDefault(p.getFileName().toString(), Set.of());
            if (!TagFilter.matches(activeTagFilters, tags)) {
                continue;
            }
            if (!q.isEmpty()) {
                String fileName = p.getFileName().toString().toLowerCase();
                if (!fileName.contains(q)) {
                    continue;
                }
            }
            filtered.add(p);
        }
        return filtered;
    }
}
