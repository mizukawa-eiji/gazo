package com.example.gazo;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagFilterTest {

    @Test
    void matches_emptyActive_alwaysTrue() {
        assertTrue(TagFilter.matches(Set.of(), Set.of("a")));
        assertTrue(TagFilter.matches(null, Set.of("a")));
    }

    @Test
    void matches_untaggedOnly() {
        Set<String> active = Set.of(TagFilter.UNTAGGED_SENTINEL);
        assertTrue(TagFilter.matches(active, Set.of()));
        assertFalse(TagFilter.matches(active, Set.of("x")));
    }

    @Test
    void matches_andTags() {
        Set<String> active = new LinkedHashSet<>(Set.of("a", "b"));
        assertTrue(TagFilter.matches(active, Set.of("a", "b", "c")));
        assertFalse(TagFilter.matches(active, Set.of("a")));
    }

    @Test
    void matches_untaggedPlusRequiredTags_emptyFileTagsFalse() {
        Set<String> active = new LinkedHashSet<>(Set.of(TagFilter.UNTAGGED_SENTINEL, "a"));
        assertFalse(TagFilter.matches(active, Set.of()));
        assertFalse(TagFilter.matches(active, Set.of("a")));
    }
}
