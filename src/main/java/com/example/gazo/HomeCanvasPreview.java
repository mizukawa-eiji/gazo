package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * メインウィンドウ「キャンバス」タブのプレビュー（ランダム選択・縮小フィット・Hub 閉じたあとの再描画）。
 */
public final class HomeCanvasPreview {
    public record Host(
            java.util.function.Supplier<GazoVaultService> vault,
            Set<Path> canvasSelection,
            CanvasEditor canvasEditor,
            ScrollPane previewScroll,
            Pane previewCanvasPane,
            Label layoutNameLabel,
            javafx.scene.transform.Scale previewScale,
            Group previewHolder) {}

    private final Host host;

    public HomeCanvasPreview(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    private GazoVaultService vault() {
        return host.vault().get();
    }

    /** キャンバスタブのキャンバスをスクロールビューポート内に収まるよう縮小（拡大はしない）。 */
    public void fitToViewport() {
        if (host.previewScroll() == null
                || host.previewCanvasPane() == null
                || host.previewScale() == null
                || host.previewHolder() == null) {
            return;
        }
        Bounds vb = host.previewScroll().getViewportBounds();
        double vw = vb.getWidth();
        double vh = vb.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        double cw = host.previewCanvasPane().getPrefWidth();
        double ch = host.previewCanvasPane().getPrefHeight();
        if (cw <= 0 || ch <= 0) {
            return;
        }
        double margin = 16;
        double s = Math.min((vw - margin) / cw, (vh - margin) / ch);
        if (Double.isNaN(s) || s <= 0) {
            s = 1.0;
        }
        if (s > 1.0) {
            s = 1.0;
        }
        host.previewScale().setX(s);
        host.previewScale().setY(s);
        double scaledW = cw * s;
        double scaledH = ch * s;
        host.previewHolder().setLayoutX((vw - scaledW) / 2.0);
        host.previewHolder().setLayoutY((vh - scaledH) / 2.0);
    }

    /**
     * キャンバス Hub を閉じたあと、メイン「キャンバス」タブのプレビューを Vault の最新内容で描き直す。
     */
    public void refreshAfterHubClose() {
        if (vault() == null || host.previewCanvasPane() == null || host.layoutNameLabel() == null) {
            return;
        }
        try {
            Set<String> names = vault().listCanvasLayouts();
            if (names.isEmpty()) {
                host.previewCanvasPane().getChildren().clear();
                host.layoutNameLabel().setText("（キャンバスがありません）");
                if (host.previewScale() != null) {
                    host.previewScale().setX(1);
                    host.previewScale().setY(1);
                }
                if (host.previewHolder() != null) {
                    host.previewHolder().setLayoutX(0);
                    host.previewHolder().setLayoutY(0);
                }
                Platform.runLater(this::fitToViewport);
                return;
            }
            String layoutName = parseLayoutName(host.layoutNameLabel());
            if (layoutName == null || !names.contains(layoutName)) {
                refreshRandomPreview(false);
                return;
            }
            renderForLayout(layoutName);
        } catch (IOException e) {
            GazoFx.showError("キャンバス表示", e.getMessage());
        }
    }

    public void renderForLayout(String layoutName) throws IOException {
        Pane canvasPane = host.previewCanvasPane();
        Label layoutNameLabel = host.layoutNameLabel();
        List<Path> backup = new ArrayList<>(host.canvasSelection());
        try {
            host.canvasSelection().clear();
            host.canvasSelection().addAll(vault().listCanvasSelectionOrder(layoutName));
            var size = vault().getCanvasSize(layoutName);
            if (size != null) {
                double cw = Math.max(480, size.width());
                double ch = Math.max(340, size.height());
                canvasPane.setPrefSize(cw, ch);
            } else {
                canvasPane.setPrefSize(2000, 1400);
            }
            host.canvasEditor().renderCanvasItems(canvasPane, layoutName, false, null, null, null, null, null);
            layoutNameLabel.setText("「" + layoutName + "」");
        } finally {
            host.canvasSelection().clear();
            host.canvasSelection().addAll(backup);
        }
        Platform.runLater(this::fitToViewport);
    }

    /**
     * メインウィンドウ「キャンバス」タブ用: Vault 内のキャンバスを 1 つランダムに選びプレビュー表示する。
     *
     * @param pickDifferentFromCurrent 登録が2件以上のとき、表示中のキャンバス名と別のものを選ぶ（「別のキャンバス」用）
     */
    public void refreshRandomPreview(boolean pickDifferentFromCurrent) {
        if (vault() == null) {
            return;
        }
        try {
            Set<String> names = vault().listCanvasLayouts();
            Pane canvasPane = host.previewCanvasPane();
            Label layoutNameLabel = host.layoutNameLabel();
            if (names.isEmpty()) {
                canvasPane.getChildren().clear();
                layoutNameLabel.setText("（キャンバスがありません）");
                if (host.previewScale() != null) {
                    host.previewScale().setX(1);
                    host.previewScale().setY(1);
                }
                if (host.previewHolder() != null) {
                    host.previewHolder().setLayoutX(0);
                    host.previewHolder().setLayoutY(0);
                }
                Platform.runLater(this::fitToViewport);
                return;
            }
            List<String> order = new ArrayList<>(names);
            if (pickDifferentFromCurrent && names.size() >= 2) {
                String current = parseLayoutName(layoutNameLabel);
                if (current != null && order.contains(current)) {
                    order.remove(current);
                }
            }
            Collections.shuffle(order, new Random());
            String layoutName = order.get(0);
            renderForLayout(layoutName);
        } catch (IOException e) {
            GazoFx.showError("キャンバス表示", e.getMessage());
        }
    }

    /** ホームのラベル「「名前」」からキャンバス名を取り出す。未選択・空状態は null。 */
    public static String parseLayoutName(Label layoutNameLabel) {
        if (layoutNameLabel == null) {
            return null;
        }
        return parseLayoutNameFromLabelText(layoutNameLabel.getText());
    }

    /**
     * {@link #parseLayoutName(Label)} と同じ規則（ラベルではなく生文字列用。テストからも参照する）。
     */
    static String parseLayoutNameFromLabelText(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || "—".equals(t) || "-".equals(t)) {
            return null;
        }
        if (t.startsWith("「") && t.endsWith("」") && t.length() >= 4) {
            return t.substring(1, t.length() - 1);
        }
        return null;
    }
}
