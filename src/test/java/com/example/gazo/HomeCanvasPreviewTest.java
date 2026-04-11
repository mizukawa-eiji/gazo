package com.example.gazo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HomeCanvasPreviewTest {

    @Test
    void parseLayoutNameFromLabelText_nullAndEmpty() {
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText(null));
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText(""));
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText("   "));
    }

    @Test
    void parseLayoutNameFromLabelText_dashPlaceholders() {
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText("—"));
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText("-"));
    }

    @Test
    void parseLayoutNameFromLabelText_bracketedName() {
        assertEquals("foo", HomeCanvasPreview.parseLayoutNameFromLabelText("「foo」"));
        assertEquals("ab", HomeCanvasPreview.parseLayoutNameFromLabelText("「ab」"));
    }

    @Test
    void parseLayoutNameFromLabelText_tooShortBracketed() {
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText("「a」"));
    }

    @Test
    void parseLayoutNameFromLabelText_notBracketed() {
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText("plain"));
        assertNull(HomeCanvasPreview.parseLayoutNameFromLabelText("「open"));
    }
}
