package com.example.gazo;

import com.example.gazo.cli.CliImport;
import com.example.gazo.vault.GazoVaultService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
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
import javafx.scene.control.Tooltip;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.transform.Scale;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.io.IOException;
import java.nio.file.Path;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 登録画像をスナップ写真風に表示し、Cryptomator 互換 Vault に保存する JavaFX アプリ。
 */
public final class GazoApp extends Application {
    GazoVaultService vault;
    private VaultConnection currentVaultConnection;
    private Stage primaryStage;
    /** 類似チェックダイアログを二重に開かないため。 */
    private Stage openDuplicateReportStage;
    /**
     * ギャラリーから「この画像／選択画像の重複・類似を検索」で開くときの注目パス。
     * {@link DuplicateCheckWindow} を開く直前にセットする。
     */
    private Set<Path> pendingDuplicateFocusPaths;
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
    /** 仮想スクロールの列・測定・直近ウィンドウ。{@link GalleryVirtualWindow} が更新する。 */
    private final GalleryVirtualState galleryVirtualState = new GalleryVirtualState();
    private VideoTabPanel videoTab;
    private CanvasEditor canvasEditor;
    /** メイン画像一覧のカード・仮想ウィンドウ・可視サムネ。 */
    private ImageGalleryCards imageGallery;
    private final VaultUnlockFlow vaultUnlock = new VaultUnlockFlow();

    private ScrollPane imageScrollPane;
    /**
     * 重複整理ダイアログのマージ後グラフ再計算など、CPU 負荷の高い処理をキューに積み、
     * 同時実行を 1 に抑える（複数ジョブが重なるとディスク・CPU が膨らむのを防ぐ）。
     */
    private final ExecutorService vaultHeavySerialExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gazo-vault-heavy-serial");
        t.setDaemon(true);
        return t;
    });
    private Label vaultPathLabel;
    private Label syncStatusLabel;
    /** インポート中のみファイル名を表示（通常は空） */
    private Label importStatusLabel;
    /** 画像タブツールバー: フィルター後の表示数と Vault 内の画像総数 */
    private Label imageGalleryCountLabel;
    static final List<String> LAYOUT_PRESETS = List.of("コラージュ風", "整列風");
    /** 画像タブ: タグ・表示・検索・一覧サイズと設定の保存。 */
    private GalleryToolbar galleryToolbar;
    final Set<Path> canvasSelection = new LinkedHashSet<>();
    final Set<Path> listCheckedSelection = new LinkedHashSet<>();
    /** メニュー「操作」のタグ一括・削除。{@link #updateGalleryListSelectionDependentControls()} で無効化する。 */
    private MenuItem menuGalleryTagBulkAdd;
    private MenuItem menuGalleryTagBulkRemove;
    private MenuItem menuGalleryDeleteCheckedImages;
    /** 画像一覧ツールバーの「選択を削除」。{@link #updateGalleryListSelectionDependentControls()} で無効化する。 */
    private Button galleryDeleteCheckedButton;
    /** 実ファイル削除を遅延させる予約。重複整理ダイアログ終了時・アプリ終了時にまとめて削除する。 */
    private final PendingDeletePaths pendingDeletePaths = new PendingDeletePaths();

    /** メイン「キャンバス」タブのプレビュー縮小表示用（ビューポートに合わせる） */
    private ScrollPane homeCanvasPreviewScroll;
    private Pane homeCanvasPreviewPane;
    private Label homeCanvasLayoutLabel;
    private Scale homeCanvasPreviewScale;
    private Group homeCanvasPreviewHolder;
    private HomeCanvasPreview homeCanvasPreview;
    private MainWindowVaultActions vaultActions;
    private final VaultImageLoader imageLoader = new VaultImageLoader(() -> vault);
    private GalleryListTagActions galleryTagActions;

    public static void main(String[] args) {
        CliImport.tryRun(args);
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        vaultUnlock.prepareStageShellBeforeVaultUnlock(stage);
        VaultConnection initialConnection = VaultPathStore.loadInitialVaultConnection();
        requestAndOpenInitialVault(stage, initialConnection);
    }

    private void requestAndOpenInitialVault(Stage stage, VaultConnection preferredConnection) {
        VaultOpenRequest initialRequest = buildInitialOpenRequest(stage, preferredConnection);
        if (initialRequest != null) {
            openInitialVault(stage, initialRequest);
            return;
        }
        // WebDAV パスワード入力のキャンセル時は終了せず、接続先選択へ戻す。
        var selected = VaultConnectionDialogs.promptForOpenRequest(stage, preferredConnection);
        if (selected.isPresent()) {
            openInitialVault(stage, selected.get());
        }
    }

    private VaultOpenRequest buildInitialOpenRequest(Stage stage, VaultConnection connection) {
        if (connection.isWebDav()) {
            Optional<char[]> stored = VaultPathStore.loadStoredWebDavPassword(connection);
            if (stored.isPresent()) {
                return VaultOpenRequest.webDav(
                        connection, stored.get(), VaultOpenRequest.WebDavPasswordPersistence.UNCHANGED);
            }
            var pwdOpt = VaultConnectionDialogs.promptForWebDavPassword(stage, connection);
            if (pwdOpt.isEmpty()) {
                return null;
            }
            return VaultOpenRequest.webDav(
                    connection, pwdOpt.get(), VaultOpenRequest.WebDavPasswordPersistence.UNCHANGED);
        }
        return VaultOpenRequest.local(connection);
    }

    private void openInitialVault(Stage stage, VaultOpenRequest initialRequest) {
        VaultConnection conn = initialRequest.connection();
        char[] pwdCopy = conn.isWebDav() ? initialRequest.webDavPassword() : null;
        VaultOpenRequest.WebDavPasswordPersistence persistence = initialRequest.webDavPasswordPersistence();
        vaultUnlock.openVaultAsync(
                stage,
                initialRequest,
                err -> {
                    if (err instanceof VaultUnlockCancelledException) {
                        if (pwdCopy != null) {
                            Arrays.fill(pwdCopy, '\0');
                        }
                        Platform.exit();
                        return;
                    }
                    if (err != null) {
                        if (pwdCopy != null) {
                            Arrays.fill(pwdCopy, '\0');
                        }
                        GazoFx.showError("アルバムを開けませんでした", err.getMessage());
                        Platform.exit();
                        return;
                    }
                    currentVaultConnection = conn;
                    VaultPathStore.saveLastVaultConnection(conn, pwdCopy, persistence);
                    if (pwdCopy != null) {
                        Arrays.fill(pwdCopy, '\0');
                    }
                    continueApplicationAfterVaultOpened(stage);
                },
                this::adoptUnlockedVault,
                () -> {
                    var switched = VaultConnectionDialogs.promptForOpenRequest(stage, initialRequest.connection());
                    if (switched.isPresent()) {
                        openInitialVault(stage, switched.get());
                    } else {
                        // 接続先切替をキャンセルした場合は、元の接続先の入力へ戻す。
                        requestAndOpenInitialVault(stage, initialRequest.connection());
                    }
                });
    }

    private void adoptUnlockedVault(GazoVaultService newVault) throws Exception {
        if (vault != null) {
            vault.close();
        }
        vault = newVault;
        reloadCanvasSelectionFromVault("default");
    }

    private void continueApplicationAfterVaultOpened(Stage stage) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flushPendingDeletes(false);
            if (vault != null) {
                vault.close();
            }
            if (imageGallery != null) {
                imageGallery.shutdown();
            }
            vaultHeavySerialExecutor.shutdownNow();
        }));

        primaryStage = stage;

        VaultPathStore.GallerySettings gallerySettings = VaultPathStore.loadGallerySettings();

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

        galleryToolbar = new GalleryToolbar(gallerySettings, () -> vault, this::refreshGallery, this::refreshVideoList);
        galleryTagActions =
                new GalleryListTagActions(
                        new GalleryListTagActions.Host(
                                () -> vault,
                                listCheckedSelection,
                                this::refreshTagFilterOptions,
                                this::refreshGallery));

        ScrollPane imageScroll = new ScrollPane(galleryVirtualRoot);
        imageScroll.setFitToWidth(true);
        imageScrollPane = imageScroll;
        imageGallery =
                new ImageGalleryCards(
                        new ImageGalleryCardsHost(
                                () -> galleryModelPaths,
                                () -> galleryTagMap,
                                () -> galleryCanvasLayoutsByFileName,
                                () -> galleryToolbar.listViewSize(),
                                listCheckedSelection,
                                () -> galleryToolbar.showFileName(),
                                () -> galleryToolbar.showDate(),
                                () -> galleryToolbar.showTags(),
                                imageLoader::loadThumbnail,
                                new ImageGalleryCardActions() {
                                    @Override
                                    public void openOriginalViewer(Path path) {
                                        GazoApp.this.showOriginalImageViewer(path);
                                    }

                                    @Override
                                    public void editTags(Path path) {
                                        galleryTagActions.editTags(path);
                                    }

                                    @Override
                                    public void bulkAddTagsToSelection() {
                                        galleryTagActions.addTagsToCanvasSelection();
                                    }

                                    @Override
                                    public void bulkRemoveTagsFromSelection() {
                                        galleryTagActions.removeTagsFromCanvasSelection();
                                    }

                                    @Override
                                    public void duplicateCheckThisImage(Path path) {
                                        pendingDuplicateFocusPaths = Set.of(path);
                                        showDuplicateReport();
                                    }

                                    @Override
                                    public void duplicateCheckSelection() {
                                        pendingDuplicateFocusPaths = new LinkedHashSet<>(listCheckedSelection);
                                        showDuplicateReport();
                                    }

                                    @Override
                                    public void onGalleryCardSelectionChanged() {
                                        updateGalleryListSelectionDependentControls();
                                    }
                                }),
                        gallery,
                        imageScroll,
                        galleryVirtualRoot,
                        galleryVirtualTopSpacer,
                        galleryVirtualBottomSpacer,
                        galleryVirtualState);
        imageScroll.vvalueProperty().addListener((obs, ov, nv) -> imageGallery.onVerticalScroll(ov, nv));
        imageScroll.hvalueProperty().addListener((obs, ov, nv) -> imageGallery.onHorizontalScroll(ov, nv));
        imageScroll.viewportBoundsProperty().addListener((obs, ov, nv) -> imageGallery.onViewportBoundsChanged(ov, nv));

        videoTab =
                new VideoTabPanel(
                        new VideoTabHost(
                                () -> vault,
                                stage,
                                this::galleryActiveTagFiltersCopy,
                                pendingDeletePaths::contains,
                                this::setImportStatusLabel,
                                this::clearImportStatusLabel,
                                this::refreshTagFilterOptions));
        canvasEditor =
                new CanvasEditor(
                        new CanvasEditorHost(
                                () -> vault,
                                canvasSelection,
                                () -> galleryToolbar.showFileName(),
                                imageLoader::loadThumbnail,
                                this::showOriginalImageViewer));
        ScrollPane videoScroll = videoTab.getScrollPane();

        vaultActions =
                new MainWindowVaultActions(
                        new MainWindowVaultActions.Host(
                                () -> vault,
                                () -> currentVaultConnection,
                                conn -> {
                                    currentVaultConnection = conn;
                                },
                                vaultUnlock,
                                this::adoptUnlockedVault,
                                this::updateVaultPathLabel,
                                () -> {
                                    refreshTagFilterOptions();
                                    refreshGallery();
                                    refreshVideoList();
                                },
                                this::setImportStatusLabel,
                                this::clearImportStatusLabel,
                                this::setMainWindowBusy,
                                this::refreshGallery));
        GazoMenuBarFactory.Result menus =
                GazoMenuBarFactory.create(
                        () -> vaultActions.addImages(stage),
                        () -> videoTab.addVideos(stage),
                        vaultActions::rebuildAllThumbnails,
                        this::showDuplicateReport,
                        galleryTagActions::addTagsToCanvasSelection,
                        galleryTagActions::removeTagsFromCanvasSelection,
                        () -> deleteCheckedImagesFromVault(stage),
                        () -> vaultActions.restoreRecentlyDeletedImages(stage),
                        () -> vaultActions.changeVaultPath(stage),
                        GazoFx::showConflictThresholdSettingsDialog,
                        this::updateGalleryListSelectionDependentControls);
        menuGalleryTagBulkAdd = menus.menuGalleryTagBulkAdd();
        menuGalleryTagBulkRemove = menus.menuGalleryTagBulkRemove();
        menuGalleryDeleteCheckedImages = menus.menuGalleryDeleteCheckedImages();
        MenuBar menuBar = menus.menuBar();
        ImageGalleryLeadingButtons.Result leading =
                ImageGalleryLeadingButtons.create(
                        this::selectAllVisibleImages,
                        this::clearListCheckedSelection,
                        () -> deleteCheckedImagesFromVault(stage),
                        () -> showCanvasHubDialog(stage),
                        () -> showCanvasHubDialogRandom(stage),
                        this::updateGalleryListSelectionDependentControls);
        galleryDeleteCheckedButton = leading.deleteChecked();
        imageGalleryCountLabel = new Label("表示 0 / 全 0 枚");
        imageGalleryCountLabel.setStyle("-fx-text-fill: #4a5560;");
        Region imageToolbarSpacer = new Region();
        HBox.setHgrow(imageToolbarSpacer, Priority.ALWAYS);
        vaultPathLabel = new Label();
        vaultPathLabel.setMinWidth(0);
        vaultPathLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        syncStatusLabel = new Label("");
        syncStatusLabel.setStyle("-fx-text-fill: #4a5560;");
        importStatusLabel = new Label("");
        importStatusLabel.setMinWidth(0);
        importStatusLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        importStatusLabel.setStyle("-fx-text-fill: #4a5560;");
        updateVaultPathLabel();

        HBox imageToolbar = new HBox(10);
        imageToolbar
                .getChildren()
                .addAll(
                        leading.selectAll(),
                        leading.clearSelection(),
                        galleryDeleteCheckedButton,
                        leading.canvasHub(),
                        leading.randomHub());
        imageToolbar.getChildren().addAll(galleryToolbar.buildToolbarNodes());
        imageToolbar.getChildren().addAll(imageToolbarSpacer, imageGalleryCountLabel);
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
        homeCanvasLayoutLabel = new Label("—");
        homeCanvasLayoutLabel.setStyle("-fx-font-weight: bold;");
        homeCanvasPreview =
                new HomeCanvasPreview(
                        new HomeCanvasPreview.Host(
                                () -> vault,
                                canvasSelection,
                                canvasEditor,
                                homeCanvasPreviewScroll,
                                homeCanvasPreviewPane,
                                homeCanvasLayoutLabel,
                                homeCanvasPreviewScale,
                                homeCanvasPreviewHolder));
        homeCanvasPreviewScroll
                .viewportBoundsProperty()
                .addListener(
                        (obs, oldB, newB) -> {
                            if (newB != null) {
                                Platform.runLater(() -> homeCanvasPreview.fitToViewport());
                            }
                        });
        Button homeCanvasShuffleButton = new Button("別のキャンバス");
        homeCanvasShuffleButton.setOnAction(e -> homeCanvasPreview.refreshRandomPreview(true));
        Button homeCanvasSlideshowButton = new Button("スライドショーで開く");
        homeCanvasSlideshowButton.setOnAction(
                e -> showSlideshow(stage, HomeCanvasPreview.parseLayoutName(homeCanvasLayoutLabel)));
        Button homeCanvasEditButton = new Button("キャンバス編集で開く");
        homeCanvasEditButton.setTooltip(
                new Tooltip("キャンバスウィンドウを「キャンバス編集」タブで開きます（プレビュー中のキャンバスを初期選択）。"));
        homeCanvasEditButton.setOnAction(
                e -> {
                    CanvasHubDialog.open(this, stage, false, HomeCanvasPreview.parseLayoutName(homeCanvasLayoutLabel));
                    homeCanvasPreview.refreshAfterHubClose();
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
        Tab videosTab = new Tab("動画", videoScroll);
        videosTab.setClosable(false);
        mainTabs.getTabs().addAll(canvasTab, imageTab, videosTab);
        mainTabs.getSelectionModel().select(canvasTab);
        mainTabs.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == canvasTab) {
                homeCanvasPreview.refreshRandomPreview(false);
            }
        });

        HBox top = new HBox(menuBar);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(Insets.EMPTY);
        // メニューバーをウィンドウ上端に密着させる。
        top.setStyle("-fx-background-color: transparent; -fx-border-color: #d7d0c2; -fx-border-width: 0 0 1 0;");

        Region statusBarSpacer = new Region();
        HBox.setHgrow(statusBarSpacer, Priority.ALWAYS);
        Button switchConnectionButton = new Button("接続先切替…");
        switchConnectionButton.setOnAction(e -> {
            if (vaultActions != null && primaryStage != null) {
                vaultActions.changeVaultPath(primaryStage);
            }
        });
        HBox statusBar = new HBox(12, vaultPathLabel, syncStatusLabel, switchConnectionButton, statusBarSpacer, importStatusLabel);
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
        stage.setTitle("Gazo — アルバムに保存する画像ビューア");
        GazoFx.applyAppIcons(stage);
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        new VaultDnDImport(
                        new ImportDropHost(
                                () -> vault,
                                gallery,
                                () -> videoTab.getGallery(),
                                this::setImportStatusLabel,
                                this::clearImportStatusLabel,
                                () -> {
                                    refreshTagFilterOptions();
                                    refreshGallery();
                                    refreshVideoList();
                                }))
                .attachTo(rootStack);
        refreshTagFilterOptions();
        refreshGallery();
        refreshVideoList();
        // 起動直後にキャンバスプレビューを初期表示する。
        homeCanvasPreview.refreshRandomPreview(false);
        Platform.runLater(() -> homeCanvasPreview.fitToViewport());
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
        } catch (IllegalStateException e) {
            // 切替直後に一時的に未解錠扱いになるケースでは、選択を空として継続する。
            if ("Album is not unlocked".equals(e.getMessage())) {
                canvasSelection.clear();
                return;
            }
            throw e;
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
        SlideshowWindow.open(owner, vault, canvasSelection, canvasEditor, initialCanvasName);
    }

    private void updateVaultPathLabel() {
        if (vaultPathLabel != null && vault != null) {
            String mode = vault.isRemoteVault() ? "WebDAV" : "Local";
            vaultPathLabel.setText("アルバム(" + mode + "): " + vault.getVaultDisplayLocation());
            if (syncStatusLabel != null) {
                syncStatusLabel.setText(vault.syncStatusSummary());
            }
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

    private void setMainWindowBusy(boolean busy, String message) {
        if (appBusyMessageLabel != null) {
            appBusyMessageLabel.setText(message != null && !message.isBlank() ? message : "処理中…");
        }
        if (appBusyPane != null) {
            appBusyPane.setVisible(busy);
            appBusyPane.setManaged(busy);
        }
    }

    void refreshGallery() {
        updateVaultPathLabel();
        imageGallery.beginFullReload();
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
            imageGallery.applyVirtualWindow(true);
            imageGallery.refreshVisibleImagesNow();
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
    private void refreshVideoList() {
        if (videoTab != null) {
            videoTab.refreshList();
        }
    }

    private List<Path> listFilteredImages(Map<String, Set<String>> tagsByFile) throws IOException {
        return listFilteredImages(tagsByFile, vault.listImages());
    }

    private List<Path> listFilteredImages(Map<String, Set<String>> tagsByFile, List<Path> allImages) {
        return ImageGalleryListing.filterPaths(
                allImages,
                tagsByFile,
                galleryToolbar.activeTagFilters(),
                galleryToolbar.imageNameQuery(),
                pendingDeletePaths::contains);
    }

    /**
     * キャンバス「画像を追加」ダイアログ用。メインのタグ／検索欄とは独立して一覧を絞り込む。
     *
     * @param tagFilters 空ならタグでは絞らない。要素は実タグ名または {@link TagFilter#UNTAGGED_SENTINEL}。
     */
    List<Path> listImagesForCanvasAddPicker(Set<String> tagFilters, String filenameQuery) throws IOException {
        Map<String, Set<String>> tagsByFile = vault.tagsByFileName();
        List<Path> allImages = vault.listImages();
        String q = filenameQuery == null ? "" : filenameQuery.trim().toLowerCase();
        List<Path> out = new ArrayList<>();
        for (Path p : allImages) {
            if (pendingDeletePaths.contains(p)) {
                continue;
            }
            Set<String> tags = tagsByFile.getOrDefault(p.getFileName().toString(), Set.of());
            if (!TagFilter.matches(tagFilters, tags)) {
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
        return galleryToolbar.imageNameQuery();
    }

    /** キャンバス追加ダイアログの初期値用（メインのタグチェック）。 */
    LinkedHashSet<String> galleryActiveTagFiltersCopy() {
        return galleryToolbar.activeTagFiltersCopy();
    }

    private void refreshTagFilterOptions() {
        galleryToolbar.refreshTagFilterOptions();
    }

    boolean isShowFileNameOption() {
        return galleryToolbar.showFileName();
    }

    void setShowFileNameOption(boolean enabled) {
        galleryToolbar.setShowFileNameOption(enabled);
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
                    if (galleryToolbar.showFileName()) {
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
     * 画像タブでチェックしたファイルを Vault から削除する（重複整理の「あとで削除」とは別経路で即時削除）。
     */
    private void deleteCheckedImagesFromVault(Stage owner) {
        GalleryCheckedImagesDelete.confirmAndDelete(
                owner,
                vault,
                listCheckedSelection,
                canvasSelection,
                pendingDeletePaths::contains,
                () -> {
                    updateGalleryListSelectionDependentControls();
                    refreshTagFilterOptions();
                    refreshGallery();
                });
    }

    /** 一覧のチェック件数に応じて、タグ一括・チェック削除のメニューとツールバーボタンを有効/無効にする。 */
    private void updateGalleryListSelectionDependentControls() {
        boolean empty = listCheckedSelection.isEmpty();
        if (menuGalleryTagBulkAdd != null) {
            menuGalleryTagBulkAdd.setDisable(empty);
        }
        if (menuGalleryTagBulkRemove != null) {
            menuGalleryTagBulkRemove.setDisable(empty);
        }
        if (menuGalleryDeleteCheckedImages != null) {
            menuGalleryDeleteCheckedImages.setDisable(empty);
        }
        if (galleryDeleteCheckedButton != null) {
            galleryDeleteCheckedButton.setDisable(empty);
        }
    }

    private void selectAllVisibleImages() {
        try {
            listCheckedSelection.clear();
            listCheckedSelection.addAll(listFilteredImages());
            imageGallery.refreshSelectionStyles();
            updateGalleryListSelectionDependentControls();
        } catch (IOException e) {
            GazoFx.showError("全選択エラー", e.getMessage());
        }
    }

    private void clearListCheckedSelection() {
        listCheckedSelection.clear();
        imageGallery.refreshSelectionStyles();
        updateGalleryListSelectionDependentControls();
    }

    /** 予約された削除をまとめて実行する。 */
    private void flushPendingDeletes(boolean showUiError) {
        pendingDeletePaths.flushAll(vault, showUiError);
    }

    private void showDuplicateReport() {
        if (openDuplicateReportStage != null && openDuplicateReportStage.isShowing()) {
            openDuplicateReportStage.requestFocus();
            return;
        }
        final Set<Path> duplicateFocusForNextScan =
                pendingDuplicateFocusPaths == null || pendingDuplicateFocusPaths.isEmpty()
                        ? null
                        : new LinkedHashSet<>(pendingDuplicateFocusPaths);
        pendingDuplicateFocusPaths = null;
        DuplicateCheckHost host =
                new DuplicateCheckHost(
                        vault,
                        primaryStage,
                        imageGallery.imageLoadExecutor(),
                        vaultHeavySerialExecutor,
                        pendingDeletePaths::contains,
                        pendingDeletePaths::addAll,
                        this::flushPendingDeletes,
                        this::refreshTagFilterOptions,
                        this::refreshGallery,
                        this::refreshVideoList,
                        UserTags::parseCommaSeparated,
                        imageLoader::load);
        DuplicateCheckWindow.open(
                host,
                duplicateFocusForNextScan,
                s -> openDuplicateReportStage = s,
                () -> openDuplicateReportStage = null);
    }

    /**
     * キャンバス追加ダイアログなど、Vault サムネを使ったプレビュー用。
     */
    public Image thumbnailForPicker(Path path, int width, int height) {
        return imageLoader.loadThumbnail(path, width, height);
    }

    private void showOriginalImageViewer(Path startPath) {
        OriginalImageViewerWindow.open(
                new OriginalImageViewerHost(vault, galleryTagActions::editTags, imageLoader::loadOriginalImage),
                startPath,
                listFilteredImagesOrEmpty("画像ビューア"));
    }

    private void showOriginalImageViewer(Path startPath, List<Path> sourceImages) {
        OriginalImageViewerWindow.open(
                new OriginalImageViewerHost(vault, galleryTagActions::editTags, imageLoader::loadOriginalImage),
                startPath,
                sourceImages);
    }

    /**
     * キャンバス機能の統合 UI は {@link CanvasHubDialog} を参照。
     */
    private void showCanvasHubDialog(Stage owner) {
        CanvasHubDialog.open(this, owner);
        homeCanvasPreview.refreshAfterHubClose();
    }

    private void showCanvasHubDialogRandom(Stage owner) {
        CanvasHubDialog.open(this, owner, true);
        homeCanvasPreview.refreshAfterHubClose();
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
        canvasEditor.renderRandomPickCanvas(canvas, paths);
    }

    void installCanvasResizeHandle(StackPane viewportOverlay, Pane canvas, Runnable onResizeFinished) {
        canvasEditor.installCanvasResizeHandle(viewportOverlay, canvas, onResizeFinished);
    }

    void copyCanvasLayoutState(String sourceLayout, String targetLayout) throws IOException {
        canvasEditor.copyCanvasLayoutState(sourceLayout, targetLayout);
    }

    void renderCanvasItems(
            Pane canvas,
            String layoutName,
            boolean interactive,
            Path selectedPath,
            Consumer<Path> onSelect,
            Consumer<Path> onRemove) {
        canvasEditor.renderCanvasItems(canvas, layoutName, interactive, selectedPath, onSelect, onRemove);
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
        canvasEditor.renderCanvasItems(
                canvas,
                layoutName,
                interactive,
                selectedPath,
                onSelect,
                onRemove,
                onTransformPersisted,
                onBringToFront);
    }

    void autoLayoutCanvas(Pane canvas, String layoutName, String presetName, double overlapTuning, double neatTuning) {
        canvasEditor.autoLayoutCanvas(canvas, layoutName, presetName, overlapTuning, neatTuning);
    }

    public void bringCanvasImageToFront(Path imagePath, String layoutName) {
        canvasEditor.bringCanvasImageToFront(imagePath, layoutName);
    }

    public void applyCanvasImageLogicalScale(Path imagePath, String layoutName, double logicalScale) {
        canvasEditor.applyCanvasImageLogicalScale(imagePath, layoutName, logicalScale);
    }

    public void applyCanvasImageLogicalRotation(Path imagePath, String layoutName, double rotationDegrees) {
        canvasEditor.applyCanvasImageLogicalRotation(imagePath, layoutName, rotationDegrees);
    }

    @Override
    public void stop() {
        if (vault != null) {
            vault.close();
        }
    }
}