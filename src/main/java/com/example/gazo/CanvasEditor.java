package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

public final class CanvasEditor {
    public static final double BASE_CANVAS_WIDTH = 2000.0;
    public static final double BASE_CANVAS_HEIGHT = 1400.0;

    private final CanvasEditorHost host;

    public CanvasEditor(CanvasEditorHost host) {
        this.host = Objects.requireNonNull(host);
    }

    private GazoVaultService vault() {
        return host.vault().get();
    }

    public void renderRandomPickCanvas(Pane canvas, List<Path> paths) {
        canvas.getChildren().clear();
        double frameW = canvas.getPrefWidth();
        double frameH = canvas.getPrefHeight();
        double fillScale = computeLayoutFillScale(paths.size(), frameW, frameH);
        Random random = new Random();
        List<double[]> placed = new ArrayList<>();
        for (Path path : paths) {
            double variety = 0.72 + random.nextDouble() * 0.48;
            double scale = (0.62 + random.nextDouble() * 0.65) * fillScale * variety;
            scale = Math.max(0.45, Math.min(2.5, scale));
            String sizeCode = scaleToDisplaySize(scale);
            VBox item = createCanvasItem(path, "default", false, 1.0, sizeCode, paths);
            if (item == null) {
                continue;
            }
            item.applyCss();
            item.autosize();
            double w = Math.max(140, item.prefWidth(-1));
            double h = Math.max(140, item.prefHeight(-1));
            double[] pos = selectBestLayoutPosition(random, frameW, frameH, w, h, placed, "コラージュ風", 0.45, 0.35);
            item.relocate(pos[0], pos[1]);
            canvas.getChildren().add(item);
            placed.add(new double[]{pos[0], pos[1], w, h});
        }
    }

    /**
     * キャンバス編集の「取っ手」を、キャンバス内容の外側（スクロール枠の右下）に置く。
     * 取っ手をキャンバス Pane 上に置くと、大きいキャンバスでは画面外にあり、縮小表示では極小になるため見失いやすい。
     */
    /** キャンバス編集でドラッグ／数値指定の両方に使う下限（論理ピクセル）。 */
    public static final double CANVAS_EDIT_MIN_WIDTH = 600.0;
    public static final double CANVAS_EDIT_MIN_HEIGHT = 450.0;

    public void installCanvasResizeHandle(StackPane viewportOverlay, Pane canvas, Runnable onResizeFinished) {
        Region handle = new Region();
        handle.setPrefSize(48, 48);
        handle.setMinSize(48, 48);
        handle.setMaxSize(48, 48);
        handle.setStyle(
                "-fx-background-color: linear-gradient(135deg, #faf6ef 0%, #d8d0c4 45%, #a69f93 100%);"
                        + "-fx-border-color: #5a5348; -fx-border-width: 2.5; -fx-background-radius: 6;");
        handle.setCursor(Cursor.SE_RESIZE);
        handle.setOpacity(0.98);
        Tooltip.install(
                handle,
                new Tooltip(
                        "ドラッグでキャンバスの幅・高さを変更。"
                                + " Shift を押しながらドラッグで素早く広げられます。"
                                + " 数値指定はツールバーの「キャンバスサイズ…」でも行えます。"));

        StackPane.setAlignment(handle, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(handle, new Insets(0, 6, 6, 0));

        final double[] drag = new double[4]; // startX, startY, startW, startH
        handle.setOnMousePressed(event -> {
            drag[0] = event.getScreenX();
            drag[1] = event.getScreenY();
            drag[2] = canvas.getPrefWidth();
            drag[3] = canvas.getPrefHeight();
            event.consume();
        });
        handle.setOnMouseDragged(event -> {
            double dx = event.getScreenX() - drag[0];
            double dy = event.getScreenY() - drag[1];
            double mult = event.isShiftDown() ? 2.0 : 1.0;
            double nextW = Math.max(CANVAS_EDIT_MIN_WIDTH, drag[2] + dx * mult);
            double nextH = Math.max(CANVAS_EDIT_MIN_HEIGHT, drag[3] + dy * mult);
            canvas.setPrefSize(nextW, nextH);
            event.consume();
        });
        handle.setOnMouseReleased(event -> {
            onResizeFinished.run();
            event.consume();
        });

        viewportOverlay.getChildren().add(handle);
    }

    /**
     * 現在のキャンバスの配置・変形・キャンバスサイズを新規キャンバスへ複製する。
     */
    public void copyCanvasLayoutState(String sourceLayout, String targetLayout) throws IOException {
        String source = (sourceLayout == null || sourceLayout.isBlank()) ? "default" : sourceLayout;
        String target = (targetLayout == null || targetLayout.isBlank()) ? "default" : targetLayout;
        if (source.equals(target)) {
            return;
        }
        var size = vault().getCanvasSize(source);
        if (size != null) {
            vault().setCanvasSize(target, size.width(), size.height());
        }
        List<Path> sourceSelection = vault().listCanvasSelectionOrder(source);
        vault().saveCanvasSelectionOrder(target, sourceSelection);
        for (Path p : sourceSelection) {
            var pos = vault().getCanvasPosition(source, p);
            if (pos != null) {
                vault().setCanvasPosition(target, p, pos.x(), pos.y());
            }
            var tf = vault().getCanvasTransform(source, p);
            if (tf != null) {
                vault().setCanvasTransform(target, p, tf.scale(), tf.rotation());
            }
        }
    }

    public void renderCanvasItems(
            Pane canvas,
            String layoutName,
            boolean interactive,
            Path selectedPath,
            Consumer<Path> onSelect,
            Consumer<Path> onRemove) {
        renderCanvasItems(canvas, layoutName, interactive, selectedPath, onSelect, onRemove, null, null);
    }

    /**
     * 右クリックメニュー用。CustomMenuItem + Slider は hideOnClick(false) で操作時に閉じない。
     */
    private VBox buildCanvasContextMenuScaleSlider(
            VBox item, Path path, String layoutName, Consumer<Path> onTransformPersisted) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(2, 10, 6, 10));
        Label headline = new Label("拡大（0.5〜2.4）");
        headline.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        Slider slider = new Slider(0.5, 2.4, 1);
        slider.setMinWidth(120);
        slider.setPrefWidth(200);
        slider.setMaxWidth(Double.MAX_VALUE);
        slider.setShowTickLabels(false);
        slider.setShowTickMarks(false);
        double gs = (Double) item.getProperties().getOrDefault("gazoScale", 1.0);
        double initial = Math.max(0.5, Math.min(2.4, gs));
        slider.setValue(initial);
        Spinner<Double> valueSpinner = new Spinner<>();
        valueSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.5, 2.4, initial, 0.05));
        valueSpinner.setEditable(true);
        valueSpinner.setPrefWidth(88);
        valueSpinner.setMinWidth(72);
        final boolean[] linkIgnore = {false};
        Runnable applyScale = () -> {
            double logical = Math.max(0.5, Math.min(2.4, slider.getValue()));
            applyCanvasImageLogicalScale(path, layoutName, logical);
            Double ratioObj = (Double) item.getProperties().getOrDefault("gazoCanvasScaleRatio", 1.0);
            double vis = Math.max(0.2, Math.min(4.0, logical * ratioObj));
            item.setScaleX(vis);
            item.setScaleY(vis);
            item.getProperties().put("gazoScale", logical);
            if (onTransformPersisted != null) {
                onTransformPersisted.accept(path);
            }
            flashCanvasTransformOverlay(item);
        };
        slider.valueProperty().addListener((o, ov, nv) -> {
            double logical = Math.max(0.5, Math.min(2.4, nv.doubleValue()));
            applyScale.run();
            if (!linkIgnore[0]) {
                linkIgnore[0] = true;
                try {
                    valueSpinner.getValueFactory().setValue(logical);
                } finally {
                    linkIgnore[0] = false;
                }
            }
        });
        valueSpinner.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null || linkIgnore[0]) {
                return;
            }
            double v = Math.max(0.5, Math.min(2.4, nv.doubleValue()));
            linkIgnore[0] = true;
            try {
                slider.setValue(v);
            } finally {
                linkIgnore[0] = false;
            }
        });
        HBox row = new HBox(8, slider, valueSpinner);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        box.getChildren().addAll(headline, row);
        return box;
    }

    private VBox buildCanvasContextMenuRotationSlider(
            VBox item, Path path, String layoutName, Consumer<Path> onTransformPersisted) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(2, 10, 6, 10));
        Label headline = new Label("回転（−180〜180°）");
        headline.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        Slider slider = new Slider(-180, 180, 0);
        slider.setMinWidth(120);
        slider.setPrefWidth(200);
        slider.setMaxWidth(Double.MAX_VALUE);
        slider.setShowTickLabels(false);
        slider.setShowTickMarks(false);
        Double rotStored = (Double) item.getProperties().getOrDefault("gazoRotation", item.getRotate());
        double disp = normalizeRotationForSlider(rotStored);
        disp = Math.max(-180, Math.min(180, disp));
        slider.setValue(disp);
        Spinner<Double> valueSpinner = new Spinner<>();
        valueSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-180.0, 180.0, disp, 1.0));
        valueSpinner.setEditable(true);
        valueSpinner.setPrefWidth(88);
        valueSpinner.setMinWidth(72);
        final boolean[] linkIgnore = {false};
        Runnable applyRotation = () -> {
            double deg = slider.getValue();
            applyCanvasImageLogicalRotation(path, layoutName, deg);
            item.setRotate(deg);
            item.getProperties().put("gazoRotation", deg);
            if (onTransformPersisted != null) {
                onTransformPersisted.accept(path);
            }
            flashCanvasTransformOverlay(item);
        };
        slider.valueProperty().addListener((o, ov, nv) -> {
            applyRotation.run();
            if (!linkIgnore[0]) {
                linkIgnore[0] = true;
                try {
                    valueSpinner.getValueFactory().setValue(slider.getValue());
                } finally {
                    linkIgnore[0] = false;
                }
            }
        });
        valueSpinner.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null || linkIgnore[0]) {
                return;
            }
            double v = Math.max(-180, Math.min(180, nv.doubleValue()));
            linkIgnore[0] = true;
            try {
                slider.setValue(v);
            } finally {
                linkIgnore[0] = false;
            }
        });
        HBox row = new HBox(8, slider, valueSpinner);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        box.getChildren().addAll(headline, row);
        return box;
    }

    public void renderCanvasItems(
            Pane canvas,
            String layoutName,
            boolean interactive,
            Path selectedPath,
            Consumer<Path> onSelect,
            Consumer<Path> onRemove,
            Consumer<Path> onTransformPersisted,
            Consumer<Path> onBringToFront) {
        canvas.getChildren().removeIf(node -> node instanceof VBox);
        double canvasW = Math.max(1, canvas.getPrefWidth());
        double canvasH = Math.max(1, canvas.getPrefHeight());
        double sizeRatio = Math.min(canvasW / BASE_CANVAS_WIDTH, canvasH / BASE_CANVAS_HEIGHT);
        sizeRatio = Math.max(0.35, Math.min(3.0, sizeRatio));
        // メイン「キャンバス」タブのプレビューなど、描画後に canvasSelection が元へ戻るため、
        // オリジナル表示の ←/→ 用にこの時点の一覧を固定する。
        List<Path> pathsSnapshot = new ArrayList<>(host.canvasSelection());
        int index = 0;
        for (Path path : pathsSnapshot) {
            VBox item = createCanvasItem(path, layoutName, interactive, sizeRatio, null, pathsSnapshot, onTransformPersisted);
            if (item == null) {
                continue;
            }
            double x = 30 + (index % 4) * 360;
            double y = 30 + (index / 4) * 380;
            try {
                var pos = vault().getCanvasPosition(layoutName, path);
                if (pos != null) {
                    // 0..1 は比率座標として解釈（旧データは絶対座標のまま互換読み込み）。
                    x = (pos.x() >= 0.0 && pos.x() <= 1.2) ? pos.x() * canvasW : pos.x();
                    y = (pos.y() >= 0.0 && pos.y() <= 1.2) ? pos.y() * canvasH : pos.y();
                }
            } catch (IOException ignored) {
                // keep default position
            }
            if (interactive) {
                makeDraggable(item, path, layoutName, canvas);
                item.setOnMouseClicked(ev -> {
                    if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 1 && onSelect != null) {
                        onSelect.accept(path);
                        ev.consume();
                    }
                });
                if (onRemove != null || onBringToFront != null || onTransformPersisted != null) {
                    ContextMenu menu = new ContextMenu();
                    if (onTransformPersisted != null) {
                        CustomMenuItem scaleItem =
                                new CustomMenuItem(buildCanvasContextMenuScaleSlider(item, path, layoutName, onTransformPersisted), false);
                        CustomMenuItem rotItem =
                                new CustomMenuItem(buildCanvasContextMenuRotationSlider(item, path, layoutName, onTransformPersisted), false);
                        menu.getItems().addAll(scaleItem, rotItem);
                        menu.getItems().add(new SeparatorMenuItem());
                    }
                    if (onBringToFront != null) {
                        MenuItem frontItem = new MenuItem("前面に表示");
                        frontItem.setOnAction(e -> onBringToFront.accept(path));
                        menu.getItems().add(frontItem);
                    }
                    if (onRemove != null) {
                        MenuItem removeItem = new MenuItem("キャンバスから除去");
                        removeItem.setOnAction(e -> onRemove.accept(path));
                        menu.getItems().add(removeItem);
                    }
                    item.setOnContextMenuRequested(ev -> {
                        menu.show(item, ev.getScreenX(), ev.getScreenY());
                        ev.consume();
                    });
                }
            }
            item.applyCss();
            item.autosize();
            double itemW = Math.max(120, item.prefWidth(-1));
            double itemH = Math.max(120, item.prefHeight(-1));
            double maxX = Math.max(0, canvas.getPrefWidth() - itemW - 8);
            double maxY = Math.max(0, canvas.getPrefHeight() - itemH - 8);
            double clampedX = Math.max(8, Math.min(x, maxX));
            double clampedY = Math.max(8, Math.min(y, maxY));
            item.relocate(clampedX, clampedY);
            if (selectedPath != null && selectedPath.equals(path)) {
                item.setStyle(item.getStyle() + "; -fx-border-color: #2d7ff9; -fx-border-width: 3;");
            }
            canvas.getChildren().add(item);
            index++;
        }
    }

    public void autoLayoutCanvas(Pane canvas, String layoutName, String presetName, double overlapTuning, double neatTuning) {
        if (host.canvasSelection().isEmpty()) {
            return;
        }
        double frameW = canvas.getPrefWidth();
        double frameH = canvas.getPrefHeight();
        double fillScale = computeLayoutFillScale(host.canvasSelection().size(), frameW, frameH);
        Random random = new Random();
        boolean neat = "整列風".equals(presetName);
        List<double[]> placed = new ArrayList<>(); // x, y, w, h
        for (Path path : host.canvasSelection()) {
            double scaleVariance = neat ? (0.2 + (1.0 - neatTuning) * 0.2) : (0.35 + (1.0 - neatTuning) * 0.35);
            double scaleBase = neat ? 0.95 : 0.75;
            double scale = (scaleBase + random.nextDouble() * (scaleVariance + 0.15)) * fillScale;
            scale = Math.max(0.45, Math.min(2.5, scale));
            String sizeCode = scaleToDisplaySize(scale);
            int[] wh = canvasDimensionsForSizeCode(sizeCode);
            double w = wh[0];
            double h = wh[1];
            double[] best = selectBestLayoutPosition(random, frameW, frameH, w, h, placed, presetName, overlapTuning, neatTuning);
            double x = best[0];
            double y = best[1];
            try {
                vault().setCanvasPosition(layoutName, path, x / Math.max(1, frameW), y / Math.max(1, frameH));
                vault().setDisplaySize(path, sizeCode);
            } catch (IOException e) {
                GazoFx.showWarn("自動レイアウト保存エラー", e.getMessage());
            }
            placed.add(new double[]{x, y, w, h});
        }
    }

    private double[] selectBestLayoutPosition(Random random, double frameW, double frameH, double w, double h, List<double[]> placed, String presetName, double overlapTuning, double neatTuning) {
        boolean neat = "整列風".equals(presetName);
        double minX = 24;
        double minY = 24;
        double maxX = Math.max(minX, frameW - w - 24);
        double maxY = Math.max(minY, frameH - h - 24);

        double bestX = minX;
        double bestY = minY;
        double bestScore = Double.POSITIVE_INFINITY;

        int attempts = neat ? 180 : 120;
        for (int attempt = 0; attempt < attempts; attempt++) {
            double x = minX + random.nextDouble() * (maxX - minX + 1);
            double y = minY + random.nextDouble() * (maxY - minY + 1);
            double overlap = overlapArea(x, y, w, h, placed);
            double aesthetic = aestheticPenalty(x, y, w, h, frameW, frameH, neat, neatTuning);
            double overlapWeight = (neat ? 10.0 : 4.0) + overlapTuning * 10.0;
            double score = overlap * overlapWeight + aesthetic;
            if (score < bestScore) {
                bestScore = score;
                bestX = x;
                bestY = y;
                if (overlap == 0.0 && aesthetic < (neat ? 60.0 : 120.0)) {
                    break;
                }
            }
        }
        return new double[]{bestX, bestY};
    }

    private double overlapArea(double x, double y, double w, double h, List<double[]> placed) {
        double total = 0.0;
        for (double[] p : placed) {
            total += intersectionArea(x, y, w, h, p[0], p[1], p[2], p[3]);
        }
        return total;
    }

    private double intersectionArea(double ax, double ay, double aw, double ah, double bx, double by, double bw, double bh) {
        double ix = Math.max(0, Math.min(ax + aw, bx + bw) - Math.max(ax, bx));
        double iy = Math.max(0, Math.min(ay + ah, by + bh) - Math.max(ay, by));
        return ix * iy;
    }

    private double aestheticPenalty(double x, double y, double w, double h, double frameW, double frameH, boolean neat, double neatTuning) {
        double cx = x + w / 2.0;
        double cy = y + h / 2.0;

        // thirds + edge anchors
        double[] anchorX = new double[]{frameW * 0.2, frameW * (1.0 / 3.0), frameW * 0.5, frameW * (2.0 / 3.0), frameW * 0.8};
        double[] anchorY = new double[]{frameH * 0.2, frameH * (1.0 / 3.0), frameH * 0.5, frameH * (2.0 / 3.0), frameH * 0.8};
        double dx = minDistance(cx, anchorX);
        double dy = minDistance(cy, anchorY);

        // too close to border is a little penalized, but still allowed
        double border = Math.min(Math.min(x, y), Math.min(frameW - (x + w), frameH - (y + h)));
        double borderPenalty = border < 12 ? (12 - border) * (neat ? 6 : 3) : 0;
        double anchorWeight = (neat ? 0.45 : 0.2) + neatTuning * 0.6;
        return dx * anchorWeight + dy * anchorWeight + borderPenalty;
    }

    private double minDistance(double v, double[] anchors) {
        double min = Double.POSITIVE_INFINITY;
        for (double a : anchors) {
            min = Math.min(min, Math.abs(v - a));
        }
        return min;
    }

    /**
     * 枚数が少なくキャンバスが広いほど 1 を超え、詰まっているほど 1 未満に寄せる（空白が多いとき画像を大きくする）。
     */
    private double computeLayoutFillScale(int imageCount, double frameW, double frameH) {
        if (imageCount <= 0) {
            return 1.0;
        }
        double canvasArea = Math.max(1.0, frameW * frameH);
        double avgSlotArea = canvasArea / imageCount;
        double targetSide = Math.sqrt(avgSlotArea * 0.40);
        double scale = targetSide / 300.0;
        return Math.max(0.55, Math.min(2.5, scale));
    }

    private String scaleToDisplaySize(double scale) {
        if (scale < 0.9) {
            return "S";
        }
        if (scale < 1.15) {
            return "M";
        }
        if (scale < 1.32) {
            return "L";
        }
        return "XL";
    }

    private int[] canvasDimensionsForSizeCode(String size) {
        return switch (size) {
            case "S" -> new int[]{220, 220};
            case "L" -> new int[]{360, 360};
            case "XL" -> new int[]{440, 440};
            default -> new int[]{300, 300};
        };
    }

    private VBox createCanvasItem(Path path, String layoutName, boolean interactive, double canvasSizeRatio) {
        return createCanvasItem(path, layoutName, interactive, canvasSizeRatio, null, null, null);
    }

    private VBox createCanvasItem(Path path, String layoutName, boolean interactive, double canvasSizeRatio, String sizeCodeOverride) {
        return createCanvasItem(path, layoutName, interactive, canvasSizeRatio, sizeCodeOverride, null, null);
    }

    /**
     * @param sizeCodeOverride 非 null のとき Vault の表示サイズより優先（ランダムピックのプレビュー用）
     * @param viewerNavSource 非 null のときオリジナル表示の ←/→ の対象（ランダムピック時は当該ピックの一覧）。null のときはキャンバス選択一覧を使う。
     */
    private VBox createCanvasItem(Path path, String layoutName, boolean interactive, double canvasSizeRatio, String sizeCodeOverride, List<Path> viewerNavSource) {
        return createCanvasItem(path, layoutName, interactive, canvasSizeRatio, sizeCodeOverride, viewerNavSource, null);
    }

    /**
     * @param onTransformPersisted キャンバス編集でホイール等による変形を Vault に保存した直後に呼ぶ（UI 連動用）
     */
    private VBox createCanvasItem(
            Path path,
            String layoutName,
            boolean interactive,
            double canvasSizeRatio,
            String sizeCodeOverride,
            List<Path> viewerNavSource,
            Consumer<Path> onTransformPersisted) {
        String size = "M";
        try {
            size = sizeCodeOverride != null ? sizeCodeOverride : vault().getDisplaySize(path);
        } catch (IOException ignored) {
            if (sizeCodeOverride != null) {
                size = sizeCodeOverride;
            }
        }
        int[] wh = switch (size) {
            case "S" -> new int[]{220, 220};
            case "L" -> new int[]{360, 360};
            case "XL" -> new int[]{440, 440};
            default -> new int[]{300, 300};
        };
        Image image = host.thumbnailLoader().load(path, wh[0], wh[1]);
        if (image == null) {
            return null;
        }
        ImageView view = new ImageView(image);
        // 画像自体は loadThumbnail 時点で縮小済み。ここで正方形の fit 枠を作らないことで、
        // 縦長画像の右側に大きな余白が出るのを防ぐ。
        view.setPreserveRatio(true);
        view.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                List<Path> nav = viewerNavSource != null ? viewerNavSource : new ArrayList<>(host.canvasSelection());
                host.showOriginalImageViewer().accept(path, new ArrayList<>(nav));
                e.consume();
            }
        });
        Label transformOverlay = null;
        Node imageArea = view;
        if (interactive) {
            transformOverlay = new Label();
            transformOverlay.setVisible(false);
            transformOverlay.setMouseTransparent(true);
            transformOverlay.setMaxWidth(Double.MAX_VALUE);
            transformOverlay.setAlignment(Pos.CENTER);
            transformOverlay.setPadding(new Insets(5, 12, 5, 12));
            transformOverlay.setStyle(
                    "-fx-background-color: rgba(32,32,38,0.88); -fx-text-fill: #fafafa; "
                            + "-fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 6;");
            StackPane imageStack = new StackPane(view, transformOverlay);
            StackPane.setAlignment(transformOverlay, Pos.TOP_CENTER);
            StackPane.setMargin(transformOverlay, new Insets(6, 0, 0, 0));
            imageArea = imageStack;
        }
        Label label = new Label(path.getFileName().toString());
        // ファイル名が長くてもカード幅（白背景）は画像幅を超えないようにする。
        double labelW = Math.max(1.0, image.getWidth());
        label.setMaxWidth(labelW);
        label.setPrefWidth(labelW);
        label.setWrapText(false);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        if (!host.showFileName().getAsBoolean()) {
            // 配置計算のサイズを安定させるため、ラベル領域は残したまま不可視化する。
            label.setVisible(false);
            label.setOpacity(0.0);
            label.setMouseTransparent(true);
        }
        VBox box = new VBox(6, imageArea, label);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: white; -fx-border-color: #cfc8ba; -fx-border-radius: 2; -fx-background-radius: 2;");
        double baseRotation = -14 + (Math.abs(path.getFileName().toString().hashCode()) % 29);
        double scale = 1.0;
        double rotation = baseRotation;
        try {
            var tf = vault().getCanvasTransform(layoutName, path);
            if (tf != null) {
                scale = tf.scale();
                rotation = tf.rotation();
            }
        } catch (IOException ignored) {
            // keep defaults
        }
        double logicalScale = Math.max(0.5, Math.min(2.4, scale));
        double visibleScale = Math.max(0.2, Math.min(4.0, logicalScale * canvasSizeRatio));
        box.setScaleX(visibleScale);
        box.setScaleY(visibleScale);
        box.setRotate(rotation);
        box.getProperties().put("gazoScale", logicalScale);
        box.getProperties().put("gazoCanvasScaleRatio", canvasSizeRatio);
        box.getProperties().put("gazoRotation", box.getRotate());
        if (interactive && transformOverlay != null) {
            box.getProperties().put("gazoTransformOverlay", transformOverlay);
            makeCanvasTransformable(box, path, layoutName, onTransformPersisted);
        }
        return box;
    }

    /**
     * 重なり順の末尾＝手前。キャンバス編集の描画順と一致させる。
     */
    public void bringCanvasImageToFront(Path imagePath, String layoutName) {
        if (imagePath == null || !host.canvasSelection().contains(imagePath)) {
            return;
        }
        host.canvasSelection().remove(imagePath);
        host.canvasSelection().add(imagePath);
        try {
            vault().saveCanvasSelectionOrder(layoutName, new ArrayList<>(host.canvasSelection()));
        } catch (IOException e) {
            GazoFx.showWarn("キャンバス順序保存エラー", e.getMessage());
        }
    }

    /**
     * キャンバス編集で選択中画像の論理スケール（0.5〜2.4、ホイール操作と同じ）を設定する。回転は維持する。
     */
    public void applyCanvasImageLogicalScale(Path imagePath, String layoutName, double logicalScale) {
        if (imagePath == null) {
            return;
        }
        try {
            logicalScale = Math.max(0.5, Math.min(2.4, logicalScale));
            double rotation = -14 + (Math.abs(imagePath.getFileName().toString().hashCode()) % 29);
            var tf = vault().getCanvasTransform(layoutName, imagePath);
            if (tf != null) {
                rotation = tf.rotation();
            }
            vault().setCanvasTransform(layoutName, imagePath, logicalScale, rotation);
        } catch (IOException e) {
            GazoFx.showWarn("キャンバス変形保存エラー", e.getMessage());
        }
    }

    /**
     * キャンバス編集で選択中画像の回転（度）を設定する。拡大率は維持する。
     */
    public void applyCanvasImageLogicalRotation(Path imagePath, String layoutName, double rotationDegrees) {
        if (imagePath == null) {
            return;
        }
        try {
            double scale = 1.0;
            var tf = vault().getCanvasTransform(layoutName, imagePath);
            if (tf != null) {
                scale = tf.scale();
            }
            scale = Math.max(0.5, Math.min(2.4, scale));
            vault().setCanvasTransform(layoutName, imagePath, scale, rotationDegrees);
        } catch (IOException e) {
            GazoFx.showWarn("キャンバス変形保存エラー", e.getMessage());
        }
    }

    /** スライダー表示用に角度を -180〜180 度付近へ正規化する。 */
    public static double normalizeRotationForSlider(double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) {
            return 0.0;
        }
        double a = degrees % 360.0;
        if (a > 180.0) {
            a -= 360.0;
        }
        if (a < -180.0) {
            a += 360.0;
        }
        return a;
    }

    private static final String GAZO_TRANSFORM_OVERLAY_HIDE = "gazoTransformOverlayHidePause";

    /**
     * 拡大・回転の操作中だけ、画像上に数値を重ねて表示し、操作が止まって少し経つと消す。
     */
    private void flashCanvasTransformOverlay(VBox card) {
        Object o = card.getProperties().get("gazoTransformOverlay");
        if (!(o instanceof Label overlay)) {
            return;
        }
        Double scObj = (Double) card.getProperties().get("gazoScale");
        Double rotObj = (Double) card.getProperties().get("gazoRotation");
        double sc = scObj != null ? scObj : 1.0;
        double rot = rotObj != null ? rotObj : card.getRotate();
        overlay.setText(String.format("%.0f%%  ·  %.1f°", sc * 100.0, rot));
        overlay.setVisible(true);
        PauseTransition pause = (PauseTransition) card.getProperties().get(GAZO_TRANSFORM_OVERLAY_HIDE);
        if (pause != null) {
            pause.stop();
        }
        pause = new PauseTransition(Duration.millis(1100));
        pause.setOnFinished(e -> overlay.setVisible(false));
        card.getProperties().put(GAZO_TRANSFORM_OVERLAY_HIDE, pause);
        pause.playFromStart();
    }

    private void makeDraggable(VBox node, Path imagePath, String layoutName, Pane canvas) {
        final double[] dragOffset = new double[2];
        node.setOnMousePressed(event -> {
            Point2D local = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
            dragOffset[0] = local.getX() - node.getLayoutX();
            dragOffset[1] = local.getY() - node.getLayoutY();
            node.toFront();
        });
        node.setOnMouseDragged(event -> {
            Point2D local = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
            node.relocate(local.getX() - dragOffset[0], local.getY() - dragOffset[1]);
        });
        node.setOnMouseReleased(event -> {
            try {
                double cw = Math.max(1.0, canvas.getPrefWidth());
                double ch = Math.max(1.0, canvas.getPrefHeight());
                vault().setCanvasPosition(layoutName, imagePath, node.getLayoutX() / cw, node.getLayoutY() / ch);
            } catch (IOException e) {
                GazoFx.showWarn("キャンバス保存エラー", e.getMessage());
            }
        });
    }

    private void makeCanvasTransformable(VBox node, Path imagePath, String layoutName, Consumer<Path> onTransformPersisted) {
        Runnable persistTransform = () -> {
            try {
                vault().setCanvasTransform(layoutName, imagePath, (Double) node.getProperties().get("gazoScale"),
                        (Double) node.getProperties().get("gazoRotation"));
            } catch (IOException e) {
                GazoFx.showWarn("キャンバス変形保存エラー", e.getMessage());
            }
            if (onTransformPersisted != null) {
                Platform.runLater(() -> onTransformPersisted.accept(imagePath));
            }
        };

        // トラックパッドのピンチは OS によって ScrollEvent ではなく ZoomEvent だけが届くことがある。
        node.setOnZoom((ZoomEvent event) -> {
            if (event.isShiftDown()) {
                return;
            }
            double factor = event.getZoomFactor();
            if (factor <= 0 || Double.isNaN(factor) || Math.abs(factor - 1.0) < 1e-9) {
                return;
            }
            Double scaleObj = (Double) node.getProperties().getOrDefault("gazoScale", 1.0);
            Double ratioObj = (Double) node.getProperties().getOrDefault("gazoCanvasScaleRatio", 1.0);
            double scale = Math.max(0.5, Math.min(2.4, scaleObj * factor));
            double visibleScale = Math.max(0.2, Math.min(4.0, scale * ratioObj));
            node.setScaleX(visibleScale);
            node.setScaleY(visibleScale);
            node.getProperties().put("gazoScale", scale);
            persistTransform.run();
            flashCanvasTransformOverlay(node);
            event.consume();
        });

        node.setOnScroll(event -> {
            Double scaleObj = (Double) node.getProperties().getOrDefault("gazoScale", 1.0);
            Double ratioObj = (Double) node.getProperties().getOrDefault("gazoCanvasScaleRatio", 1.0);
            Double rotationObj = (Double) node.getProperties().getOrDefault("gazoRotation", node.getRotate());
            double scale = scaleObj;
            double sizeRatio = ratioObj;
            double rotation = rotationObj;
            double direction = scrollDirection(event);
            if (Math.abs(direction) < 1e-9) {
                return;
            }

            if (event.isShiftDown()) {
                rotation += direction * 2.0;
                node.setRotate(rotation);
                node.getProperties().put("gazoRotation", rotation);
            } else {
                double step = direction * 0.05;
                scale = Math.max(0.5, Math.min(2.4, scale + step));
                double visibleScale = Math.max(0.2, Math.min(4.0, scale * sizeRatio));
                node.setScaleX(visibleScale);
                node.setScaleY(visibleScale);
                node.getProperties().put("gazoScale", scale);
            }
            persistTransform.run();
            flashCanvasTransformOverlay(node);
            event.consume();
        });
    }

    /**
     * トラックパッドのピンチでは |deltaX| が |deltaY| より大きく出ることがあり、横優先だと縮小方向が逆転して縮められない。
     * 縦スクロール（deltaY）を先に解釈する。
     */
    private double scrollDirection(ScrollEvent event) {
        double dy = event.getDeltaY();
        double dx = event.getDeltaX();
        if (Math.abs(dy) > 0.0001) {
            return Math.signum(dy);
        }
        if (Math.abs(dx) > 0.0001) {
            return Math.signum(dx);
        }
        double tdy = event.getTextDeltaYUnits() == ScrollEvent.VerticalTextScrollUnits.NONE
                ? 0.0
                : event.getTextDeltaY();
        double tdx = event.getTextDeltaXUnits() == ScrollEvent.HorizontalTextScrollUnits.NONE
                ? 0.0
                : event.getTextDeltaX();
        if (Math.abs(tdy) > 0.0001) {
            return Math.signum(tdy);
        }
        if (Math.abs(tdx) > 0.0001) {
            return Math.signum(tdx);
        }
        return 0.0;
    }
}
