package com.example.gazo;

/**
 * 一覧サイズ（小／中／大）に応じたカード寸法と、仮想スクロール用の行高・セル幅。
 */
public final class ImageGalleryLayoutMetrics {

    private ImageGalleryLayoutMetrics() {}

    public static int[] imageSizeByCode(String code) {
        return switch (code) {
            case "小" -> new int[] {160, 150};
            case "大" -> new int[] {320, 300};
            default -> new int[] {240, 225};
        };
    }

    /**
     * 1 行あたりの高さ（カードの固定高さ・FlowPane の行ピッチと一致させる）。
     */
    public static double estimatedRowHeight(String listViewSize) {
        int[] sz = imageSizeByCode(listViewSize);
        double photoBlockH = 12 + 12 + 6 + sz[1];
        double captionMaxH = 80;
        double vboxBottomPad = 18;
        double borderFudge = 16;
        return photoBlockH + captionMaxH + vboxBottomPad + borderFudge;
    }

    /**
     * 一覧カードの外側幅。仮想スクロールの列・cellPitch と FlowPane の折り返しを一致させる。
     */
    public static double cardOuterWidth(String listViewSize) {
        int[] sz = imageSizeByCode(listViewSize);
        return sz[0] + 28 + 24;
    }

    public static double estimatedCellWidth(String listViewSize) {
        return cardOuterWidth(listViewSize);
    }

    public static String thumbCacheKey(java.nio.file.Path path, int w, int h) {
        return path.normalize().toString() + "|" + w + "x" + h;
    }
}
