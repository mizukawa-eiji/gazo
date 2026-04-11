package com.example.gazo;

/**
 * 画像ギャラリー仮想スクロールの測定値・直近ウィンドウ（列数・先頭インデックスなど）。
 * {@link GalleryVirtualWindow} が更新する。
 */
public final class GalleryVirtualState {
    public int lastFirstIndex = Integer.MIN_VALUE;
    public int lastCols = -1;
    public int stickyCols = -1;
    public int measuredCols = -1;
    public double measuredRowPitch = -1;
    public double measuredInnerW = -1;
    public double firstTileMinY = -1;

    public void resetForFullReload() {
        lastFirstIndex = Integer.MIN_VALUE;
        lastCols = -1;
        stickyCols = -1;
        measuredCols = -1;
        measuredRowPitch = -1;
        measuredInnerW = -1;
        firstTileMinY = -1;
    }

    public void resetAfterEmptyModel() {
        lastFirstIndex = Integer.MIN_VALUE;
        stickyCols = -1;
        measuredCols = -1;
        measuredRowPitch = -1;
        measuredInnerW = -1;
        firstTileMinY = -1;
    }
}
