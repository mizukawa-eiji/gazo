package com.example.gazo;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * キャンバス機能の統合 UI: 画像選択・ランダムピック・Vault への保存・スライドショー。
 */
public final class CanvasHubDialog {
    private CanvasHubDialog() {
    }

    /** ツールバー用: 絵文字／記号＋ツールチップのみ（ラベルなし）。 */
    private static void setCanvasHubIconButton(Button button, String glyph, String tooltipText) {
        Text g = new Text(glyph);
        g.setStyle("-fx-font-size: 16px; -fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', 'Segoe UI', sans-serif;");
        button.setGraphic(g);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setText(null);
        Tooltip.install(button, new Tooltip(tooltipText));
    }

    public static void open(GazoApp app, Stage owner) {
        open(app, owner, false, null);
    }

    public static void open(GazoApp app, Stage owner, boolean openRandomTabOnStart) {
        open(app, owner, openRandomTabOnStart, null);
    }

    /**
     * @param initialLayoutNameOrNull メイン「キャンバス」タブのプレビューと同じ名前で開きたいとき。存在しない名前や null のときは従来どおり default 等を選ぶ。
     */
    public static void open(GazoApp app, Stage owner, boolean openRandomTabOnStart, String initialLayoutNameOrNull) {
    if (!app.listCheckedSelection.isEmpty()) {
        app.canvasSelection.clear();
        app.canvasSelection.addAll(app.listCheckedSelection);
    }
    // Dialog ではなく Stage を使う。Linux/Chromebook の GTK では Dialog のウィンドウ種別のせいで最大化できないことがある。
    final Stage hubStage = new Stage();
    // initOwner すると GTK 等で最大化できない環境があるため付けない（メインウィンドウ上に中央寄せは別途考慮可能）。
    hubStage.initModality(Modality.APPLICATION_MODAL);
    hubStage.initStyle(StageStyle.DECORATED);
    hubStage.setTitle("キャンバス");
    hubStage.setResizable(true);
    hubStage.setMinWidth(640);
    hubStage.setMinHeight(420);

    AtomicReference<String> currentLayout = new AtomicReference<>("default");
    AtomicBoolean suppressLayoutChooser = new AtomicBoolean(false);

    Button overwriteSaveButton = new Button();
    setCanvasHubIconButton(overwriteSaveButton, "💾", "上書き保存（現在のキャンバス一覧を Vault に記録）");
    overwriteSaveButton.setOnAction(e -> app.persistCanvasSelectionToVaultWithFeedback(currentLayout.get()));

    Button addImagesButton = new Button();
    setCanvasHubIconButton(
            addImagesButton,
            "➕",
            "画像を追加（ダイアログ内でファイル名・タグで絞り込み、一覧から選んで現在のキャンバスに追加）");

    Button canvasSizeButton = new Button();
    setCanvasHubIconButton(
            canvasSizeButton,
            "⤢",
            "キャンバスサイズ（幅・高さを数値指定。右下の取っ手でも可。Shift+ドラッグで素早く広げられる）");

    Button slideshowButton = new Button("スライドショー");
    slideshowButton.setOnAction(e -> app.showSlideshow(owner));

    Label hubHint = new Label(
            "ギャラリーで「キャンバス対象」をチェックするか、「キャンバス編集」の「画像を追加…」／「ランダムピック」の「名前を付けて保存…」からキャンバスに取り込みます。"
                    + "ツールバーの「上書き保存」で、現在のキャンバスの画像一覧を Vault に記録します。"
                    + "上部の「キャンバス」一覧で編集対象を切り替えると、切り替え前のキャンバスは自動で保存されます。"
                    + "ダイアログを閉じるだけでは、最後に表示していたキャンバスの一覧は保存されないので、閉じる前に「上書き保存」してください。"
                    + "「名前を付けて保存…」は現在の内容を保存したうえで別名のキャンバスを複製します。"
                    + "画像の拡大・回転は右クリックメニューまたはホイールで調整できます。");
    hubHint.setWrapText(true);
    hubHint.setMaxWidth(1000);
    hubHint.setStyle("-fx-text-fill: #555;");

    Button closeHubButton = new Button("閉じる");
    closeHubButton.setOnAction(e -> hubStage.close());
    Region topBarSpacer = new Region();
    HBox.setHgrow(topBarSpacer, Priority.ALWAYS);
    HBox topBar = new HBox(12, slideshowButton, topBarSpacer, closeHubButton);
    topBar.setAlignment(Pos.CENTER_LEFT);

    AtomicReference<Runnable> refreshEditor = new AtomicReference<>(() -> {});
    AtomicReference<TabPane> tabsRef = new AtomicReference<>();
    ListView<String> canvasListView = new ListView<>();
    AtomicReference<Runnable> refreshCanvasList = new AtomicReference<>(() -> {});
    AtomicReference<Path> selectedCanvasImage = new AtomicReference<>(null);
    BooleanProperty showFileNameInCanvas = new SimpleBooleanProperty(app.isShowFileNameOption());
    AtomicReference<Pane> randomCanvasRef = new AtomicReference<>(null);
    /** ランダムピックタブで ←/→ 用（プール空のときは no-op） */
    AtomicReference<Runnable> randomKeyPrev = new AtomicReference<>(() -> {});
    AtomicReference<Runnable> randomKeyNext = new AtomicReference<>(() -> {});

    Tab editTab = new Tab("キャンバス編集");
    editTab.setClosable(false);

    Pane editCanvas = new Pane();
    editCanvas.setPrefSize(1100, 760);
    editCanvas.setStyle("-fx-background-color: linear-gradient(to bottom, #f0ede4, #e4dccb);");
    ComboBox<String> layoutChooser = new ComboBox<>();
    layoutChooser.setPrefWidth(220);
    layoutChooser.setTooltip(new Tooltip("編集するキャンバス（保存済みレイアウト）を選びます"));
    try {
        List<String> layoutNames = new ArrayList<>(app.vault.listCanvasLayouts());
        if (!layoutNames.contains("default")) {
            layoutNames.add(0, "default");
        }
        layoutChooser.getItems().setAll(layoutNames);
        String initialLayout;
        if (initialLayoutNameOrNull != null
                && !initialLayoutNameOrNull.isBlank()
                && layoutNames.contains(initialLayoutNameOrNull)) {
            initialLayout = initialLayoutNameOrNull;
        } else {
            initialLayout = layoutNames.contains("default") ? "default" : layoutNames.get(0);
        }
        layoutChooser.setValue(initialLayout);
        currentLayout.set(initialLayout);
    } catch (IOException e) {
        GazoFx.showError("キャンバス読込エラー", e.getMessage());
        layoutChooser.getItems().setAll("default");
        layoutChooser.setValue("default");
        currentLayout.set("default");
    }
    ComboBox<String> presetCombo = new ComboBox<>();
    Slider overlapSlider = new Slider(0, 100, 50);
    Slider neatSlider = new Slider(0, 100, 50);
    Button newLayoutButton = new Button();
    setCanvasHubIconButton(
            newLayoutButton,
            "📄",
            "名前を付けて保存（現在の内容を保存したうえで、別名のキャンバスとして複製）");
    Button autoLayoutButton = new Button();
    setCanvasHubIconButton(
            autoLayoutButton,
            "▦",
            "自動レイアウト（プリセット・重なり・整列感に従って配置し直す）");
    presetCombo.getItems().setAll(GazoApp.LAYOUT_PRESETS);
    presetCombo.setValue("コラージュ風");
    overlapSlider.setPrefWidth(120);
    neatSlider.setPrefWidth(120);
    overlapSlider.setShowTickLabels(false);
    neatSlider.setShowTickLabels(false);
    overlapSlider.setShowTickMarks(false);
    neatSlider.setShowTickMarks(false);
    presetCombo.setOnAction(e -> {
        boolean neatPreset = "整列風".equals(presetCombo.getValue());
        overlapSlider.setValue(neatPreset ? 80 : 40);
        neatSlider.setValue(neatPreset ? 80 : 35);
    });
    presetCombo.getOnAction().handle(null);

    Runnable saveCanvasSize = () -> {
        try {
            app.vault.setCanvasSize(currentLayout.get(), editCanvas.getPrefWidth(), editCanvas.getPrefHeight());
        } catch (IOException ex) {
            GazoFx.showWarn("キャンバスサイズ保存エラー", ex.getMessage());
        }
    };
    Runnable applyCanvasSize = () -> {
        try {
            var size = app.vault.getCanvasSize(currentLayout.get());
            if (size != null) {
                editCanvas.setPrefSize(
                        Math.max(CanvasEditor.CANVAS_EDIT_MIN_WIDTH, size.width()),
                        Math.max(CanvasEditor.CANVAS_EDIT_MIN_HEIGHT, size.height()));
            } else {
                editCanvas.setPrefSize(2000, 1400);
            }
        } catch (IOException ex) {
            GazoFx.showWarn("キャンバスサイズ読込エラー", ex.getMessage());
        }
    };

    final Scale editHolderScale = new Scale(1, 1, 0, 0);
    Group editCanvasHolder = new Group(editCanvas);
    editCanvasHolder.getTransforms().add(editHolderScale);
    editCanvasHolder.setManaged(false);
    Pane editViewportPane = new Pane();
    editViewportPane.getChildren().add(editCanvasHolder);
    ScrollPane editScroll = new ScrollPane(editViewportPane);
    editScroll.setFitToWidth(true);
    editScroll.setFitToHeight(true);
    editScroll.setPrefViewportWidth(1100);
    editScroll.setPrefViewportHeight(760);
    StackPane editScrollStack = new StackPane(editScroll);
    Runnable updateEditCanvasFit = () -> {
        Bounds vb = editScroll.getViewportBounds();
        double vw = vb.getWidth();
        double vh = vb.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        double cw = editCanvas.getPrefWidth();
        double ch = editCanvas.getPrefHeight();
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
        editHolderScale.setX(s);
        editHolderScale.setY(s);
        double scaledW = cw * s;
        double scaledH = ch * s;
        editCanvasHolder.setLayoutX((vw - scaledW) / 2.0);
        editCanvasHolder.setLayoutY((vh - scaledH) / 2.0);
    };
    editScroll.viewportBoundsProperty().addListener((obs, oldB, newB) -> {
        if (newB == null) {
            return;
        }
        Platform.runLater(updateEditCanvasFit);
    });

    Runnable render = () -> {
        app.renderCanvasItems(
                editCanvas,
                currentLayout.get(),
                true,
                selectedCanvasImage.get(),
                path -> {
                    selectedCanvasImage.set(path);
                    refreshEditor.get().run();
                },
                path -> {
                    if (path == null || !app.canvasSelection.contains(path)) {
                        return;
                    }
                    app.canvasSelection.remove(path);
                    selectedCanvasImage.set(null);
                    try {
                        app.vault.saveCanvasSelectionOrder(currentLayout.get(), new ArrayList<>(app.canvasSelection));
                    } catch (IOException ex) {
                        GazoFx.showWarn("除去", "画像一覧の保存に失敗しました: " + ex.getMessage());
                    }
                    app.refreshGallery();
                    refreshEditor.get().run();
                },
                transformPath -> {
                },
                path -> {
                    app.bringCanvasImageToFront(path, currentLayout.get());
                    refreshEditor.get().run();
                });
        Platform.runLater(updateEditCanvasFit);
    };
    refreshEditor.set(render);

    addImagesButton.setOnAction(e -> showAddImagesToCanvasDialog(app, hubStage, currentLayout, refreshEditor));
    canvasSizeButton.setOnAction(
            e -> showEditCanvasSizeDialog(hubStage, editCanvas, saveCanvasSize, updateEditCanvasFit, refreshEditor));

    ContextMenu canvasBackgroundMenu = new ContextMenu();
    MenuItem ctxAddImages = new MenuItem("画像を追加…");
    ctxAddImages.setOnAction(e -> showAddImagesToCanvasDialog(app, hubStage, currentLayout, refreshEditor));
    MenuItem ctxCanvasSize = new MenuItem("キャンバスサイズ…");
    ctxCanvasSize.setOnAction(
            e -> showEditCanvasSizeDialog(hubStage, editCanvas, saveCanvasSize, updateEditCanvasFit, refreshEditor));
    canvasBackgroundMenu.getItems().addAll(ctxAddImages, ctxCanvasSize);
    editCanvas.setOnContextMenuRequested(
            ev -> {
                Node hit = ev.getPickResult().getIntersectedNode();
                if (hit == null) {
                    return;
                }
                Node n = hit;
                while (n != null && n != editCanvas) {
                    if (n.getParent() == editCanvas && n instanceof VBox) {
                        return;
                    }
                    n = n.getParent();
                }
                if (n != editCanvas) {
                    return;
                }
                canvasBackgroundMenu.show(editCanvas, ev.getScreenX(), ev.getScreenY());
                ev.consume();
            });

    layoutChooser.valueProperty().addListener((obs, oldV, newV) -> {
        if (suppressLayoutChooser.get()) {
            return;
        }
        if (oldV != null && !Objects.equals(oldV, newV)) {
            app.persistCanvasSelectionToVault(oldV);
        }
        String selected = newV == null ? "default" : newV;
        currentLayout.set(selected);
        app.reloadCanvasSelectionFromVault(currentLayout.get());
        selectedCanvasImage.set(null);
        applyCanvasSize.run();
        refreshEditor.get().run();
    });

    Runnable onEditResizeFinished = () -> {
        saveCanvasSize.run();
        Platform.runLater(updateEditCanvasFit);
    };
    app.installCanvasResizeHandle(editScrollStack, editCanvas, onEditResizeFinished);

    newLayoutButton.setOnAction(e -> {
        app.persistCanvasSelectionToVault(currentLayout.get());
        TextInputDialog input = new TextInputDialog();
        input.setTitle("名前を付けて保存");
        input.setHeaderText("現在のキャンバスの内容を保存したうえで、別名のキャンバスとして複製します。");
        input.setContentText("保存名（キャンバス名）:");
        Optional<String> name = input.showAndWait();
        if (name.isEmpty()) {
            return;
        }
        String layout = name.get().trim();
        if (layout.isEmpty()) {
            return;
        }
        boolean exists = layoutChooser.getItems().contains(layout);
        if (!exists) {
            String sourceLayout = currentLayout.get();
            try {
                app.copyCanvasLayoutState(sourceLayout, layout);
            } catch (IOException ex) {
                GazoFx.showError("名前を付けて保存エラー", ex.getMessage());
                return;
            }
            suppressLayoutChooser.set(true);
            try {
                layoutChooser.getItems().add(layout);
                layoutChooser.setValue(layout);
                currentLayout.set(layout);
            } finally {
                suppressLayoutChooser.set(false);
            }
            refreshCanvasList.get().run();
            GazoFx.showWarn("名前を付けて保存", "「" + layout + "」として保存しました（「" + sourceLayout + "」の内容を複製）。");
            app.reloadCanvasSelectionFromVault(layout);
            applyCanvasSize.run();
            refreshEditor.get().run();
        } else {
            suppressLayoutChooser.set(true);
            try {
                layoutChooser.setValue(layout);
                currentLayout.set(layout);
            } finally {
                suppressLayoutChooser.set(false);
            }
            app.reloadCanvasSelectionFromVault(layout);
            applyCanvasSize.run();
            refreshEditor.get().run();
        }
    });
    Runnable deleteActiveLayout = () -> {
        String selected = layoutChooser.getValue();
        if (selected == null || "default".equals(selected)) {
            GazoFx.showWarn("削除不可", "default キャンバスは削除できません。");
            return;
        }
        try {
            app.vault.deleteCanvasLayout(selected);
            suppressLayoutChooser.set(true);
            try {
                layoutChooser.getItems().remove(selected);
                layoutChooser.setValue("default");
                currentLayout.set("default");
            } finally {
                suppressLayoutChooser.set(false);
            }
            refreshCanvasList.get().run();
            app.reloadCanvasSelectionFromVault("default");
            applyCanvasSize.run();
            refreshEditor.get().run();
        } catch (IOException ex) {
            GazoFx.showError("キャンバス削除エラー", ex.getMessage());
        }
    };
    autoLayoutButton.setOnAction(e -> {
        app.autoLayoutCanvas(
                editCanvas,
                currentLayout.get(),
                presetCombo.getValue(),
                overlapSlider.getValue() / 100.0,
                neatSlider.getValue() / 100.0);
        render.run();
    });
    showFileNameInCanvas.addListener((obs, oldV, newV) -> {
        app.setShowFileNameOption(Boolean.TRUE.equals(newV));
        app.applyCanvasFileNameVisibility(editCanvas);
        app.applyCanvasFileNameVisibility(randomCanvasRef.get());
    });

    HBox editToolBarPrimary =
            new HBox(
                    12,
                    new Label("キャンバス"),
                    layoutChooser,
                    overwriteSaveButton,
                    newLayoutButton,
                    addImagesButton,
                    canvasSizeButton);
    editToolBarPrimary.setPadding(new Insets(8, 12, 8, 12));
    editToolBarPrimary.setAlignment(Pos.CENTER_LEFT);
    editToolBarPrimary.setStyle(
            "-fx-background-color: linear-gradient(to bottom, #faf8f3, #f2efe7);"
                    + "-fx-border-color: #d7d0c2; -fx-border-width: 0 0 1 0;");

    CheckBox editShowNameCheck = new CheckBox("ファイル名");
    editShowNameCheck.selectedProperty().bindBidirectional(showFileNameInCanvas);
    HBox editToolBarControls = new HBox(
            8,
            new Label("プリセット:"),
            presetCombo,
            new Label("重なり回避"),
            overlapSlider,
            new Label("整列感"),
            neatSlider,
            new Label("表示:"),
            editShowNameCheck,
            autoLayoutButton);
    editToolBarControls.setPadding(new Insets(6, 12, 8, 12));
    editToolBarControls.setAlignment(Pos.CENTER_LEFT);
    editToolBarControls.setStyle(
            "-fx-background-color: rgba(255,255,255,0.65); -fx-border-color: #e7e2d8; -fx-border-width: 0 0 1 0;");

    Label editHelp = new Label(
            "操作: 画像をドラッグで移動 / ホイールで拡大縮小 / Shift+ホイールで回転 / 右クリックで拡大・回転スライダー・前面表示・除去。"
                    + " キャンバス全体の広さは「キャンバスサイズ…」で数値指定するか、編集エリア右下の取っ手をドラッグ（Shift+ドラッグで素早く変更）。");
    editHelp.setWrapText(true);
    editHelp.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
    editHelp.setPadding(new Insets(2, 12, 10, 12));

    VBox editorVBox = new VBox(8, editToolBarPrimary, editToolBarControls, editScrollStack, editHelp);
    VBox.setVgrow(editScrollStack, Priority.ALWAYS);
    editTab.setContent(editorVBox);

    Tab randomTab = new Tab("ランダムピック");
    randomTab.setClosable(false);

    final List<Path> pool = app.listFilteredImagesOrEmpty("キャンバス");

    Node randomNode;
    Runnable randomBootstrap = () -> {
    };

    if (pool.isEmpty()) {
        randomNode = new Label("タグ／フィルターに一致する画像がありません。条件を変えてください。");
    } else {
        Pane randomCanvas = new Pane();
        randomCanvasRef.set(randomCanvas);
        randomCanvas.setStyle("-fx-background-color: linear-gradient(to bottom, #f0ede4, #e4dccb);");
        randomCanvas.setPrefSize(1000, 700);

        int initialPickCount = Math.max(1, Math.min(6, pool.size()));
        Spinner<Integer> countSpinner = new Spinner<>();
        countSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, pool.size(), initialPickCount));
        countSpinner.setEditable(false);
        countSpinner.setPrefWidth(84);
        Button prevButton = new Button("前のランダムピック");
        Button nextButton = new Button("次のランダムピック");
        Button createLayoutButton = new Button("名前を付けて保存…");
        CheckBox randomShowNameCheck = new CheckBox("ファイル名");
        randomShowNameCheck.selectedProperty().bindBidirectional(showFileNameInCanvas);
        Label pageLabel = new Label();

        List<List<Path>> history = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(-1);

        Runnable renderCurrent = () -> {
            int i = index.get();
            if (i < 0 || i >= history.size()) {
                randomCanvas.getChildren().clear();
                pageLabel.setText("0/0");
                return;
            }
            List<Path> current = history.get(i);
            pageLabel.setText((i + 1) + "/" + history.size());
            app.renderRandomPickCanvas(randomCanvas, current);
        };

        Runnable nextPick = () -> {
            int cur = index.get();
            if (cur + 1 < history.size()) {
                index.incrementAndGet();
                renderCurrent.run();
                return;
            }
            Integer selected = countSpinner.getValue();
            int count = selected == null ? initialPickCount : Math.max(1, Math.min(pool.size(), selected));
            List<Path> pick = app.makeRandomPick(pool, count);
            history.add(pick);
            index.set(history.size() - 1);
            renderCurrent.run();
        };

        Runnable prevPick = () -> {
            if (index.get() > 0) {
                index.decrementAndGet();
                renderCurrent.run();
            }
        };
        prevButton.setOnAction(e -> prevPick.run());
        nextButton.setOnAction(e -> nextPick.run());
        randomKeyPrev.set(prevPick);
        randomKeyNext.set(nextPick);
        createLayoutButton.setOnAction(e -> {
            int i = index.get();
            if (i < 0 || i >= history.size()) {
                return;
            }
            List<Path> pick = history.get(i);
            TextInputDialog layoutDialog = new TextInputDialog("random-" + LocalDate.now().format(DateTimeFormatter.ofPattern("MMdd")));
            layoutDialog.setTitle("名前を付けて保存");
            layoutDialog.setHeaderText("現在のランダムピックをキャンバスに載せ、別名のキャンバスとして保存します。");
            layoutDialog.setContentText("保存名（キャンバス名）:");
            Optional<String> layoutResult = layoutDialog.showAndWait();
            if (layoutResult.isEmpty()) {
                return;
            }
            String newLayout = layoutResult.get().trim();
            if (newLayout.isEmpty()) {
                return;
            }
            app.canvasSelection.clear();
            app.canvasSelection.addAll(pick);
            try {
                app.vault.saveCanvasSelectionOrder(newLayout, new ArrayList<>(app.canvasSelection));
            } catch (IOException ex) {
                GazoFx.showError("名前を付けて保存エラー", ex.getMessage());
                return;
            }
            boolean exists = layoutChooser.getItems().contains(newLayout);
            if (!exists) {
                suppressLayoutChooser.set(true);
                try {
                    layoutChooser.getItems().add(newLayout);
                    layoutChooser.setValue(newLayout);
                    currentLayout.set(newLayout);
                } finally {
                    suppressLayoutChooser.set(false);
                }
                refreshCanvasList.get().run();
            } else {
                suppressLayoutChooser.set(true);
                try {
                    layoutChooser.setValue(newLayout);
                    currentLayout.set(newLayout);
                } finally {
                    suppressLayoutChooser.set(false);
                }
            }
            app.reloadCanvasSelectionFromVault(newLayout);
            applyCanvasSize.run();
            app.autoLayoutCanvas(
                    editCanvas,
                    newLayout,
                    presetCombo.getValue(),
                    overlapSlider.getValue() / 100.0,
                    neatSlider.getValue() / 100.0);
            app.refreshGallery();
            refreshEditor.get().run();
            TabPane tp = tabsRef.get();
            if (tp != null) {
                tp.getSelectionModel().select(editTab);
            }
            GazoFx.showWarn("名前を付けて保存", "「" + newLayout + "」として保存しました（ランダムピックをキャンバスに反映）。");
        });

        ScrollPane randomScroll = new ScrollPane(randomCanvas);
        randomScroll.setFitToWidth(true);
        randomScroll.setFitToHeight(true);
        randomScroll.viewportBoundsProperty().addListener((obs, oldB, newB) -> {
            if (newB == null) {
                return;
            }
            double vw = Math.max(300, newB.getWidth());
            double vh = Math.max(220, newB.getHeight());
            double baseW = 1000;
            double baseH = 700;
            randomCanvas.setPrefSize(Math.max(baseW, vw), Math.max(baseH, vh));
            // リサイズ時は再抽選・再配置しない（現在表示中の配置を維持）。
        });
        HBox controls = new HBox(8, new Label("枚数"), countSpinner, randomShowNameCheck, prevButton, nextButton, createLayoutButton, new Label("履歴"), pageLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox randomVBox = new VBox(8, controls, randomScroll);
        VBox.setVgrow(randomScroll, Priority.ALWAYS);
        randomVBox.setPadding(new Insets(4));
        randomNode = randomVBox;
        randomBootstrap = nextPick;
    }

    randomTab.setContent(randomNode);

    Tab listTab = new Tab("キャンバス一覧");
    listTab.setClosable(false);
    Pane previewCanvas = new Pane();
    previewCanvas.setPrefSize(760, 460);
    previewCanvas.setStyle("-fx-background-color: linear-gradient(to bottom, #f0ede4, #e4dccb);");
    ScrollPane previewScroll = new ScrollPane(previewCanvas);
    previewScroll.setFitToWidth(true);
    previewScroll.setFitToHeight(true);
    previewScroll.setMinWidth(200);
    previewScroll.setMinHeight(160);
    Runnable renderCanvasPreview = () -> {
        String selectedName = canvasListView.getSelectionModel().getSelectedItem();
        if (selectedName == null || selectedName.isBlank()) {
            previewCanvas.getChildren().clear();
            return;
        }
        List<Path> backup = new ArrayList<>(app.canvasSelection);
        try {
            List<Path> selectedPaths = app.vault.listCanvasSelectionOrder(selectedName);
            app.canvasSelection.clear();
            app.canvasSelection.addAll(selectedPaths);
            var size = app.vault.getCanvasSize(selectedName);
            if (size != null) {
                previewCanvas.setPrefSize(Math.max(400, size.width()), Math.max(260, size.height()));
            } else {
                previewCanvas.setPrefSize(1200, 840);
            }
            app.renderCanvasItems(previewCanvas, selectedName, false, null, null, null);
        } catch (IOException ex) {
            previewCanvas.getChildren().clear();
        } finally {
            app.canvasSelection.clear();
            app.canvasSelection.addAll(backup);
        }
    };
    Runnable openListSelectionInEditor = () -> {
        String selectedName = canvasListView.getSelectionModel().getSelectedItem();
        if (selectedName == null || selectedName.isBlank()) {
            return;
        }
        suppressLayoutChooser.set(true);
        try {
            layoutChooser.setValue(selectedName);
            currentLayout.set(selectedName);
        } finally {
            suppressLayoutChooser.set(false);
        }
        app.reloadCanvasSelectionFromVault(selectedName);
        applyCanvasSize.run();
        refreshEditor.get().run();
        TabPane tp = tabsRef.get();
        if (tp != null) {
            tp.getSelectionModel().select(editTab);
        }
    };
    SplitPane listSplit = new SplitPane();
    listSplit.setOrientation(Orientation.HORIZONTAL);
    ComboBox<String> previewPositionCombo = new ComboBox<>();
    previewPositionCombo.getItems().addAll("プレビュー: 右", "プレビュー: 下");
    previewPositionCombo.setValue("プレビュー: 右");
    previewPositionCombo.setOnAction(e -> {
        int i = previewPositionCombo.getSelectionModel().getSelectedIndex();
        listSplit.setOrientation(i == 0 ? Orientation.HORIZONTAL : Orientation.VERTICAL);
        Platform.runLater(() -> listSplit.setDividerPositions(0.38));
    });
    HBox listControls = new HBox(12, new Label("表示:"), previewPositionCombo);
    listControls.setAlignment(Pos.CENTER_LEFT);
    canvasListView.setCellFactory(lv -> new ListCell<String>() {
        private final Label nameLabel = new Label();
        private final Button editBtn = new Button();
        private final Button delBtn = new Button();
        private final HBox actions = new HBox(2);
        private final HBox row = new HBox(8);

        {
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);
            Text editGlyph = new Text("✎");
            editGlyph.setStyle("-fx-font-size: 13px;");
            Text delGlyph = new Text("🗑");
            delGlyph.setStyle("-fx-font-size: 12px;");
            editBtn.setGraphic(editGlyph);
            editBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            delBtn.setGraphic(delGlyph);
            delBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            editBtn.setFocusTraversable(false);
            delBtn.setFocusTraversable(false);
            editBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 6;");
            delBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 6;");
            Tooltip.install(editBtn, new Tooltip("キャンバス編集で開く"));
            Tooltip.install(delBtn, new Tooltip("キャンバスを削除"));
            editBtn.setOnAction(ev -> {
                String name = getItem();
                if (name == null) {
                    return;
                }
                getListView().getSelectionModel().select(name);
                openListSelectionInEditor.run();
            });
            delBtn.setOnAction(ev -> {
                String name = getItem();
                if (name == null) {
                    return;
                }
                getListView().getSelectionModel().select(name);
                suppressLayoutChooser.set(true);
                try {
                    layoutChooser.setValue(name);
                    currentLayout.set(name);
                } finally {
                    suppressLayoutChooser.set(false);
                }
                deleteActiveLayout.run();
            });
            actions.getChildren().addAll(editBtn, delBtn);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.setVisible(false);
            actions.setManaged(false);
            row.getChildren().addAll(nameLabel, actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(0, 4, 0, 0));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                nameLabel.setText(item);
                setGraphic(row);
                setText(null);
                boolean sel = isSelected();
                actions.setVisible(sel);
                actions.setManaged(sel);
            }
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            if (!isEmpty()) {
                actions.setVisible(selected);
                actions.setManaged(selected);
            }
        }
    });
    canvasListView.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> renderCanvasPreview.run());
    canvasListView.setOnMouseClicked(e -> {
        if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
            openListSelectionInEditor.run();
        }
    });
    VBox listPane = new VBox(8, listControls, canvasListView);
    VBox.setVgrow(canvasListView, Priority.ALWAYS);
    listPane.setMinWidth(120);
    listSplit.getItems().addAll(listPane, previewScroll);
    listSplit.setDividerPositions(0.38);
    VBox listContent = new VBox(listSplit);
    VBox.setVgrow(listSplit, Priority.ALWAYS);
    listContent.setPadding(new Insets(4));
    listTab.setContent(listContent);

    refreshCanvasList.set(() -> {
        String current = canvasListView.getSelectionModel().getSelectedItem();
        try {
            List<String> names = new ArrayList<>(app.vault.listCanvasLayouts());
            String layoutPick = layoutChooser.getValue();
            suppressLayoutChooser.set(true);
            try {
                layoutChooser.getItems().setAll(names);
                if (layoutPick != null && names.contains(layoutPick)) {
                    layoutChooser.setValue(layoutPick);
                } else if (!names.isEmpty()) {
                    layoutChooser.setValue(names.get(0));
                }
                String v = layoutChooser.getValue();
                if (v != null) {
                    currentLayout.set(v);
                }
            } finally {
                suppressLayoutChooser.set(false);
            }
            canvasListView.getItems().setAll(names);
            if (current != null && names.contains(current)) {
                canvasListView.getSelectionModel().select(current);
            } else if (!names.isEmpty()) {
                canvasListView.getSelectionModel().select(names.get(0));
            }
        } catch (IOException ex) {
            GazoFx.showWarn("キャンバス一覧", ex.getMessage());
        }
        renderCanvasPreview.run();
    });
    refreshCanvasList.get().run();

    TabPane tabPane = new TabPane(editTab, randomTab, listTab);
    tabPane.setMinHeight(420);
    tabsRef.set(tabPane);
    if (openRandomTabOnStart) {
        tabPane.getSelectionModel().select(randomTab);
    }

    tabPane.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
        if (n == editTab) {
            refreshEditor.get().run();
        }
    });

    VBox root = new VBox(8, topBar, hubHint, tabPane);
    VBox.setVgrow(tabPane, Priority.ALWAYS);
    root.setPadding(new Insets(8));
    Scene hubScene = new Scene(root, 1180, 820);
    hubStage.setScene(hubScene);
    GazoFx.applyAppIcons(hubStage);
    hubStage.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
        if (e.getCode() == KeyCode.ESCAPE) {
            hubStage.close();
            e.consume();
            return;
        }
        if (tabPane.getSelectionModel().getSelectedItem() == randomTab) {
            if (e.getCode() == KeyCode.LEFT) {
                randomKeyPrev.get().run();
                e.consume();
            } else if (e.getCode() == KeyCode.RIGHT) {
                randomKeyNext.get().run();
                e.consume();
            }
        }
    });

    app.reloadCanvasSelectionFromVault(currentLayout.get());
    applyCanvasSize.run();
    render.run();
    randomBootstrap.run();

    hubStage.setOnShown(e -> {
        if (owner != null && owner.isShowing()) {
            hubStage.setX(Math.round(owner.getX() + (owner.getWidth() - hubStage.getWidth()) / 2));
            hubStage.setY(Math.round(owner.getY() + (owner.getHeight() - hubStage.getHeight()) / 2));
        }
        Platform.runLater(updateEditCanvasFit);
    });
    hubStage.showAndWait();
    }

    private static void showEditCanvasSizeDialog(
            Stage owner,
            Pane editCanvas,
            Runnable saveCanvasSize,
            Runnable updateEditCanvasFit,
            AtomicReference<Runnable> refreshEditor) {
        int curW = (int) Math.round(editCanvas.getPrefWidth());
        int curH = (int) Math.round(editCanvas.getPrefHeight());
        int minW = (int) Math.round(CanvasEditor.CANVAS_EDIT_MIN_WIDTH);
        int minH = (int) Math.round(CanvasEditor.CANVAS_EDIT_MIN_HEIGHT);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("キャンバスサイズ");
        dialog.setHeaderText("作業エリアの幅・高さ（ピクセル）を指定します。");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12, 16, 12, 16));
        Spinner<Integer> wSpin = new Spinner<>();
        wSpin.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(minW, 12000, Math.max(minW, curW), 10));
        Spinner<Integer> hSpin = new Spinner<>();
        hSpin.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(minH, 12000, Math.max(minH, curH), 10));
        wSpin.setEditable(true);
        hSpin.setEditable(true);
        wSpin.setPrefWidth(104);
        hSpin.setPrefWidth(104);
        grid.add(new Label("幅（px）"), 0, 0);
        grid.add(wSpin, 1, 0);
        grid.add(new Label("高さ（px）"), 0, 1);
        grid.add(hSpin, 1, 1);
        Label hint = new Label("右下の取っ手でも変更できます。Shift を押しながらドラッグすると、一回り広げやすくなります。");
        hint.setWrapText(true);
        hint.setMaxWidth(340);
        hint.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        grid.add(hint, 0, 2, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? ButtonType.OK : null);
        Optional<ButtonType> opt = dialog.showAndWait();
        if (opt.isEmpty() || opt.get() != ButtonType.OK) {
            return;
        }
        int nw = Math.max(minW, wSpin.getValue());
        int nh = Math.max(minH, hSpin.getValue());
        editCanvas.setPrefSize(nw, nh);
        saveCanvasSize.run();
        Platform.runLater(updateEditCanvasFit);
        refreshEditor.get().run();
    }

    private static void showAddImagesToCanvasDialog(
            GazoApp app,
            Stage owner,
            AtomicReference<String> currentLayoutRef,
            AtomicReference<Runnable> refreshEditor) {
        List<String> allTagNames;
        try {
            if (app.vault.listImages().isEmpty()) {
                GazoFx.showWarn("画像を追加", "Vault に画像がありません。");
                return;
            }
            allTagNames = new ArrayList<>(app.vault.listAllTags());
            Collections.sort(allTagNames);
        } catch (IOException e) {
            GazoFx.showError("画像を追加", e.getMessage());
            return;
        }

        Dialog<List<Path>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("キャンバスに画像を追加");
        dialog.setHeaderText("ファイル名・タグで絞り込みできます（Ctrl／Shift で複数選択）。");

        TextField nameField = new TextField();
        nameField.setPromptText("ファイル名に含まれる文字（部分一致・大文字小文字無視）");
        nameField.setText(app.galleryImageNameQuery());

        LinkedHashMap<String, CheckBox> tagChecks = new LinkedHashMap<>();
        CheckBox untaggedCb = new CheckBox("タグなし");
        tagChecks.put(TagFilter.UNTAGGED_SENTINEL, untaggedCb);
        for (String t : allTagNames) {
            tagChecks.put(t, new CheckBox(t));
        }
        Set<String> initialTags = app.galleryActiveTagFiltersCopy();
        for (Map.Entry<String, CheckBox> e : tagChecks.entrySet()) {
            e.getValue().setSelected(initialTags.contains(e.getKey()));
        }

        VBox tagBox = new VBox(4);
        tagBox.getChildren().add(untaggedCb);
        for (String t : allTagNames) {
            tagBox.getChildren().add(tagChecks.get(t));
        }
        ScrollPane tagScroll = new ScrollPane(tagBox);
        tagScroll.setFitToWidth(true);
        tagScroll.setMaxHeight(160);
        tagScroll.setMinHeight(80);

        ListView<Path> listView = new ListView<>();
        listView.setItems(FXCollections.observableArrayList());
        listView.setFixedCellSize(66);
        listView.setCellFactory(lv -> new ListCell<Path>() {
            private final ImageView thumb = new ImageView();
            private final Label name = new Label();
            private final HBox row = new HBox(10, thumb, name);

            {
                thumb.setFitWidth(56);
                thumb.setFitHeight(56);
                thumb.setPreserveRatio(true);
                thumb.setSmooth(true);
                name.setMaxWidth(360);
                name.setWrapText(false);
                HBox.setHgrow(name, Priority.ALWAYS);
                row.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                name.setText(item.getFileName().toString());
                Image im = app.thumbnailForPicker(item, 112, 112);
                thumb.setImage(im);
                setGraphic(row);
            }
        });
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        Label countLabel = new Label();
        countLabel.setStyle("-fx-text-fill: #555;");

        Runnable applyFilter =
                () -> {
                    try {
                        LinkedHashSet<String> sel = new LinkedHashSet<>();
                        for (Map.Entry<String, CheckBox> e : tagChecks.entrySet()) {
                            if (e.getValue().isSelected()) {
                                sel.add(e.getKey());
                            }
                        }
                        List<Path> paths = app.listImagesForCanvasAddPicker(sel, nameField.getText());
                        listView.getItems().setAll(paths);
                        countLabel.setText("表示 " + paths.size() + " 件");
                    } catch (IOException ex) {
                        GazoFx.showError("画像を追加", ex.getMessage());
                    }
                };

        nameField.textProperty().addListener((obs, o, n) -> applyFilter.run());
        for (CheckBox cb : tagChecks.values()) {
            cb.selectedProperty().addListener((obs, o, n) -> applyFilter.run());
        }
        applyFilter.run();

        VBox content =
                new VBox(
                        8,
                        new Label("ファイル名"),
                        nameField,
                        new Label("タグ（複数選択はすべて含む AND）"),
                        tagScroll,
                        countLabel,
                        listView);
        VBox.setVgrow(listView, Priority.ALWAYS);
        content.setPrefWidth(520);
        content.setMinHeight(420);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().setPrefHeight(580);
        ButtonType addType = new ButtonType("追加", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("キャンセル", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(addType, cancelType);
        dialog.setResultConverter(btn -> btn == addType ? new ArrayList<>(listView.getSelectionModel().getSelectedItems()) : null);
        Optional<List<Path>> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        List<Path> chosen = result.get();
        if (chosen == null) {
            return;
        }
        if (chosen.isEmpty()) {
            GazoFx.showWarn("画像を追加", "追加する画像を選択してください。");
            return;
        }
        boolean any = false;
        for (Path p : chosen) {
            if (!app.canvasSelection.contains(p)) {
                app.canvasSelection.add(p);
                any = true;
            }
        }
        if (!any) {
            GazoFx.showWarn("画像を追加", "選択した画像はすべてすでにキャンバスに含まれています。");
            return;
        }
        String layoutName = currentLayoutRef.get();
        try {
            app.vault.saveCanvasSelectionOrder(layoutName, new ArrayList<>(app.canvasSelection));
        } catch (IOException ex) {
            GazoFx.showError("画像を追加", ex.getMessage());
            return;
        }
        app.refreshGallery();
        refreshEditor.get().run();
    }
}
