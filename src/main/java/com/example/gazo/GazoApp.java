package com.example.gazo;

import com.example.gazo.cli.CliImport;
import com.example.gazo.vault.GazoVaultService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.text.Text;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.cryptomator.cryptolib.api.InvalidPassphraseException;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 登録画像をスナップ写真風に表示し、Cryptomator 互換 Vault に保存する JavaFX アプリ。
 */
public final class GazoApp extends Application {
    private static final double BASE_CANVAS_WIDTH = 2000.0;
    private static final double BASE_CANVAS_HEIGHT = 1400.0;
    GazoVaultService vault;
    private Stage primaryStage;
    /** メインウィンドウに重ねる処理中オーバーレイ（サムネイル再作成など） */
    private StackPane appBusyPane;
    private Label appBusyMessageLabel;
    private FlowPane gallery;
    private FlowPane videoGallery;
    private Label vaultPathLabel;
    /** インポート中のみファイル名を表示（通常は空） */
    private Label importStatusLabel;
    /** 画像タブツールバー: フィルター後の表示数と Vault 内の画像総数 */
    private Label imageGalleryCountLabel;
    /** 絞り込みに使うタグ（正規化済み・小文字）。空なら「すべて表示」。複数指定時は AND（すべて含む）。 */
    private final LinkedHashSet<String> activeTagFilters = new LinkedHashSet<>();
    private MenuButton tagFilterMenuButton;
    private ListView<String> tagFilterListView;
    private final Map<String, BooleanProperty> tagFilterSelectionMap = new java.util.LinkedHashMap<>();
    private boolean updatingTagFilterSelection;
    private MenuButton displayOptionsMenuButton;
    private ListView<String> displayOptionsListView;
    private TextField imageSearchField;
    private final Map<String, BooleanProperty> displayOptionSelectionMap = new java.util.LinkedHashMap<>();
    private boolean updatingDisplayOptionSelection;
    private boolean showFileName = true;
    private boolean showDate = true;
    private boolean showTags = true;
    private String imageNameQuery = "";
    static final List<String> LAYOUT_PRESETS = List.of("コラージュ風", "整列風");
    private static final List<String> LIST_VIEW_SIZE_OPTIONS = List.of("小", "中", "大");
    final Set<Path> canvasSelection = new LinkedHashSet<>();
    final Set<Path> listCheckedSelection = new LinkedHashSet<>();
    private String listViewSize = "中";

    /** メイン「キャンバス」タブのプレビュー縮小表示用（ビューポートに合わせる） */
    private ScrollPane homeCanvasPreviewScroll;
    private Pane homeCanvasPreviewPane;
    private Scale homeCanvasPreviewScale;
    private Group homeCanvasPreviewHolder;

    public static void main(String[] args) {
        CliImport.tryRun(args);
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Path defaultVaultDir = VaultPathStore.loadInitialVaultPath();
        try {
            openVault(stage, defaultVaultDir);
        } catch (Exception e) {
            GazoFx.showError("Vault を開けませんでした", e.getMessage());
            Platform.exit();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (vault != null) {
                vault.close();
            }
        }));

        primaryStage = stage;

        VaultPathStore.GallerySettings gallerySettings = VaultPathStore.loadGallerySettings();
        activeTagFilters.clear();
        activeTagFilters.addAll(gallerySettings.tagFilters());
        showFileName = gallerySettings.showFileName();
        showDate = gallerySettings.showDate();
        showTags = gallerySettings.showTags();
        listViewSize = gallerySettings.listViewSize();
        if (!LIST_VIEW_SIZE_OPTIONS.contains(listViewSize)) {
            listViewSize = "中";
        }
        String loadedSearch = gallerySettings.imageNameQuery() == null ? "" : gallerySettings.imageNameQuery();
        imageNameQuery = loadedSearch.trim().toLowerCase();

        gallery = new FlowPane();
        gallery.setHgap(16);
        gallery.setVgap(16);
        gallery.setPadding(new Insets(22));
        gallery.setStyle("-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);");

        ScrollPane imageScroll = new ScrollPane(gallery);
        imageScroll.setFitToWidth(true);

        videoGallery = new FlowPane();
        videoGallery.setHgap(16);
        videoGallery.setVgap(16);
        videoGallery.setPadding(new Insets(22));
        videoGallery.setStyle("-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);");
        ScrollPane videoScroll = new ScrollPane(videoGallery);
        videoScroll.setFitToWidth(true);

        MenuItem addMenu = new MenuItem("画像を追加…");
        addMenu.setOnAction(e -> addImages(stage));
        MenuItem addVideosMenu = new MenuItem("動画を追加…");
        addVideosMenu.setOnAction(e -> addVideos(stage));
        MenuItem rebuildThumbsMenu = new MenuItem("サムネイル再作成…");
        rebuildThumbsMenu.setOnAction(e -> rebuildAllThumbnails());
        MenuItem duplicateMenu = new MenuItem("重複チェック");
        duplicateMenu.setOnAction(e -> showDuplicateReport());
        MenuItem bulkAddMenu = new MenuItem("タグ一括追加");
        bulkAddMenu.setOnAction(e -> addTagsToCanvasSelection());
        MenuItem bulkRemoveMenu = new MenuItem("タグ一括削除");
        bulkRemoveMenu.setOnAction(e -> removeTagsFromCanvasSelection());
        MenuItem changeVaultMenu = new MenuItem("Vault変更…");
        changeVaultMenu.setOnAction(e -> changeVaultPath(stage));
        MenuItem exitMenu = new MenuItem("終了");
        exitMenu.setOnAction(e -> Platform.exit());

        Menu fileMenu = new Menu("ファイル");
        fileMenu.getItems().addAll(
                addMenu,
                addVideosMenu,
                rebuildThumbsMenu,
                new SeparatorMenuItem(),
                changeVaultMenu,
                new SeparatorMenuItem(),
                exitMenu);

        Menu actionsMenu = new Menu("操作");
        actionsMenu.getItems().addAll(
                duplicateMenu,
                new SeparatorMenuItem(),
                bulkAddMenu,
                bulkRemoveMenu,
                new SeparatorMenuItem());
        MenuBar menuBar = new MenuBar(fileMenu, actionsMenu);
        Button selectAll = new Button("全選択");
        selectAll.setOnAction(e -> selectAllVisibleImages());
        Button clearSelection = new Button("クリア");
        clearSelection.setOnAction(e -> clearListCheckedSelection());
        Button canvasHub = new Button("選択画像でキャンバス作成");
        canvasHub.setOnAction(e -> showCanvasHubDialog(stage));
        Button randomCanvasHub = new Button("ランダムにキャンバスを作成");
        randomCanvasHub.setOnAction(e -> showCanvasHubDialogRandom(stage));
        tagFilterListView = new ListView<>();
        tagFilterListView.setPrefWidth(260);
        tagFilterListView.setPrefHeight(220);
        tagFilterListView.setCellFactory(CheckBoxListCell.forListView(tag -> {
            BooleanProperty prop = tagFilterSelectionMap.computeIfAbsent(tag, k -> {
                SimpleBooleanProperty p = new SimpleBooleanProperty(false);
                p.addListener((obs, oldV, newV) -> {
                    if (!updatingTagFilterSelection) {
                        onTagFilterCheckboxChanged();
                    }
                });
                return p;
            });
            return prop;
        }));
        CustomMenuItem tagFilterMenuItem = new CustomMenuItem(tagFilterListView, false);
        tagFilterMenuButton = new MenuButton("タグ: すべて");
        tagFilterMenuButton.getItems().setAll(tagFilterMenuItem);
        tagFilterMenuButton.setPrefWidth(180);
        Button clearTagFiltersBtn = new Button("×");
        clearTagFiltersBtn.setTooltip(new Tooltip("タグの絞り込みを解除"));
        clearTagFiltersBtn.setFocusTraversable(false);
        clearTagFiltersBtn.setStyle("-fx-font-weight: bold; -fx-padding: 2 8;");
        clearTagFiltersBtn.setOnAction(e -> {
            activeTagFilters.clear();
            updatingTagFilterSelection = true;
            try {
                for (BooleanProperty p : tagFilterSelectionMap.values()) {
                    p.set(false);
                }
            } finally {
                updatingTagFilterSelection = false;
            }
            refreshGallery();
            refreshVideoList();
            updateTagFilterButtonText();
            persistGallerySettings();
        });
        displayOptionsListView = new ListView<>();
        displayOptionsListView.setPrefWidth(170);
        displayOptionsListView.setPrefHeight(124);
        displayOptionsListView.setCellFactory(CheckBoxListCell.forListView(this::displayOptionProperty));
        displayOptionsListView.getItems().setAll("ファイル名", "日付", "タグ");
        CustomMenuItem displayOptionsMenuItem = new CustomMenuItem(displayOptionsListView, false);
        displayOptionsMenuButton = new MenuButton("表示: 3/3");
        displayOptionsMenuButton.getItems().setAll(displayOptionsMenuItem);
        updatingDisplayOptionSelection = true;
        try {
            displayOptionProperty("ファイル名").set(showFileName);
            displayOptionProperty("日付").set(showDate);
            displayOptionProperty("タグ").set(showTags);
        } finally {
            updatingDisplayOptionSelection = false;
        }
        updateDisplayOptionsButtonText();
        ComboBox<String> listSizeCombo = new ComboBox<>();
        listSizeCombo.getItems().setAll(LIST_VIEW_SIZE_OPTIONS);
        listSizeCombo.setValue(listViewSize);
        listSizeCombo.setOnAction(e -> {
            String selected = listSizeCombo.getValue();
            listViewSize = selected == null ? "中" : selected;
            refreshGallery();
            persistGallerySettings();
        });
        imageSearchField = new TextField();
        imageSearchField.setPromptText("ファイル名検索");
        imageSearchField.setPrefWidth(180);
        imageSearchField.setText(loadedSearch);
        imageSearchField.textProperty().addListener((obs, oldV, newV) -> {
            imageNameQuery = newV == null ? "" : newV.trim().toLowerCase();
            refreshGallery();
            persistGallerySettings();
        });
        Button clearImageSearchButton = new Button("×");
        clearImageSearchButton.setTooltip(new Tooltip("ファイル名検索をクリア"));
        clearImageSearchButton.setFocusTraversable(false);
        clearImageSearchButton.setStyle("-fx-font-weight: bold; -fx-padding: 2 8;");
        clearImageSearchButton.setOnAction(e -> imageSearchField.clear());
        imageGalleryCountLabel = new Label("表示 0 / 全 0 枚");
        imageGalleryCountLabel.setStyle("-fx-text-fill: #4a5560;");
        Region imageToolbarSpacer = new Region();
        HBox.setHgrow(imageToolbarSpacer, Priority.ALWAYS);
        vaultPathLabel = new Label();
        vaultPathLabel.setMinWidth(0);
        vaultPathLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        importStatusLabel = new Label("");
        importStatusLabel.setMinWidth(0);
        importStatusLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        importStatusLabel.setStyle("-fx-text-fill: #4a5560;");
        updateVaultPathLabel();

        HBox imageToolbar = new HBox(
                10,
                selectAll,
                clearSelection,
                canvasHub,
                randomCanvasHub,
                new Label("タグ:"),
                tagFilterMenuButton,
                clearTagFiltersBtn,
                displayOptionsMenuButton,
                new Label("検索:"),
                imageSearchField,
                clearImageSearchButton,
                new Label("一覧サイズ:"),
                listSizeCombo,
                imageToolbarSpacer,
                imageGalleryCountLabel);
        imageToolbar.setAlignment(Pos.CENTER_LEFT);
        imageToolbar.setPadding(new Insets(10));
        imageToolbar.setStyle(
                "-fx-background-color: rgba(255,255,255,0.72); -fx-border-color: #d7d0c2; -fx-border-width: 0 0 1 0;");

        VBox imageTabContent = new VBox(0, imageToolbar, imageScroll);
        VBox.setVgrow(imageScroll, Priority.ALWAYS);

        Pane homeCanvasPane = new Pane();
        homeCanvasPane.setStyle("-fx-background-color: linear-gradient(to bottom, #f0ede4, #e4dccb);");
        homeCanvasPreviewScale = new Scale(1, 1, 0, 0);
        homeCanvasPreviewHolder = new Group(homeCanvasPane);
        homeCanvasPreviewHolder.getTransforms().add(homeCanvasPreviewScale);
        homeCanvasPreviewHolder.setManaged(false);
        Pane homeCanvasViewport = new Pane();
        homeCanvasViewport.getChildren().add(homeCanvasPreviewHolder);
        homeCanvasPreviewScroll = new ScrollPane(homeCanvasViewport);
        homeCanvasPreviewScroll.setFitToWidth(true);
        homeCanvasPreviewScroll.setFitToHeight(true);
        homeCanvasPreviewPane = homeCanvasPane;
        homeCanvasPreviewScroll.viewportBoundsProperty().addListener((obs, oldB, newB) -> {
            if (newB != null) {
                Platform.runLater(this::fitHomeCanvasPreview);
            }
        });
        Label homeCanvasLayoutLabel = new Label("—");
        homeCanvasLayoutLabel.setStyle("-fx-font-weight: bold;");
        Button homeCanvasShuffleButton = new Button("別のキャンバス");
        homeCanvasShuffleButton.setOnAction(e -> refreshRandomCanvasPreview(homeCanvasPane, homeCanvasLayoutLabel, true));
        Button homeCanvasSlideshowButton = new Button("スライドショーで開く");
        homeCanvasSlideshowButton.setOnAction(e ->
                showSlideshow(stage, parseHomeCanvasLayoutName(homeCanvasLayoutLabel)));
        HBox homeCanvasBar = new HBox(12, new Label("キャンバス:"), homeCanvasLayoutLabel, homeCanvasShuffleButton, homeCanvasSlideshowButton);
        homeCanvasBar.setAlignment(Pos.CENTER_LEFT);
        homeCanvasBar.setPadding(new Insets(8, 10, 8, 10));
        homeCanvasBar.setStyle(
                "-fx-background-color: rgba(255,255,255,0.72); -fx-border-color: #d7d0c2; -fx-border-width: 0 0 1 0;");
        VBox canvasTabContent = new VBox(0, homeCanvasBar, homeCanvasPreviewScroll);
        VBox.setVgrow(homeCanvasPreviewScroll, Priority.ALWAYS);

        TabPane mainTabs = new TabPane();
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab canvasTab = new Tab("キャンバス", canvasTabContent);
        canvasTab.setClosable(false);
        Tab imageTab = new Tab("画像", imageTabContent);
        imageTab.setClosable(false);
        Tab videoTab = new Tab("動画", videoScroll);
        videoTab.setClosable(false);
        mainTabs.getTabs().addAll(canvasTab, imageTab, videoTab);
        mainTabs.getSelectionModel().select(canvasTab);
        mainTabs.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == canvasTab) {
                refreshRandomCanvasPreview(homeCanvasPane, homeCanvasLayoutLabel, false);
            }
        });

        HBox top = new HBox(menuBar);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(Insets.EMPTY);
        // メニューバーをウィンドウ上端に密着させる。
        top.setStyle("-fx-background-color: transparent; -fx-border-color: #d7d0c2; -fx-border-width: 0 0 1 0;");

        Region statusBarSpacer = new Region();
        HBox.setHgrow(statusBarSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, vaultPathLabel, statusBarSpacer, importStatusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(6, 10, 6, 10));
        statusBar.setStyle("-fx-background-color: rgba(255,255,255,0.78); -fx-border-color: #d7d0c2; -fx-border-width: 1 0 0 0;");

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(mainTabs);
        root.setBottom(statusBar);

        appBusyPane = new StackPane();
        Region appBusyBg = new Region();
        appBusyBg.setStyle("-fx-background-color: rgba(255,255,255,0.82);");
        ProgressIndicator appBusySpinner = new ProgressIndicator();
        appBusySpinner.setPrefSize(44, 44);
        appBusyMessageLabel = new Label("処理中…");
        appBusyMessageLabel.setStyle("-fx-font-size: 13px;");
        VBox appBusyCenter = new VBox(10, appBusySpinner, appBusyMessageLabel);
        appBusyCenter.setAlignment(Pos.CENTER);
        appBusyPane.getChildren().addAll(appBusyBg, appBusyCenter);
        appBusyPane.setVisible(false);
        appBusyPane.setManaged(false);
        StackPane rootStack = new StackPane(root, appBusyPane);

        Scene scene = new Scene(rootStack, 920, 680);
        stage.setTitle("Gazo — 暗号化フォルダに保存する写真ビューア (JavaFX)");
        GazoFx.applyAppIcons(stage);
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        enableImageDragAndDrop(rootStack);
        refreshTagFilterOptions();
        refreshGallery();
        refreshVideoList();
        // 起動直後にキャンバスプレビューを初期表示する。
        refreshRandomCanvasPreview(homeCanvasPane, homeCanvasLayoutLabel, false);
        Platform.runLater(this::fitHomeCanvasPreview);
    }

    /** キャンバスタブのキャンバスをスクロールビューポート内に収まるよう縮小（拡大はしない）。 */
    private void fitHomeCanvasPreview() {
        if (homeCanvasPreviewScroll == null
                || homeCanvasPreviewPane == null
                || homeCanvasPreviewScale == null
                || homeCanvasPreviewHolder == null) {
            return;
        }
        Bounds vb = homeCanvasPreviewScroll.getViewportBounds();
        double vw = vb.getWidth();
        double vh = vb.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        double cw = homeCanvasPreviewPane.getPrefWidth();
        double ch = homeCanvasPreviewPane.getPrefHeight();
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
        homeCanvasPreviewScale.setX(s);
        homeCanvasPreviewScale.setY(s);
        double scaledW = cw * s;
        double scaledH = ch * s;
        homeCanvasPreviewHolder.setLayoutX((vw - scaledW) / 2.0);
        homeCanvasPreviewHolder.setLayoutY((vh - scaledH) / 2.0);
    }

    private void openVault(Stage stage, Path vaultDir) throws IOException, MasterkeyLoadingFailedException {
        GazoVaultService newVault = new GazoVaultService(vaultDir);
        unlockOrCreate(stage, newVault);
        if (vault != null) {
            vault.close();
        }
        vault = newVault;
        VaultPathStore.saveLastVaultPath(vault.getVaultPath());
        reloadCanvasSelectionFromVault("default");
    }

    void reloadCanvasSelectionFromVault(String canvasName) {
        if (vault == null) {
            return;
        }
        try {
            List<Path> paths = vault.listCanvasSelectionOrder(canvasName);
            canvasSelection.clear();
            canvasSelection.addAll(paths);
        } catch (IOException e) {
            GazoFx.showWarn("キャンバス選択の読込", e.getMessage());
        }
    }

    /** キャンバス選択の並びを Vault に書き込む（トーストなし）。 */
    void persistCanvasSelectionToVault(String canvasName) {
        if (vault == null) {
            return;
        }
        try {
            vault.saveCanvasSelectionOrder(canvasName, new ArrayList<>(canvasSelection));
        } catch (IOException e) {
            GazoFx.showError("保存エラー", e.getMessage());
        }
    }

    /** 現在のキャンバス画像一覧を指定キャンバス名で Vault に上書きし、完了を通知する。 */
    void persistCanvasSelectionToVaultWithFeedback(String canvasName) {
        if (vault == null) {
            return;
        }
        try {
            vault.saveCanvasSelectionOrder(canvasName, new ArrayList<>(canvasSelection));
            GazoFx.showWarn("上書き保存", "「" + canvasName + "」の画像一覧を保存しました。");
        } catch (IOException e) {
            GazoFx.showError("保存エラー", e.getMessage());
        }
    }

    void showSlideshow(Stage owner) {
        showSlideshow(owner, null);
    }

    void showSlideshow(Stage owner, String initialCanvasName) {
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
        slideStage.initOwner(owner);
        slideStage.initModality(Modality.WINDOW_MODAL);
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
        Runnable fitCanvas = () -> {
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
        Runnable applyLayout = () -> {
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
            renderCanvasItems(canvas, name, false, null, null, null);
            Platform.runLater(fitCanvas);
        };
        Runnable prev = () -> {
            idx.updateAndGet(v -> (v - 1 + layoutNames.size()) % layoutNames.size());
            applyLayout.run();
        };
        Runnable next = () -> {
            idx.updateAndGet(v -> (v + 1) % layoutNames.size());
            applyLayout.run();
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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
        slideStage.setOnShown(e -> {
            applyLayout.run();
            Platform.runLater(() -> {
                fitCanvas.run();
                root.requestFocus();
            });
        });
        slideStage.setOnHidden(e -> {
            canvasSelection.clear();
            canvasSelection.addAll(selectionBackup);
        });
        slideStage.show();
    }

    private void updateVaultPathLabel() {
        if (vaultPathLabel != null && vault != null) {
            vaultPathLabel.setText("Vault: " + vault.getVaultPath());
        }
    }

    private void setImportStatusLabel(String fileName) {
        if (importStatusLabel != null) {
            importStatusLabel.setText("インポート中: " + fileName);
        }
    }

    private void clearImportStatusLabel() {
        if (importStatusLabel != null) {
            importStatusLabel.setText("");
        }
    }

    private void changeVaultPath(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Vault の保存フォルダを選択");
        if (vault != null) {
            java.io.File current = vault.getVaultPath().toFile();
            java.io.File initial = current.isDirectory() ? current : current.getParentFile();
            if (initial != null && initial.exists()) {
                chooser.setInitialDirectory(initial);
            }
        }
        java.io.File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        try {
            openVault(stage, selected.toPath());
            updateVaultPathLabel();
            refreshTagFilterOptions();
            refreshGallery();
            refreshVideoList();
        } catch (Exception e) {
            GazoFx.showError("Vault 変更エラー", e.getMessage());
        }
    }

    private void unlockOrCreate(Stage stage, GazoVaultService targetVault) throws IOException, MasterkeyLoadingFailedException {
        if (!targetVault.vaultExists()) {
            char[] pass = GazoFx.promptPasswordTwice("新しい Vault を作成", "パスワードを設定してください");
            if (pass == null) {
                throw new IllegalStateException("キャンセルされました");
            }
            try {
                targetVault.createVault(new String(pass));
            } finally {
                Arrays.fill(pass, '\0');
            }
            return;
        }

        while (true) {
            char[] pass = GazoFx.promptPassword("Vault のロックを解除", "パスワードを入力してください");
            if (pass == null) {
                throw new IllegalStateException("キャンセルされました");
            }
            try {
                targetVault.unlock(new String(pass));
                Arrays.fill(pass, '\0');
                return;
            } catch (InvalidPassphraseException e) {
                Arrays.fill(pass, '\0');
                GazoFx.showWarn("解除できません", "パスワードが正しくありません。");
            }
        }
    }

    private void addImages(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("画像を選択");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("画像", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            for (java.io.File f : files) {
                setImportStatusLabel(f.getName());
                try {
                    vault.importImage(f.toPath());
                } catch (IOException e) {
                    GazoFx.showError("保存エラー", f.getName() + " の保存に失敗しました: " + e.getMessage());
                }
            }
        } finally {
            clearImportStatusLabel();
        }
        refreshGallery();
    }

    private void addVideos(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("動画を選択");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("動画", "*.mp4", "*.webm", "*.m4v", "*.mov", "*.mkv"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            for (java.io.File f : files) {
                setImportStatusLabel(f.getName());
                try {
                    vault.importVideo(f.toPath());
                } catch (IOException e) {
                    GazoFx.showError("保存エラー", f.getName() + " の保存に失敗しました: " + e.getMessage());
                }
            }
        } finally {
            clearImportStatusLabel();
        }
        refreshTagFilterOptions();
        refreshVideoList();
    }

    private void setMainWindowBusy(boolean busy, String message) {
        if (appBusyMessageLabel != null) {
            appBusyMessageLabel.setText(message != null && !message.isBlank() ? message : "処理中…");
        }
        if (appBusyPane != null) {
            appBusyPane.setVisible(busy);
            appBusyPane.setManaged(busy);
        }
    }

    private void rebuildAllThumbnails() {
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "登録済み画像のサムネイルを再作成します。画像数が多い場合は時間がかかります。実行しますか？",
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.setTitle("サムネイル再作成");
        confirm.setHeaderText(null);
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        setMainWindowBusy(true, "サムネイルを再作成しています…");

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return vault.rebuildAllThumbnails();
            }
        };
        task.setOnSucceeded(e -> {
            setMainWindowBusy(false, "");
            Integer count = task.getValue();
            GazoFx.showWarn("サムネイル再作成", (count == null ? 0 : count) + " 件を再作成しました。");
            refreshGallery();
        });
        task.setOnFailed(e -> {
            setMainWindowBusy(false, "");
            Throwable ex = task.getException();
            GazoFx.showError("サムネイル再作成エラー", ex != null ? ex.getMessage() : "不明なエラー");
        });

        Thread t = new Thread(task, "gazo-rebuild-thumbnails");
        t.setDaemon(true);
        t.start();
    }

    void refreshGallery() {
        gallery.getChildren().clear();
        try {
            Map<String, Set<String>> tagMap = vault.tagsByFileName();
            List<Path> allImages = vault.listImages();
            List<Path> paths = listFilteredImages(tagMap, allImages);
            updateImageGalleryCountLabel(paths.size(), allImages.size());
            scheduleGalleryCards(paths, tagMap, 0, 28);
        } catch (IOException e) {
            if (imageGalleryCountLabel != null) {
                imageGalleryCountLabel.setText("—");
            }
            GazoFx.showError("読み込みエラー", e.getMessage());
        }
    }

    private void updateImageGalleryCountLabel(int shown, int total) {
        if (imageGalleryCountLabel != null) {
            imageGalleryCountLabel.setText("表示 " + shown + " / 全 " + total + " 枚");
        }
    }

    /**
     * 大量画像でも UI を止めないよう、カードを複数フレームに分けて追加する。
     */
    private void scheduleGalleryCards(List<Path> paths, Map<String, Set<String>> tagMap, int start, int batchSize) {
        int end = Math.min(start + batchSize, paths.size());
        for (int i = start; i < end; i++) {
            Path p = paths.get(i);
            Set<String> tags = tagMap.getOrDefault(p.getFileName().toString(), Set.of());
            gallery.getChildren().add(createSnapCard(p, tags));
        }
        if (end < paths.size()) {
            Platform.runLater(() -> scheduleGalleryCards(paths, tagMap, end, batchSize));
        }
    }

    private void refreshVideoList() {
        if (videoGallery == null) {
            return;
        }
        videoGallery.getChildren().clear();
        try {
            Map<String, Set<String>> tagMap = vault.tagsByFileName();
            for (Path p : listFilteredVideos(tagMap)) {
                videoGallery.getChildren().add(createVideoCard(p));
            }
        } catch (IOException e) {
            GazoFx.showError("動画一覧エラー", e.getMessage());
        }
    }

    private List<Path> listFilteredVideos(Map<String, Set<String>> tagsByFile) throws IOException {
        List<Path> filtered = new ArrayList<>();
        for (Path p : vault.listVideos()) {
            Set<String> tags = tagsByFile.getOrDefault(p.getFileName().toString(), Set.of());
            if (!matchesTagFilter(tags)) {
                continue;
            }
            filtered.add(p);
        }
        return filtered;
    }

    private VBox createVideoCard(Path videoPath) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setPrefWidth(220);
        card.setStyle("-fx-background-color: #fffdf8; -fx-border-color: #d5cec0; -fx-border-width: 2; "
                + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 10, 0.2, 2, 3);");
        Label title = new Label(videoPath.getFileName().toString());
        title.setWrapText(true);
        Label hint = new Label("ダブルクリックで再生");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #6a6355;");
        card.getChildren().addAll(title, hint);
        card.setUserData(videoPath);
        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                showVideoPlayer(videoPath);
            }
        });
        return card;
    }

    /**
     * Cryptomator 上のパスは OS のネイティブデコーダが直接読めないことが多いため、一時ファイルにコピーして再生する。
     */
    private void showVideoPlayer(Path vaultPath) {
        Stage playerStage = new Stage();
        GazoFx.applyAppIcons(playerStage);
        playerStage.initOwner(primaryStage);
        Label status = new Label("動画を準備しています…");
        status.setPadding(new Insets(16));
        StackPane waitRoot = new StackPane(status);
        playerStage.setScene(new Scene(waitRoot, 420, 100));
        playerStage.setTitle(vaultPath.getFileName().toString());
        playerStage.show();

        Task<Path> copyTask = new Task<>() {
            @Override
            protected Path call() throws IOException {
                String suffix = extensionForTempFile(vaultPath);
                Path temp = Files.createTempFile("gazo-play-", suffix);
                Files.copy(vaultPath, temp, StandardCopyOption.REPLACE_EXISTING);
                return temp;
            }
        };
        copyTask.setOnSucceeded(ev -> {
            Path temp = copyTask.getValue();
            try {
                Media media = new Media(temp.toUri().toString());
                media.setOnError(() -> {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // ignore
                    }
                    javafx.scene.media.MediaException mex = media.getError();
                    String msg = mex != null && mex.getMessage() != null ? mex.getMessage() : "メディアを読み取れませんでした";
                    Platform.runLater(() -> {
                        GazoFx.showError("再生エラー", msg);
                        playerStage.close();
                    });
                });
                MediaPlayer mediaPlayer = new MediaPlayer(media);
                MediaView mediaView = new MediaView(mediaPlayer);
                BorderPane root = new BorderPane(mediaView);
                Scene scene = new Scene(root, 920, 520);
                mediaView.setPreserveRatio(true);
                mediaView.fitWidthProperty().bind(scene.widthProperty());
                mediaView.fitHeightProperty().bind(scene.heightProperty());
                playerStage.setScene(scene);
                Runnable cleanup = () -> {
                    mediaPlayer.stop();
                    mediaPlayer.dispose();
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // ignore
                    }
                };
                playerStage.setOnCloseRequest(e -> cleanup.run());
                mediaPlayer.setOnError(() -> {
                    String msg = mediaPlayer.getError() != null ? mediaPlayer.getError().getMessage() : "不明なエラー";
                    cleanup.run();
                    Platform.runLater(() -> {
                        GazoFx.showError("再生エラー", msg);
                        playerStage.close();
                    });
                });
                mediaPlayer.play();
            } catch (Exception ex) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // ignore
                }
                GazoFx.showError("再生エラー", ex.getMessage());
                playerStage.close();
            }
        });
        copyTask.setOnFailed(ev -> {
            Throwable ex = copyTask.getException();
            Platform.runLater(() -> {
                GazoFx.showError("読み込みエラー", ex != null ? ex.getMessage() : "不明なエラー");
                playerStage.close();
            });
        });
        Thread copyThread = new Thread(copyTask, "gazo-video-temp");
        copyThread.setDaemon(true);
        copyThread.start();
    }

    private static String extensionForTempFile(Path vaultPath) {
        String n = vaultPath.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i) : ".mp4";
    }

    private List<Path> listFilteredImages(Map<String, Set<String>> tagsByFile) throws IOException {
        return listFilteredImages(tagsByFile, vault.listImages());
    }

    private List<Path> listFilteredImages(Map<String, Set<String>> tagsByFile, List<Path> allImages) {
        List<Path> filtered = new ArrayList<>();
        for (Path p : allImages) {
            Set<String> tags = tagsByFile.getOrDefault(p.getFileName().toString(), Set.of());
            if (!matchesTagFilter(tags)) {
                continue;
            }
            if (!imageNameQuery.isBlank()) {
                String fileName = p.getFileName().toString().toLowerCase();
                if (!fileName.contains(imageNameQuery)) {
                    continue;
                }
            }
            filtered.add(p);
        }
        return filtered;
    }

    /** タグ未選択ならすべて表示。1つ以上なら、そのタグをすべて含むファイルだけ（AND）。 */
    private boolean matchesTagFilter(Set<String> fileTags) {
        if (activeTagFilters.isEmpty()) {
            return true;
        }
        return fileTags.containsAll(activeTagFilters);
    }

    private void onTagFilterCheckboxChanged() {
        activeTagFilters.clear();
        for (Map.Entry<String, BooleanProperty> entry : tagFilterSelectionMap.entrySet()) {
            if (entry.getValue().get()) {
                activeTagFilters.add(entry.getKey());
            }
        }
        refreshGallery();
        refreshVideoList();
        updateTagFilterButtonText();
        persistGallerySettings();
    }

    private void updateTagFilterButtonText() {
        if (tagFilterMenuButton == null) {
            return;
        }
        if (activeTagFilters.isEmpty()) {
            tagFilterMenuButton.setText("タグ: すべて");
            return;
        }
        tagFilterMenuButton.setText("タグ: " + activeTagFilters.size() + "件選択");
    }

    private BooleanProperty displayOptionProperty(String label) {
        return displayOptionSelectionMap.computeIfAbsent(label, k -> {
            SimpleBooleanProperty p = new SimpleBooleanProperty(false);
            p.addListener((obs, oldV, newV) -> {
                if (!updatingDisplayOptionSelection) {
                    onDisplayOptionsChanged();
                }
            });
            return p;
        });
    }

    private void onDisplayOptionsChanged() {
        BooleanProperty fileNameProp = displayOptionSelectionMap.get("ファイル名");
        BooleanProperty dateProp = displayOptionSelectionMap.get("日付");
        BooleanProperty tagProp = displayOptionSelectionMap.get("タグ");
        showFileName = fileNameProp != null && fileNameProp.get();
        showDate = dateProp != null && dateProp.get();
        showTags = tagProp != null && tagProp.get();
        updateDisplayOptionsButtonText();
        refreshGallery();
        persistGallerySettings();
    }

    private void persistGallerySettings() {
        String searchRaw = imageSearchField == null ? "" : imageSearchField.getText();
        VaultPathStore.saveGallerySettings(new VaultPathStore.GallerySettings(
                showFileName,
                showDate,
                showTags,
                listViewSize,
                searchRaw,
                new ArrayList<>(activeTagFilters)));
    }

    private void updateDisplayOptionsButtonText() {
        if (displayOptionsMenuButton == null) {
            return;
        }
        int selected = 0;
        if (showFileName) {
            selected++;
        }
        if (showDate) {
            selected++;
        }
        if (showTags) {
            selected++;
        }
        displayOptionsMenuButton.setText("表示: " + selected + "/3");
    }

    boolean isShowFileNameOption() {
        return showFileName;
    }

    void setShowFileNameOption(boolean enabled) {
        showFileName = enabled;
        BooleanProperty fileNameProp = displayOptionSelectionMap.get("ファイル名");
        if (fileNameProp != null) {
            updatingDisplayOptionSelection = true;
            try {
                fileNameProp.set(enabled);
            } finally {
                updatingDisplayOptionSelection = false;
            }
        }
        updateDisplayOptionsButtonText();
        refreshGallery();
        persistGallerySettings();
    }

    /**
     * 既に描画済みのキャンバス項目に対して、ファイル名ラベルの表示だけを切り替える。
     * 配置再計算は行わないため、ON/OFFで座標は変わらない。
     */
    void applyCanvasFileNameVisibility(Pane canvas) {
        if (canvas == null) {
            return;
        }
        for (Node node : canvas.getChildren()) {
            if (node instanceof VBox box && box.getChildren().size() >= 2) {
                Node n = box.getChildren().get(1);
                if (n instanceof Label label) {
                    if (showFileName) {
                        label.setVisible(true);
                        label.setOpacity(1.0);
                        label.setMouseTransparent(false);
                    } else {
                        label.setVisible(false);
                        label.setOpacity(0.0);
                        label.setMouseTransparent(true);
                    }
                }
            }
        }
    }

    private List<Path> listFilteredImages() throws IOException {
        return listFilteredImages(vault.tagsByFileName());
    }

    List<Path> listFilteredImagesOrEmpty(String errorTitle) {
        try {
            return listFilteredImages();
        } catch (IOException e) {
            GazoFx.showError(errorTitle, e.getMessage());
            return List.of();
        }
    }

    private void refreshTagFilterOptions() {
        if (tagFilterListView == null || vault == null) {
            return;
        }
        try {
            List<String> allTags = new ArrayList<>(vault.listAllTags());
            Collections.sort(allTags);
            activeTagFilters.retainAll(allTags);
            tagFilterSelectionMap.keySet().retainAll(allTags);
            updatingTagFilterSelection = true;
            try {
                tagFilterListView.getItems().setAll(allTags);
                for (String tag : allTags) {
                    BooleanProperty prop = tagFilterSelectionMap.computeIfAbsent(tag, k -> {
                        SimpleBooleanProperty p = new SimpleBooleanProperty(false);
                        p.addListener((obs, oldV, newV) -> {
                            if (!updatingTagFilterSelection) {
                                onTagFilterCheckboxChanged();
                            }
                        });
                        return p;
                    });
                    prop.set(activeTagFilters.contains(tag));
                }
            } finally {
                updatingTagFilterSelection = false;
            }
            updateTagFilterButtonText();
            persistGallerySettings();
        } catch (IOException e) {
            GazoFx.showError("タグ読み込みエラー", e.getMessage());
        }
    }

    private void enableImageDragAndDrop(Node dropTarget) {
        dropTarget.setOnDragOver(event -> {
            if (event.getGestureSource() != dropTarget && event.getDragboard().hasFiles() && hasImportableEntries(event.getDragboard().getFiles())) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        dropTarget.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles() && hasImportableEntries(event.getDragboard().getFiles())) {
                String hi = "-fx-background-color: linear-gradient(to bottom, #e9e5da, #ddd5c3); -fx-border-color: #b9ae97; -fx-border-width: 2; -fx-border-style: segments(8, 6);";
                gallery.setStyle(hi);
                videoGallery.setStyle(hi);
            }
            event.consume();
        });

        dropTarget.setOnDragExited(event -> {
            String normal = "-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);";
            gallery.setStyle(normal);
            videoGallery.setStyle(normal);
            event.consume();
        });

        dropTarget.setOnDragDropped(event -> {
            var db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                success = importDroppedFiles(db.getFiles());
            }
            event.setDropCompleted(success);
            String normal = "-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);";
            gallery.setStyle(normal);
            videoGallery.setStyle(normal);
            event.consume();
        });
    }

    private boolean importDroppedFiles(List<java.io.File> files) {
        boolean importedAny = false;
        try {
            for (java.io.File file : files) {
                setImportStatusLabel(file.getName());
                Path path = file.toPath();
                if (Files.isDirectory(path)) {
                    importedAny |= importImagesFromDirectory(path);
                } else if (isImageFile(path)) {
                    try {
                        vault.importImage(path);
                        importedAny = true;
                    } catch (IOException e) {
                        GazoFx.showError("保存エラー", file.getName() + " の保存に失敗しました: " + e.getMessage());
                    }
                } else if (isVideoFile(path)) {
                    try {
                        vault.importVideo(path);
                        importedAny = true;
                    } catch (IOException e) {
                        GazoFx.showError("保存エラー", file.getName() + " の保存に失敗しました: " + e.getMessage());
                    }
                }
            }
        } finally {
            clearImportStatusLabel();
        }
        if (importedAny) {
            refreshTagFilterOptions();
            refreshGallery();
            refreshVideoList();
        }
        return importedAny;
    }

    private boolean hasImportableEntries(List<java.io.File> files) {
        for (java.io.File file : files) {
            Path path = file.toPath();
            if (Files.isDirectory(path) || isImageFile(path) || isVideoFile(path)) {
                return true;
            }
        }
        return false;
    }

    private boolean importImagesFromDirectory(Path directory) {
        boolean importedAny = false;
        try (Stream<Path> stream = Files.walk(directory)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isImageFile(p) || isVideoFile(p))
                    .toList();
            try {
                for (Path file : files) {
                    String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
                    setImportStatusLabel(name);
                    try {
                        Path importedPath;
                        if (isImageFile(file)) {
                            importedPath = vault.importImage(file);
                        } else {
                            importedPath = vault.importVideo(file);
                        }
                        importedAny = true;
                        Set<String> folderTags = ImportFolderTagging.folderTagsForPathUnderRoot(directory, file);
                        if (!folderTags.isEmpty()) {
                            Set<String> tags = new LinkedHashSet<>(vault.getTags(importedPath));
                            tags.addAll(folderTags);
                            vault.setTags(importedPath, tags);
                        }
                    } catch (IOException e) {
                        GazoFx.showError("保存エラー", file.getFileName() + " の保存に失敗しました: " + e.getMessage());
                    }
                }
            } finally {
                clearImportStatusLabel();
            }
        } catch (IOException e) {
            GazoFx.showError("フォルダー読み込みエラー", directory.getFileName() + " の読み込みに失敗しました: " + e.getMessage());
        }
        return importedAny;
    }

    private boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".gif")
                || name.endsWith(".bmp")
                || name.endsWith(".webp");
    }

    private boolean isVideoFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".mp4")
                || name.endsWith(".webm")
                || name.endsWith(".m4v")
                || name.endsWith(".mov")
                || name.endsWith(".mkv");
    }

    private String snapCardBorderStyle(boolean selectedForCanvas) {
        String borderColor = selectedForCanvas ? "#7a9a5b" : "#d5cec0";
        return "-fx-background-color: #fffdf8; -fx-border-color: "
                + borderColor
                + "; -fx-border-width: 2; -fx-border-radius: 2; -fx-background-radius: 2; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.24), 13, 0.22, 3, 4);";
    }

    private static final String GALLERY_CANVAS_CHECK_KEY = "gazoCanvasCheck";

    private void refreshGallerySelectionStyles() {
        for (Node n : gallery.getChildren()) {
            if (!(n instanceof VBox card)) {
                continue;
            }
            if (!(card.getUserData() instanceof Path path)) {
                continue;
            }
            boolean sel = listCheckedSelection.contains(path);
            card.setStyle(snapCardBorderStyle(sel));
            Object chk = card.getProperties().get(GALLERY_CANVAS_CHECK_KEY);
            if (chk instanceof CheckBox box) {
                if (box.isSelected() != sel) {
                    box.setSelected(sel);
                }
            }
        }
    }

    private VBox createSnapCard(Path imagePath, Set<String> captionTags) {
        int[] imageSize = imageSizeByCode(listViewSize);
        int imageW = imageSize[0];
        int imageH = imageSize[1];

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setFitWidth(imageW);
        view.setFitHeight(imageH);
        try (InputStream in = Files.newInputStream(imagePath)) {
            Image image = new Image(in, imageW, imageH, true, true);
            view.setImage(image);
        } catch (IOException ignored) {
            // ignore broken image
        }

        StackPane photoArea = new StackPane(view);
        photoArea.setPadding(new Insets(12, 12, 6, 12));
        photoArea.setStyle("-fx-background-color: #fbfaf8;");
        photoArea.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                showOriginalImageViewer(imagePath);
                e.consume();
            }
        });

        String captionText = buildCaption(imagePath, captionTags);
        Label caption = new Label(captionText);
        caption.setWrapText(true);
        caption.setMaxWidth(224);
        caption.setAlignment(Pos.CENTER_LEFT);
        caption.setStyle("-fx-text-fill: #4f4a41; -fx-font-size: 12px; -fx-font-family: 'Segoe Script', 'Bradley Hand', 'Comic Sans MS', 'Segoe UI';");
        caption.setVisible(!captionText.isBlank());
        caption.setManaged(!captionText.isBlank());

        boolean selectedForCanvas = listCheckedSelection.contains(imagePath);
        CheckBox canvasPickCheck = new CheckBox();
        canvasPickCheck.setSelected(selectedForCanvas);
        canvasPickCheck.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-padding: 2 4 2 4;");
        StackPane.setAlignment(canvasPickCheck, Pos.TOP_LEFT);
        StackPane.setMargin(canvasPickCheck, new Insets(6, 0, 0, 6));
        photoArea.getChildren().add(canvasPickCheck);

        VBox card = new VBox(photoArea, caption);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(0, 12, 18, 12));
        card.setPrefWidth(imageW + 28);
        card.setStyle(snapCardBorderStyle(selectedForCanvas));
        card.setRotate(cardTiltFor(imagePath));
        card.setUserData(imagePath);
        card.getProperties().put(GALLERY_CANVAS_CHECK_KEY, canvasPickCheck);
        canvasPickCheck.setOnAction(e -> {
            if (canvasPickCheck.isSelected()) {
                listCheckedSelection.add(imagePath);
            } else {
                listCheckedSelection.remove(imagePath);
            }
            card.setStyle(snapCardBorderStyle(listCheckedSelection.contains(imagePath)));
        });
        return card;
    }

    private int[] imageSizeByCode(String code) {
        return switch (code) {
            case "小" -> new int[]{160, 150};
            case "大" -> new int[]{320, 300};
            default -> new int[]{240, 225};
        };
    }

    private String buildCaption(Path imagePath, Set<String> tags) {
        List<String> lines = new ArrayList<>();
        if (showFileName) {
            String name = imagePath.getFileName().toString();
            if (showDate) {
                String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
                lines.add(name + "  -  " + date);
            } else {
                lines.add(name);
            }
        } else if (showDate) {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
            lines.add(date);
        }
        if (showTags && tags != null && !tags.isEmpty()) {
            lines.add("#" + String.join(" #", tags));
        }
        return String.join("\n", lines);
    }

    private double cardTiltFor(Path imagePath) {
        int hash = Math.abs(imagePath.getFileName().toString().hashCode());
        return (hash % 9) - 4; // -4 to +4 degrees
    }

    private void editTags(Path imagePath) {
        String current = "";
        try {
            current = String.join(", ", vault.getTags(imagePath));
        } catch (IOException e) {
            GazoFx.showError("タグ読み込みエラー", e.getMessage());
            return;
        }

        TextInputDialog dialog = new TextInputDialog(current);
        dialog.setTitle("タグ編集");
        dialog.setHeaderText(imagePath.getFileName().toString());
        dialog.setContentText("タグ（カンマ区切り）:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }

        Set<String> tags = parseUserTags(result.get());
        try {
            vault.setTags(imagePath, tags);
            refreshTagFilterOptions();
            refreshGallery();
        } catch (IOException e) {
            GazoFx.showError("タグ保存エラー", e.getMessage());
        }
    }

    private void addTagsToCanvasSelection() {
        if (listCheckedSelection.isEmpty()) {
            GazoFx.showWarn("タグ一括追加", "先に画像をチェックしてください。");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("タグ一括追加");
        dialog.setHeaderText("チェック済み画像 " + listCheckedSelection.size() + " 件にタグを追加");
        dialog.setContentText("追加するタグ（カンマ区切り）:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        Set<String> addTags = parseUserTags(result.get());
        if (addTags.isEmpty()) {
            return;
        }
        try {
            for (Path p : listCheckedSelection) {
                Set<String> tags = new LinkedHashSet<>(vault.getTags(p));
                tags.addAll(addTags);
                vault.setTags(p, tags);
            }
            refreshTagFilterOptions();
            refreshGallery();
            GazoFx.showWarn("タグ一括追加", addTags.size() + " 個のタグを追加しました。");
        } catch (IOException e) {
            GazoFx.showError("タグ一括追加エラー", e.getMessage());
        }
    }

    private void removeTagsFromCanvasSelection() {
        if (listCheckedSelection.isEmpty()) {
            GazoFx.showWarn("タグ一括削除", "先に画像をチェックしてください。");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("タグ一括削除");
        dialog.setHeaderText("チェック済み画像 " + listCheckedSelection.size() + " 件からタグを削除");
        dialog.setContentText("削除するタグ（カンマ区切り）:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        Set<String> removeTags = parseUserTags(result.get());
        if (removeTags.isEmpty()) {
            return;
        }
        try {
            for (Path p : listCheckedSelection) {
                Set<String> tags = new LinkedHashSet<>(vault.getTags(p));
                tags.removeAll(removeTags);
                vault.setTags(p, tags);
            }
            refreshTagFilterOptions();
            refreshGallery();
            GazoFx.showWarn("タグ一括削除", removeTags.size() + " 個のタグを削除しました。");
        } catch (IOException e) {
            GazoFx.showError("タグ一括削除エラー", e.getMessage());
        }
    }

    private Set<String> parseUserTags(String text) {
        Set<String> tags = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tags;
        }
        for (String raw : text.split(",")) {
            String normalized = raw.trim().toLowerCase();
            if (!normalized.isEmpty()) {
                tags.add(normalized);
            }
        }
        return tags;
    }

    private void selectAllVisibleImages() {
        try {
            listCheckedSelection.clear();
            listCheckedSelection.addAll(listFilteredImages());
            refreshGallerySelectionStyles();
        } catch (IOException e) {
            GazoFx.showError("全選択エラー", e.getMessage());
        }
    }

    private void clearListCheckedSelection() {
        listCheckedSelection.clear();
        refreshGallerySelectionStyles();
    }

    private void showDuplicateReport() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("重複チェック結果");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextField thresholdField = new TextField("8");
        thresholdField.setPrefWidth(60);
        Button rerunButton = new Button("再チェック");

        TextArea reportArea = new TextArea();
        reportArea.setEditable(false);
        reportArea.setWrapText(false);
        reportArea.setPrefRowCount(16);

        ListView<Path> candidateList = new ListView<>();
        candidateList.setPrefHeight(180);
        candidateList.setCellFactory(ignored -> new javafx.scene.control.ListCell<>() {
            private final ImageView thumb = new ImageView();
            private final Label text = new Label();
            private final HBox box = new HBox(8, thumb, text);
            {
                thumb.setFitWidth(44);
                thumb.setFitHeight(44);
                thumb.setPreserveRatio(true);
                text.setMaxWidth(340);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                text.setText(item.getFileName().toString() + "  " + formatImagePixelSize(item));
                thumb.setImage(loadThumbnail(item, 44, 44));
                setGraphic(box);
            }
        });

        ImageView leftPreview = new ImageView();
        leftPreview.setFitWidth(220);
        leftPreview.setFitHeight(220);
        leftPreview.setPreserveRatio(true);
        Label leftLabel = new Label("選択画像");
        Label othersHeaderLabel = new Label("重複・類似候補");
        HBox othersRow = new HBox(10);
        othersRow.setAlignment(Pos.CENTER_LEFT);
        ScrollPane othersScroll = new ScrollPane(othersRow);
        othersScroll.setFitToHeight(true);
        othersScroll.setMinHeight(200);
        othersScroll.setPrefHeight(240);
        othersScroll.setMaxHeight(300);
        othersScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        othersScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox leftBox = new VBox(6, leftLabel, leftPreview);
        VBox rightBox = new VBox(6, othersHeaderLabel, othersScroll);
        rightBox.setPrefWidth(520);
        HBox previewBox = new HBox(16, leftBox, rightBox);
        previewBox.setAlignment(Pos.TOP_LEFT);
        Button enlargeCompareButton = new Button("一覧を拡大表示");
        HBox previewControls = new HBox(8, enlargeCompareButton);
        previewControls.setAlignment(Pos.CENTER_LEFT);

        Label exactDupSectionTitle = new Label("同一画像（SHA-256一致）の整理");
        exactDupSectionTitle.setStyle("-fx-font-weight: bold;");
        Label exactDupHint = new Label("グループ内で残す画像を選び、他を削除します。削除した画像のタグは残す画像に追加されます。");
        exactDupHint.setWrapText(true);
        exactDupHint.setMaxWidth(720);
        VBox exactRadioList = new VBox(4);
        ScrollPane exactRadioScroll = new ScrollPane(exactRadioList);
        exactRadioScroll.setFitToWidth(true);
        exactRadioScroll.setMaxHeight(140);
        exactRadioScroll.setMinHeight(Region.USE_PREF_SIZE);
        Button mergeDeleteExactButton = new Button("選択した画像以外を削除（タグを統合）");
        mergeDeleteExactButton.setDisable(true);
        VBox exactDupBox = new VBox(6, exactDupSectionTitle, exactDupHint, exactRadioScroll, mergeDeleteExactButton);
        exactDupBox.setVisible(false);
        exactDupBox.setManaged(false);
        AtomicReference<ToggleGroup> exactKeepToggleGroupRef = new AtomicReference<>(new ToggleGroup());

        Button deleteButton = new Button("選択削除");
        Button tagButton = new Button("選択にタグ付与");
        AtomicReference<List<GazoVaultService.SimilarPair>> similarRef = new AtomicReference<>(List.of());
        AtomicReference<List<List<Path>>> exactGroupsRef = new AtomicReference<>(List.of());
        AtomicReference<Path> selectedRef = new AtomicReference<>(null);

        HBox controls = new HBox(8, new Label("類似判定距離:"), thresholdField, rerunButton, deleteButton, tagButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(
                8,
                controls,
                new Label("候補画像:"),
                candidateList,
                previewBox,
                previewControls,
                exactDupBox,
                reportArea);
        content.setPadding(new Insets(10));

        StackPane busyPane = new StackPane();
        Region busyBg = new Region();
        busyBg.setStyle("-fx-background-color: rgba(255,255,255,0.82);");
        ProgressIndicator busySpinner = new ProgressIndicator();
        busySpinner.setPrefSize(44, 44);
        Label busyLabel = new Label("重複を検索しています…");
        busyLabel.setStyle("-fx-font-size: 13px;");
        VBox busyCenter = new VBox(10, busySpinner, busyLabel);
        busyCenter.setAlignment(Pos.CENTER);
        busyPane.getChildren().addAll(busyBg, busyCenter);
        busyPane.setVisible(false);
        busyPane.setManaged(false);

        StackPane rootStack = new StackPane(content, busyPane);
        dialog.getDialogPane().setContent(rootStack);

        Runnable setDuplicateBusy = () -> {
            boolean busy = busyPane.isVisible();
            rerunButton.setDisable(busy);
            thresholdField.setDisable(busy);
            deleteButton.setDisable(busy);
            tagButton.setDisable(busy);
            candidateList.setDisable(busy);
            enlargeCompareButton.setDisable(busy);
            if (busy) {
                mergeDeleteExactButton.setDisable(true);
            }
            javafx.scene.Node closeBtn = dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
            if (closeBtn != null) {
                closeBtn.setDisable(busy);
            }
        };

        AtomicReference<Path> pendingSelectAfterDupScanRef = new AtomicReference<>(null);

        Runnable refreshExactGroupKeepUi = () -> {
            exactRadioList.getChildren().clear();
            ToggleGroup tg = new ToggleGroup();
            exactKeepToggleGroupRef.set(tg);
            Path sel = selectedRef.get();
            List<List<Path>> eg = exactGroupsRef.get();
            if (sel == null || eg == null) {
                exactDupBox.setVisible(false);
                exactDupBox.setManaged(false);
                mergeDeleteExactButton.setDisable(true);
                return;
            }
            Optional<List<Path>> gOpt = findExactGroupContaining(sel, eg);
            if (gOpt.isEmpty() || gOpt.get().size() < 2) {
                exactDupBox.setVisible(false);
                exactDupBox.setManaged(false);
                mergeDeleteExactButton.setDisable(true);
                return;
            }
            List<Path> group = sortPathsByImageAreaDesc(gOpt.get());
            exactDupBox.setVisible(true);
            exactDupBox.setManaged(true);
            mergeDeleteExactButton.setDisable(false);
            boolean matched = false;
            for (Path p : group) {
                RadioButton rb = new RadioButton(p.getFileName().toString() + "  " + formatImagePixelSize(p));
                rb.setUserData(p);
                rb.setToggleGroup(tg);
                exactRadioList.getChildren().add(rb);
            }
            for (Node n : exactRadioList.getChildren()) {
                if (n instanceof RadioButton rb && sel.equals(rb.getUserData())) {
                    rb.setSelected(true);
                    matched = true;
                    break;
                }
            }
            if (!matched && !exactRadioList.getChildren().isEmpty()) {
                ((RadioButton) exactRadioList.getChildren().get(0)).setSelected(true);
            }
        };

        Runnable refreshAsync = () -> {
            int threshold = parseThreshold(thresholdField.getText(), 8);
            busyPane.setVisible(true);
            busyPane.setManaged(true);
            setDuplicateBusy.run();

            record DupScan(List<List<Path>> exact, List<GazoVaultService.SimilarPair> similar) {}

            Task<DupScan> task = new Task<>() {
                @Override
                protected DupScan call() throws IOException {
                    List<List<Path>> exact = vault.findExactDuplicateGroups();
                    List<GazoVaultService.SimilarPair> similar = vault.findSimilarPairs(threshold);
                    return new DupScan(exact, similar);
                }
            };
            task.setOnSucceeded(ev -> {
                busyPane.setVisible(false);
                busyPane.setManaged(false);
                setDuplicateBusy.run();
                DupScan result = task.getValue();
                if (result == null) {
                    return;
                }
                similarRef.set(result.similar());
                exactGroupsRef.set(result.exact());
                reportArea.setText(buildDuplicateReport(result.exact(), result.similar(), threshold));
                candidateList.getItems().setAll(collectDuplicateCandidates(result.exact(), result.similar()));
                Path pending = pendingSelectAfterDupScanRef.getAndSet(null);
                Path selected = candidateList.getSelectionModel().getSelectedItem();
                if (pending != null && candidateList.getItems().contains(pending)) {
                    candidateList.getSelectionModel().select(pending);
                    selected = pending;
                }
                selectedRef.set(selected);
                updateDuplicatePreview(selected, result.exact(), result.similar(), leftPreview, leftLabel, othersRow, othersHeaderLabel);
                refreshExactGroupKeepUi.run();
            });
            task.setOnFailed(ev -> {
                busyPane.setVisible(false);
                busyPane.setManaged(false);
                setDuplicateBusy.run();
                Throwable ex = task.getException();
                String msg = ex != null && ex.getMessage() != null ? ex.getMessage() : "不明なエラー";
                GazoFx.showError("重複チェックエラー", msg);
            });
            Thread t = new Thread(task, "gazo-duplicate-scan");
            t.setDaemon(true);
            t.start();
        };

        mergeDeleteExactButton.setOnAction(e -> {
            ToggleGroup tg = exactKeepToggleGroupRef.get();
            javafx.scene.control.Toggle t = tg.getSelectedToggle();
            if (!(t instanceof RadioButton rb)) {
                return;
            }
            Path keep = (Path) rb.getUserData();
            List<List<Path>> eg = exactGroupsRef.get();
            Optional<List<Path>> gOpt = findExactGroupContaining(keep, eg);
            if (gOpt.isEmpty() || gOpt.get().size() < 2) {
                return;
            }
            List<Path> group = gOpt.get();
            List<Path> toDelete = group.stream().filter(p -> !p.equals(keep)).toList();
            StringBuilder sb = new StringBuilder();
            sb.append("残す: ").append(keep.getFileName());
            sb.append("\n削除 ").append(toDelete.size()).append(" 件: ");
            for (int i = 0; i < toDelete.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(toDelete.get(i).getFileName());
            }
            sb.append("\n削除した画像のタグは、残す画像に追加されます。");
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, sb.toString(), ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle("重複削除の確認");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            try {
                LinkedHashSet<String> merged = new LinkedHashSet<>(vault.getTags(keep));
                for (Path p : toDelete) {
                    merged.addAll(vault.getTags(p));
                }
                vault.setTags(keep, merged);
                for (Path p : toDelete) {
                    vault.deleteImage(p);
                }
                refreshTagFilterOptions();
                refreshGallery();
                refreshVideoList();
                pendingSelectAfterDupScanRef.set(keep);
                refreshAsync.run();
            } catch (IOException ex) {
                GazoFx.showError("重複削除エラー", ex.getMessage());
            }
        });

        rerunButton.setOnAction(e -> refreshAsync.run());
        deleteButton.setOnAction(e -> deleteDuplicateSelection(candidateList.getSelectionModel().getSelectedItem(), refreshAsync));
        tagButton.setOnAction(e -> addTagToDuplicateSelection(candidateList.getSelectionModel().getSelectedItem(), refreshAsync));
        candidateList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            selectedRef.set(newV);
            updateDuplicatePreview(newV, exactGroupsRef.get(), similarRef.get(), leftPreview, leftLabel, othersRow, othersHeaderLabel);
            refreshExactGroupKeepUi.run();
        });
        enlargeCompareButton.setOnAction(e -> showDuplicateExpandDialog(
                selectedRef.get(),
                duplicateOthersOrdered(selectedRef.get(), exactGroupsRef.get(), similarRef.get())));

        refreshAsync.run();
        dialog.showAndWait();
    }

    private int parseThreshold(String text, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value < 0) {
                return fallback;
            }
            return value;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String buildDuplicateReport(List<List<Path>> exact, List<GazoVaultService.SimilarPair> similar, int threshold) {
        StringBuilder report = new StringBuilder();
        report.append("【同一画像（SHA-256一致）】\n");
        if (exact.isEmpty()) {
            report.append("なし\n");
        } else {
            int group = 1;
            for (List<Path> paths : exact) {
                report.append("グループ ").append(group++).append(":\n");
                for (Path p : sortPathsByImageAreaDesc(paths)) {
                    report.append("  - ")
                            .append(p.getFileName())
                            .append("  ")
                            .append(formatImagePixelSize(p))
                            .append('\n');
                }
            }
        }
        report.append("\n【似た画像候補（dHash距離 <= ").append(threshold).append("）】\n");
        if (similar.isEmpty()) {
            report.append("なし\n");
        } else {
            List<GazoVaultService.SimilarPair> pairsBySize = new ArrayList<>(similar);
            pairsBySize.sort(Comparator.comparingLong(
                            (GazoVaultService.SimilarPair pair) -> Math.max(imagePixelArea(pair.left()), imagePixelArea(pair.right())))
                    .reversed()
                    .thenComparingInt(GazoVaultService.SimilarPair::distance)
                    .thenComparing(p -> p.left().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            for (GazoVaultService.SimilarPair pair : pairsBySize) {
                report.append("  - ")
                        .append(pair.left().getFileName())
                        .append(" ")
                        .append(formatImagePixelSize(pair.left()))
                        .append(" <-> ")
                        .append(pair.right().getFileName())
                        .append(" ")
                        .append(formatImagePixelSize(pair.right()))
                        .append("  (distance=")
                        .append(pair.distance())
                        .append(")\n");
            }
        }
        return report.toString();
    }

    /** ピクセル幅・高さ。読み取れないときは null。 */
    private int[] readImagePixelDimensions(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            ImageInputStream iis = ImageIO.createImageInputStream(in);
            if (iis != null) {
                try {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                    if (readers.hasNext()) {
                        ImageReader reader = readers.next();
                        try {
                            reader.setInput(iis);
                            int w = reader.getWidth(0);
                            int h = reader.getHeight(0);
                            return new int[]{w, h};
                        } finally {
                            reader.dispose();
                        }
                    }
                } finally {
                    iis.close();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        try (InputStream in = Files.newInputStream(path)) {
            Image img = new Image(in);
            if (img.isError() || img.getWidth() <= 0 || img.getHeight() <= 0) {
                return null;
            }
            return new int[]{(int) Math.round(img.getWidth()), (int) Math.round(img.getHeight())};
        } catch (IOException e) {
            return null;
        }
    }

    /** 画像のピクセル幅×高さ。読み取れない場合は "—"。 */
    private String formatImagePixelSize(Path path) {
        int[] d = readImagePixelDimensions(path);
        if (d == null) {
            return "—";
        }
        return d[0] + "×" + d[1];
    }

    private long imagePixelArea(Path path) {
        int[] d = readImagePixelDimensions(path);
        if (d == null) {
            return 0L;
        }
        return (long) d[0] * (long) d[1];
    }

    private List<Path> sortPathsByImageAreaDesc(List<Path> paths) {
        List<Path> copy = new ArrayList<>(paths);
        copy.sort(Comparator.comparingLong(this::imagePixelArea)
                .reversed()
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return copy;
    }

    private Optional<List<Path>> findExactGroupContaining(Path path, List<List<Path>> exactGroups) {
        if (path == null || exactGroups == null) {
            return Optional.empty();
        }
        for (List<Path> g : exactGroups) {
            if (g.contains(path)) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    private List<Path> collectDuplicateCandidates(List<List<Path>> exact, List<GazoVaultService.SimilarPair> similar) {
        // 同一SHAグループと類似ペアを1つの無向グラフとして扱い、
        // 連結成分（重複クラスタ）ごとに代表1件だけ候補リストへ出す。
        Map<Path, Set<Path>> graph = new HashMap<>();
        for (List<Path> group : exact) {
            List<Path> paths = group == null ? List.of() : group;
            if (paths.isEmpty()) {
                continue;
            }
            for (Path p : paths) {
                graph.computeIfAbsent(p, k -> new LinkedHashSet<>());
            }
            // 完全グラフは作らず、先頭ノードへのスター接続で連結性だけ担保する。
            Path anchor = paths.get(0);
            for (int i = 1; i < paths.size(); i++) {
                Path p = paths.get(i);
                graph.get(anchor).add(p);
                graph.get(p).add(anchor);
            }
        }
        for (GazoVaultService.SimilarPair pair : similar) {
            if (pair == null || pair.left() == null || pair.right() == null) {
                continue;
            }
            graph.computeIfAbsent(pair.left(), k -> new LinkedHashSet<>()).add(pair.right());
            graph.computeIfAbsent(pair.right(), k -> new LinkedHashSet<>()).add(pair.left());
        }

        Set<Path> visited = new LinkedHashSet<>();
        List<Path> representatives = new ArrayList<>();
        for (Path start : graph.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            List<Path> component = new ArrayList<>();
            Deque<Path> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                Path cur = stack.pop();
                component.add(cur);
                for (Path next : graph.getOrDefault(cur, Set.of())) {
                    if (visited.add(next)) {
                        stack.push(next);
                    }
                }
            }
            representatives.add(sortPathsByImageAreaDesc(component).get(0));
        }
        return sortPathsByImageAreaDesc(representatives);
    }

    private Image loadThumbnail(Path path, int width, int height) {
        Path source = path;
        if (vault != null) {
            Path thumb = vault.thumbnailFor(path);
            if (thumb != null) {
                source = thumb;
            }
        }
        try (InputStream in = Files.newInputStream(source)) {
            return new Image(in, width, height, true, true);
        } catch (IOException e) {
            return null;
        }
    }

    private Image loadOriginalImage(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return new Image(in);
        } catch (IOException e) {
            return null;
        }
    }

    private void showOriginalImageViewer(Path startPath) {
        showOriginalImageViewer(startPath, listFilteredImagesOrEmpty("画像ビューア"));
    }

    private void showOriginalImageViewer(Path startPath, List<Path> sourceImages) {
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

        Runnable applyViewMode = () -> {
            Image img = view.getImage();
            if (img == null) {
                return;
            }
            if (fitCheck.isSelected()) {
                // AS_NEEDED だとスクロールバー有無でビューポート幅が変わり、viewportBounds → 再レイアウトで振動する。
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

        Runnable render = () -> {
            int i = index.get();
            Path path = images.get(i);
            Image img = loadOriginalImage(path);
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
                Set<String> tags = vault.getTags(path);
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
            } catch (IOException ex) {
                tagsLine.setText("タグを読み込めませんでした");
                tagsLine.setVisible(true);
                tagsLine.setManaged(true);
            }
        };
        tagEditButton.setOnAction(e -> {
            Path path = images.get(index.get());
            editTags(path);
            render.run();
        });
        copyFileNameButton.setOnAction(e -> {
            Path path = images.get(index.get());
            ClipboardContent content = new ClipboardContent();
            content.putString(path.getFileName().toString());
            Clipboard.getSystemClipboard().setContent(content);
            GazoFx.showWarn("コピー", "ファイル名をクリップボードにコピーしました。");
        });
        fitCheck.setOnAction(e -> applyViewMode.run());
        scroll.viewportBoundsProperty().addListener((obs, oldB, newB) -> {
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

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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

    /** 重複チェック: 同一SHAグループは距離0、類似のみは dHash 距離の最小値。 */
    private record DuplicateOther(Path path, int distance) {}

    private List<DuplicateOther> duplicateOthersOrdered(
            Path selected,
            List<List<Path>> exactGroups,
            List<GazoVaultService.SimilarPair> similarPairs) {
        if (selected == null) {
            return List.of();
        }
        for (List<Path> g : exactGroups) {
            if (g.contains(selected)) {
                List<Path> others = g.stream().filter(p -> !p.equals(selected)).toList();
                return sortPathsByImageAreaDesc(others).stream().map(p -> new DuplicateOther(p, 0)).toList();
            }
        }
        Map<Path, Integer> minDist = new HashMap<>();
        for (GazoVaultService.SimilarPair pair : similarPairs) {
            if (pair.left().equals(selected)) {
                minDist.merge(pair.right(), pair.distance(), Math::min);
            } else if (pair.right().equals(selected)) {
                minDist.merge(pair.left(), pair.distance(), Math::min);
            }
        }
        return minDist.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Path, Integer> e) -> imagePixelArea(e.getKey()))
                        .reversed()
                        .thenComparingInt(Map.Entry::getValue)
                        .thenComparing(e -> e.getKey().getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .map(e -> new DuplicateOther(e.getKey(), e.getValue()))
                .toList();
    }

    private void updateDuplicatePreview(
            Path selected,
            List<List<Path>> exactGroups,
            List<GazoVaultService.SimilarPair> allSimilarPairs,
            ImageView leftPreview,
            Label leftLabel,
            HBox othersRow,
            Label othersHeaderLabel) {
        othersRow.getChildren().clear();
        if (selected == null) {
            leftPreview.setImage(null);
            leftLabel.setText("選択画像");
            othersHeaderLabel.setText("重複・類似候補");
            return;
        }
        leftPreview.setImage(loadThumbnail(selected, 220, 220));
        leftLabel.setText("選択: " + selected.getFileName() + "  " + formatImagePixelSize(selected));

        List<DuplicateOther> others = duplicateOthersOrdered(selected, exactGroups, allSimilarPairs);
        othersHeaderLabel.setText("他の候補 (" + others.size() + " 件)");
        if (others.isEmpty()) {
            Label empty = new Label("なし");
            empty.setStyle("-fx-text-fill: #666;");
            othersRow.getChildren().add(empty);
            return;
        }
        for (DuplicateOther o : others) {
            othersRow.getChildren().add(buildDuplicatePreviewTile(o));
        }
    }

    private VBox buildDuplicatePreviewTile(DuplicateOther o) {
        ImageView iv = new ImageView(loadThumbnail(o.path(), 120, 120));
        iv.setPreserveRatio(true);
        iv.setFitWidth(120);
        iv.setFitHeight(120);
        String line3 = o.distance() == 0 ? "同一" : "d=" + o.distance();
        Label cap = new Label(o.path().getFileName().toString() + "\n" + formatImagePixelSize(o.path()) + "\n" + line3);
        cap.setWrapText(true);
        cap.setMaxWidth(136);
        cap.setStyle("-fx-font-size: 11px;");
        VBox tile = new VBox(4, iv, cap);
        tile.setStyle("-fx-padding: 6; -fx-background-color: #f8f6f0; -fx-background-radius: 4;");
        return tile;
    }

    private void showDuplicateExpandDialog(Path selected, List<DuplicateOther> others) {
        if (selected == null) {
            GazoFx.showWarn("比較表示", "候補画像を選択してください。");
            return;
        }
        if (others == null || others.isEmpty()) {
            GazoFx.showWarn("比較表示", "表示できる他の候補がありません。");
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("重複・類似一覧");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(buildDuplicateExpandTile(selected, null, true));
        for (DuplicateOther o : others) {
            row.getChildren().add(buildDuplicateExpandTile(o.path(), o.distance(), false));
        }
        ScrollPane scroll = new ScrollPane(row);
        scroll.setFitToHeight(true);
        scroll.setPrefViewportHeight(520);
        scroll.setPrefViewportWidth(920);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox root = new VBox(scroll);
        root.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(root);
        dialog.showAndWait();
    }

    private VBox buildDuplicateExpandTile(Path path, Integer distanceOrNull, boolean isSelected) {
        ImageView iv = new ImageView(loadThumbnail(path, 320, 320));
        iv.setPreserveRatio(true);
        iv.setFitWidth(320);
        iv.setFitHeight(320);
        String line3 = isSelected ? "選択" : (distanceOrNull != null && distanceOrNull == 0 ? "同一" : "d=" + distanceOrNull);
        Label cap = new Label(path.getFileName().toString() + "\n" + formatImagePixelSize(path) + "\n" + line3);
        cap.setWrapText(true);
        cap.setMaxWidth(340);
        cap.setStyle("-fx-font-size: 12px;");
        return new VBox(8, cap, iv);
    }

    private void deleteDuplicateSelection(Path selected, Runnable refresh) {
        if (selected == null) {
            GazoFx.showWarn("削除", "候補画像を選択してください。");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, selected.getFileName() + " を削除しますか？", ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("削除確認");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            vault.deleteImage(selected);
            refreshTagFilterOptions();
            refreshGallery();
            refresh.run();
        } catch (IOException e) {
            GazoFx.showError("削除エラー", e.getMessage());
        }
    }

    private void addTagToDuplicateSelection(Path selected, Runnable refresh) {
        if (selected == null) {
            GazoFx.showWarn("タグ付与", "候補画像を選択してください。");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("タグ付与");
        dialog.setHeaderText(selected.getFileName().toString());
        dialog.setContentText("追加タグ（カンマ区切り）:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        Set<String> addTags = parseUserTags(result.get());
        if (addTags.isEmpty()) {
            return;
        }
        try {
            Set<String> tags = new LinkedHashSet<>(vault.getTags(selected));
            tags.addAll(addTags);
            vault.setTags(selected, tags);
            refreshTagFilterOptions();
            refreshGallery();
            refresh.run();
        } catch (IOException e) {
            GazoFx.showError("タグ付与エラー", e.getMessage());
        }
    }

    /**
     * キャンバス機能の統合 UI は {@link CanvasHubDialog} を参照。
     */
    private void showCanvasHubDialog(Stage owner) {
        CanvasHubDialog.open(this, owner);
    }

    private void showCanvasHubDialogRandom(Stage owner) {
        CanvasHubDialog.open(this, owner, true);
    }

    int parsePickCount(String text, int max) {
        try {
            int n = Integer.parseInt(text.trim());
            return Math.max(1, Math.min(max, n));
        } catch (Exception e) {
            return Math.min(6, max);
        }
    }

    List<Path> makeRandomPick(List<Path> pool, int count) {
        List<Path> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, new Random());
        return new ArrayList<>(copy.subList(0, Math.min(count, copy.size())));
    }

    void renderRandomPickCanvas(Pane canvas, List<Path> paths) {
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

    void installCanvasResizeHandle(Pane canvas, Runnable onResizeFinished) {
        Region handle = new Region();
        handle.setPrefSize(16, 16);
        handle.setStyle("-fx-background-color: rgba(80,80,80,0.65); -fx-background-radius: 2;");
        handle.setCursor(Cursor.SE_RESIZE);
        handle.setManaged(false);

        Runnable relocateHandle = () -> {
            double x = Math.max(0, canvas.getPrefWidth() - 20);
            double y = Math.max(0, canvas.getPrefHeight() - 20);
            handle.relocate(x, y);
        };
        relocateHandle.run();

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
            double nextW = Math.max(900, drag[2] + dx);
            double nextH = Math.max(700, drag[3] + dy);
            canvas.setPrefSize(nextW, nextH);
            relocateHandle.run();
            event.consume();
        });
        handle.setOnMouseReleased(event -> {
            onResizeFinished.run();
            event.consume();
        });

        canvas.getChildren().add(handle);
        handle.toFront();
    }

    /**
     * 現在のキャンバスの配置・変形・キャンバスサイズを新規キャンバスへ複製する。
     */
    void copyCanvasLayoutState(String sourceLayout, String targetLayout) throws IOException {
        String source = (sourceLayout == null || sourceLayout.isBlank()) ? "default" : sourceLayout;
        String target = (targetLayout == null || targetLayout.isBlank()) ? "default" : targetLayout;
        if (source.equals(target)) {
            return;
        }
        var size = vault.getCanvasSize(source);
        if (size != null) {
            vault.setCanvasSize(target, size.width(), size.height());
        }
        List<Path> sourceSelection = vault.listCanvasSelectionOrder(source);
        vault.saveCanvasSelectionOrder(target, sourceSelection);
        for (Path p : sourceSelection) {
            var pos = vault.getCanvasPosition(source, p);
            if (pos != null) {
                vault.setCanvasPosition(target, p, pos.x(), pos.y());
            }
            var tf = vault.getCanvasTransform(source, p);
            if (tf != null) {
                vault.setCanvasTransform(target, p, tf.scale(), tf.rotation());
            }
        }
    }

    /**
     * ホームのラベル「「名前」」からキャンバス名を取り出す。未選択・空状態は null。
     */
    private static String parseHomeCanvasLayoutName(Label layoutNameLabel) {
        if (layoutNameLabel == null) {
            return null;
        }
        String t = layoutNameLabel.getText().trim();
        if (t.isEmpty() || "—".equals(t) || "-".equals(t)) {
            return null;
        }
        if (t.startsWith("「") && t.endsWith("」") && t.length() >= 4) {
            return t.substring(1, t.length() - 1);
        }
        return null;
    }

    /**
     * メインウィンドウ「キャンバス」タブ用: Vault 内のキャンバスを 1 つランダムに選びプレビュー表示する。
     *
     * @param pickDifferentFromCurrent 登録が2件以上のとき、表示中のキャンバス名と別のものを選ぶ（「別のキャンバス」用）
     */
    private void refreshRandomCanvasPreview(Pane canvasPane, Label layoutNameLabel, boolean pickDifferentFromCurrent) {
        if (vault == null) {
            return;
        }
        try {
            Set<String> names = vault.listCanvasLayouts();
            if (names.isEmpty()) {
                canvasPane.getChildren().clear();
                layoutNameLabel.setText("（キャンバスがありません）");
                if (homeCanvasPreviewScale != null) {
                    homeCanvasPreviewScale.setX(1);
                    homeCanvasPreviewScale.setY(1);
                }
                if (homeCanvasPreviewHolder != null) {
                    homeCanvasPreviewHolder.setLayoutX(0);
                    homeCanvasPreviewHolder.setLayoutY(0);
                }
                Platform.runLater(this::fitHomeCanvasPreview);
                return;
            }
            List<String> order = new ArrayList<>(names);
            if (pickDifferentFromCurrent && names.size() >= 2) {
                String current = parseHomeCanvasLayoutName(layoutNameLabel);
                if (current != null && order.contains(current)) {
                    order.remove(current);
                }
            }
            Collections.shuffle(order, new Random());
            String layoutName = order.get(0);
            List<Path> backup = new ArrayList<>(canvasSelection);
            try {
                canvasSelection.clear();
                canvasSelection.addAll(vault.listCanvasSelectionOrder(layoutName));
                var size = vault.getCanvasSize(layoutName);
                if (size != null) {
                    double cw = Math.max(480, size.width());
                    double ch = Math.max(340, size.height());
                    canvasPane.setPrefSize(cw, ch);
                } else {
                    canvasPane.setPrefSize(2000, 1400);
                }
                renderCanvasItems(canvasPane, layoutName, false, null, null, null);
                layoutNameLabel.setText("「" + layoutName + "」");
            } finally {
                canvasSelection.clear();
                canvasSelection.addAll(backup);
            }
            Platform.runLater(this::fitHomeCanvasPreview);
        } catch (IOException e) {
            GazoFx.showError("キャンバス表示", e.getMessage());
        }
    }

    void renderCanvasItems(Pane canvas, String layoutName, boolean interactive, Path selectedPath, java.util.function.Consumer<Path> onSelect, java.util.function.Consumer<Path> onRemove) {
        canvas.getChildren().removeIf(node -> node instanceof VBox);
        double canvasW = Math.max(1, canvas.getPrefWidth());
        double canvasH = Math.max(1, canvas.getPrefHeight());
        double sizeRatio = Math.min(canvasW / BASE_CANVAS_WIDTH, canvasH / BASE_CANVAS_HEIGHT);
        sizeRatio = Math.max(0.35, Math.min(3.0, sizeRatio));
        // メイン「キャンバス」タブのプレビューなど、描画後に canvasSelection が元へ戻るため、
        // オリジナル表示の ←/→ 用にこの時点の一覧を固定する。
        List<Path> pathsSnapshot = new ArrayList<>(canvasSelection);
        int index = 0;
        for (Path path : pathsSnapshot) {
            VBox item = createCanvasItem(path, layoutName, interactive, sizeRatio, null, pathsSnapshot);
            if (item == null) {
                continue;
            }
            double x = 30 + (index % 4) * 360;
            double y = 30 + (index / 4) * 380;
            try {
                var pos = vault.getCanvasPosition(layoutName, path);
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
                if (onRemove != null) {
                    ContextMenu menu = new ContextMenu();
                    MenuItem removeItem = new MenuItem("キャンバスから除去");
                    removeItem.setOnAction(e -> onRemove.accept(path));
                    menu.getItems().add(removeItem);
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

    void autoLayoutCanvas(Pane canvas, String layoutName, String presetName, double overlapTuning, double neatTuning) {
        if (canvasSelection.isEmpty()) {
            return;
        }
        double frameW = canvas.getPrefWidth();
        double frameH = canvas.getPrefHeight();
        double fillScale = computeLayoutFillScale(canvasSelection.size(), frameW, frameH);
        Random random = new Random();
        boolean neat = "整列風".equals(presetName);
        List<double[]> placed = new ArrayList<>(); // x, y, w, h
        for (Path path : canvasSelection) {
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
                vault.setCanvasPosition(layoutName, path, x / Math.max(1, frameW), y / Math.max(1, frameH));
                vault.setDisplaySize(path, sizeCode);
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
        return createCanvasItem(path, layoutName, interactive, canvasSizeRatio, null, null);
    }

    private VBox createCanvasItem(Path path, String layoutName, boolean interactive, double canvasSizeRatio, String sizeCodeOverride) {
        return createCanvasItem(path, layoutName, interactive, canvasSizeRatio, sizeCodeOverride, null);
    }

    /**
     * @param sizeCodeOverride 非 null のとき Vault の表示サイズより優先（ランダムピックのプレビュー用）
     * @param viewerNavSource 非 null のときオリジナル表示の ←/→ の対象（ランダムピック時は当該ピックの一覧）。null のときはキャンバス選択一覧を使う。
     */
    private VBox createCanvasItem(Path path, String layoutName, boolean interactive, double canvasSizeRatio, String sizeCodeOverride, List<Path> viewerNavSource) {
        String size = "M";
        try {
            size = sizeCodeOverride != null ? sizeCodeOverride : vault.getDisplaySize(path);
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
        Image image = loadThumbnail(path, wh[0], wh[1]);
        if (image == null) {
            return null;
        }
        ImageView view = new ImageView(image);
        // 画像自体は loadThumbnail 時点で縮小済み。ここで正方形の fit 枠を作らないことで、
        // 縦長画像の右側に大きな余白が出るのを防ぐ。
        view.setPreserveRatio(true);
        view.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                List<Path> nav = viewerNavSource != null ? viewerNavSource : new ArrayList<>(canvasSelection);
                showOriginalImageViewer(path, new ArrayList<>(nav));
                e.consume();
            }
        });
        Label label = new Label(path.getFileName().toString());
        // ファイル名が長くてもカード幅（白背景）は画像幅を超えないようにする。
        double labelW = Math.max(1.0, image.getWidth());
        label.setMaxWidth(labelW);
        label.setPrefWidth(labelW);
        label.setWrapText(false);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        if (!showFileName) {
            // 配置計算のサイズを安定させるため、ラベル領域は残したまま不可視化する。
            label.setVisible(false);
            label.setOpacity(0.0);
            label.setMouseTransparent(true);
        }
        VBox box = new VBox(6, view, label);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: white; -fx-border-color: #cfc8ba; -fx-border-radius: 2; -fx-background-radius: 2;");
        double baseRotation = -14 + (Math.abs(path.getFileName().toString().hashCode()) % 29);
        double scale = 1.0;
        double rotation = baseRotation;
        try {
            var tf = vault.getCanvasTransform(layoutName, path);
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
        if (interactive) {
            makeCanvasTransformable(box, path, layoutName);
        }
        return box;
    }

    private void makeDraggable(VBox node, Path imagePath, String layoutName, Pane canvas) {
        final double[] dragOffset = new double[2];
        node.setOnMousePressed(event -> {
            dragOffset[0] = event.getSceneX() - node.getLayoutX();
            dragOffset[1] = event.getSceneY() - node.getLayoutY();
            node.toFront();
        });
        node.setOnMouseDragged(event -> {
            double nextX = event.getSceneX() - dragOffset[0];
            double nextY = event.getSceneY() - dragOffset[1];
            node.relocate(nextX, nextY);
        });
        node.setOnMouseReleased(event -> {
            try {
                double cw = Math.max(1.0, canvas.getPrefWidth());
                double ch = Math.max(1.0, canvas.getPrefHeight());
                vault.setCanvasPosition(layoutName, imagePath, node.getLayoutX() / cw, node.getLayoutY() / ch);
            } catch (IOException e) {
                GazoFx.showWarn("キャンバス保存エラー", e.getMessage());
            }
        });
    }

    private void makeCanvasTransformable(VBox node, Path imagePath, String layoutName) {
        node.setOnScroll(event -> {
            Double scaleObj = (Double) node.getProperties().getOrDefault("gazoScale", 1.0);
            Double ratioObj = (Double) node.getProperties().getOrDefault("gazoCanvasScaleRatio", 1.0);
            Double rotationObj = (Double) node.getProperties().getOrDefault("gazoRotation", node.getRotate());
            double scale = scaleObj;
            double sizeRatio = ratioObj;
            double rotation = rotationObj;
            double direction = scrollDirection(event);

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
            try {
                vault.setCanvasTransform(layoutName, imagePath, (Double) node.getProperties().get("gazoScale"), (Double) node.getProperties().get("gazoRotation"));
            } catch (IOException e) {
                GazoFx.showWarn("キャンバス変形保存エラー", e.getMessage());
            }
            event.consume();
        });
    }

    private double scrollDirection(javafx.scene.input.ScrollEvent event) {
        double dy = event.getDeltaY();
        double dx = event.getDeltaX();
        if (Math.abs(dx) > Math.abs(dy)) {
            return Math.signum(dx);
        }
        if (Math.abs(dy) > 0.0001) {
            return Math.signum(dy);
        }
        // Fallback for devices reporting text deltas.
        double tdy = event.getTextDeltaYUnits() == javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE
                ? 0.0
                : event.getTextDeltaY();
        double tdx = event.getTextDeltaXUnits() == javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE
                ? 0.0
                : event.getTextDeltaX();
        if (Math.abs(tdx) > Math.abs(tdy)) {
            return Math.signum(tdx);
        }
        if (Math.abs(tdy) > 0.0001) {
            return Math.signum(tdy);
        }
        return 1.0;
    }

    @Override
    public void stop() {
        if (vault != null) {
            vault.close();
        }
    }
}
