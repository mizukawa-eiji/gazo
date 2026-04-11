package com.example.gazo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ユーザー入力（カンマ区切り）から正規化済みタグ集合へ。
 */
public final class UserTags {
    private UserTags() {}

    /** カンマ区切りを分割し、前後空白を除き、小文字に正規化する。 */
    public static Set<String> parseCommaSeparated(String text) {
        Set<String> tags = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tags;
        }
        for (String raw : text.split(",")) {
            String normalized = raw.trim().toLowerCase();
            if (!normalized.isEmpty()) {
                tags.add(normalized);
            }
        }
        return tags;
    }
}
