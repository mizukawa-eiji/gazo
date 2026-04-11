package com.example.gazo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * メイン画面のタグ絞り込みと同じ AND／「タグなし」ルール。
 */
public final class TagFilter {
    private TagFilter() {}

    /**
     * タグ絞り込みで「タグなし」を表す。実タグ名としては使わない。キャンバス追加ダイアログからも参照する。
     */
    public static final String UNTAGGED_SENTINEL = "__gazo_untagged__";

    /**
     * メイン画面のタグ欄と同じ AND／「タグなし」ルール。キャンバス追加ダイアログなどメインとは独立した条件セット用。
     */
    public static boolean matches(Set<String> activeTagFilters, Set<String> fileTags) {
        if (activeTagFilters == null || activeTagFilters.isEmpty()) {
            return true;
        }
        boolean wantUntagged = activeTagFilters.contains(UNTAGGED_SENTINEL);
        Set<String> requiredTags = new LinkedHashSet<>(activeTagFilters);
        requiredTags.remove(UNTAGGED_SENTINEL);
        if (wantUntagged) {
            if (!fileTags.isEmpty()) {
                return false;
            }
            return requiredTags.isEmpty();
        }
        return fileTags.containsAll(requiredTags);
    }
}
