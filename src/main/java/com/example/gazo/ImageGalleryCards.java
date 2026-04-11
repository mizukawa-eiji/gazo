package com.example.gazo;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * メイン画像タブの FlowPane カードと、仮想ウィンドウ・可視サムネの付け外し。
 */
public final class ImageGalleryCards {
    /** 可視付近ならサムネを読み込む（FlowPane 座標系での拡張 px）。 */
    public static final double GALLERY_THUMB_LOAD_MARGIN = 520;
    /**
     * これより外側に出たカードだけ Image を外す。読み込みマージンと大きく離すとスクロール中の点滅が減る（メモリはやや増える）。
     */
    public static final double GALLERY_THUMB_UNLOAD_MARGIN = 1800;

    private static final int GALLERY_THUMB_CACHE_MAX = 320;

    static final String GALLERY_CANVAS_CHECK_KEY = "gazoCanvasCheck";
    static final String GALLERY_IMAGE_VIEW_KEY = "gazoImageView";
    static final String GALLERY_IMAGE_PATH_KEY = "gazoImagePath";
    static final String GALLERY_IMAGE_W_KEY = "gazoImageW";
    static final String GALLERY_IMAGE_H_KEY = "gazoImageH";
    static final String GALLERY_IMAGE_REQUEST_KEY = "gazoImageRequest";

    private final ImageGalleryCardsHost host;
    private final FlowPane gallery;
    private final ScrollPane imageScrollPane;
    private final VBox galleryVirtualRoot;
    private final Region galleryVirtualTopSpacer;
    private final Region galleryVirtualBottomSpacer;
    private final GalleryVirtualState galleryVirtualState;

    private boolean galleryVirtualRebuilding;

    private final ExecutorService galleryImageLoadExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gazo-gallery-image-loader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger galleryImageLoadVersion = new AtomicInteger();

    private final Map<String, Image> galleryThumbCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > GALLERY_THUMB_CACHE_MAX;
        }
    };

    private final PauseTransition galleryVisibleDebounce = new PauseTransition(Duration.millis(150));
    private final PauseTransition galleryEvictDebounce = new PauseTransition(Duration.millis(420));

    public ImageGalleryCards(
            ImageGalleryCardsHost host,
            FlowPane gallery,
            ScrollPane imageScrollPane,
            VBox galleryVirtualRoot,
            Region galleryVirtualTopSpacer,
            Region galleryVirtualBottomSpacer,
            GalleryVirtualState galleryVirtualState) {
        this.host = Objects.requireNonNull(host);
        this.gallery = Objects.requireNonNull(gallery);
        this.imageScrollPane = Objects.requireNonNull(imageScrollPane);
        this.galleryVirtualRoot = Objects.requireNonNull(galleryVirtualRoot);
        this.galleryVirtualTopSpacer = Objects.requireNonNull(galleryVirtualTopSpacer);
        this.galleryVirtualBottomSpacer = Objects.requireNonNull(galleryVirtualBottomSpacer);
        this.galleryVirtualState = Objects.requireNonNull(galleryVirtualState);
        galleryVisibleDebounce.setOnFinished(ev -> {
            applyVirtualWindow(false);
            refreshVisibleImagesNow(false);
        });
        galleryEvictDebounce.setOnFinished(ev -> refreshVisibleImagesNow(true));
    }

    /** 重複チェックなど、一覧と同じスレッドプールでサムネを読む用途。 */
    public ExecutorService imageLoadExecutor() {
        return galleryImageLoadExecutor;
    }

    public void shutdown() {
        galleryImageLoadExecutor.shutdownNow();
    }

    /**
     * Vault 再読込・フィルター変更など、一覧をまっさらに作り直す直前に呼ぶ。
     */
    public void beginFullReload() {
        galleryVisibleDebounce.stop();
        galleryEvictDebounce.stop();
        galleryImageLoadVersion.incrementAndGet();
        galleryThumbCache.clear();
        galleryVirtualState.resetForFullReload();
    }

    /**
     * FlowPane に載せるカードをスクロール位置に応じた一部だけに絞る（全件ノードを持たない）。
     */
    public void applyVirtualWindow(boolean force) {
        if (gallery == null || imageScrollPane == null) {
            return;
        }
        if (galleryVirtualRebuilding) {
            return;
        }
        List<Path> paths = host.modelPaths().get();
        int n = paths.size();
        if (n == 0) {
            galleryVirtualRebuilding = true;
            try {
                galleryVirtualTopSpacer.setMinHeight(0);
                galleryVirtualTopSpacer.setPrefHeight(0);
                galleryVirtualTopSpacer.setMaxHeight(0);
                galleryVirtualBottomSpacer.setMinHeight(0);
                galleryVirtualBottomSpacer.setPrefHeight(0);
                galleryVirtualBottomSpacer.setMaxHeight(0);
                gallery.getChildren().clear();
                galleryVirtualRoot.setMinHeight(Region.USE_COMPUTED_SIZE);
                galleryVirtualRoot.setPrefHeight(Region.USE_COMPUTED_SIZE);
                galleryVirtualRoot.setMaxHeight(Region.USE_COMPUTED_SIZE);
            } finally {
                galleryVirtualRebuilding = false;
            }
            galleryVirtualState.resetAfterEmptyModel();
            return;
        }
        String listViewSize = host.listViewSize().get();
        GalleryVirtualWindow.RootEstimate est =
                GalleryVirtualWindow.estimateRootLayout(
                        galleryVirtualState, imageScrollPane, gallery, n, listViewSize);
        galleryVirtualRoot.setMinHeight(est.totalContentH());
        galleryVirtualRoot.setPrefHeight(est.totalContentH());
        galleryVirtualRoot.setMaxHeight(est.totalContentH());

        double topSpacerMeas = galleryVirtualTopSpacer.getHeight();
        double galleryHMeas = gallery.getBoundsInLocal().getHeight();
        double bottomSpacerMeas = galleryVirtualBottomSpacer.getHeight();

        GalleryVirtualWindow.SliceApply apply =
                GalleryVirtualWindow.planAfterSpacers(
                        galleryVirtualState,
                        imageScrollPane,
                        est,
                        n,
                        topSpacerMeas,
                        galleryHMeas,
                        bottomSpacerMeas,
                        force);

        if (apply.skipRebuild()) {
            return;
        }

        int firstIndex = apply.firstIndex();
        int count = apply.count();

        galleryVirtualRebuilding = true;
        try {
            galleryVirtualTopSpacer.setMinHeight(apply.topSpacerH());
            galleryVirtualTopSpacer.setPrefHeight(apply.topSpacerH());
            galleryVirtualTopSpacer.setMaxHeight(apply.topSpacerH());
            galleryVirtualBottomSpacer.setMinHeight(apply.bottomSpacerH());
            galleryVirtualBottomSpacer.setPrefHeight(apply.bottomSpacerH());
            galleryVirtualBottomSpacer.setMaxHeight(apply.bottomSpacerH());

            gallery.setMinHeight(apply.galleryBlockH());
            gallery.setPrefHeight(apply.galleryBlockH());

            gallery.getChildren().clear();
            Map<String, Set<String>> tagMap = host.tagsByFileName().get();
            for (int i = 0; i < count; i++) {
                Path p = paths.get(firstIndex + i);
                Set<String> tags = tagMap.getOrDefault(p.getFileName().toString(), Set.of());
                gallery.getChildren().add(createSnapCard(p, tags));
            }
        } finally {
            galleryVirtualRebuilding = false;
        }
        Platform.runLater(this::measureVirtualLayoutAfterLayout);
    }

    /**
     * FlowPane レイアウト後の子ノードの Y 座標から列数と行ピッチを測り、次回の仮想ウィンドウ計算に使う。
     */
    public void measureVirtualLayoutAfterLayout() {
        if (gallery == null || imageScrollPane == null || galleryVirtualRebuilding) {
            return;
        }
        List<Node> ch = gallery.getChildren();
        GalleryVirtualWindow.measureAfterLayout(galleryVirtualState, imageScrollPane, gallery, ch);
    }

    /** スクロール等から呼ぶ。短時間に何度も走る操作はデバウンスしてまとめる。 */
    public void refreshVisibleImagesDebounced() {
        galleryVisibleDebounce.playFromStart();
        galleryEvictDebounce.playFromStart();
    }

    /** 一覧の可視サムネを更新。スクロール中は {@code evictDistantImages == false} にして画像を外さないとちらつきが減る。 */
    public void refreshVisibleImagesNow() {
        refreshVisibleImagesNow(true);
    }

    public void refreshVisibleImagesNow(boolean evictDistantImages) {
        if (imageScrollPane == null || gallery == null || gallery.getScene() == null || galleryVirtualRoot == null) {
            return;
        }
        if (galleryVirtualRebuilding) {
            return;
        }
        int version = galleryImageLoadVersion.get();
        Bounds vp = imageScrollPane.getViewportBounds();
        if (vp == null || vp.getWidth() <= 0 || vp.getHeight() <= 0) {
            return;
        }
        double vw = vp.getWidth();
        double vh = vp.getHeight();
        Bounds galleryBounds = gallery.getBoundsInLocal();
        double galleryW = galleryBounds.getWidth();
        double galleryH = galleryBounds.getHeight();
        if (galleryW <= 0 || galleryH <= 0) {
            return;
        }
        double topSpacerH = galleryVirtualTopSpacer.getHeight();
        double bottomSpacerH = galleryVirtualBottomSpacer.getHeight();
        double totalH = topSpacerH + galleryH + bottomSpacerH;
        if (totalH <= 0) {
            return;
        }
        Bounds rootBounds = galleryVirtualRoot.getBoundsInLocal();
        double contentW = rootBounds.getWidth() > 0 ? rootBounds.getWidth() : galleryW;
        double hmin = imageScrollPane.getHmin();
        double hmax = imageScrollPane.getHmax();
        double hspan = hmax - hmin;
        double hNorm = hspan > 1e-9 ? (imageScrollPane.getHvalue() - hmin) / hspan : 0;
        double vmin = imageScrollPane.getVmin();
        double vmax = imageScrollPane.getVmax();
        double vspan = vmax - vmin;
        double vNorm = vspan > 1e-9 ? (imageScrollPane.getVvalue() - vmin) / vspan : 0;
        double scrollRangeX = Math.max(0, contentW - vw);
        double minVisibleX = scrollRangeX * hNorm;
        double maxVisibleX = minVisibleX + vw;
        double scrollRangeY = Math.max(0, totalH - vh);
        double minVisibleY = scrollRangeY * vNorm;
        double maxVisibleY = minVisibleY + vh;
        double flowY1 = minVisibleY - topSpacerH;
        double flowY2 = maxVisibleY - topSpacerH;
        double lm = GALLERY_THUMB_LOAD_MARGIN;
        double um = GALLERY_THUMB_UNLOAD_MARGIN;
        double loadX1 = minVisibleX - lm;
        double loadY1 = flowY1 - lm;
        double loadX2 = maxVisibleX + lm;
        double loadY2 = flowY2 + lm;
        double keepX1 = minVisibleX - um;
        double keepY1 = flowY1 - um;
        double keepX2 = maxVisibleX + um;
        double keepY2 = flowY2 + um;
        for (Node n : gallery.getChildren()) {
            if (!(n instanceof VBox card)) {
                continue;
            }
            Object ivObj = card.getProperties().get(GALLERY_IMAGE_VIEW_KEY);
            Object pathObj = card.getProperties().get(GALLERY_IMAGE_PATH_KEY);
            Object wObj = card.getProperties().get(GALLERY_IMAGE_W_KEY);
            Object hObj = card.getProperties().get(GALLERY_IMAGE_H_KEY);
            if (!(ivObj instanceof ImageView iv)
                    || !(pathObj instanceof Path path)
                    || !(wObj instanceof Integer w)
                    || !(hObj instanceof Integer h)) {
                continue;
            }
            Bounds b = card.getBoundsInParent();
            boolean inLoadBand = b.getMaxX() >= loadX1 && b.getMinX() <= loadX2 && b.getMaxY() >= loadY1 && b.getMinY() <= loadY2;
            boolean inKeepBand = b.getMaxX() >= keepX1 && b.getMinX() <= keepX2 && b.getMaxY() >= keepY1 && b.getMinY() <= keepY2;
            if (inLoadBand) {
                if (iv.getImage() == null) {
                    requestGalleryImageLoad(iv, path, w, h, version);
                }
            } else if (evictDistantImages && !inKeepBand && iv.getImage() != null) {
                iv.setImage(null);
                iv.getProperties().remove(GALLERY_IMAGE_REQUEST_KEY);
            } else if (evictDistantImages && !inKeepBand) {
                iv.getProperties().remove(GALLERY_IMAGE_REQUEST_KEY);
            }
        }
    }

    /**
     * 縦スクロール: 仮想ウィンドウの先頭行が変わり得る。大きく飛んだときだけ即 {@link #applyVirtualWindow}。
     */
    public void onVerticalScroll(Number ov, Number nv) {
        if (ov == null || nv == null) {
            refreshVisibleImagesDebounced();
            return;
        }
        double delta = Math.abs(nv.doubleValue() - ov.doubleValue());
        if (delta > 0.06) {
            galleryVisibleDebounce.stop();
            galleryEvictDebounce.playFromStart();
            applyVirtualWindow(true);
            refreshVisibleImagesNow(false);
        } else {
            refreshVisibleImagesDebounced();
        }
    }

    /**
     * 横スクロール: 縦スライスは同じなので {@link #applyVirtualWindow} は呼ばない（全カード作り直しによるちらつき防止）。
     */
    public void onHorizontalScroll(Number ov, Number nv) {
        if (ov == null || nv == null) {
            refreshVisibleImagesDebounced();
            return;
        }
        double delta = Math.abs(nv.doubleValue() - ov.doubleValue());
        if (delta > 0.06) {
            galleryVisibleDebounce.stop();
            galleryEvictDebounce.playFromStart();
            refreshVisibleImagesNow(false);
        } else {
            refreshVisibleImagesDebounced();
        }
    }

    public void onViewportBoundsChanged(Bounds oldB, Bounds newB) {
        if (newB == null || newB.getWidth() <= 0 || newB.getHeight() <= 0) {
            return;
        }
        if (oldB == null
                || Math.abs(newB.getWidth() - oldB.getWidth()) > 8
                || Math.abs(newB.getHeight() - oldB.getHeight()) > 8) {
            galleryVisibleDebounce.stop();
            galleryEvictDebounce.stop();
            applyVirtualWindow(true);
            refreshVisibleImagesNow(true);
        } else {
            refreshVisibleImagesDebounced();
        }
    }

    private void requestGalleryImageLoad(ImageView target, Path path, int w, int h, int version) {
        String requestId = version + "|" + path + "|" + w + "x" + h;
        Image cached = galleryThumbCache.get(ImageGalleryLayoutMetrics.thumbCacheKey(path, w, h));
        if (cached != null) {
            target.getProperties().put(GALLERY_IMAGE_REQUEST_KEY, requestId);
            target.setImage(cached);
            return;
        }
        Object prev = target.getProperties().get(GALLERY_IMAGE_REQUEST_KEY);
        if (requestId.equals(prev)) {
            return;
        }
        target.getProperties().put(GALLERY_IMAGE_REQUEST_KEY, requestId);
        galleryImageLoadExecutor.submit(() -> {
            Image img = host.thumbnailLoader().load(path, w, h);
            Platform.runLater(() -> {
                Object current = target.getProperties().get(GALLERY_IMAGE_REQUEST_KEY);
                if (!requestId.equals(current)) {
                    return;
                }
                if (img != null) {
                    galleryThumbCache.put(ImageGalleryLayoutMetrics.thumbCacheKey(path, w, h), img);
                }
                target.setImage(img);
            });
        });
    }

    public void refreshSelectionStyles() {
        for (Node n : gallery.getChildren()) {
            if (!(n instanceof VBox card)) {
                continue;
            }
            if (!(card.getUserData() instanceof Path path)) {
                continue;
            }
            boolean sel = host.listCheckedSelection().contains(path);
            card.setStyle(snapCardBorderStyle(sel));
            Object chk = card.getProperties().get(GALLERY_CANVAS_CHECK_KEY);
            if (chk instanceof CheckBox box) {
                if (box.isSelected() != sel) {
                    box.setSelected(sel);
                }
            }
        }
    }

    private String snapCardBorderStyle(boolean selectedForCanvas) {
        String borderColor = selectedForCanvas ? "#7a9a5b" : "#d5cec0";
        return "-fx-background-color: #fffdf8; -fx-border-color: "
                + borderColor
                + "; -fx-border-width: 2; -fx-border-radius: 2; -fx-background-radius: 2;";
    }

    public VBox createSnapCard(Path imagePath, Set<String> captionTags) {
        String listViewSize = host.listViewSize().get();
        int[] imageSize = ImageGalleryLayoutMetrics.imageSizeByCode(listViewSize);
        int imageW = imageSize[0];
        int imageH = imageSize[1];
        double outerW = ImageGalleryLayoutMetrics.cardOuterWidth(listViewSize);

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setFitWidth(imageW);
        view.setFitHeight(imageH);
        view.setSmooth(true);
        view.setCache(true);
        view.setCacheHint(CacheHint.SPEED);
        view.setImage(
                galleryThumbCache.get(ImageGalleryLayoutMetrics.thumbCacheKey(imagePath, imageW, imageH)));

        StackPane photoArea = new StackPane(view);
        photoArea.setPadding(new Insets(12, 12, 6, 12));
        photoArea.setStyle("-fx-background-color: #fbfaf8;");
        photoArea.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                host.cardActions().openOriginalViewer(imagePath);
                e.consume();
            }
        });

        String captionText =
                ImageGalleryCaption.build(
                        imagePath,
                        captionTags,
                        host.showFileName().getAsBoolean(),
                        host.showDate().getAsBoolean(),
                        host.showTags().getAsBoolean());
        Label caption = new Label(captionText);
        caption.setWrapText(true);
        caption.setMaxWidth(Math.max(80, outerW - 24));
        caption.setMaxHeight(80);
        caption.setTextOverrun(OverrunStyle.CLIP);
        caption.setAlignment(Pos.CENTER_LEFT);
        caption.setStyle("-fx-text-fill: #4f4a41; -fx-font-size: 12px; -fx-font-family: 'Segoe UI';");
        caption.setVisible(!captionText.isBlank());
        caption.setManaged(!captionText.isBlank());

        boolean selectedForCanvas = host.listCheckedSelection().contains(imagePath);
        CheckBox canvasPickCheck = new CheckBox();
        canvasPickCheck.setSelected(selectedForCanvas);
        canvasPickCheck.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-padding: 2 4 2 4;");
        StackPane.setAlignment(canvasPickCheck, Pos.TOP_LEFT);
        StackPane.setMargin(canvasPickCheck, new Insets(6, 0, 0, 6));
        photoArea.getChildren().add(canvasPickCheck);

        Set<String> canvasLayouts =
                host.canvasLayoutsByFileName()
                        .get()
                        .getOrDefault(imagePath.getFileName().toString(), Set.of());
        if (!canvasLayouts.isEmpty()) {
            List<String> sortedLayouts = new ArrayList<>(canvasLayouts);
            Collections.sort(sortedLayouts);
            String tipText = "キャンバスに登録: " + String.join(", ", sortedLayouts);
            String shortMark = sortedLayouts.size() == 1 ? sortedLayouts.get(0) : sortedLayouts.size() + "件";
            if (shortMark.length() > 12) {
                shortMark = shortMark.substring(0, 11) + "…";
            }
            Label canvasRegBadge = new Label(shortMark);
            canvasRegBadge.setTooltip(new Tooltip(tipText));
            canvasRegBadge.setStyle(
                    "-fx-background-color: rgba(238,232,220,0.95); -fx-text-fill: #4a4030; -fx-font-size: 10px; -fx-padding: 2 5;");
            StackPane.setAlignment(canvasRegBadge, Pos.TOP_RIGHT);
            StackPane.setMargin(canvasRegBadge, new Insets(6, 6, 0, 0));
            photoArea.getChildren().add(canvasRegBadge);
        }

        VBox card = new VBox(photoArea, caption);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(0, 12, 18, 12));
        card.setMinWidth(outerW);
        card.setPrefWidth(outerW);
        card.setMaxWidth(outerW);
        card.setStyle(snapCardBorderStyle(selectedForCanvas));
        card.setUserData(imagePath);
        card.getProperties().put(GALLERY_CANVAS_CHECK_KEY, canvasPickCheck);
        card.getProperties().put(GALLERY_IMAGE_VIEW_KEY, view);
        card.getProperties().put(GALLERY_IMAGE_PATH_KEY, imagePath);
        card.getProperties().put(GALLERY_IMAGE_W_KEY, imageW);
        card.getProperties().put(GALLERY_IMAGE_H_KEY, imageH);
        ImageGalleryCardActions actions = host.cardActions();
        canvasPickCheck.setOnAction(e -> {
            if (canvasPickCheck.isSelected()) {
                host.listCheckedSelection().add(imagePath);
            } else {
                host.listCheckedSelection().remove(imagePath);
            }
            card.setStyle(snapCardBorderStyle(host.listCheckedSelection().contains(imagePath)));
            actions.onGalleryCardSelectionChanged();
        });
        double cardH = ImageGalleryLayoutMetrics.estimatedRowHeight(listViewSize);
        card.setMinHeight(cardH);
        card.setPrefHeight(cardH);
        card.setMaxHeight(cardH);

        ContextMenu cardCtx = new ContextMenu();
        MenuItem miEditTags = new MenuItem("タグを編集");
        miEditTags.setOnAction(e -> actions.editTags(imagePath));
        MenuItem miBulkAdd = new MenuItem("タグ一括追加...");
        miBulkAdd.setOnAction(e -> actions.bulkAddTagsToSelection());
        MenuItem miBulkRemove = new MenuItem("タグ一括削除...");
        miBulkRemove.setOnAction(e -> actions.bulkRemoveTagsFromSelection());
        MenuItem miDupThis = new MenuItem("この画像の重複/類似を検索");
        miDupThis.setOnAction(e -> actions.duplicateCheckThisImage(imagePath));
        MenuItem miDupSelected = new MenuItem("選択画像の重複/類似を検索");
        miDupSelected.setOnAction(e -> actions.duplicateCheckSelection());
        cardCtx.getItems().addAll(
                miEditTags,
                miBulkAdd,
                miBulkRemove,
                new SeparatorMenuItem(),
                miDupThis,
                miDupSelected);
        cardCtx.setOnShowing(e -> {
            boolean none = host.listCheckedSelection().isEmpty();
            miBulkAdd.setDisable(none);
            miBulkRemove.setDisable(none);
            miDupSelected.setDisable(none);
        });
        card.setOnContextMenuRequested(e -> cardCtx.show(card, e.getScreenX(), e.getScreenY()));
        return card;
    }
}
