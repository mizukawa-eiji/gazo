package com.example.gazo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageGalleryLayoutMetricsTest {

    @Test
    void imageSizeByCode_knownSizes() {
        assertArrayEquals(new int[] {160, 150}, ImageGalleryLayoutMetrics.imageSizeByCode("小"));
        assertArrayEquals(new int[] {320, 300}, ImageGalleryLayoutMetrics.imageSizeByCode("大"));
    }

    @Test
    void imageSizeByCode_defaultIsMedium() {
        assertArrayEquals(new int[] {240, 225}, ImageGalleryLayoutMetrics.imageSizeByCode("中"));
        assertArrayEquals(new int[] {240, 225}, ImageGalleryLayoutMetrics.imageSizeByCode("unknown"));
    }

    @Test
    void estimatedRowHeight_increasesWithLargerCard() {
        double small = ImageGalleryLayoutMetrics.estimatedRowHeight("小");
        double medium = ImageGalleryLayoutMetrics.estimatedRowHeight("中");
        double large = ImageGalleryLayoutMetrics.estimatedRowHeight("大");
        assertTrue(small < medium && medium < large);
    }

    @Test
    void cardOuterWidth_matchesImageWidthPlusPadding() {
        int[] sz = ImageGalleryLayoutMetrics.imageSizeByCode("小");
        assertEquals(sz[0] + 28 + 24, ImageGalleryLayoutMetrics.cardOuterWidth("小"), 0.001);
    }

    @Test
    void thumbCacheKey_includesNormalizedPathAndDimensions() {
        Path p = Path.of("a", "b", "c.png");
        String key = ImageGalleryLayoutMetrics.thumbCacheKey(p, 120, 80);
        assertTrue(key.endsWith("|120x80"));
        assertTrue(key.contains("c.png"));
    }
}
