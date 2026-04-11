package com.example.gazo;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTagsTest {

    @Test
    void parseCommaSeparated_trimsAndLowercases() {
        assertEquals(Set.of("a", "b"), UserTags.parseCommaSeparated(" A , b "));
    }

    @Test
    void parseCommaSeparated_blankPartsSkipped() {
        assertTrue(UserTags.parseCommaSeparated(" , , ").isEmpty());
    }
}
