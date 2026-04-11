package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * キャンバス一覧のスライドショー（全キャンバスを順に表示）。
 */
public final class SlideshowWindow {
    private SlideshowWindow() {}

    public static void open(
            Stage owner,
            GazoVaultService vault,
            Set<Path> canvasSelection,
            CanvasEditor canvasEditor,
            String initialCanvasName) {
        if (canvasSelection.isEmpty()) {
            GazoFx.showWarn("スライドショー", "キャンバスに画像を追加してください。");
            return;
        }
        final List<String> layoutNames;
        try {
            layoutNames = new ArrayList<>(vault.listCanvasLayouts());
        } catch (IOException e) {
            GazoFx.showError("スライドショー", e.getMessage());
            return;
        }
        if (layoutNames.isEmpty()) {
            GazoFx.showWarn("スライドショー", "キャンバスがありません。");
            return;
        }
        List<Path> selectionBackup = new ArrayList<>(canvasSelection);
        int initialIndex = 0;
        if (initialCanvasName != null && !initialCanvasName.isBlank()) {
            int i = layoutNames.indexOf(initialCanvasName);
            if (i >= 0) {
                initialIndex = i;
            }
        }
        AtomicInteger idx = new AtomicInteger(initialIndex);
        Pane canvas = new Pane();
        canvas.setStyle("-fx-background-color: linear-gradient(to bottom, #f0ede4, #e4dccb);");
        final Scale holderScale = new Scale(1, 1, 0, 0);
        Group canvasHolder = new Group(canvas);
        canvasHolder.getTransforms().add(holderScale);
        canvasHolder.setManaged(false);
        Pane slideViewport = new Pane();
        slideViewport.setStyle("-fx-background-color: #1a1a1a;");
        slideViewport.getChildren().add(canvasHolder);
        StackPane slideArea = new StackPane(slideViewport);
        slideArea.setAlignment(Pos.CENTER);
        slideArea.setStyle("-fx-background-color: #1a1a1a;");
        slideArea.setPadding(new Insets(12));
        Label counter = new Label();
        counter.setStyle("-fx-text-fill: #eaeaea;");
        Label hint = new Label("← / → または A / D でキャンバス切替（キャンバス全体を表示）　Esc で閉じる");
        hint.setStyle("-fx-text-fill: #aaa;");
        Stage slideStage = new Stage();
        // initOwner すると GTK 等でトランジェント扱いになり最大化できない環境がある（Chromebook Linux 等）。
        slideStage.initModality(Modality.WINDOW_MODAL);
        slideStage.initStyle(StageStyle.DECORATED);
        slideStage.setResizable(true);
        slideStage.setMinWidth(480);
        slideStage.setMinHeight(360);
        slideStage.setTitle("スライドショー — キャンバス全体");
        GazoFx.applyAppIcons(slideStage);
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1a1a1a;");
        VBox bottom = new VBox(6, counter, hint);
        bottom.setPadding(new Insets(8, 16, 16, 16));
        root.setCenter(slideArea);
        root.setBottom(bottom);
        Scene scene = new Scene(root, 960, 640);
        slideStage.setScene(scene);
        Runnable fitCanvas =
                () -> {
                    double w = slideViewport.getWidth();
                    double h = slideViewport.getHeight();
                    if (w <= 0 || h <= 0) {
                        return;
                    }
                    double cw = canvas.getPrefWidth();
                    double ch = canvas.getPrefHeight();
                    if (cw <= 0 || ch <= 0) {
                        return;
                    }
                    double s = Math.min(w / cw, h / ch) * 0.96;
                    holderScale.setPivotX(0);
                    holderScale.setPivotY(0);
                    holderScale.setX(s);
                    holderScale.setY(s);
                    double scaledW = cw * s;
                    double scaledH = ch * s;
                    canvasHolder.setLayoutX((w - scaledW) / 2);
                    canvasHolder.setLayoutY((h - scaledH) / 2);
                };
        Runnable applyLayout =
                () -> {
                    int i = idx.get();
                    String name = layoutNames.get(i);
                    counter.setText((i + 1) + " / " + layoutNames.size() + "  キャンバス: " + name);
                    try {
                        canvasSelection.clear();
                        canvasSelection.addAll(vault.listCanvasSelectionOrder(name));
                    } catch (IOException ex) {
                        canvasSelection.clear();
                    }
                    try {
                        var size = vault.getCanvasSize(name);
                        if (size != null) {
                            double cw = Math.max(300, size.width());
                            double ch = Math.max(220, size.height());
                            canvas.setPrefSize(cw, ch);
                            canvas.setMinSize(cw, ch);
                            canvas.setMaxSize(cw, ch);
                            canvas.setClip(new Rectangle(cw, ch));
                        } else {
                            canvas.setPrefSize(2000, 1400);
                            canvas.setMinSize(2000, 1400);
                            canvas.setMaxSize(2000, 1400);
                            canvas.setClip(new Rectangle(2000, 1400));
                        }
                    } catch (IOException ex) {
                        canvas.setPrefSize(2000, 1400);
                        canvas.setMinSize(2000, 1400);
                        canvas.setMaxSize(2000, 1400);
                        canvas.setClip(new Rectangle(2000, 1400));
                    }
                    canvasEditor.renderCanvasItems(canvas, name, false, null, null, null, null, null);
                    Platform.runLater(fitCanvas);
                };
        Runnable prev =
                () -> {
                    idx.updateAndGet(v -> (v - 1 + layoutNames.size()) % layoutNames.size());
                    applyLayout.run();
                };
        Runnable next =
                () -> {
                    idx.updateAndGet(v -> (v + 1) % layoutNames.size());
                    applyLayout.run();
                };
        scene.addEventFilter(
                KeyEvent.KEY_PRESSED,
                e -> {
                    if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.A) {
                        prev.run();
                        e.consume();
                    } else if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.D) {
                        next.run();
                        e.consume();
                    } else if (e.getCode() == KeyCode.ESCAPE) {
                        slideStage.close();
                        e.consume();
                    }
                });
        slideViewport.widthProperty().addListener((o, ov, nv) -> fitCanvas.run());
        slideViewport.heightProperty().addListener((o, ov, nv) -> fitCanvas.run());
        slideStage.setOnShown(
                e -> {
                    if (owner != null && owner.isShowing()) {
                        slideStage.setX(Math.round(owner.getX() + (owner.getWidth() - slideStage.getWidth()) / 2));
                        slideStage.setY(Math.round(owner.getY() + (owner.getHeight() - slideStage.getHeight()) / 2));
                    }
                    applyLayout.run();
                    Platform.runLater(
                            () -> {
                                fitCanvas.run();
                                root.requestFocus();
                            });
                });
        slideStage.setOnHidden(
                e -> {
                    canvasSelection.clear();
                    canvasSelection.addAll(selectionBackup);
                });
        slideStage.show();
    }
}
