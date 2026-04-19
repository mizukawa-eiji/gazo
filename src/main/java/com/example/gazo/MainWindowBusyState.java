package com.example.gazo;

import javafx.scene.control.ProgressBar;

/**
 * メインウィンドウの処理中オーバーレイ（{@link GazoApp}）の表示状態。
 */
public record MainWindowBusyState(boolean busy, String message, double progressFraction) {
    /** プログレスバーを出さない（テキストのみ） */
    public static final double NO_PROGRESS = Double.NaN;

    public static MainWindowBusyState idle() {
        return new MainWindowBusyState(false, "", NO_PROGRESS);
    }

    public static MainWindowBusyState busy(String message) {
        return new MainWindowBusyState(true, message, NO_PROGRESS);
    }

    /**
     * @param progressFraction 0..1、または {@link #NO_PROGRESS} でバー非表示
     */
    public static MainWindowBusyState busy(String message, double progressFraction) {
        return new MainWindowBusyState(true, message, progressFraction);
    }

    public static MainWindowBusyState busy(String message, int done, int total) {
        if (total <= 0) {
            return new MainWindowBusyState(true, message, NO_PROGRESS);
        }
        if (done <= 0) {
            // 0/total は「まだ一件も終わっていない」だけで、実際には処理中。空のバーに見えないよう不定表示にする。
            return new MainWindowBusyState(true, message, ProgressBar.INDETERMINATE_PROGRESS);
        }
        double f = Math.min(1.0, Math.max(0.0, (double) done / total));
        return new MainWindowBusyState(true, message, f);
    }
}
