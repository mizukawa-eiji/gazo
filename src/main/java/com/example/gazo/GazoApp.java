package com.example.gazo;

import com.example.gazo.cli.CliImport;
import com.example.gazo.vault.GazoVaultService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
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
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
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
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.ZoomEvent;
import javafx.scene.text.Text;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.cryptomator.cryptolib.api.InvalidPassphraseException;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 登録画像をスナップ写真風に表示し、Cryptomator 互換 Vault に保存する JavaFX アプリ。
 */
public final class GazoApp extends Application {
    private static final double BASE_CANVAS_WIDTH = 2000.0;
    private static final double BASE_CANVAS_HEIGHT = 1400.0;
    /** 可視付近ならサムネを読み込む（FlowPane 座標系での拡張 px）。 */
    private static final double GALLERY_THUMB_LOAD_MARGIN = 520;
    /**
     * これより外側に出たカードだけ Image を外す。読み込みマージンと大きく離すとスクロール中の点滅が減る（メモリはやや増える）。
     */
    private static final double GALLERY_THUMB_UNLOAD_MARGIN = 1800;
    GazoVaultService vault;
    private Stage primaryStage;
    /** 類似チェックダイアログを二重に開かないため。 */
    private Stage openDuplicateReportStage;
    /** メインウィンドウに重ねる処理中オーバーレイ（サムネイル再作成など） */
    private StackPane appBusyPane;
    private Label appBusyMessageLabel;
    private FlowPane gallery;
    /** 仮想スクロール用。上・下の余白で全体スクロール長を確保する。 */
    private Region galleryVirtualTopSpacer;
    private Region galleryVirtualBottomSpacer;
    private VBox galleryVirtualRoot;
    /** 現在の一覧モデル（フィルター後の全パス）。仮想ウィンドウはこれの一部だけを FlowPane に載せる。 */
    private List<Path> galleryModelPaths = List.of();
    private Map<String, Set<String>> galleryTagMap = Map.of();
    /** ファイル名 → その画像が載っているキャンバス名（保存済み）。ギャラリー・類似チェックの表示用。 */
    private Map<String, Set<String>> galleryCanvasLayoutsByFileName = Map.of();
    /** 仮想ウィンドウ再構築中は高さリスナーなどの再入を防ぐ。 */
    private boolean galleryVirtualRebuilding;
    private int galleryVirtualLastFirstIndex = Integer.MIN_VALUE;
    private int galleryVirtualLastCols = -1;
    /**
     * 列数がスクロールのたびに変わるとグリッドの折り返しが変わり、表示される画像の並びが入れ替わって見える。
     * 隣接する列数の間では幅のヒステリシスで安定させる。
     */
    private int galleryVirtualStickyCols = -1;
    /** 直近レイアウトで測った列数・行ピッチ（推定 cellW と FlowPane 実レイアウトの差を吸収する） */
    private int galleryVirtualMeasuredCols = -1;
    private double galleryVirtualMeasuredRowPitch = -1;
    /** {@link #galleryVirtualMeasuredCols} を測定したときの rawInnerW */
    private double galleryVirtualMeasuredInnerW = -1;
    /**
     * 直近レイアウトで測った先頭タイルの FlowPane 内 minY。padTop と一致しないと「1 行消えた直後」で境界が 1 ずれる。
     */
    private double galleryVirtualFirstTileMinY = -1;
    private FlowPane videoGallery;
    /** 親ウィンドウ内パスワード入力のオーバーレイ／中央パネル（除去用）。 */
    private Node vaultPasswordMountNode;

    private ScrollPane imageScrollPane;
    /** 一覧サムネイルの読み込みを UI スレッド外で処理する。 */
    private final ExecutorService galleryImageLoadExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gazo-gallery-image-loader");
        t.setDaemon(true);
        return t;
    });
    /**
     * 重複整理ダイアログのマージ後グラフ再計算など、CPU 負荷の高い処理をキューに積み、
     * 同時実行を 1 に抑える（複数ジョブが重なるとディスク・CPU が膨らむのを防ぐ）。
     */
    private final ExecutorService vaultHeavySerialExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gazo-vault-heavy-serial");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger galleryImageLoadVersion = new AtomicInteger();
    /**
     * 仮想ウィンドウでカードを捨ててもすぐ戻せるよう、サムネ {@link Image} を LRU 保持する（スクロール時の一瞬の空白を抑える）。
     */
    private static final int GALLERY_THUMB_CACHE_MAX = 320;
    private final Map<String, Image> galleryThumbCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > GALLERY_THUMB_CACHE_MAX;
        }
    };
    /**
     * スクロール・ビューポート・FlowPane 高さの変化が連続するとき、サムネの可視判定をまとめて実行する。
     */
    /** ホイールで短周期に刻まれるため長めにすると、仮想ウィンドウの作り直しとサムネの付け外しが減る。 */
    private final PauseTransition galleryVisibleDebounce = new PauseTransition(Duration.millis(150));
    /** スクロール中はサムネを外さず、止まってからまとめて解放する（ちらつき低減）。 */
    private final PauseTransition galleryEvictDebounce = new PauseTransition(Duration.millis(420));
    private Label vaultPathLabel;
    /** インポート中のみファイル名を表示（通常は空） */
    private Label importStatusLabel;
    /** 画像タブツールバー: フィルター後の表示数と Vault 内の画像総数 */
    private Label imageGalleryCountLabel;
    /** 絞り込みに使うタグ（正規化済み・小文字）。空なら「すべて表示」。複数指定時は AND（すべて含む）。 */
    private final LinkedHashSet<String> activeTagFilters = new LinkedHashSet<>();
    private MenuButton tagFilterMenuButton;
    private ListView<String> tagFilterListView;
    /** タグ絞り込みリストの各行に表示する件数（画像＋動画）。キーは {@link #TAG_FILTER_UNTAGGED} またはタグ名。 */
    private final Map<String, Integer> tagFilterCounts = new HashMap<>();
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
    /** タグ絞り込みで「タグなし」を表す。実タグ名としては使わない。キャンバス追加ダイアログからも参照する。 */
    static final String TAG_FILTER_UNTAGGED = "__gazo_untagged__";
    final Set<Path> canvasSelection = new LinkedHashSet<>();
    final Set<Path> listCheckedSelection = new LinkedHashSet<>();
    /** 実ファイル削除を遅延させる予約セット。重複整理ダイアログ終了時・アプリ終了時にまとめて削除する。 */
    private final Set<Path> pendingDeletePaths = Collections.synchronizedSet(new LinkedHashSet<>());
    private String listViewSize = "中";

    /** メイン「キャンバス」タブのプレビュー縮小表示用（ビューポートに合わせる） */
    private ScrollPane homeCanvasPreviewScroll;
    private Pane homeCanvasPreviewPane;
    private Label homeCanvasLayoutLabel;
    private Scale homeCanvasPreviewScale;
    private Group homeCanvasPreviewHolder;

    public static void main(String[] args) {
        CliImport.tryRun(args);
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        prepareStageShellBeforeVaultUnlock(stage);
        Path defaultVaultDir = VaultPathStore.loadInitialVaultPath();
        openVaultAsync(stage, defaultVaultDir, err -> {
            if (err instanceof VaultUnlockCancelledException) {
                Platform.exit();
                return;
            }
            if (err != null) {
                GazoFx.showError("Vault を開けませんでした", err.getMessage());
                Platform.exit();
                return;
            }
            continueApplicationAfterVaultOpened(stage);
        });
    }

    private void continueApplicationAfterVaultOpened(Stage stage) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flushPendingDeletes(false);
            if (vault != null) {
                vault.close();
            }
            galleryImageLoadExecutor.shutdownNow();
            vaultHeavySerialExecutor.shutdownNow();
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
        gallery.setSnapToPixel(true);
        gallery.setPadding(new Insets(22));
        gallery.setStyle("-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);");
        galleryVirtualTopSpacer = new Region();
        galleryVirtualBottomSpacer = new Region();
        galleryVirtualRoot = new VBox(galleryVirtualTopSpacer, gallery, galleryVirtualBottomSpacer);
        VBox.setVgrow(gallery, Priority.NEVER);

        ScrollPane imageScroll = new ScrollPane(galleryVirtualRoot);
        imageScroll.setFitToWidth(true);
        imageScrollPane = imageScroll;
        galleryVisibleDebounce.setOnFinished(ev -> {
            applyGalleryVirtualWindow(false);
            refreshVisibleGalleryImagesNow(false);
        });
        galleryEvictDebounce.setOnFinished(ev -> refreshVisibleGalleryImagesNow(true));
        imageScroll.vvalueProperty().addListener((obs, ov, nv) -> onGalleryVerticalScrollChanged(ov, nv));
        imageScroll.hvalueProperty().addListener((obs, ov, nv) -> onGalleryHorizontalScrollChanged(ov, nv));
        imageScroll.viewportBoundsProperty().addListener((obs, ov, nv) -> onGalleryViewportBoundsChanged(ov, nv));

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
        MenuItem duplicateMenu = new MenuItem("重複/類似チェック...");
        duplicateMenu.setOnAction(e -> showDuplicateReport());
        MenuItem bulkAddMenu = new MenuItem("タグ一括追加...");
        bulkAddMenu.setOnAction(e -> addTagsToCanvasSelection());
        MenuItem bulkRemoveMenu = new MenuItem("タグ一括削除...");
        bulkRemoveMenu.setOnAction(e -> removeTagsFromCanvasSelection());
        MenuItem deleteCheckedImagesMenu = new MenuItem("チェックした画像を削除…");
        deleteCheckedImagesMenu.setOnAction(e -> deleteCheckedImagesFromVault(stage));
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
                deleteCheckedImagesMenu,
                new SeparatorMenuItem());
        MenuBar menuBar = new MenuBar(fileMenu, actionsMenu);
        Button selectAll = new Button("全選択");
        selectAll.setOnAction(e -> selectAllVisibleImages());
        Button clearSelection = new Button("クリア");
        clearSelection.setOnAction(e -> clearListCheckedSelection());
        Button deleteCheckedImagesButton = new Button("選択を削除");
        deleteCheckedImagesButton.setTooltip(new Tooltip("チェックした画像を Vault から削除（元に戻せません）"));
        deleteCheckedImagesButton.setOnAction(e -> deleteCheckedImagesFromVault(stage));
        Button canvasHub = new Button("選択画像でキャンバス作成");
        canvasHub.setOnAction(e -> showCanvasHubDialog(stage));
        Button randomCanvasHub = new Button("ランダムにキャンバスを作成");
        randomCanvasHub.setOnAction(e -> showCanvasHubDialogRandom(stage));
        tagFilterListView = new ListView<>();
        tagFilterListView.setPrefWidth(260);
        tagFilterListView.setPrefHeight(220);
        tagFilterListView.setCellFactory(CheckBoxListCell.forListView(
                this::tagFilterBooleanProperty,
                new StringConverter<>() {
                    @Override
                    public String toString(String object) {
                        if (object == null) {
                            return "";
                        }
                        int n = tagFilterCounts.getOrDefault(object, 0);
                        if (TAG_FILTER_UNTAGGED.equals(object)) {
                            return "（タグなし） (" + n + ")";
                        }
                        return object + " (" + n + ")";
                    }

                    @Override
                    public String fromString(String string) {
                        return null;
                    }
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
                deleteCheckedImagesButton,
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
        homeCanvasLayoutLabel = new Label("—");
        homeCanvasLayoutLabel.setStyle("-fx-font-weight: bold;");
        Button homeCanvasShuffleButton = new Button("別のキャンバス");
        homeCanvasShuffleButton.setOnAction(e -> refreshRandomCanvasPreview(homeCanvasPane, homeCanvasLayoutLabel, true));
        Button homeCanvasSlideshowButton = new Button("スライドショーで開く");
        homeCanvasSlideshowButton.setOnAction(e ->
                showSlideshow(stage, parseHomeCanvasLayoutName(homeCanvasLayoutLabel)));
        Button homeCanvasEditButton = new Button("キャンバス編集で開く");
        homeCanvasEditButton.setTooltip(
                new Tooltip("キャンバスウィンドウを「キャンバス編集」タブで開きます（プレビュー中のキャンバスを初期選択）。"));
        homeCanvasEditButton.setOnAction(e -> {
            CanvasHubDialog.open(this, stage, false, parseHomeCanvasLayoutName(homeCanvasLayoutLabel));
            refreshHomeCanvasPreviewAfterHubClose();
        });
        HBox homeCanvasBar =
                new HBox(
                        12,
                        new Label("キャンバス:"),
                        homeCanvasLayoutLabel,
                        homeCanvasShuffleButton,
                        homeCanvasSlideshowButton,
                        homeCanvasEditButton);
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
        // Linux/GTK: ProgressIndicator の常時アニメーションが XSetErrorHandler 警告の原因になりやすいため使わない。
        Label appBusyHeadline = new Label("処理中");
        appBusyHeadline.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        appBusyMessageLabel = new Label("処理中…");
        appBusyMessageLabel.setStyle("-fx-font-size: 13px;");
        VBox appBusyCenter = new VBox(8, appBusyHeadline, appBusyMessageLabel);
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

    /**
     * Vault 解錠の前にメイン {@link Stage} を表示する（パスワードは {@link #mountVaultPasswordForm} でこのウィンドウ内に出す）。
     * 本体 UI の {@link Scene} は {@link #continueApplicationAfterVaultOpened} で差し替える。
     */
    private void prepareStageShellBeforeVaultUnlock(Stage stage) {
        if (stage.getScene() != null) {
            return;
        }
        BorderPane shell = new BorderPane();
        shell.setStyle("-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);");
        stage.setTitle("Gazo — 暗号化フォルダに保存する写真ビューア (JavaFX)");
        GazoFx.applyAppIcons(stage);
        VBox topStrip = new VBox(16);
        topStrip.setAlignment(Pos.TOP_CENTER);
        topStrip.setPadding(new Insets(36, 24, 16, 24));
        Node iconGraphic = GazoFx.createAppIconView(128);
        if (iconGraphic != null) {
            topStrip.getChildren().add(iconGraphic);
        }
        Label shellHint = new Label("Vault を準備しています…");
        shellHint.setStyle("-fx-text-fill: #5c564a; -fx-font-size: 14px;");
        topStrip.getChildren().add(shellHint);
        shell.setTop(topStrip);

        stage.setScene(new Scene(shell, 560, 400));
        stage.setResizable(true);
        stage.centerOnScreen();
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private void openVaultAsync(Stage stage, Path vaultDir, Consumer<Exception> done) {
        try {
            GazoVaultService newVault = new GazoVaultService(vaultDir);
            unlockOrCreateInline(stage, newVault, () -> {
                try {
                    if (vault != null) {
                        vault.close();
                    }
                    vault = newVault;
                    VaultPathStore.saveLastVaultPath(vault.getVaultPath());
                    reloadCanvasSelectionFromVault("default");
                    done.accept(null);
                } catch (Exception e) {
                    done.accept(e);
                }
            }, () -> done.accept(new VaultUnlockCancelledException()));
        } catch (Exception e) {
            done.accept(e);
        }
    }

    private void clearVaultPasswordMount(Stage stage) {
        if (vaultPasswordMountNode == null || stage.getScene() == null) {
            return;
        }
        Parent root = stage.getScene().getRoot();
        if (root instanceof BorderPane bp) {
            bp.setCenter(null);
        } else if (root instanceof StackPane sp) {
            sp.getChildren().remove(vaultPasswordMountNode);
        }
        vaultPasswordMountNode = null;
    }

    private void mountVaultPasswordForm(Stage stage, Node form) {
        clearVaultPasswordMount(stage);
        Parent root = stage.getScene().getRoot();
        if (root instanceof BorderPane bp) {
            ScrollPane scroll = new ScrollPane(form);
            scroll.setFitToWidth(true);
            scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
            bp.setCenter(scroll);
            vaultPasswordMountNode = scroll;
        } else if (root instanceof StackPane sp) {
            StackPane overlay = new StackPane(form);
            overlay.setAlignment(Pos.CENTER);
            overlay.setStyle("-fx-background-color: rgba(252,250,245,0.97);");
            overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            sp.getChildren().add(overlay);
            vaultPasswordMountNode = overlay;
        }
    }

    /** 明るい背景でも読める濃い文字色（テーマ既定だと白文字になる環境がある）。 */
    private static final String VAULT_FORM_LABEL_TEXT = "-fx-text-fill: #33312d;";
    private static final String VAULT_FORM_FIELD_STYLE = "-fx-text-fill: #33312d; -fx-prompt-text-fill: #6a6355;";

    /** 親ウィンドウ内にロック解除用パスワード欄を出す（別ダイアログは使わない）。 */
    private void showUnlockPasswordInline(Stage stage, String inlineError, Consumer<char[]> onSubmit, Runnable onCancel) {
        Label title = new Label("Vault のロックを解除");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; " + VAULT_FORM_LABEL_TEXT);
        Label msg = new Label("パスワードを入力してください。");
        msg.setStyle(VAULT_FORM_LABEL_TEXT);
        Label errLabel = new Label(inlineError == null ? "" : inlineError);
        errLabel.setStyle("-fx-text-fill: #b83232;");
        errLabel.setWrapText(true);
        boolean showErr = inlineError != null && !inlineError.isBlank();
        errLabel.setVisible(showErr);
        errLabel.setManaged(showErr);
        PasswordField passField = new PasswordField();
        passField.setPromptText("パスワード");
        passField.setStyle(VAULT_FORM_FIELD_STYLE);
        Button ok = new Button("OK");
        Button cancel = new Button("キャンセル");
        ok.setDefaultButton(true);
        HBox row = new HBox(8, ok, cancel);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, title, msg, errLabel, passField, row);
        box.setPadding(new Insets(20));
        box.setMaxWidth(440);
        box.setStyle("-fx-background-color: #f5f2ea; -fx-background-radius: 8;");
        ok.setOnAction(e -> onSubmit.accept(passField.getText().toCharArray()));
        cancel.setOnAction(e -> {
            clearVaultPasswordMount(stage);
            onCancel.run();
        });
        passField.setOnAction(e -> ok.fire());
        mountVaultPasswordForm(stage, box);
        Platform.runLater(passField::requestFocus);
    }

    private void showCreateVaultPasswordInline(Stage stage, Consumer<char[]> onSuccess, Runnable onCancel) {
        Label title = new Label("新しい Vault を作成");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; " + VAULT_FORM_LABEL_TEXT);
        Label msg = new Label("パスワードを設定してください。");
        msg.setStyle(VAULT_FORM_LABEL_TEXT);
        Label errLabel = new Label();
        errLabel.setStyle("-fx-text-fill: #b83232;");
        errLabel.setWrapText(true);
        errLabel.setVisible(false);
        errLabel.setManaged(false);
        Label lab1 = new Label("パスワード");
        lab1.setStyle(VAULT_FORM_LABEL_TEXT);
        Label lab2 = new Label("確認");
        lab2.setStyle(VAULT_FORM_LABEL_TEXT);
        PasswordField p1 = new PasswordField();
        p1.setPromptText("パスワード");
        p1.setStyle(VAULT_FORM_FIELD_STYLE);
        PasswordField p2 = new PasswordField();
        p2.setPromptText("確認");
        p2.setStyle(VAULT_FORM_FIELD_STYLE);
        Button ok = new Button("OK");
        Button cancel = new Button("キャンセル");
        ok.setDefaultButton(true);
        HBox row = new HBox(8, ok, cancel);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, title, msg, errLabel, lab1, p1, lab2, p2, row);
        box.setPadding(new Insets(20));
        box.setMaxWidth(440);
        box.setStyle("-fx-background-color: #f5f2ea; -fx-background-radius: 8;");
        ok.setOnAction(e -> {
            char[] a = p1.getText().toCharArray();
            char[] b = p2.getText().toCharArray();
            if (a.length == 0) {
                errLabel.setText("パスワードを入力してください。");
                errLabel.setVisible(true);
                errLabel.setManaged(true);
                Arrays.fill(b, '\0');
                return;
            }
            if (!Arrays.equals(a, b)) {
                errLabel.setText("確認用パスワードが一致しません。");
                errLabel.setVisible(true);
                errLabel.setManaged(true);
                Arrays.fill(a, '\0');
                Arrays.fill(b, '\0');
                return;
            }
            Arrays.fill(b, '\0');
            clearVaultPasswordMount(stage);
            onSuccess.accept(a);
        });
        cancel.setOnAction(e -> {
            clearVaultPasswordMount(stage);
            onCancel.run();
        });
        p2.setOnAction(e -> ok.fire());
        mountVaultPasswordForm(stage, box);
        Platform.runLater(p1::requestFocus);
    }

    private void unlockOrCreateInline(Stage stage, GazoVaultService targetVault, Runnable onUnlocked, Runnable onCancelled) {
        if (!targetVault.vaultExists()) {
            showCreateVaultPasswordInline(stage, pass -> {
                try {
                    targetVault.createVault(new String(pass));
                    Arrays.fill(pass, '\0');
                    onUnlocked.run();
                } catch (IOException | MasterkeyLoadingFailedException e) {
                    Arrays.fill(pass, '\0');
                    GazoFx.showError("Vault を作成できませんでした", e.getMessage());
                    onCancelled.run();
                }
            }, onCancelled);
            return;
        }
        runUnlockLoop(stage, targetVault, null, onUnlocked, onCancelled);
    }

    private void runUnlockLoop(Stage stage, GazoVaultService targetVault, String inlineError, Runnable onUnlocked,
            Runnable onCancelled) {
        showUnlockPasswordInline(stage, inlineError, pass -> {
            try {
                targetVault.unlock(new String(pass));
                Arrays.fill(pass, '\0');
                clearVaultPasswordMount(stage);
                onUnlocked.run();
            } catch (InvalidPassphraseException e) {
                Arrays.fill(pass, '\0');
                clearVaultPasswordMount(stage);
                runUnlockLoop(stage, targetVault, "パスワードが正しくありません。", onUnlocked, onCancelled);
            } catch (IOException | MasterkeyLoadingFailedException e) {
                Arrays.fill(pass, '\0');
                clearVaultPasswordMount(stage);
                GazoFx.showError("Vault を開けませんでした", e.getMessage());
                onCancelled.run();
            }
        }, onCancelled);
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
            renderCanvasItems(canvas, name, false, null, null, null, null, null);
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
            if (owner != null && owner.isShowing()) {
                slideStage.setX(Math.round(owner.getX() + (owner.getWidth() - slideStage.getWidth()) / 2));
                slideStage.setY(Math.round(owner.getY() + (owner.getHeight() - slideStage.getHeight()) / 2));
            }
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
        openVaultAsync(stage, selected.toPath(), err -> {
            if (err instanceof VaultUnlockCancelledException) {
                return;
            }
            if (err != null) {
                GazoFx.showError("Vault 変更エラー", err.getMessage());
                return;
            }
            updateVaultPathLabel();
            refreshTagFilterOptions();
            refreshGallery();
            refreshVideoList();
        });
    }

    /** パスワード入力をキャンセルしたときに {@link #openVaultAsync} の完了コールバックへ渡す。 */
    private static final class VaultUnlockCancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
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
        galleryVisibleDebounce.stop();
        galleryEvictDebounce.stop();
        galleryImageLoadVersion.incrementAndGet();
        galleryThumbCache.clear();
        galleryVirtualLastFirstIndex = Integer.MIN_VALUE;
        galleryVirtualLastCols = -1;
        galleryVirtualStickyCols = -1;
        galleryVirtualMeasuredCols = -1;
        galleryVirtualMeasuredRowPitch = -1;
        galleryVirtualMeasuredInnerW = -1;
        galleryVirtualFirstTileMinY = -1;
        try {
            galleryTagMap = vault.tagsByFileName();
            galleryCanvasLayoutsByFileName = new HashMap<>(vault.mapCanvasLayoutsByFileName());
            List<Path> allImages = vault.listImages();
            List<Path> paths = listFilteredImages(galleryTagMap, allImages);
            galleryModelPaths = new ArrayList<>(paths);
            updateImageGalleryCountLabel(paths.size(), allImages.size());
            if (imageScrollPane != null) {
                imageScrollPane.setVvalue(0);
                imageScrollPane.setHvalue(0);
            }
            applyGalleryVirtualWindow(true);
            refreshVisibleGalleryImagesNow();
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
     * 1 行あたりの高さ（createSnapCard の固定高さ・FlowPane の行ピッチと一致させる）。
     * キャプション行数でカード高さが変わると仮想スペーサーと実レイアウトがずれ、スクロールのたびに画像がずれて見える。
     */
    private double galleryEstimatedRowHeight() {
        int[] sz = imageSizeByCode(listViewSize);
        double photoBlockH = 12 + 12 + 6 + sz[1];
        double captionMaxH = 80;
        double vboxBottomPad = 18;
        double borderFudge = 16;
        return photoBlockH + captionMaxH + vboxBottomPad + borderFudge;
    }

    /**
     * 一覧カードの外側幅（一覧サイズごとに一定）。仮想スクロールの列・cellPitch と FlowPane の折り返しを一致させる。
     * 内側: 画像列 + photoArea 水平パディングに合わせた幅。外側: VBox 左右パディング 12+12 を加算。
     */
    private double galleryCardOuterWidth() {
        int[] sz = imageSizeByCode(listViewSize);
        return sz[0] + 28 + 24;
    }

    /**
     * {@link #galleryCardOuterWidth()} と同じ（仮想ウィンドウの列数計算用）。
     */
    private double galleryEstimatedCellWidth() {
        return galleryCardOuterWidth();
    }

    /**
     * FlowPane に載せるカードをスクロール位置に応じた一部だけに絞る（全件ノードを持たない）。
     */
    private void applyGalleryVirtualWindow(boolean force) {
        if (gallery == null || imageScrollPane == null) {
            return;
        }
        if (galleryVirtualRebuilding) {
            return;
        }
        List<Path> paths = galleryModelPaths;
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
            galleryVirtualLastFirstIndex = Integer.MIN_VALUE;
            galleryVirtualStickyCols = -1;
            galleryVirtualMeasuredCols = -1;
            galleryVirtualMeasuredRowPitch = -1;
            galleryVirtualMeasuredInnerW = -1;
            galleryVirtualFirstTileMinY = -1;
            return;
        }
        Bounds vp = imageScrollPane.getViewportBounds();
        double vw = vp != null && vp.getWidth() > 0 ? vp.getWidth() : 800;
        double vh = vp != null && vp.getHeight() > 0 ? vp.getHeight() : 600;
        Insets gin = gallery.getInsets();
        // ギャラリー実測幅はレイアウトで揺れる。列数はビューポート幅ベースに統一（スクロールバーで列が変わるのを防ぐ）。
        double rawInnerW = Math.max(0, vw - gin.getLeft() - gin.getRight());
        gallery.setPrefWrapLength(Math.max(200, rawInnerW));

        if (galleryVirtualMeasuredInnerW > 0 && Math.abs(rawInnerW - galleryVirtualMeasuredInnerW) > 8) {
            galleryVirtualMeasuredCols = -1;
            galleryVirtualMeasuredRowPitch = -1;
            galleryVirtualMeasuredInnerW = -1;
            galleryVirtualFirstTileMinY = -1;
        }

        // createSnapCard の card 高さと一致させる（max(96,…) だと実レイアウトより行ピッチが広くなり境界で 1 行ずれる）。
        double rowH = galleryEstimatedRowHeight();
        double cellW = galleryEstimatedCellWidth();
        double hgap = gallery.getHgap();
        double vgap = gallery.getVgap();
        double rowPitchEst = rowH + vgap;
        double cellPitch = cellW + hgap;
        int idealCols = Math.max(1, (int) Math.floor((rawInnerW + hgap) / cellPitch));
        int cols;
        boolean useMeasuredCols = galleryVirtualMeasuredCols > 0
                && galleryVirtualMeasuredInnerW > 0
                && Math.abs(rawInnerW - galleryVirtualMeasuredInnerW) < 8;
        if (useMeasuredCols) {
            cols = galleryVirtualMeasuredCols;
        } else if (galleryVirtualStickyCols > 0) {
            int s = galleryVirtualStickyCols;
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
        galleryVirtualStickyCols = cols;
        int totalRows = (int) Math.ceil(n / (double) cols);
        // 行ピッチは推定に固定（実測 y1-y0 はレイアウトで数 px 変わり、境界の firstRow が 1 行ずれる）。
        // 列数・折り返しの補正には galleryVirtualMeasuredCols / RowPitch を measure 側で別途使う。
        double rowPitch = rowPitchEst;

        double padTop = gin.getTop();
        double padBottom = gin.getBottom();
        double padV = padTop + padBottom;
        double totalContentH = padV + totalRows * rowPitch - vgap;

        double tileY0 = galleryVirtualFirstTileMinY >= 0 ? galleryVirtualFirstTileMinY : padTop;

        double hmin = imageScrollPane.getHmin();
        double hmax = imageScrollPane.getHmax();
        double hspan = hmax - hmin;
        double hNorm = hspan > 1e-9 ? (imageScrollPane.getHvalue() - hmin) / hspan : 0;
        double vmin = imageScrollPane.getVmin();
        double vmax = imageScrollPane.getVmax();
        double vspan = vmax - vmin;
        double vNorm = vspan > 1e-9 ? (imageScrollPane.getVvalue() - vmin) / vspan : 0;

        // vNorm は ScrollPane が使う「実コンテンツ高さ − vh」に対する割合（refreshVisibleGalleryImagesNow と同じ）。
        // 理論 totalContentH だけで vNorm×scrollRange を取ると、実測 totalH と数 px ずれて firstRow がずれる。
        galleryVirtualRoot.setMinHeight(totalContentH);
        galleryVirtualRoot.setPrefHeight(totalContentH);
        galleryVirtualRoot.setMaxHeight(totalContentH);

        double topSpacerH = galleryVirtualTopSpacer.getHeight();
        double galleryHMeas = gallery.getBoundsInLocal().getHeight();
        double bottomSpacerH = galleryVirtualBottomSpacer.getHeight();
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
        // バッファは先読み行数のみ。maxFirstRow に含めるとスクロール範囲が狭まり、行インデックスと vvalue がずれる。
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
        if (!force && firstIndex == galleryVirtualLastFirstIndex && cols == galleryVirtualLastCols) {
            return;
        }
        galleryVirtualLastFirstIndex = firstIndex;
        galleryVirtualLastCols = cols;

        double topH = firstRow * rowPitch;
        int rowsUsed = (int) Math.ceil(count / (double) cols);
        double bottomRows = Math.max(0, totalRows - firstRow - rowsUsed);
        double bottomH = bottomRows * rowPitch;

        galleryVirtualRebuilding = true;
        try {
            galleryVirtualTopSpacer.setMinHeight(topH);
            galleryVirtualTopSpacer.setPrefHeight(topH);
            galleryVirtualTopSpacer.setMaxHeight(topH);
            galleryVirtualBottomSpacer.setMinHeight(bottomH);
            galleryVirtualBottomSpacer.setPrefHeight(bottomH);
            galleryVirtualBottomSpacer.setMaxHeight(bottomH);

            double galleryBlockH = padV + rowsUsed * rowPitch - vgap;
            gallery.setMinHeight(galleryBlockH);
            gallery.setPrefHeight(galleryBlockH);

            gallery.getChildren().clear();
            for (int i = 0; i < count; i++) {
                Path p = paths.get(firstIndex + i);
                Set<String> tags = galleryTagMap.getOrDefault(p.getFileName().toString(), Set.of());
                gallery.getChildren().add(createSnapCard(p, tags));
            }
        } finally {
            galleryVirtualRebuilding = false;
        }
        Platform.runLater(this::measureGalleryVirtualLayoutAfterLayout);
    }

    /**
     * FlowPane レイアウト後の子ノードの Y 座標から列数と行ピッチを測り、次回の仮想ウィンドウ計算に使う。
     */
    private void measureGalleryVirtualLayoutAfterLayout() {
        if (gallery == null || imageScrollPane == null || galleryVirtualRebuilding) {
            return;
        }
        List<Node> ch = gallery.getChildren();
        if (ch.isEmpty()) {
            return;
        }
        double y0 = ch.get(0).getBoundsInParent().getMinY();
        int nFirst = 1;
        for (int i = 1; i < ch.size(); i++) {
            if (Math.abs(ch.get(i).getBoundsInParent().getMinY() - y0) > 4.0) {
                break;
            }
            nFirst++;
        }
        int mc;
        if (nFirst < ch.size()) {
            mc = nFirst;
        } else {
            if (galleryVirtualMeasuredCols <= 0) {
                Bounds vp0 = imageScrollPane.getViewportBounds();
                if (vp0 != null && vp0.getWidth() > 0) {
                    Insets gin0 = gallery.getInsets();
                    galleryVirtualMeasuredInnerW = Math.max(0, vp0.getWidth() - gin0.getLeft() - gin0.getRight());
                }
                return;
            }
            mc = galleryVirtualMeasuredCols;
        }
        galleryVirtualMeasuredCols = Math.max(1, mc);
        galleryVirtualFirstTileMinY = y0;
        if (ch.size() > mc) {
            double y1 = ch.get(mc).getBoundsInParent().getMinY();
            galleryVirtualMeasuredRowPitch = Math.max(1.0, y1 - y0);
        } else {
            galleryVirtualMeasuredRowPitch = -1;
        }
        Bounds vp = imageScrollPane.getViewportBounds();
        if (vp == null || vp.getWidth() <= 0) {
            return;
        }
        Insets gin = gallery.getInsets();
        galleryVirtualMeasuredInnerW = Math.max(0, vp.getWidth() - gin.getLeft() - gin.getRight());
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
            if (isPendingDelete(p)) {
                continue;
            }
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
            if (isPendingDelete(p)) {
                continue;
            }
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

    /**
     * タグ未選択ならすべて表示。
     * 1つ以上なら AND。{@link #TAG_FILTER_UNTAGGED} が含まれる場合は「タグが1つも付いていない」ことを要求する。
     */
    private boolean matchesTagFilter(Set<String> fileTags) {
        return matchesTagFilterSet(activeTagFilters, fileTags);
    }

    /**
     * メイン画面のタグ欄と同じ AND／「タグなし」ルール。キャンバス追加ダイアログなどメインとは独立した条件セット用。
     */
    static boolean matchesTagFilterSet(Set<String> activeTagFilters, Set<String> fileTags) {
        if (activeTagFilters == null || activeTagFilters.isEmpty()) {
            return true;
        }
        boolean wantUntagged = activeTagFilters.contains(TAG_FILTER_UNTAGGED);
        Set<String> requiredTags = new LinkedHashSet<>(activeTagFilters);
        requiredTags.remove(TAG_FILTER_UNTAGGED);
        if (wantUntagged) {
            if (!fileTags.isEmpty()) {
                return false;
            }
            return requiredTags.isEmpty();
        }
        return fileTags.containsAll(requiredTags);
    }

    /**
     * キャンバス「画像を追加」ダイアログ用。メインのタグ／検索欄とは独立して一覧を絞り込む。
     *
     * @param tagFilters 空ならタグでは絞らない。要素は実タグ名または {@link #TAG_FILTER_UNTAGGED}。
     */
    List<Path> listImagesForCanvasAddPicker(Set<String> tagFilters, String filenameQuery) throws IOException {
        Map<String, Set<String>> tagsByFile = vault.tagsByFileName();
        List<Path> allImages = vault.listImages();
        String q = filenameQuery == null ? "" : filenameQuery.trim().toLowerCase();
        List<Path> out = new ArrayList<>();
        for (Path p : allImages) {
            if (isPendingDelete(p)) {
                continue;
            }
            Set<String> tags = tagsByFile.getOrDefault(p.getFileName().toString(), Set.of());
            if (!matchesTagFilterSet(tagFilters, tags)) {
                continue;
            }
            if (!q.isEmpty() && !p.getFileName().toString().toLowerCase().contains(q)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** キャンバス追加ダイアログの初期値用（メインの検索欄）。 */
    String galleryImageNameQuery() {
        return imageNameQuery;
    }

    /** キャンバス追加ダイアログの初期値用（メインのタグチェック）。 */
    LinkedHashSet<String> galleryActiveTagFiltersCopy() {
        return new LinkedHashSet<>(activeTagFilters);
    }

    private BooleanProperty tagFilterBooleanProperty(String tag) {
        return tagFilterSelectionMap.computeIfAbsent(tag, k -> {
            SimpleBooleanProperty p = new SimpleBooleanProperty(false);
            p.addListener((obs, oldV, newV) -> {
                if (!updatingTagFilterSelection) {
                    onTagFilterCheckboxChanged();
                }
            });
            return p;
        });
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

    /**
     * 各タグを少なくとも1つ含むファイル数（画像および動画）と、タグなし件数を集計する。
     */
    private void rebuildTagFilterCounts(Map<String, Set<String>> tagMap) throws IOException {
        tagFilterCounts.clear();
        int untagged = 0;
        Map<String, Integer> perTag = new HashMap<>();
        for (Path p : vault.listImages()) {
            Set<String> tags = tagMap.getOrDefault(p.getFileName().toString(), Set.of());
            if (tags.isEmpty()) {
                untagged++;
            } else {
                for (String t : tags) {
                    perTag.merge(t, 1, Integer::sum);
                }
            }
        }
        for (Path p : vault.listVideos()) {
            Set<String> tags = tagMap.getOrDefault(p.getFileName().toString(), Set.of());
            if (tags.isEmpty()) {
                untagged++;
            } else {
                for (String t : tags) {
                    perTag.merge(t, 1, Integer::sum);
                }
            }
        }
        tagFilterCounts.put(TAG_FILTER_UNTAGGED, untagged);
        tagFilterCounts.putAll(perTag);
    }

    private void refreshTagFilterOptions() {
        if (tagFilterListView == null || vault == null) {
            return;
        }
        try {
            Map<String, Set<String>> tagMap = vault.tagsByFileName();
            rebuildTagFilterCounts(tagMap);
            List<String> allTags = new ArrayList<>(vault.listAllTags());
            Collections.sort(allTags);
            LinkedHashSet<String> allowed = new LinkedHashSet<>(allTags);
            allowed.add(TAG_FILTER_UNTAGGED);
            activeTagFilters.retainAll(allowed);
            tagFilterSelectionMap.keySet().retainAll(allowed);
            List<String> items = new ArrayList<>(allTags.size() + 1);
            items.add(TAG_FILTER_UNTAGGED);
            items.addAll(allTags);
            updatingTagFilterSelection = true;
            try {
                tagFilterListView.getItems().setAll(items);
                for (String tag : items) {
                    BooleanProperty prop = tagFilterBooleanProperty(tag);
                    prop.set(activeTagFilters.contains(tag));
                }
            } finally {
                updatingTagFilterSelection = false;
            }
            tagFilterListView.refresh();
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
        // ._foo.jpg（AppleDouble）等は拡張子だけ .jpg でも実画像ではない。
        if (!name.isEmpty() && name.charAt(0) == '.') {
            return false;
        }
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
        // dropshadow は枚数が多いと GPU 負荷が跳ね上がるため、フラットな枠のみにする。
        return "-fx-background-color: #fffdf8; -fx-border-color: "
                + borderColor
                + "; -fx-border-width: 2; -fx-border-radius: 2; -fx-background-radius: 2;";
    }

    private static final String GALLERY_CANVAS_CHECK_KEY = "gazoCanvasCheck";
    private static final String GALLERY_IMAGE_VIEW_KEY = "gazoImageView";
    private static final String GALLERY_IMAGE_PATH_KEY = "gazoImagePath";
    private static final String GALLERY_IMAGE_W_KEY = "gazoImageW";
    private static final String GALLERY_IMAGE_H_KEY = "gazoImageH";
    private static final String GALLERY_IMAGE_REQUEST_KEY = "gazoImageRequest";

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
        double outerW = galleryCardOuterWidth();

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setFitWidth(imageW);
        view.setFitHeight(imageH);
        view.setSmooth(true);
        view.setCache(true);
        view.setCacheHint(CacheHint.SPEED);
        // キャッシュがあれば即表示（仮想ウィンドウ再構築で ImageView が新規でも空白を短くする）。
        view.setImage(galleryThumbCache.get(galleryThumbCacheKey(imagePath, imageW, imageH)));

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
        caption.setMaxWidth(Math.max(80, outerW - 24));
        caption.setMaxHeight(80);
        caption.setTextOverrun(OverrunStyle.CLIP);
        caption.setAlignment(Pos.CENTER_LEFT);
        caption.setStyle("-fx-text-fill: #4f4a41; -fx-font-size: 12px; -fx-font-family: 'Segoe UI';");
        caption.setVisible(!captionText.isBlank());
        caption.setManaged(!captionText.isBlank());

        boolean selectedForCanvas = listCheckedSelection.contains(imagePath);
        CheckBox canvasPickCheck = new CheckBox();
        canvasPickCheck.setSelected(selectedForCanvas);
        canvasPickCheck.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-padding: 2 4 2 4;");
        StackPane.setAlignment(canvasPickCheck, Pos.TOP_LEFT);
        StackPane.setMargin(canvasPickCheck, new Insets(6, 0, 0, 6));
        photoArea.getChildren().add(canvasPickCheck);

        Set<String> canvasLayouts =
                galleryCanvasLayoutsByFileName.getOrDefault(imagePath.getFileName().toString(), Set.of());
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
        canvasPickCheck.setOnAction(e -> {
            if (canvasPickCheck.isSelected()) {
                listCheckedSelection.add(imagePath);
            } else {
                listCheckedSelection.remove(imagePath);
            }
            card.setStyle(snapCardBorderStyle(listCheckedSelection.contains(imagePath)));
        });
        double cardH = galleryEstimatedRowHeight();
        card.setMinHeight(cardH);
        card.setPrefHeight(cardH);
        card.setMaxHeight(cardH);
        return card;
    }

    /** スクロール等から呼ぶ。短時間に何度も走る操作はデバウンスしてまとめる。 */
    private void refreshVisibleGalleryImages() {
        galleryVisibleDebounce.playFromStart();
        galleryEvictDebounce.playFromStart();
    }

    /** 一覧の可視サムネを更新。スクロール中は {@code evictDistantImages == false} にして画像を外さないとちらつきが減る。 */
    private void refreshVisibleGalleryImagesNow() {
        refreshVisibleGalleryImagesNow(true);
    }

    private void refreshVisibleGalleryImagesNow(boolean evictDistantImages) {
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
        // ScrollPane のコンテンツ高は上スペーサー + FlowPane + 下スペーサー（vvalue はこの全体に対する割合）。
        double totalH = topSpacerH + galleryH + bottomSpacerH;
        if (totalH <= 0) {
            return;
        }
        Bounds rootBounds = galleryVirtualRoot.getBoundsInLocal();
        double contentW = rootBounds.getWidth() > 0 ? rootBounds.getWidth() : galleryW;
        // ScrollPane の値でビューポートに見えている矩形（コンテンツルート座標）。カードは FlowPane 座標なので Y はスペーサー分ずらす。
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
     * 縦スクロール: 仮想ウィンドウの先頭行が変わり得る。大きく飛んだときだけ即 {@link #applyGalleryVirtualWindow}。
     */
    private void onGalleryVerticalScrollChanged(Number ov, Number nv) {
        if (ov == null || nv == null) {
            refreshVisibleGalleryImages();
            return;
        }
        double delta = Math.abs(nv.doubleValue() - ov.doubleValue());
        if (delta > 0.06) {
            galleryVisibleDebounce.stop();
            galleryEvictDebounce.playFromStart();
            applyGalleryVirtualWindow(true);
            refreshVisibleGalleryImagesNow(false);
        } else {
            refreshVisibleGalleryImages();
        }
    }

    /**
     * 横スクロール: 縦スライスは同じなので {@link #applyGalleryVirtualWindow} は呼ばない（全カード作り直しによるちらつき防止）。
     */
    private void onGalleryHorizontalScrollChanged(Number ov, Number nv) {
        if (ov == null || nv == null) {
            refreshVisibleGalleryImages();
            return;
        }
        double delta = Math.abs(nv.doubleValue() - ov.doubleValue());
        if (delta > 0.06) {
            galleryVisibleDebounce.stop();
            galleryEvictDebounce.playFromStart();
            refreshVisibleGalleryImagesNow(false);
        } else {
            refreshVisibleGalleryImages();
        }
    }

    private void onGalleryViewportBoundsChanged(Bounds oldB, Bounds newB) {
        if (newB == null || newB.getWidth() <= 0 || newB.getHeight() <= 0) {
            return;
        }
        if (oldB == null
                || Math.abs(newB.getWidth() - oldB.getWidth()) > 8
                || Math.abs(newB.getHeight() - oldB.getHeight()) > 8) {
            galleryVisibleDebounce.stop();
            galleryEvictDebounce.stop();
            applyGalleryVirtualWindow(true);
            refreshVisibleGalleryImagesNow(true);
        } else {
            refreshVisibleGalleryImages();
        }
    }

    private static String galleryThumbCacheKey(Path path, int w, int h) {
        return path.normalize().toString() + "|" + w + "x" + h;
    }

    private void requestGalleryImageLoad(ImageView target, Path path, int w, int h, int version) {
        String requestId = version + "|" + path + "|" + w + "x" + h;
        Image cached = galleryThumbCache.get(galleryThumbCacheKey(path, w, h));
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
            Image img = loadThumbnail(path, w, h);
            Platform.runLater(() -> {
                Object current = target.getProperties().get(GALLERY_IMAGE_REQUEST_KEY);
                if (!requestId.equals(current)) {
                    return;
                }
                if (img != null) {
                    galleryThumbCache.put(galleryThumbCacheKey(path, w, h), img);
                }
                // 一覧更新やスクロールで要求が切り替わっていなければ反映する。
                target.setImage(img);
            });
        });
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

    /**
     * 画像タブでチェックしたファイルを Vault から削除する（重複整理の「あとで削除」とは別経路で即時削除）。
     */
    private void deleteCheckedImagesFromVault(Stage owner) {
        if (listCheckedSelection.isEmpty()) {
            GazoFx.showWarn("画像を削除", "先に画像をチェックしてください。");
            return;
        }
        List<Path> targets = new ArrayList<>();
        for (Path p : new ArrayList<>(listCheckedSelection)) {
            if (!isPendingDelete(p)) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) {
            GazoFx.showWarn("画像を削除", "削除できる画像がありません。");
            return;
        }
        int n = targets.size();
        int maxLines = 24;
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < n && i < maxLines; i++) {
            preview.append("・ ").append(targets.get(i).getFileName().toString()).append('\n');
        }
        if (n > maxLines) {
            preview.append("… 他 ").append(n - maxLines).append(" 件\n");
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Vault から次の画像ファイルを完全に削除します。元に戻せません。\n\n" + preview,
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.setTitle("画像を削除");
        confirm.setHeaderText(n + " 件の画像を削除しますか？");
        if (owner != null) {
            confirm.initOwner(owner);
        }
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        List<Path> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Path p : targets) {
            try {
                vault.deleteImage(p);
                deleted.add(p);
            } catch (IOException ex) {
                failed.add(p.getFileName() + ": " + ex.getMessage());
            }
        }
        for (Path p : deleted) {
            listCheckedSelection.remove(p);
            canvasSelection.remove(p);
        }
        refreshTagFilterOptions();
        refreshGallery();
        if (!failed.isEmpty()) {
            GazoFx.showError("削除エラー", String.join("\n", failed));
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

    private boolean isPendingDelete(Path path) {
        if (path == null) {
            return false;
        }
        synchronized (pendingDeletePaths) {
            return pendingDeletePaths.contains(path);
        }
    }

    private void markPendingDelete(List<Path> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        synchronized (pendingDeletePaths) {
            pendingDeletePaths.addAll(targets);
        }
    }

    /** 予約された削除をまとめて実行する。 */
    private void flushPendingDeletes(boolean showUiError) {
        List<Path> targets;
        synchronized (pendingDeletePaths) {
            if (pendingDeletePaths.isEmpty()) {
                return;
            }
            targets = new ArrayList<>(pendingDeletePaths);
        }
        List<String> failed = new ArrayList<>();
        for (Path p : targets) {
            try {
                vault.deleteImage(p);
                synchronized (pendingDeletePaths) {
                    pendingDeletePaths.remove(p);
                }
            } catch (Exception ex) {
                failed.add(p.getFileName() + ": " + ex.getMessage());
            }
        }
        if (showUiError && !failed.isEmpty()) {
            GazoFx.showError("削除エラー", String.join("\n", failed));
        }
    }

    private void showDuplicateReport() {
        if (openDuplicateReportStage != null && openDuplicateReportStage.isShowing()) {
            openDuplicateReportStage.requestFocus();
            return;
        }
        try {
            if (vault != null) {
                galleryCanvasLayoutsByFileName = new HashMap<>(vault.mapCanvasLayoutsByFileName());
            } else {
                galleryCanvasLayoutsByFileName = Map.of();
            }
        } catch (IOException e) {
            galleryCanvasLayoutsByFileName = Map.of();
        }

        Stage dupStage = new Stage();
        dupStage.setTitle("重複/類似チェック");
        if (primaryStage != null) {
            dupStage.initOwner(primaryStage);
        }
        dupStage.initModality(Modality.WINDOW_MODAL);

        Spinner<Integer> thresholdSpinner = new Spinner<>();
        SpinnerValueFactory.IntegerSpinnerValueFactory thresholdFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 64, 8, 1);
        thresholdSpinner.setValueFactory(thresholdFactory);
        thresholdSpinner.setEditable(true);
        thresholdSpinner.setPrefWidth(92);
        ComboBox<String> thresholdPresetCombo = new ComboBox<>();
        thresholdPresetCombo.getItems().setAll(
                "同一のみ",
                "厳しめ (4)",
                "標準 (8)",
                "ゆるめ (12)",
                "かなりゆるめ (16)");
        thresholdPresetCombo.setValue("標準 (8)");
        thresholdPresetCombo.setOnAction(e -> {
            String v = thresholdPresetCombo.getValue();
            if (v == null) {
                return;
            }
            if ("同一のみ".equals(v)) {
                thresholdFactory.setValue(0);
            } else if (v.contains("(4)")) {
                thresholdFactory.setValue(4);
            } else if (v.contains("(8)")) {
                thresholdFactory.setValue(8);
            } else if (v.contains("(12)")) {
                thresholdFactory.setValue(12);
            } else if (v.contains("(16)")) {
                thresholdFactory.setValue(16);
            }
        });
        Button rerunButton = new Button("チェック開始");

        TextArea reportArea = new TextArea();
        reportArea.setEditable(false);
        reportArea.setWrapText(false);
        reportArea.setPrefRowCount(16);
        reportArea.setText("「チェック開始」を押すと類似候補（重複含む）を検索します。");

        ListView<Path> candidateList = new ListView<>();
        candidateList.setPrefHeight(280);
        candidateList.setMinHeight(200);
        // サムネ+長いラベルでも行高が変わらないようにし、類似削除済み表示→更新時のガタつきを抑える。
        candidateList.setFixedCellSize(60);
        /** 類似削除直後〜リスト再構築まで、削除対象行に「類似削除済み」を付ける。 */
        Set<Path> duplicateMergeRemovedMarkPaths = new HashSet<>();
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
                    thumb.setImage(null);
                    setGraphic(null);
                    return;
                }
                // 選択イベント中に I/O + Image 生成を走らせると Linux/GTK で警告が出ることがあるため、
                // 次のパルスに回す。
                final Path rowPath = item;
                // 「類似削除済み」はリスト参照と一致させるため、同期で先に付ける（runLater より前に見える）。
                boolean marked = duplicateMergeRemovedMarkPaths.contains(rowPath);
                if (marked) {
                    text.setText(rowPath.getFileName().toString() + " （類似削除済み）");
                    text.setStyle("-fx-text-fill: #888;");
                } else {
                    text.setText(rowPath.getFileName().toString());
                    text.setStyle(null);
                }
                thumb.setImage(null);
                setGraphic(box);
                Platform.runLater(() -> {
                    if (isEmpty() || getItem() == null || !getItem().equals(rowPath)) {
                        return;
                    }
                    String base = rowPath.getFileName().toString() + "  " + formatImagePixelSize(rowPath);
                    List<String> onCanvas =
                            new ArrayList<>(
                                    galleryCanvasLayoutsByFileName.getOrDefault(
                                            rowPath.getFileName().toString(), Set.of()));
                    Collections.sort(onCanvas);
                    String canvasSuffix = onCanvas.isEmpty() ? "" : "  [キャンバス]";
                    if (duplicateMergeRemovedMarkPaths.contains(rowPath)) {
                        text.setText(base + " （類似削除済み）" + canvasSuffix);
                        text.setStyle("-fx-text-fill: #888;");
                    } else {
                        text.setText(base + canvasSuffix);
                        text.setStyle(null);
                    }
                    if (onCanvas.isEmpty()) {
                        text.setTooltip(null);
                    } else {
                        text.setTooltip(new Tooltip("キャンバスに登録: " + String.join(", ", onCanvas)));
                    }
                    thumb.setImage(loadThumbnail(rowPath, 44, 44));
                });
            }
        });

        ImageView leftPreview = new ImageView();
        leftPreview.setFitWidth(220);
        leftPreview.setFitHeight(220);
        leftPreview.setPreserveRatio(true);
        Label leftLabel = new Label("選択画像");
        leftLabel.setWrapText(true);
        leftLabel.setMaxWidth(400);
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

        Label exactDupSectionTitle = new Label("重複・類似候補の整理");
        exactDupSectionTitle.setStyle("-fx-font-weight: bold;");
        Label exactDupHint = new Label("候補内で残す画像を選び、他を削除します。削除した画像のタグは残す画像に追加されます。");
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

        Label scanStatusLabel = new Label("");
        scanStatusLabel.setStyle("-fx-text-fill: #4a5560;");
        AtomicReference<List<GazoVaultService.SimilarPair>> similarRef = new AtomicReference<>(List.of());
        AtomicReference<List<List<Path>>> exactGroupsRef = new AtomicReference<>(List.of());
        AtomicReference<Path> selectedRef = new AtomicReference<>(null);

        HBox controls = new HBox(
                8,
                new Label("類似判定距離:"),
                thresholdSpinner,
                new Label("プリセット:"),
                thresholdPresetCombo,
                rerunButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox controlsBox = new VBox(4, controls, scanStatusLabel);
        controlsBox.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(
                8,
                controlsBox,
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
        // Linux/GTK: ProgressIndicator は常時アニメーションでネイティブ描画と衝突しやすい。テキストのみにする。
        Label busyStaticLabel = new Label("検索中");
        busyStaticLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label busyLabel = new Label("類似候補（重複含む）を検索しています…");
        busyLabel.setStyle("-fx-font-size: 13px;");
        VBox busyCenter = new VBox(8, busyStaticLabel, busyLabel);
        busyCenter.setAlignment(Pos.CENTER);
        busyPane.getChildren().addAll(busyBg, busyCenter);
        busyPane.setVisible(false);
        busyPane.setManaged(false);

        // 別 Stage を出すと Linux/GTK で XSetErrorHandler 警告が出ることがあるため、
        // 同一ダイアログ内のオーバーレイで拡大表示する。
        StackPane expandOverlayPane = new StackPane();
        expandOverlayPane.setVisible(false);
        expandOverlayPane.setManaged(false);

        StackPane rootStack = new StackPane(content, busyPane, expandOverlayPane);
        busyPane.setMouseTransparent(true);

        Button dupCloseButton = new Button("閉じる");
        dupCloseButton.setOnAction(e -> dupStage.close());
        HBox dupButtonRow = new HBox(dupCloseButton);
        dupButtonRow.setAlignment(Pos.CENTER_RIGHT);
        dupButtonRow.setPadding(new Insets(8, 12, 12, 12));
        VBox dupShell = new VBox(rootStack, dupButtonRow);
        VBox.setVgrow(rootStack, Priority.ALWAYS);

        AtomicBoolean duplicateScanRunning = new AtomicBoolean(false);
        AtomicBoolean duplicateScanRerunRequested = new AtomicBoolean(false);
        Runnable setDuplicateBusy = () -> {
            boolean busy = duplicateScanRunning.get();
            rerunButton.setDisable(busy);
            thresholdSpinner.setDisable(busy);
            thresholdPresetCombo.setDisable(busy);
            boolean hasSelection = candidateList.getSelectionModel().getSelectedItem() != null;
            // スキャン中は Task が runLater で UI 更新中。拡大オーバーレイと重なると Linux/GTK で警告が出ることがある。
            enlargeCompareButton.setDisable(busy || !hasSelection);
            dupCloseButton.setDisable(busy);
        };

        Runnable refreshDuplicateClusterUiRunnable = () -> refreshDuplicateClusterUi(
                selectedRef.get(),
                exactGroupsRef.get(),
                similarRef.get(),
                null,
                leftPreview,
                leftLabel,
                othersRow,
                othersHeaderLabel,
                exactRadioList,
                exactKeepToggleGroupRef,
                duplicateScanRunning,
                mergeDeleteExactButton,
                exactDupBox);

        // スキャン終了直後は refreshDuplicateClusterUi 済みなので、マージボタンの無効化だけ直す。
        Runnable refreshDuplicateMergeButtonOnly = () ->
                mergeDeleteExactButton.setDisable(duplicateScanRunning.get() || !exactDupBox.isVisible());

        AtomicReference<Runnable> refreshAsyncRef = new AtomicReference<>();
        Runnable refreshAsync = () -> {
            if (duplicateScanRunning.get()) {
                duplicateScanRerunRequested.set(true);
                return;
            }
            int threshold = thresholdFactory.getValue();
            final boolean exactOnlyScan = "同一のみ".equals(thresholdPresetCombo.getValue());
            duplicateScanRunning.set(true);
            duplicateScanRerunRequested.set(false);
            scanStatusLabel.setText("類似候補（重複含む）をチェック中…");
            candidateList.getItems().clear();
            selectedRef.set(null);
            reportArea.setText("類似候補（重複含む）を検索しています…");
            similarRef.set(List.of());
            exactGroupsRef.set(List.of());
            refreshDuplicateClusterUiRunnable.run();
            busyPane.setVisible(true);
            busyPane.setManaged(true);
            setDuplicateBusy.run();

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    List<Path> images = vault.listImages().stream()
                            .filter(p -> !isPendingDelete(p))
                            .toList();
                    int total = images.size();
                    long totalPairs = (long) total * (long) Math.max(0, total - 1) / 2L;
                    updateMessage("類似チェック中: 0 / " + total + "  (比較 0 / " + totalPairs + ")");
                    long lastRowProgressMsgMs = 0L;
                    AtomicLong lastPairProgressMsgMs = new AtomicLong(0L);
                    List<List<Path>> exact = new ArrayList<>();
                    List<GazoVaultService.SimilarPair> similar = new ArrayList<>();
                    Map<Path, String> shaCache = new ConcurrentHashMap<>();
                    Map<Path, Long> dHashCache = new ConcurrentHashMap<>();
                    AtomicLong donePairsAtomic = new AtomicLong(0L);

                    int parallelism = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors()));
                    ExecutorService exec = Executors.newFixedThreadPool(parallelism, r -> {
                        Thread t = new Thread(r, "gazo-dup-scan");
                        t.setDaemon(true);
                        return t;
                    });
                    record DuplicateScanRowResult(List<Path> exactMatches, List<GazoVaultService.SimilarPair> similarMatches) {}
                    List<Future<DuplicateScanRowResult>> rowFutures = new ArrayList<>();
                    try {
                        for (int i = 0; i < total; i++) {
                            final int iFinal = i;
                            rowFutures.add(exec.submit(() -> {
                                Path current = images.get(iFinal);
                                String shaCurrent = shaCache.computeIfAbsent(current, p -> {
                                    try {
                                        return vault.sha256(p);
                                    } catch (IOException e) {
                                        return "";
                                    }
                                });
                                Long dhCurrent = exactOnlyScan
                                        ? null
                                        : dHashCache.computeIfAbsent(current, vault::dHash64);
                                List<Path> exactMatches = new ArrayList<>();
                                List<GazoVaultService.SimilarPair> similarMatches = new ArrayList<>();
                                for (int j = iFinal + 1; j < total; j++) {
                                    Path other = images.get(j);
                                    long d = donePairsAtomic.incrementAndGet();
                                    if ((d & 255L) == 0L || d == totalPairs) {
                                        long now = System.currentTimeMillis();
                                        long prev = lastPairProgressMsgMs.get();
                                        if (d == totalPairs || now - prev >= 200L) {
                                            lastPairProgressMsgMs.set(now);
                                            updateMessage("類似チェック中: 比較 " + d + " / " + totalPairs);
                                        }
                                    }
                                    String shaOther = shaCache.computeIfAbsent(other, p -> {
                                        try {
                                            return vault.sha256(p);
                                        } catch (IOException e) {
                                            return "";
                                        }
                                    });
                                    if (!shaCurrent.isEmpty() && shaCurrent.equals(shaOther)) {
                                        exactMatches.add(other);
                                    }
                                    if (!exactOnlyScan) {
                                        Long dhOther = dHashCache.computeIfAbsent(other, vault::dHash64);
                                        if (dhCurrent != null && dhOther != null) {
                                            int dist = Long.bitCount(dhCurrent ^ dhOther);
                                            if (dist <= threshold) {
                                                similarMatches.add(new GazoVaultService.SimilarPair(current, other, dist));
                                            }
                                        }
                                    }
                                }
                                return new DuplicateScanRowResult(exactMatches, similarMatches);
                            }));
                        }

                        for (int i = 0; i < total; i++) {
                        DuplicateScanRowResult row = rowFutures.get(i).get();
                        List<Path> exactMatches = row.exactMatches();
                        List<GazoVaultService.SimilarPair> similarMatches = row.similarMatches();
                        Path current = images.get(i);

                        if (!exactMatches.isEmpty()) {
                            List<Path> group = new ArrayList<>();
                            group.add(current);
                            group.addAll(exactMatches);
                            exact.add(group);
                        }
                        if (!similarMatches.isEmpty()) {
                            similar.addAll(similarMatches);
                        }
                        // 途中経過の runLater で ListView/プレビューを更新すると Linux/GTK で負荷・警告が増えるため、
                        // 完了時の1回だけ反映する。
                        long now = System.currentTimeMillis();
                        if (now - lastRowProgressMsgMs >= 200L || i == total - 1) {
                            lastRowProgressMsgMs = now;
                            long dp = donePairsAtomic.get();
                            updateMessage("類似チェック中: " + (i + 1) + " / " + total + "  (比較 " + dp + " / " + totalPairs + ")");
                        }
                        }
                    } finally {
                        exec.shutdown();
                    }
                    similar.sort(Comparator.comparingInt(GazoVaultService.SimilarPair::distance));
                    List<List<Path>> exactFinal = new ArrayList<>(exact);
                    List<GazoVaultService.SimilarPair> similarFinal = new ArrayList<>(similar);
                    List<Path> finalCandidates = collectDuplicateCandidates(exactFinal, similarFinal);
                    Platform.runLater(() -> {
                        similarRef.set(similarFinal);
                        exactGroupsRef.set(exactFinal);
                        reportArea.setText(buildDuplicateReport(exactFinal, similarFinal, threshold, exactOnlyScan));
                        candidateList.getItems().setAll(finalCandidates);
                        Path selected = null;
                        if (!finalCandidates.isEmpty()) {
                            // 候補はクラスタ代表を面積降順で並べているので先頭が最大面積の代表。
                            selected = finalCandidates.get(0);
                            candidateList.getSelectionModel().select(selected);
                        }
                        selectedRef.set(selected);
                        refreshDuplicateClusterUiRunnable.run();
                        Platform.runLater(() -> {
                            busyPane.setVisible(false);
                            busyPane.setManaged(false);
                        });
                    });
                    return null;
                }
            };
            busyLabel.textProperty().unbind();
            busyLabel.textProperty().bind(task.messageProperty());
            scanStatusLabel.textProperty().unbind();
            // message を二重に bind すると更新のたびにレイアウトが二重に走り GTK と相性が悪い。オーバーレイ側のみ bind する。
            // タスク完了直後に unbind + オーバーレイ非表示をすると Linux/GTK で
            // XSetErrorHandler 警告が出ることがあるため、イベント処理の外に出す（二重 runLater）。
            task.setOnSucceeded(ev -> Platform.runLater(() -> Platform.runLater(() -> {
                duplicateScanRunning.set(false);
                busyLabel.textProperty().unbind();
                busyPane.setVisible(false);
                busyPane.setManaged(false);
                scanStatusLabel.setText("類似チェック完了");
                rerunButton.setText("再チェック");
                setDuplicateBusy.run();
                // call() 側の最終 runLater が先に走ると、まだ duplicateScanRunning==true のまま refresh されて
                // merge ボタンが無効のまま残る。スキャン終了後にもう一度だけ更新する。
                refreshDuplicateMergeButtonOnly.run();
                if (duplicateScanRerunRequested.getAndSet(false)) {
                    Runnable rr = refreshAsyncRef.get();
                    if (rr != null) {
                        rr.run();
                    }
                }
            })));
            task.setOnFailed(ev -> Platform.runLater(() -> Platform.runLater(() -> {
                duplicateScanRunning.set(false);
                busyLabel.textProperty().unbind();
                busyPane.setVisible(false);
                busyPane.setManaged(false);
                scanStatusLabel.setText("");
                rerunButton.setText("再チェック");
                setDuplicateBusy.run();
                refreshDuplicateMergeButtonOnly.run();
                Throwable ex = task.getException();
                String msg = ex != null && ex.getMessage() != null ? ex.getMessage() : "不明なエラー";
                GazoFx.showError("類似チェックエラー", msg);
                if (duplicateScanRerunRequested.getAndSet(false)) {
                    Runnable rr = refreshAsyncRef.get();
                    if (rr != null) {
                        rr.run();
                    }
                }
            })));
            Thread t = new Thread(task, "gazo-duplicate-scan");
            t.setDaemon(true);
            t.start();
        };
        refreshAsyncRef.set(refreshAsync);

        mergeDeleteExactButton.setOnAction(e -> {
            ToggleGroup tg = exactKeepToggleGroupRef.get();
            javafx.scene.control.Toggle t = tg.getSelectedToggle();
            if (!(t instanceof RadioButton rb)) {
                return;
            }
            Path keep = (Path) rb.getUserData();
            List<DuplicateOther> others = duplicateOthersWithSelectedDHash(keep, exactGroupsRef.get(), similarRef.get());
            if (others.isEmpty()) {
                GazoFx.showWarn("削除", "削除対象にできる候補がありません。");
                return;
            }
            runMergeDeleteFlow(
                    dupStage,
                    keep,
                    others,
                    "削除する候補の選択",
                    "重複・類似候補削除の確認",
                    "重複・類似候補削除エラー",
                    true,
                    (k, deleted) -> applyDuplicateUiAfterMergeDeletes(
                            k,
                            deleted,
                            thresholdFactory.getValue(),
                            "同一のみ".equals(thresholdPresetCombo.getValue()),
                            similarRef,
                            exactGroupsRef,
                            candidateList,
                            reportArea,
                            selectedRef,
                            setDuplicateBusy,
                            duplicateMergeRemovedMarkPaths,
                            leftPreview,
                            leftLabel,
                            othersRow,
                            othersHeaderLabel,
                            exactRadioList,
                            exactKeepToggleGroupRef,
                            duplicateScanRunning,
                            mergeDeleteExactButton,
                            exactDupBox));
        });
        rerunButton.setOnAction(e -> refreshAsync.run());
        candidateList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            // Linux/GTK では選択イベントの最中に重い UI 更新をすると
            // 「XSetErrorHandler() called with a GTK error trap pushed」が出ることがあるため、
            // イベント処理完了後にまとめて更新する。
            Platform.runLater(() -> {
                selectedRef.set(newV);
                refreshDuplicateClusterUiRunnable.run();
                setDuplicateBusy.run();
            });
        });
        enlargeCompareButton.setOnAction(e -> Platform.runLater(() -> {
            if (duplicateScanRunning.get()) {
                GazoFx.showWarn("比較表示", "類似チェックが終わるまでお待ちください。");
                return;
            }
            Path selected = selectedRef.get();
            List<DuplicateOther> others = duplicateOthersWithSelectedDHash(selected, exactGroupsRef.get(), similarRef.get());
            showDuplicateExpandOverlay(expandOverlayPane, selected, others);
        }));

        ScrollPane dupScroll = new ScrollPane(dupShell);
        dupScroll.setFitToWidth(true);
        dupScroll.setPannable(false);
        Scene dupScene = new Scene(dupScroll);
        if (primaryStage != null && primaryStage.getScene() != null) {
            dupScene.getStylesheets().setAll(primaryStage.getScene().getStylesheets());
        }
        dupStage.setScene(dupScene);
        dupStage.sizeToScene();
        dupStage.setMinWidth(480);
        dupStage.setMinHeight(400);
        openDuplicateReportStage = dupStage;
        dupStage.setOnHidden(ev -> {
            openDuplicateReportStage = null;
            flushPendingDeletes(true);
            refreshTagFilterOptions();
            refreshGallery();
            refreshVideoList();
        });
        dupScene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                dupStage.close();
                e.consume();
            }
        });
        dupStage.show();
    }

    private void runMergeDeleteFlow(
            Window owner,
            Path keep,
            List<DuplicateOther> others,
            String pickTitle,
            String confirmTitle,
            String errorTitle,
            boolean preselectAll,
            BiConsumer<Path, List<Path>> onMergeCommitted) {
        // ネストした Dialog をボタンイベントの最中に開くと Linux/GTK で警告が出ることがあるため、
        // 次のパルスにまわす。解像度は ImageIO をバックグラウンドで読み、ラベルを後から追記する。
        Platform.runLater(() -> {
            Dialog<List<Path>> pickDialog = new Dialog<>();
            if (owner != null) {
                pickDialog.initOwner(owner);
            }
            pickDialog.setTitle(pickTitle);
            pickDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            TextField keepTagsField;
            try {
                keepTagsField = new TextField(String.join(", ", vault.getTags(keep)));
            } catch (IOException ex) {
                GazoFx.showError(pickTitle, ex.getMessage());
                return;
            }
            keepTagsField.setPromptText("タグ（カンマ区切り）");
            VBox keepBox = new VBox(
                    4,
                    new Label("残す画像: " + keep.getFileName()),
                    keepTagsField);

            VBox checks = new VBox(6);
            List<CheckBox> checkBoxes = new ArrayList<>();
            for (DuplicateOther o : others) {
                String line3 = formatDHashDistanceLabel(o.distance(), o.sameByteIdentityAsReference());
                Path path = o.path();
                CheckBox cb = new CheckBox(path.getFileName() + "  —  (" + line3 + ")");
                cb.setUserData(path);
                cb.setSelected(preselectAll);
                Label tagHint;
                try {
                    String t = String.join(", ", vault.getTags(path));
                    tagHint = new Label(t.isEmpty() ? "タグ: （なし）" : "タグ: " + t);
                } catch (IOException ex) {
                    tagHint = new Label("タグ: （読込エラー）");
                }
                tagHint.setStyle("-fx-text-fill: #5c6570;");
                tagHint.setWrapText(true);
                tagHint.setMaxWidth(420);
                VBox row = new VBox(4, cb, tagHint);
                checks.getChildren().add(row);
                checkBoxes.add(cb);
            }
            galleryImageLoadExecutor.execute(() -> {
                for (int i = 0; i < checkBoxes.size(); i++) {
                    DuplicateOther o = others.get(i);
                    CheckBox cb = checkBoxes.get(i);
                    String line3 = formatDHashDistanceLabel(o.distance(), o.sameByteIdentityAsReference());
                    Path path = o.path();
                    int[] wh = readImagePixelDimensionsImageIo(path);
                    if (wh != null) {
                        String full = path.getFileName() + "  " + wh[0] + "×" + wh[1] + "  (" + line3 + ")";
                        Platform.runLater(() -> cb.setText(full));
                    } else {
                        Platform.runLater(() -> {
                            String dimStr = formatImagePixelSize(path);
                            cb.setText(path.getFileName() + "  " + dimStr + "  (" + line3 + ")");
                        });
                    }
                }
            });
            ScrollPane sp = new ScrollPane(checks);
            sp.setFitToWidth(true);
            sp.setPrefViewportHeight(220);

            Label mergedPreviewLabel = new Label();
            mergedPreviewLabel.setWrapText(true);
            mergedPreviewLabel.setMaxWidth(480);
            mergedPreviewLabel.setStyle("-fx-text-fill: #2d3748;");
            Runnable refreshMergedPreview = () -> {
                LinkedHashSet<String> merged = new LinkedHashSet<>(parseUserTags(keepTagsField.getText()));
                for (CheckBox cb : checkBoxes) {
                    if (!cb.isSelected() || !(cb.getUserData() instanceof Path p)) {
                        continue;
                    }
                    try {
                        merged.addAll(vault.getTags(p));
                    } catch (IOException ex) {
                        mergedPreviewLabel.setText("（削除候補のタグの読込に失敗しました）");
                        return;
                    }
                }
                mergedPreviewLabel.setText(merged.isEmpty() ? "（なし）" : String.join(", ", merged));
            };
            keepTagsField.textProperty().addListener((obs, o, n) -> refreshMergedPreview.run());
            for (CheckBox cb : checkBoxes) {
                cb.selectedProperty().addListener((obs, o, n) -> refreshMergedPreview.run());
            }
            refreshMergedPreview.run();

            Label mergedHint = new Label("統合後のタグ（予定）※残す画像のタグに、チェックした削除候補のタグを足したもの");
            mergedHint.setWrapText(true);
            mergedHint.setMaxWidth(480);
            mergedHint.setStyle("-fx-font-weight: bold;");
            VBox mergedBox = new VBox(6, mergedHint, mergedPreviewLabel);

            pickDialog.getDialogPane().setContent(new VBox(
                    8,
                    new Label("残す画像のタグだけ編集できます（消える候補のタグは参照のみ）。削除する候補にチェックを入れてください。"),
                    keepBox,
                    sp,
                    mergedBox));
            pickDialog.setResultConverter(bt -> {
                if (bt != ButtonType.OK) {
                    return null;
                }
                List<Path> picked = new ArrayList<>();
                for (CheckBox cb : checkBoxes) {
                    if (cb.isSelected() && cb.getUserData() instanceof Path p) {
                        picked.add(p);
                    }
                }
                return picked;
            });
            Optional<List<Path>> pickedOpt = pickDialog.showAndWait();
            if (pickedOpt.isEmpty()) {
                return;
            }
            List<Path> toDelete = pickedOpt.get();
            if (toDelete.isEmpty()) {
                GazoFx.showWarn("削除", "削除する候補を1件以上選択してください。");
                return;
            }
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
            sb.append("\nキャンバスに載っている場合は、削除側の位置・拡大・回転・一覧の表示サイズを残す画像に引き継ぎます。");
            sb.append("\n実際のファイル削除は、この画面を閉じるとき（またはアプリ終了時）にまとめて実行されます。");
            try {
                LinkedHashSet<String> mergedForConfirm = new LinkedHashSet<>(parseUserTags(keepTagsField.getText()));
                for (Path p : toDelete) {
                    mergedForConfirm.addAll(vault.getTags(p));
                }
                sb.append("\n\n統合後のタグ（予定）: ");
                sb.append(mergedForConfirm.isEmpty() ? "（なし）" : String.join(", ", mergedForConfirm));
            } catch (IOException ex) {
                sb.append("\n\n統合後のタグ（予定）: （読込エラー）");
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, sb.toString(), ButtonType.OK, ButtonType.CANCEL);
            if (owner != null) {
                confirm.initOwner(owner);
            }
            confirm.setTitle(confirmTitle);
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            try {
                vault.setTags(keep, parseUserTags(keepTagsField.getText()));
                LinkedHashSet<String> merged = new LinkedHashSet<>(vault.getTags(keep));
                for (Path p : toDelete) {
                    merged.addAll(vault.getTags(p));
                }
                vault.setTags(keep, merged);
                for (Path p : toDelete) {
                    vault.substituteCanvasImageReferences(keep, p);
                }
                markPendingDelete(toDelete);
                refreshTagFilterOptions();
                refreshGallery();
                refreshVideoList();
                onMergeCommitted.accept(keep, toDelete);
            } catch (IOException ex) {
                GazoFx.showError(errorTitle, ex.getMessage());
            }
        });
    }

    private String buildDuplicateReport(
            List<List<Path>> exact, List<GazoVaultService.SimilarPair> similar, int threshold, boolean exactOnlyScan) {
        StringBuilder report = new StringBuilder();
        if (exactOnlyScan) {
            report.append("【同一のみ（SHA-256 が一致する重複）】\n");
        } else {
            report.append("【類似候補（重複含む、dHash距離 <= ").append(threshold).append("）】\n");
        }
        if (!exact.isEmpty()) {
            int group = 1;
            for (List<Path> paths : exact) {
                report.append("クラスタ ").append(group++).append(" (d=0 (同一)):\n");
                for (Path p : sortPathsByImageAreaDesc(paths)) {
                    report.append("  - ")
                            .append(p.getFileName())
                            .append("  ")
                            .append(formatImagePixelSize(p))
                            .append('\n');
                }
            }
            if (!similar.isEmpty()) {
                report.append('\n');
            }
        }
        if (similar.isEmpty()) {
            if (exact.isEmpty()) {
                report.append("なし\n");
            }
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
                        .append("  (")
                        .append(formatDHashDistanceLabel(pair.distance(), false))
                        .append(")\n");
            }
        }
        return report.toString();
    }

    /**
     * ImageIO のみでピクセル幅・高さを取得。JavaFX {@link Image} は使わないためバックグラウンドスレッドから呼べる。
     * 読み取れないときは null（{@link #readImagePixelDimensions(Path)} はその後 JavaFX 経由のフォールバックを試す）。
     */
    private int[] readImagePixelDimensionsImageIo(Path path) {
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
                            if (w <= 0 || h <= 0) {
                                return null;
                            }
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
        return null;
    }

    /** ピクセル幅・高さ。読み取れないときは null。 */
    private int[] readImagePixelDimensions(Path path) {
        int[] wh = readImagePixelDimensionsImageIo(path);
        if (wh != null && wh[0] > 0 && wh[1] > 0) {
            return wh;
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

    /**
     * 参照画像からの dHash 距離の短い表示。
     * d=0 でも、参照とバイト同一（同一SHAグループ）のときだけ「(同一)」を付ける。
     */
    private String formatDHashDistanceLabel(int distance, boolean sameBytesAsReference) {
        if (distance == 0) {
            return sameBytesAsReference ? "d=0 (同一)" : "d=0";
        }
        return "d=" + distance;
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

    /**
     * マージ削除後に類似チェックを最初からやり直さず、メモリ上の類似結果から削除済みを除いて UI だけ更新する。
     * 削除対象行はリスト再構築まで「類似削除済み」表示とし、下の行を直ちに選択する。
     * 候補の再計算・レポート・dHash はバックグラウンド、JavaFX ではサムネとノード構築のみ行う。
     */
    private void applyDuplicateUiAfterMergeDeletes(
            Path keep,
            List<Path> toDelete,
            int threshold,
            boolean exactOnlyReport,
            AtomicReference<List<GazoVaultService.SimilarPair>> similarRef,
            AtomicReference<List<List<Path>>> exactGroupsRef,
            ListView<Path> candidateList,
            TextArea reportArea,
            AtomicReference<Path> selectedRef,
            Runnable setDuplicateBusy,
            Set<Path> duplicateMergeRemovedMarkPaths,
            ImageView leftPreview,
            Label leftLabel,
            HBox othersRow,
            Label othersHeaderLabel,
            VBox exactRadioList,
            AtomicReference<ToggleGroup> exactKeepToggleGroupRef,
            AtomicBoolean duplicateScanRunning,
            Button mergeDeleteExactButton,
            VBox exactDupBox) {
        Set<Path> gone = new HashSet<>(toDelete);
        List<Path> listBefore = new ArrayList<>(candidateList.getItems());
        Path selBefore = selectedRef.get();
        int idxBefore = listBefore.indexOf(selBefore);

        List<GazoVaultService.SimilarPair> simBefore = new ArrayList<>(similarRef.get());
        List<List<Path>> exactBefore = new ArrayList<>();
        for (List<Path> g : exactGroupsRef.get()) {
            if (g == null || g.isEmpty()) {
                continue;
            }
            exactBefore.add(new ArrayList<>(g));
        }

        List<GazoVaultService.SimilarPair> sim = new ArrayList<>(similarRef.get());
        sim.removeIf(p -> p == null || gone.contains(p.left()) || gone.contains(p.right()));
        similarRef.set(sim);

        List<List<Path>> exact = new ArrayList<>();
        for (List<Path> g : exactGroupsRef.get()) {
            if (g == null || g.isEmpty()) {
                continue;
            }
            List<Path> ng = new ArrayList<>();
            for (Path p : g) {
                if (!gone.contains(p)) {
                    ng.add(p);
                }
            }
            if (ng.size() >= 2) {
                exact.add(ng);
            }
        }
        exactGroupsRef.set(exact);

        // 一覧はクラスタ代表のみのため、削除対象が代表以外でも「同じ連結成分に削除があった」代表行に付ける。
        fillDuplicateMergeRemovedMarks(duplicateMergeRemovedMarkPaths, candidateList.getItems(), gone, exactBefore, simBefore);
        candidateList.refresh();

        Path immediatePick = pickImmediateDuplicateSelectionAfterMerge(listBefore, idxBefore, gone);
        if (immediatePick != null) {
            candidateList.getSelectionModel().select(immediatePick);
            selectedRef.set(immediatePick);
        }

        final List<List<Path>> exactFinal = exact;
        final List<GazoVaultService.SimilarPair> simFinal = sim;
        final Path keepPath = keep;
        final List<Path> listBeforeFinal = listBefore;
        final int idxBeforeFinal = idxBefore;
        final Path immediatePickFinal = immediatePick;
        vaultHeavySerialExecutor.execute(() -> {
            Map<Path, Set<Path>> graph = buildDuplicateSimilarityGraph(exactFinal, simFinal);
            List<Path> candidates = duplicateClusterRepresentativesFromGraph(graph);
            String report = buildDuplicateReport(exactFinal, simFinal, threshold, exactOnlyReport);
            final Path resolved = resolveDuplicateCandidateSelectionAfterMerge(
                    keepPath, candidates, graph, listBeforeFinal, idxBeforeFinal);
            final Path selFinal = (immediatePickFinal != null && candidates.contains(immediatePickFinal))
                    ? immediatePickFinal
                    : resolved;
            List<DuplicateOther> orderedOthersForSelFinal = selFinal == null
                    ? List.of()
                    : duplicateOthersWithSelectedDHash(selFinal, exactFinal, simFinal);
            Platform.runLater(() -> {
                reportArea.setText(report);
                // 灰色の「類似削除済み」行を一瞬残してから差し替え、急な消え方を和らげる。
                PauseTransition pause = new PauseTransition(Duration.millis(160));
                pause.setOnFinished(ev -> {
                    // setAll の前に選んでいた行を覚え、新しい候補にまだあればそのまま維持する。
                    Path userSelected = candidateList.getSelectionModel().getSelectedItem();
                    duplicateMergeRemovedMarkPaths.clear();
                    candidateList.getItems().setAll(candidates);
                    Path toSelect = null;
                    if (userSelected != null && candidates.contains(userSelected)) {
                        toSelect = userSelected;
                    } else if (selFinal != null && candidates.contains(selFinal)) {
                        toSelect = selFinal;
                    } else if (!candidates.isEmpty()) {
                        toSelect = candidates.get(0);
                    }
                    List<DuplicateOther> orderedOthers =
                            (toSelect != null && selFinal != null && toSelect.equals(selFinal))
                                    ? orderedOthersForSelFinal
                                    : (toSelect == null
                                            ? List.of()
                                            : duplicateOthersWithSelectedDHash(
                                                    toSelect, exactFinal, simFinal));
                    if (toSelect != null) {
                        candidateList.getSelectionModel().select(toSelect);
                        candidateList.scrollTo(toSelect);
                    } else {
                        candidateList.getSelectionModel().clearSelection();
                    }
                    selectedRef.set(toSelect);
                    refreshDuplicateClusterUi(
                            toSelect,
                            exactFinal,
                            simFinal,
                            orderedOthers,
                            leftPreview,
                            leftLabel,
                            othersRow,
                            othersHeaderLabel,
                            exactRadioList,
                            exactKeepToggleGroupRef,
                            duplicateScanRunning,
                            mergeDeleteExactButton,
                            exactDupBox);
                    setDuplicateBusy.run();
                });
                pause.playFromStart();
            });
        });
    }

    /**
     * 削除直前のリスト順で、まだ残る行のうち「下方向に最初の1件」、なければ上方向。削除対象はスキップ。
     */
    private Path pickImmediateDuplicateSelectionAfterMerge(List<Path> listBefore, int idxBefore, Set<Path> gone) {
        if (listBefore == null || listBefore.isEmpty() || idxBefore < 0) {
            return null;
        }
        for (int i = idxBefore + 1; i < listBefore.size(); i++) {
            Path p = listBefore.get(i);
            if (!gone.contains(p)) {
                return p;
            }
        }
        for (int i = idxBefore - 1; i >= 0; i--) {
            Path p = listBefore.get(i);
            if (!gone.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 同一SHAグループと類似ペアから無向グラフを構築する（{@link #collectDuplicateCandidates} と同じ定義）。
     */
    private Map<Path, Set<Path>> buildDuplicateSimilarityGraph(
            List<List<Path>> exact, List<GazoVaultService.SimilarPair> similar) {
        Map<Path, Set<Path>> graph = new HashMap<>();
        for (List<Path> group : exact) {
            List<Path> paths = group == null ? List.of() : group;
            if (paths.isEmpty()) {
                continue;
            }
            for (Path p : paths) {
                graph.computeIfAbsent(p, k -> new LinkedHashSet<>());
            }
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
        return graph;
    }

    /**
     * マージ削除の「削除済み」表示用。候補行は代表パスのみのため、削除されたのが同クラスタの別ファイルでも
     * マージ前グラフ上でその代表と同じ連結成分に {@code gone} が含まれる行にマークする。
     * <p>
     * リスト差し替え（{@code setAll}）前に連続でマージすると、前回のマークを消さずに {@code add} だけする。
     */
    private void fillDuplicateMergeRemovedMarks(
            Set<Path> duplicateMergeRemovedMarkPaths,
            List<Path> candidateItems,
            Set<Path> gone,
            List<List<Path>> exactBefore,
            List<GazoVaultService.SimilarPair> simBefore) {
        duplicateMergeRemovedMarkPaths.removeIf(p -> !candidateItems.contains(p));
        if (gone.isEmpty() || candidateItems.isEmpty()) {
            return;
        }
        Map<Path, Set<Path>> graph = buildDuplicateSimilarityGraph(exactBefore, simBefore);
        for (Path item : candidateItems) {
            if (item == null) {
                continue;
            }
            Set<Path> visited = new LinkedHashSet<>();
            Deque<Path> stack = new ArrayDeque<>();
            stack.push(item);
            visited.add(item);
            boolean hit = false;
            while (!stack.isEmpty()) {
                Path cur = stack.pop();
                if (gone.contains(cur)) {
                    hit = true;
                    break;
                }
                for (Path n : graph.getOrDefault(cur, Set.of())) {
                    if (visited.add(n)) {
                        stack.push(n);
                    }
                }
            }
            if (hit) {
                duplicateMergeRemovedMarkPaths.add(item);
            }
        }
    }

    /** 各連結成分の面積最大代表を列挙し、面積降順で並べる。 */
    private List<Path> duplicateClusterRepresentativesFromGraph(Map<Path, Set<Path>> graph) {
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

    /**
     * 削除後の候補選択。削除直前のリスト順で「一つ下」を優先し、最下行なら「一つ上」。
     * 候補はクラスタ代表のみのため、残す画像が代表でないときは同一クラスタの代表へ合わせる。
     * 先頭（最大面積代表の先頭）へ飛ぶのは最後の手段。
     */
    private Path resolveDuplicateCandidateSelectionAfterMerge(
            Path keepPath,
            List<Path> candidates,
            Map<Path, Set<Path>> graph,
            List<Path> listBefore,
            int idxBefore) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (listBefore != null && !listBefore.isEmpty() && idxBefore >= 0) {
            if (idxBefore < listBefore.size() - 1) {
                Path wantBelow = listBefore.get(idxBefore + 1);
                if (candidates.contains(wantBelow)) {
                    return wantBelow;
                }
            } else if (idxBefore > 0) {
                Path wantAbove = listBefore.get(idxBefore - 1);
                if (candidates.contains(wantAbove)) {
                    return wantAbove;
                }
            }
        }
        if (keepPath != null && candidates.contains(keepPath)) {
            return keepPath;
        }
        if (keepPath != null) {
            Path rep = duplicateClusterRepresentativeForPath(keepPath, graph);
            if (rep != null && candidates.contains(rep)) {
                return rep;
            }
        }
        return candidates.get(0);
    }

    /** path が属する連結成分の代表（面積最大）。グラフに含まれない場合は null。 */
    private Path duplicateClusterRepresentativeForPath(Path path, Map<Path, Set<Path>> graph) {
        if (path == null || graph == null || !graph.containsKey(path)) {
            return null;
        }
        Set<Path> visited = new LinkedHashSet<>();
        List<Path> component = new ArrayList<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(path);
        visited.add(path);
        while (!stack.isEmpty()) {
            Path cur = stack.pop();
            component.add(cur);
            for (Path next : graph.getOrDefault(cur, Set.of())) {
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return sortPathsByImageAreaDesc(component).get(0);
    }

    private List<Path> collectDuplicateCandidates(List<List<Path>> exact, List<GazoVaultService.SimilarPair> similar) {
        return duplicateClusterRepresentativesFromGraph(buildDuplicateSimilarityGraph(exact, similar));
    }

    /** サムネ用ファイルの生バイト（I/O のみ。JavaFX {@link Image} は必ず FX スレッドで生成する）。 */
    private byte[] readThumbnailFileBytes(Path path) throws IOException {
        Path source = path;
        if (vault != null) {
            Path thumb = vault.thumbnailFor(path);
            if (thumb != null) {
                source = thumb;
            }
        }
        return Files.readAllBytes(source);
    }

    /**
     * キャンバス追加ダイアログなど、Vault サムネを使ったプレビュー用。
     */
    public Image thumbnailForPicker(Path path, int width, int height) {
        return loadThumbnail(path, width, height);
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
    private record DuplicateOther(Path path, int distance, boolean sameByteIdentityAsReference) {}

    /** 同一SHAグループにいればバイト同一（参照と other）。 */
    private boolean sameByteIdentity(Path reference, Path other, List<List<Path>> exactGroups) {
        if (reference == null || other == null) {
            return false;
        }
        if (reference.equals(other)) {
            return true;
        }
        if (exactGroups == null) {
            return false;
        }
        for (List<Path> g : exactGroups) {
            if (g != null && g.contains(reference) && g.contains(other)) {
                return true;
            }
        }
        return false;
    }

    /**
     * リストで選択中の画像を基準に、同一クラスタ内の他候補との dHash 距離を付けて並べ替える。
     */
    private List<DuplicateOther> duplicateOthersWithSelectedDHash(
            Path selected,
            List<List<Path>> exactGroups,
            List<GazoVaultService.SimilarPair> similarPairs) {
        List<DuplicateOther> base = duplicateOthersOrdered(selected, exactGroups, similarPairs);
        if (base.isEmpty() || selected == null) {
            return base;
        }
        Long dhSel = vault.dHash64(selected);
        List<DuplicateOther> out = new ArrayList<>();
        for (DuplicateOther x : base) {
            int d;
            if (dhSel != null) {
                Long dhO = vault.dHash64(x.path());
                d = (dhO != null) ? Long.bitCount(dhSel ^ dhO) : x.distance();
            } else {
                d = x.distance();
            }
            out.add(new DuplicateOther(x.path(), d, x.sameByteIdentityAsReference()));
        }
        out.sort(Comparator.comparingLong((DuplicateOther o) -> imagePixelArea(o.path()))
                .reversed()
                .thenComparingInt(DuplicateOther::distance)
                .thenComparing(o -> o.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private List<DuplicateOther> duplicateOthersOrdered(
            Path selected,
            List<List<Path>> exactGroups,
            List<GazoVaultService.SimilarPair> similarPairs) {
        if (selected == null) {
            return List.of();
        }
        Map<Path, Integer> minDist = new HashMap<>();
        for (List<Path> g : exactGroups) {
            if (g.contains(selected)) {
                for (Path p : g) {
                    if (!p.equals(selected)) {
                        minDist.merge(p, 0, Math::min);
                    }
                }
            }
        }
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
                .map(e -> new DuplicateOther(
                        e.getKey(),
                        e.getValue(),
                        sameByteIdentity(selected, e.getKey(), exactGroups)))
                .toList();
    }

    /**
     * 候補プレビューと「残す」ラジオを一度だけ計算して更新する（dHash 二重計算を避ける）。
     *
     * @param orderedOthersPrecomputed 非 null のときはそのリストを使い、dHash 計算を省略する（マージ削除後の非同期更新用）。
     */
    private void refreshDuplicateClusterUi(
            Path sel,
            List<List<Path>> eg,
            List<GazoVaultService.SimilarPair> sim,
            List<DuplicateOther> orderedOthersPrecomputed,
            ImageView leftPreview,
            Label leftLabel,
            HBox othersRow,
            Label othersHeaderLabel,
            VBox exactRadioList,
            AtomicReference<ToggleGroup> exactKeepToggleGroupRef,
            AtomicBoolean duplicateScanRunning,
            Button mergeDeleteExactButton,
            VBox exactDupBox) {
        exactRadioList.getChildren().clear();
        ToggleGroup tg = new ToggleGroup();
        exactKeepToggleGroupRef.set(tg);

        if (sel == null || eg == null) {
            exactDupBox.setVisible(false);
            exactDupBox.setManaged(false);
            mergeDeleteExactButton.setDisable(true);
            othersRow.getChildren().clear();
            leftPreview.setImage(null);
            leftLabel.setText("選択画像");
            othersHeaderLabel.setText("重複・類似候補");
            return;
        }

        List<DuplicateOther> orderedOthers = orderedOthersPrecomputed != null
                ? orderedOthersPrecomputed
                : duplicateOthersWithSelectedDHash(sel, eg, sim);

        othersRow.getChildren().clear();
        leftPreview.setImage(loadThumbnail(sel, 220, 220));
        List<String> selCanvasLayouts =
                new ArrayList<>(galleryCanvasLayoutsByFileName.getOrDefault(sel.getFileName().toString(), Set.of()));
        Collections.sort(selCanvasLayouts);
        String selLine = "選択: " + sel.getFileName() + "  " + formatImagePixelSize(sel);
        if (!selCanvasLayouts.isEmpty()) {
            selLine += "\nキャンバス: " + String.join(", ", selCanvasLayouts);
        }
        leftLabel.setText(selLine);
        othersHeaderLabel.setText("他の候補 (" + orderedOthers.size() + " 件)");
        if (orderedOthers.isEmpty()) {
            Label empty = new Label("なし");
            empty.setStyle("-fx-text-fill: #666;");
            othersRow.getChildren().add(empty);
        } else {
            for (DuplicateOther o : orderedOthers) {
                othersRow.getChildren().add(buildDuplicatePreviewTile(o));
            }
        }

        List<Path> group = new ArrayList<>();
        group.add(sel);
        Map<Path, Integer> distanceByPath = new HashMap<>();
        distanceByPath.put(sel, 0);
        for (DuplicateOther o : orderedOthers) {
            if (!group.contains(o.path())) {
                group.add(o.path());
            }
            distanceByPath.putIfAbsent(o.path(), o.distance());
        }
        if (group.size() < 2) {
            exactDupBox.setVisible(false);
            exactDupBox.setManaged(false);
            mergeDeleteExactButton.setDisable(true);
            return;
        }
        group = sortPathsByImageAreaDesc(group);
        exactDupBox.setVisible(true);
        exactDupBox.setManaged(true);
        mergeDeleteExactButton.setDisable(false);
        if (duplicateScanRunning.get()) {
            mergeDeleteExactButton.setDisable(true);
        }
        boolean matched = false;
        for (Path p : group) {
            int d = distanceByPath.getOrDefault(p, 0);
            String relation = sel.equals(p)
                    ? "選択中"
                    : "類似 " + formatDHashDistanceLabel(d, sameByteIdentity(sel, p, eg));
            RadioButton rb = new RadioButton(p.getFileName().toString() + "  " + formatImagePixelSize(p) + "  [" + relation + "]");
            rb.setUserData(p);
            rb.setToggleGroup(tg);
            rb.addEventFilter(MouseEvent.MOUSE_PRESSED, ev -> {
                if (ev.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                if (tg.getSelectedToggle() == rb) {
                    return;
                }
                ev.consume();
                Platform.runLater(() -> tg.selectToggle(rb));
            });
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
    }

    /**
     * 類似チェックのサムネ列・拡大表示用。キャンバス未登録なら null。
     */
    private Label buildDuplicateCanvasLineLabel(Path path, double maxWidth) {
        List<String> cv =
                new ArrayList<>(galleryCanvasLayoutsByFileName.getOrDefault(path.getFileName().toString(), Set.of()));
        Collections.sort(cv);
        if (cv.isEmpty()) {
            return null;
        }
        Label L = new Label("キャンバス: " + String.join(", ", cv));
        L.setWrapText(true);
        L.setMaxWidth(maxWidth);
        L.setStyle("-fx-font-size: 11px; -fx-text-fill: #5a5340;");
        return L;
    }

    private VBox buildDuplicatePreviewTile(DuplicateOther o) {
        Path p = o.path();
        ImageView iv = new ImageView(loadThumbnail(p, 120, 120));
        iv.setPreserveRatio(true);
        iv.setFitWidth(120);
        iv.setFitHeight(120);
        String line3 = formatDHashDistanceLabel(o.distance(), o.sameByteIdentityAsReference());
        Label cap = new Label(p.getFileName().toString() + "\n" + formatImagePixelSize(p) + "\n" + line3);
        cap.setWrapText(true);
        cap.setMaxWidth(136);
        cap.setStyle("-fx-font-size: 11px;");
        VBox tile = new VBox(4, iv, cap);
        Label canvasLine = buildDuplicateCanvasLineLabel(p, 136);
        if (canvasLine != null) {
            canvasLine.setStyle("-fx-font-size: 10px; -fx-text-fill: #5a5340;");
            tile.getChildren().add(canvasLine);
        }
        tile.setStyle("-fx-padding: 6; -fx-background-color: #f8f6f0; -fx-background-radius: 4;");
        tile.setFocusTraversable(false);
        iv.setFocusTraversable(false);
        return tile;
    }

    /** 別ウィンドウは出さず、類似チェックダイアログ内の {@link StackPane} に重ねる（Linux/GTK 警告回避）。 */
    private void showDuplicateExpandOverlay(StackPane expandOverlay, Path selected, List<DuplicateOther> others) {
        if (selected == null) {
            GazoFx.showWarn("比較表示", "候補画像を選択してください。");
            return;
        }
        if (others == null || others.isEmpty()) {
            GazoFx.showWarn("比較表示", "表示できる他の候補がありません。");
            return;
        }
        Runnable close = () -> {
            expandOverlay.setVisible(false);
            expandOverlay.setManaged(false);
            expandOverlay.getChildren().clear();
        };

        expandOverlay.getChildren().clear();
        Region backdrop = new Region();
        backdrop.setStyle("-fx-background-color: rgba(0,0,0,0.35);");
        backdrop.setMaxWidth(Double.MAX_VALUE);
        backdrop.setMaxHeight(Double.MAX_VALUE);
        backdrop.setOnMouseClicked(e -> close.run());

        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(buildDuplicateExpandTile(selected, null, true, false));
        for (DuplicateOther o : others) {
            row.getChildren().add(buildDuplicateExpandTile(o.path(), o.distance(), false, o.sameByteIdentityAsReference()));
        }
        ScrollPane scroll = new ScrollPane(row);
        scroll.setFitToHeight(true);
        scroll.setPrefViewportHeight(520);
        scroll.setPrefViewportWidth(920);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Button closeBtn = new Button("閉じる");
        closeBtn.setOnAction(ev -> close.run());
        HBox bottom = new HBox(closeBtn);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(8, 0, 0, 0));
        Label title = new Label("重複・類似一覧（距離は左の選択画像から）");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        VBox panel = new VBox(10, title, scroll, bottom);
        panel.setPadding(new Insets(14));
        panel.setStyle("-fx-background-color: #f8f6f0; -fx-background-radius: 8;");
        panel.setMaxWidth(960);
        panel.setMaxHeight(640);

        expandOverlay.getChildren().addAll(backdrop, panel);
        StackPane.setAlignment(panel, Pos.CENTER);
        expandOverlay.setMouseTransparent(false);
        expandOverlay.setVisible(true);
        expandOverlay.setManaged(true);
    }

    private VBox buildDuplicateExpandTile(
            Path path, Integer distanceOrNull, boolean isSelected, boolean sameBytesAsReference) {
        ImageView iv = new ImageView();
        iv.setPreserveRatio(true);
        iv.setFitWidth(320);
        iv.setFitHeight(320);
        String line3 = isSelected
                ? "選択"
                : formatDHashDistanceLabel(distanceOrNull != null ? distanceOrNull : 0, sameBytesAsReference);
        Label cap = new Label(path.getFileName().toString() + "\n—\n" + line3);
        cap.setWrapText(true);
        cap.setMaxWidth(340);
        cap.setStyle("-fx-font-size: 12px;");
        Label canvasLine = buildDuplicateCanvasLineLabel(path, 340);
        VBox column = new VBox(8, cap, iv);
        if (canvasLine != null) {
            column.getChildren().add(canvasLine);
        }
        galleryImageLoadExecutor.execute(() -> {
            byte[] raw;
            try {
                raw = readThumbnailFileBytes(path);
            } catch (IOException ex) {
                raw = null;
            }
            int[] wh = readImagePixelDimensionsImageIo(path);
            final byte[] rawFinal = raw;
            final int[] whFinal = wh;
            Platform.runLater(() -> {
                if (rawFinal != null && rawFinal.length > 0) {
                    iv.setImage(new Image(new ByteArrayInputStream(rawFinal), 320, 320, true, true));
                }
                if (whFinal != null) {
                    cap.setText(path.getFileName().toString() + "\n" + whFinal[0] + "×" + whFinal[1] + "\n" + line3);
                } else {
                    cap.setText(path.getFileName().toString() + "\n" + formatImagePixelSize(path) + "\n" + line3);
                }
            });
        });
        return column;
    }

    /**
     * キャンバス機能の統合 UI は {@link CanvasHubDialog} を参照。
     */
    private void showCanvasHubDialog(Stage owner) {
        CanvasHubDialog.open(this, owner);
        refreshHomeCanvasPreviewAfterHubClose();
    }

    private void showCanvasHubDialogRandom(Stage owner) {
        CanvasHubDialog.open(this, owner, true);
        refreshHomeCanvasPreviewAfterHubClose();
    }

    /**
     * キャンバス Hub を閉じたあと、メイン「キャンバス」タブのプレビューを Vault の最新内容で描き直す。
     * 表示中のキャンバス名が分かるときはそのレイアウトのみ再読込し、無効なら従来どおりランダム 1 件を表示する。
     */
    private void refreshHomeCanvasPreviewAfterHubClose() {
        if (vault == null || homeCanvasPreviewPane == null || homeCanvasLayoutLabel == null) {
            return;
        }
        try {
            Set<String> names = vault.listCanvasLayouts();
            if (names.isEmpty()) {
                homeCanvasPreviewPane.getChildren().clear();
                homeCanvasLayoutLabel.setText("（キャンバスがありません）");
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
            String layoutName = parseHomeCanvasLayoutName(homeCanvasLayoutLabel);
            if (layoutName == null || !names.contains(layoutName)) {
                refreshRandomCanvasPreview(homeCanvasPreviewPane, homeCanvasLayoutLabel, false);
                return;
            }
            renderHomeCanvasPreviewForLayout(homeCanvasPreviewPane, homeCanvasLayoutLabel, layoutName);
        } catch (IOException e) {
            GazoFx.showError("キャンバス表示", e.getMessage());
        }
    }

    private void renderHomeCanvasPreviewForLayout(Pane canvasPane, Label layoutNameLabel, String layoutName)
            throws IOException {
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
            renderCanvasItems(canvasPane, layoutName, false, null, null, null, null, null);
            layoutNameLabel.setText("「" + layoutName + "」");
        } finally {
            canvasSelection.clear();
            canvasSelection.addAll(backup);
        }
        Platform.runLater(this::fitHomeCanvasPreview);
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

    /**
     * キャンバス編集の「取っ手」を、キャンバス内容の外側（スクロール枠の右下）に置く。
     * 取っ手をキャンバス Pane 上に置くと、大きいキャンバスでは画面外にあり、縮小表示では極小になるため見失いやすい。
     */
    /** キャンバス編集でドラッグ／数値指定の両方に使う下限（論理ピクセル）。 */
    static final double CANVAS_EDIT_MIN_WIDTH = 600.0;
    static final double CANVAS_EDIT_MIN_HEIGHT = 450.0;

    void installCanvasResizeHandle(StackPane viewportOverlay, Pane canvas, Runnable onResizeFinished) {
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
            renderHomeCanvasPreviewForLayout(canvasPane, layoutNameLabel, layoutName);
        } catch (IOException e) {
            GazoFx.showError("キャンバス表示", e.getMessage());
        }
    }

    void renderCanvasItems(
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

    void renderCanvasItems(
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
        List<Path> pathsSnapshot = new ArrayList<>(canvasSelection);
        int index = 0;
        for (Path path : pathsSnapshot) {
            VBox item = createCanvasItem(path, layoutName, interactive, sizeRatio, null, pathsSnapshot, onTransformPersisted);
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
        if (!showFileName) {
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
        if (imagePath == null || !canvasSelection.contains(imagePath)) {
            return;
        }
        canvasSelection.remove(imagePath);
        canvasSelection.add(imagePath);
        try {
            vault.saveCanvasSelectionOrder(layoutName, new ArrayList<>(canvasSelection));
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
            var tf = vault.getCanvasTransform(layoutName, imagePath);
            if (tf != null) {
                rotation = tf.rotation();
            }
            vault.setCanvasTransform(layoutName, imagePath, logicalScale, rotation);
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
            var tf = vault.getCanvasTransform(layoutName, imagePath);
            if (tf != null) {
                scale = tf.scale();
            }
            scale = Math.max(0.5, Math.min(2.4, scale));
            vault.setCanvasTransform(layoutName, imagePath, scale, rotationDegrees);
        } catch (IOException e) {
            GazoFx.showWarn("キャンバス変形保存エラー", e.getMessage());
        }
    }

    /** スライダー表示用に角度を -180〜180 度付近へ正規化する。 */
    static double normalizeRotationForSlider(double degrees) {
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
                vault.setCanvasPosition(layoutName, imagePath, node.getLayoutX() / cw, node.getLayoutY() / ch);
            } catch (IOException e) {
                GazoFx.showWarn("キャンバス保存エラー", e.getMessage());
            }
        });
    }

    private void makeCanvasTransformable(VBox node, Path imagePath, String layoutName, Consumer<Path> onTransformPersisted) {
        Runnable persistTransform = () -> {
            try {
                vault.setCanvasTransform(layoutName, imagePath, (Double) node.getProperties().get("gazoScale"),
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

    @Override
    public void stop() {
        if (vault != null) {
            vault.close();
        }
    }
}
