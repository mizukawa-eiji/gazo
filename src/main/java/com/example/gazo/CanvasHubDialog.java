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
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * キャンバス機能の統合 UI: 画像選択・ランダムピック・Vault への保存・スライドショー。
 */
public final class CanvasHubDialog {
    private CanvasHubDialog() {
    }

    public static void open(GazoApp app, Stage owner) {
        open(app, owner, false);
    }

    public static void open(GazoApp app, Stage owner, boolean openRandomTabOnStart) {
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

    Button overwriteSaveButton = new Button("上書き保存");
    overwriteSaveButton.setOnAction(e -> app.persistCanvasSelectionToVaultWithFeedback(currentLayout.get()));

    Button slideshowButton = new Button("スライドショー");
    slideshowButton.setOnAction(e -> app.showSlideshow(owner));

    Label hubHint = new Label(
            "ギャラリーで「キャンバス対象」をチェックするか、「ランダムピック」タブで「名前を付けて保存…」からキャンバスに取り込みます。"
                    + "「キャンバス編集」タブのツールバーから「上書き保存」で、現在のキャンバスの画像一覧を Vault に記録します。"
                    + "キャンバス名タブを切り替えると、切り替え前のキャンバスの一覧は自動で保存されます。"
                    + "ダイアログを閉じるだけでは、最後に表示していたキャンバスの一覧は保存されないので、閉じる前に「上書き保存」してください。"
                    + "「名前を付けて保存…」は現在の内容を保存したうえで別名のキャンバスを複製します。"
                    + "キャンバス編集で位置・回転を調整し、スライドショーで全体を表示できます。");
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

    Tab editTab = new Tab("キャンバス編集");
    editTab.setClosable(false);

    Pane editCanvas = new Pane();
    editCanvas.setPrefSize(1100, 760);
    editCanvas.setStyle("-fx-background-color: linear-gradient(to bottom, #f0ede4, #e4dccb);");
    TabPane layoutTabs = new TabPane();
    layoutTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    ComboBox<String> presetCombo = new ComboBox<>();
    Slider overlapSlider = new Slider(0, 100, 50);
    Slider neatSlider = new Slider(0, 100, 50);
    Button newLayoutButton = new Button("名前を付けて保存…");
    Button autoLayoutButton = new Button("自動レイアウト");

    try {
        for (String name : app.vault.listCanvasLayouts()) {
            Tab t = new Tab(name);
            t.setClosable(false);
            layoutTabs.getTabs().add(t);
        }
    } catch (IOException e) {
        GazoFx.showError("キャンバス読込エラー", e.getMessage());
    }
    if (layoutTabs.getTabs().stream().noneMatch(t -> "default".equals(t.getText()))) {
        Tab t = new Tab("default");
        t.setClosable(false);
        layoutTabs.getTabs().add(t);
    }
    Tab initialTab = layoutTabs.getTabs().stream()
            .filter(t -> "default".equals(t.getText()))
            .findFirst()
            .orElse(layoutTabs.getTabs().isEmpty() ? null : layoutTabs.getTabs().get(0));
    if (initialTab != null) {
        layoutTabs.getSelectionModel().select(initialTab);
    }
    Label layoutNameLabel = new Label();
    layoutNameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #3a3833;");
    Runnable updateLayoutNameLabel = () -> layoutNameLabel.setText("キャンバス名「" + currentLayout.get() + "」");
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
                editCanvas.setPrefSize(Math.max(900, size.width()), Math.max(700, size.height()));
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
                });
        Platform.runLater(updateEditCanvasFit);
    };
    refreshEditor.set(render);

    Runnable onEditResizeFinished = () -> {
        saveCanvasSize.run();
        Platform.runLater(updateEditCanvasFit);
    };
    app.installCanvasResizeHandle(editCanvas, onEditResizeFinished);

    layoutTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
        if (oldTab != null) {
            app.persistCanvasSelectionToVault(oldTab.getText());
        }
        String selected = newTab == null ? "default" : newTab.getText();
        currentLayout.set(selected == null ? "default" : selected);
        app.reloadCanvasSelectionFromVault(currentLayout.get());
        selectedCanvasImage.set(null);
        applyCanvasSize.run();
        render.run();
        updateLayoutNameLabel.run();
    });
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
        boolean exists = layoutTabs.getTabs().stream().anyMatch(t -> layout.equals(t.getText()));
        if (!exists) {
            String sourceLayout = currentLayout.get();
            try {
                app.copyCanvasLayoutState(sourceLayout, layout);
            } catch (IOException ex) {
                GazoFx.showError("名前を付けて保存エラー", ex.getMessage());
                return;
            }
            Tab tab = new Tab(layout);
            tab.setClosable(false);
            layoutTabs.getTabs().add(tab);
            refreshCanvasList.get().run();
            GazoFx.showWarn("名前を付けて保存", "「" + layout + "」として保存しました（「" + sourceLayout + "」の内容を複製）。");
        }
        layoutTabs.getTabs().stream()
                .filter(t -> layout.equals(t.getText()))
                .findFirst()
                .ifPresent(t -> layoutTabs.getSelectionModel().select(t));
    });
    Runnable deleteActiveLayout = () -> {
        Tab selectedTab = layoutTabs.getSelectionModel().getSelectedItem();
        String selected = selectedTab == null ? null : selectedTab.getText();
        if (selected == null || "default".equals(selected)) {
            GazoFx.showWarn("削除不可", "default キャンバスは削除できません。");
            return;
        }
        try {
            app.vault.deleteCanvasLayout(selected);
            layoutTabs.getTabs().removeIf(t -> selected.equals(t.getText()));
            refreshCanvasList.get().run();
            layoutTabs.getTabs().stream()
                    .filter(t -> "default".equals(t.getText()))
                    .findFirst()
                    .ifPresent(t -> layoutTabs.getSelectionModel().select(t));
            app.reloadCanvasSelectionFromVault("default");
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

    HBox editToolBarPrimary = new HBox(12, layoutNameLabel, overwriteSaveButton, newLayoutButton);
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
            "操作: 画像をドラッグで移動 / ホイールで拡大縮小 / Shift+ホイールで回転 / 右クリックで削除。"
                    + " 右下ハンドルでキャンバスサイズ変更。");
    editHelp.setWrapText(true);
    editHelp.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
    editHelp.setPadding(new Insets(2, 12, 10, 12));

    VBox editorVBox = new VBox(8, layoutTabs, editToolBarPrimary, editToolBarControls, editScroll, editHelp);
    VBox.setVgrow(editScroll, Priority.ALWAYS);
    editTab.setContent(editorVBox);
    updateLayoutNameLabel.run();

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

        prevButton.setOnAction(e -> {
            if (index.get() > 0) {
                index.decrementAndGet();
                renderCurrent.run();
            }
        });
        nextButton.setOnAction(e -> nextPick.run());
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
            boolean exists = layoutTabs.getTabs().stream().anyMatch(t -> newLayout.equals(t.getText()));
            if (!exists) {
                Tab tab = new Tab(newLayout);
                tab.setClosable(false);
                layoutTabs.getTabs().add(tab);
                refreshCanvasList.get().run();
            }
            layoutTabs.getTabs().stream()
                    .filter(t -> newLayout.equals(t.getText()))
                    .findFirst()
                    .ifPresent(t -> layoutTabs.getSelectionModel().select(t));
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
        layoutTabs.getTabs().stream()
                .filter(t -> selectedName.equals(t.getText()))
                .findFirst()
                .ifPresent(t -> {
                    layoutTabs.getSelectionModel().select(t);
                    TabPane tp = tabsRef.get();
                    if (tp != null) {
                        tp.getSelectionModel().select(editTab);
                    }
                });
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
                layoutTabs.getTabs().stream()
                        .filter(t -> name.equals(t.getText()))
                        .findFirst()
                        .ifPresent(t -> layoutTabs.getSelectionModel().select(t));
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
        List<String> names = layoutTabs.getTabs().stream().map(Tab::getText).toList();
        canvasListView.getItems().setAll(names);
        if (current != null && names.contains(current)) {
            canvasListView.getSelectionModel().select(current);
        } else if (!names.isEmpty()) {
            canvasListView.getSelectionModel().select(names.get(0));
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
        }
    });

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
}
