package com.example.gazo;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;

import java.util.List;

/**
 * 画像一覧の仮想スクロール: ルート高さの見積りと、スペーサー実測後の表示スライス計算、レイアウト後の列・行ピッチ測定。
 *
 * <p>元の {@code GazoApp#applyGalleryVirtualWindow} と同じ順序を保つ: (1) 列・totalContentH 算出と {@code galleryVirtualRoot}
 * への高さ適用、(2) スペーサー実測、(3) スクロール補正つき firstIndex/count、(4) 必要なら FlowPane の子差し替え。
 */
public final class GalleryVirtualWindow {

    private GalleryVirtualWindow() {}

    /** ルート高さ確定に必要な値（スペーサー実測の前まで）。 */
    public record RootEstimate(double totalContentH, double rowPitch, int cols, int totalRows, double padV, double vgap, double vh, double tileY0) {}

    /**
     * スペーサー実測後の表示範囲。{@link #skipRebuild()} が true のときは子の載せ替え不要（ルート高さは既に適用済み）。
     */
    public record SliceApply(
            boolean skipRebuild,
            int firstIndex,
            int count,
            int cols,
            int firstRow,
            double topSpacerH,
            double bottomSpacerH,
            double galleryBlockH,
            double padV,
            double rowPitch) {}

    /**
     * ビューポート幅・列・行からコンテンツ高さを求め、{@link GalleryVirtualState#stickyCols} を更新する。
     * {@link FlowPane#setPrefWrapLength(double)} を呼ぶ。
     */
    public static RootEstimate estimateRootLayout(
            GalleryVirtualState vs,
            ScrollPane imageScrollPane,
            FlowPane gallery,
            int modelCount,
            String listViewSize) {
        Bounds vp = imageScrollPane.getViewportBounds();
        double vw = vp != null && vp.getWidth() > 0 ? vp.getWidth() : 800;
        double vh = vp != null && vp.getHeight() > 0 ? vp.getHeight() : 600;
        Insets gin = gallery.getInsets();
        double rawInnerW = Math.max(0, vw - gin.getLeft() - gin.getRight());
        gallery.setPrefWrapLength(Math.max(200, rawInnerW));

        if (vs.measuredInnerW > 0 && Math.abs(rawInnerW - vs.measuredInnerW) > 8) {
            vs.measuredCols = -1;
            vs.measuredRowPitch = -1;
            vs.measuredInnerW = -1;
            vs.firstTileMinY = -1;
        }

        double rowH = ImageGalleryLayoutMetrics.estimatedRowHeight(listViewSize);
        double cellW = ImageGalleryLayoutMetrics.estimatedCellWidth(listViewSize);
        double hgap = gallery.getHgap();
        double vgap = gallery.getVgap();
        double rowPitchEst = rowH + vgap;
        double cellPitch = cellW + hgap;
        int idealCols = Math.max(1, (int) Math.floor((rawInnerW + hgap) / cellPitch));
        int cols;
        boolean useMeasuredCols =
                vs.measuredCols > 0 && vs.measuredInnerW > 0 && Math.abs(rawInnerW - vs.measuredInnerW) < 8;
        if (useMeasuredCols) {
            cols = vs.measuredCols;
        } else if (vs.stickyCols > 0) {
            int s = vs.stickyCols;
            if (idealCols == s) {
                cols = s;
            } else if (idealCols == s + 1) {
                double needW = (s + 1) * cellPitch - hgap;
                cols = rawInnerW >= needW - cellPitch * 0.35 ? idealCols : s;
            } else if (idealCols == s - 1) {
                double needW = s * cellPitch - hgap;
                cols = rawInnerW <= needW - cellPitch * 0.35 ? idealCols : s;
            } else {
                cols = idealCols;
            }
        } else {
            cols = idealCols;
        }
        double wNeededForCols = cols * cellPitch - hgap;
        if (!useMeasuredCols && rawInnerW + 0.5 < wNeededForCols) {
            cols = idealCols;
        }
        vs.stickyCols = cols;
        int totalRows = (int) Math.ceil(modelCount / (double) cols);
        double rowPitch = rowPitchEst;

        double padTop = gin.getTop();
        double padBottom = gin.getBottom();
        double padV = padTop + padBottom;
        double totalContentH = padV + totalRows * rowPitch - vgap;

        double tileY0 = vs.firstTileMinY >= 0 ? vs.firstTileMinY : padTop;

        return new RootEstimate(totalContentH, rowPitch, cols, totalRows, padV, vgap, vh, tileY0);
    }

    /**
     * {@link RootEstimate} のあと {@code galleryVirtualRoot} に高さを入れた直後に読んだスペーサー高さで、表示スライスを求める。
     * 載せ替え不要なら {@link SliceApply#skipRebuild()} が true（この場合 {@link GalleryVirtualState#lastFirstIndex} は更新しない）。
     */
    public static SliceApply planAfterSpacers(
            GalleryVirtualState vs,
            ScrollPane imageScrollPane,
            RootEstimate est,
            int modelCount,
            double topSpacerH,
            double galleryHMeas,
            double bottomSpacerH,
            boolean force) {
        int n = modelCount;
        double vh = est.vh();
        double totalContentH = est.totalContentH();
        double rowPitch = est.rowPitch();
        int cols = est.cols();
        int totalRows = est.totalRows();
        double padV = est.padV();
        double vgap = est.vgap();
        double tileY0 = est.tileY0();

        double vmin = imageScrollPane.getVmin();
        double vmax = imageScrollPane.getVmax();
        double vspan = vmax - vmin;
        double vNorm = vspan > 1e-9 ? (imageScrollPane.getVvalue() - vmin) / vspan : 0;

        double totalH = topSpacerH + galleryHMeas + bottomSpacerH;
        if (galleryHMeas < 1.0 || totalH < 1.0 || Double.isNaN(totalH)) {
            totalH = totalContentH;
        }
        double scrollRangeActual = Math.max(0.0, totalH - vh);
        double scrollYActual = vNorm * scrollRangeActual;
        double scrollRangeModel = Math.max(0.0, totalContentH - vh);
        double scrollY;
        if (scrollRangeActual > 1e-6 && scrollRangeModel > 1e-6) {
            scrollY = scrollYActual * (scrollRangeModel / scrollRangeActual);
        } else {
            scrollY = 0.0;
        }

        int bufferRows = 8;
        int viewportRows = Math.max(1, (int) Math.ceil(vh / rowPitch));
        int maxFirstRow = Math.max(0, totalRows - viewportRows);
        int visibleRows = viewportRows + bufferRows;
        int firstRow;
        if (maxFirstRow <= 0) {
            firstRow = 0;
        } else {
            firstRow = (int) Math.floor(Math.max(0.0, scrollY - tileY0) / rowPitch);
            if (firstRow > maxFirstRow) {
                firstRow = maxFirstRow;
            }
        }
        int firstIndex = firstRow * cols;
        if (firstIndex >= n) {
            firstIndex = Math.max(0, n - cols);
        }
        int maxCount = visibleRows * cols + cols * 4;
        int count = Math.min(maxCount, n - firstIndex);
        if (count <= 0) {
            firstIndex = Math.max(0, n - 1);
            count = 1;
        }
        if (!force && firstIndex == vs.lastFirstIndex && cols == vs.lastCols) {
            return new SliceApply(true, firstIndex, count, cols, firstRow, 0, 0, 0, padV, rowPitch);
        }
        vs.lastFirstIndex = firstIndex;
        vs.lastCols = cols;

        double topH = firstRow * rowPitch;
        int rowsUsed = (int) Math.ceil(count / (double) cols);
        double bottomRows = Math.max(0, totalRows - firstRow - rowsUsed);
        double bottomH = bottomRows * rowPitch;
        double galleryBlockH = padV + rowsUsed * rowPitch - vgap;

        return new SliceApply(false, firstIndex, count, cols, firstRow, topH, bottomH, galleryBlockH, padV, rowPitch);
    }

    /** FlowPane レイアウト後の子ノードの Y から列数と行ピッチを測る。 */
    public static void measureAfterLayout(
            GalleryVirtualState vs,
            ScrollPane imageScrollPane,
            FlowPane gallery,
            List<Node> children) {
        if (children.isEmpty()) {
            return;
        }
        double y0 = children.get(0).getBoundsInParent().getMinY();
        int nFirst = 1;
        for (int i = 1; i < children.size(); i++) {
            if (Math.abs(children.get(i).getBoundsInParent().getMinY() - y0) > 4.0) {
                break;
            }
            nFirst++;
        }
        int mc;
        if (nFirst < children.size()) {
            mc = nFirst;
        } else {
            if (vs.measuredCols <= 0) {
                Bounds vp0 = imageScrollPane.getViewportBounds();
                if (vp0 != null && vp0.getWidth() > 0) {
                    Insets gin0 = gallery.getInsets();
                    vs.measuredInnerW = Math.max(0, vp0.getWidth() - gin0.getLeft() - gin0.getRight());
                }
                return;
            }
            mc = vs.measuredCols;
        }
        vs.measuredCols = Math.max(1, mc);
        vs.firstTileMinY = y0;
        if (children.size() > mc) {
            double y1 = children.get(mc).getBoundsInParent().getMinY();
            vs.measuredRowPitch = Math.max(1.0, y1 - y0);
        } else {
            vs.measuredRowPitch = -1;
        }
        Bounds vp = imageScrollPane.getViewportBounds();
        if (vp == null || vp.getWidth() <= 0) {
            return;
        }
        Insets gin = gallery.getInsets();
        vs.measuredInnerW = Math.max(0, vp.getWidth() - gin.getLeft() - gin.getRight());
    }
}
