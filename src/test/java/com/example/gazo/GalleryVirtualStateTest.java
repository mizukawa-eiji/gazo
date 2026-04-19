package com.example.gazo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GalleryVirtualStateTest {

    @Test
    void resetForFullReload_clearsAllTrackedFields() {
        GalleryVirtualState s = new GalleryVirtualState();
        s.lastFirstIndex = 3;
        s.lastCols = 4;
        s.stickyCols = 5;
        s.measuredCols = 6;
        s.measuredRowPitch = 7;
        s.measuredInnerW = 8;
        s.firstTileMinY = 9;

        s.resetForFullReload();

        assertEquals(Integer.MIN_VALUE, s.lastFirstIndex);
        assertEquals(-1, s.lastCols);
        assertEquals(-1, s.stickyCols);
        assertEquals(-1, s.measuredCols);
        assertEquals(-1, s.measuredRowPitch, 0.0);
        assertEquals(-1, s.measuredInnerW, 0.0);
        assertEquals(-1, s.firstTileMinY, 0.0);
    }

    @Test
    void resetAfterEmptyModel_preservesLastCols() {
        GalleryVirtualState s = new GalleryVirtualState();
        s.lastCols = 7;
        s.lastFirstIndex = 10;
        s.stickyCols = 2;

        s.resetAfterEmptyModel();

        assertEquals(7, s.lastCols);
        assertEquals(Integer.MIN_VALUE, s.lastFirstIndex);
        assertEquals(-1, s.stickyCols);
        assertEquals(-1, s.measuredCols);
    }
}
