package com.example.gazo;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一覧・キャンバスから開くオリジナル解像度の画像ビューア。
 */
public final class OriginalImageViewerWindow {

    private OriginalImageViewerWindow() {}

    public static void open(OriginalImageViewerHost host, Path startPath, List<Path> sourceImages) {
        final List<Path> images = new ArrayList<>(sourceImages);
        if (images.isEmpty()) {
            GazoFx.showWarn("画像ビューア", "表示できる画像がありません。");
            return;
        }
        int startIndex = images.indexOf(startPath);
        if (startIndex < 0) {
            startIndex = 0;
        }
        AtomicInteger index = new AtomicInteger(startIndex);

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("オリジナル表示");

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        CheckBox fitCheck = new CheckBox("ウィンドウにフィット");
        fitCheck.setSelected(true);
        fitCheck.setStyle("-fx-text-fill: #ddd;");
        Button tagEditButton = new Button("タグ編集");
        Button copyFileNameButton = new Button("ファイル名コピー");
        copyFileNameButton.setTooltip(new Tooltip("現在表示中のファイル名をコピー"));
        Label label = new Label();
        label.setStyle("-fx-text-fill: #ddd;");
        Label tagsLine = new Label();
        tagsLine.setWrapText(true);
        tagsLine.setStyle("-fx-text-fill: #a8b896;");
        tagsLine.setVisible(false);
        tagsLine.setManaged(false);
        Label hint = new Label("← / → または A / D で移動、Esc で閉じる");
        hint.setStyle("-fx-text-fill: #999;");

        StackPane center = new StackPane(view);
        ScrollPane scroll = new ScrollPane(center);
        scroll.setPannable(true);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);

        VBox bottom = new VBox(6, new HBox(10, fitCheck, tagEditButton, copyFileNameButton, label), tagsLine, hint);
        bottom.setPadding(new Insets(8, 14, 12, 14));

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #161616;");
        root.setCenter(scroll);
        root.setBottom(bottom);

        Scene scene = new Scene(root, 1000, 760);
        tagsLine.maxWidthProperty().bind(scene.widthProperty().subtract(28));
        stage.setScene(scene);

        Runnable applyViewMode =
                () -> {
                    Image img = view.getImage();
                    if (img == null) {
                        return;
                    }
                    if (fitCheck.isSelected()) {
                        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                        scroll.setPannable(false);
                        scroll.setFitToWidth(true);
                        scroll.setFitToHeight(true);
                        double vw = Math.max(100, scroll.getViewportBounds().getWidth());
                        double vh = Math.max(100, scroll.getViewportBounds().getHeight());
                        view.setFitWidth(vw);
                        view.setFitHeight(vh);
                        center.setMinSize(0, 0);
                        center.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                    } else {
                        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                        scroll.setPannable(true);
                        scroll.setFitToWidth(false);
                        scroll.setFitToHeight(false);
                        view.setFitWidth(img.getWidth());
                        view.setFitHeight(img.getHeight());
                        center.setMinSize(img.getWidth(), img.getHeight());
                        center.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                    }
                };

        Runnable render =
                () -> {
                    int i = index.get();
                    Path path = images.get(i);
                    Image img = host.loadOriginalImage().apply(path);
                    view.setImage(img);
                    if (img != null) {
                        applyViewMode.run();
                    } else {
                        center.setMinSize(0, 0);
                    }
                    String sizeText = "";
                    if (img != null) {
                        int w = (int) Math.round(img.getWidth());
                        int h = (int) Math.round(img.getHeight());
                        sizeText = "  (" + w + " x " + h + ")";
                    }
                    label.setText((i + 1) + "/" + images.size() + "  " + path.getFileName() + sizeText);
                    try {
                        if (host.vault() == null) {
                            tagsLine.setText("");
                            tagsLine.setVisible(false);
                            tagsLine.setManaged(false);
                        } else {
                            Set<String> tags = host.vault().getTags(path);
                            if (tags.isEmpty()) {
                                tagsLine.setText("");
                                tagsLine.setVisible(false);
                                tagsLine.setManaged(false);
                            } else {
                                List<String> sorted = new ArrayList<>(tags);
                                Collections.sort(sorted);
                                tagsLine.setText("#" + String.join(" #", sorted));
                                tagsLine.setVisible(true);
                                tagsLine.setManaged(true);
                            }
                        }
                    } catch (IOException ex) {
                        tagsLine.setText("タグを読み込めませんでした");
                        tagsLine.setVisible(true);
                        tagsLine.setManaged(true);
                    }
                };
        tagEditButton.setOnAction(
                e -> {
                    Path path = images.get(index.get());
                    host.editTags().accept(path);
                    render.run();
                });
        copyFileNameButton.setOnAction(
                e -> {
                    Path path = images.get(index.get());
                    ClipboardContent content = new ClipboardContent();
                    content.putString(path.getFileName().toString());
                    Clipboard.getSystemClipboard().setContent(content);
                    GazoFx.showWarn("コピー", "ファイル名をクリップボードにコピーしました。");
                });
        fitCheck.setOnAction(e -> applyViewMode.run());
        scroll.viewportBoundsProperty()
                .addListener(
                        (obs, oldB, newB) -> {
                            if (!fitCheck.isSelected() || newB == null) {
                                return;
                            }
                            if (oldB != null
                                    && Math.abs(newB.getWidth() - oldB.getWidth()) < 0.5
                                    && Math.abs(newB.getHeight() - oldB.getHeight()) < 0.5) {
                                return;
                            }
                            applyViewMode.run();
                        });

        scene.addEventFilter(
                KeyEvent.KEY_PRESSED,
                e -> {
                    if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.A) {
                        index.updateAndGet(v -> (v - 1 + images.size()) % images.size());
                        render.run();
                        e.consume();
                    } else if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.D) {
                        index.updateAndGet(v -> (v + 1) % images.size());
                        render.run();
                        e.consume();
                    } else if (e.getCode() == KeyCode.ESCAPE) {
                        stage.close();
                        e.consume();
                    }
                });

        stage.setOnShown(e -> render.run());
        stage.show();
    }
}
