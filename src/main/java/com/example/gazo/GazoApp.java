package com.example.gazo;

import com.example.gazo.cli.CliImport;
import com.example.gazo.vault.GazoVaultService;
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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 登録画像をスナップ写真風に表示し、Cryptomator 互換 Vault に保存する JavaFX アプリ。
 */
public final class GazoApp extends Application {
    private static final String APP_DIR_NAME = ".gazo";
    private static final String DEFAULT_VAULT_DIR_NAME = "vault";
    private static final String CONFIG_FILE_NAME = "settings.properties";
    private static final String CONFIG_KEY_LAST_VAULT_PATH = "lastVaultPath";
    private static final double BASE_CANVAS_WIDTH = 2000.0;
    private static final double BASE_CANVAS_HEIGHT = 1400.0;
    /** クラスパス上のウィンドウアイコン（PNG）。差し替えはこのファイルを置き換える。 */
    private static final String APP_ICON_RESOURCE = "/com/example/gazo/app-icon.png";

    private GazoVaultService vault;
    private Stage primaryStage;
    private FlowPane gallery;
    private FlowPane videoGallery;
    private Label vaultPathLabel;
    /** 絞り込みに使うタグ（正規化済み・小文字）。空なら「すべて表示」。複数指定時は AND（すべて含む）。 */
    private final LinkedHashSet<String> activeTagFilters = new LinkedHashSet<>();
    private FlowPane tagFilterPane;
    private boolean showFileName = true;
    private boolean showDate = true;
    private boolean showTags = true;
    private static final List<String> LAYOUT_PRESETS = List.of("コラージュ風", "整列風");
    private static final List<String> LIST_VIEW_SIZE_OPTIONS = List.of("小", "中", "大");
    private final Set<Path> canvasSelection = new LinkedHashSet<>();
    private final Set<Path> listCheckedSelection = new LinkedHashSet<>();
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
        Path defaultVaultDir = loadInitialVaultPath();
        try {
            openVault(stage, defaultVaultDir);
        } catch (Exception e) {
            showError("Vault を開けませんでした", e.getMessage());
            Platform.exit();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (vault != null) {
                vault.close();
            }
        }));

        primaryStage = stage;

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
        MenuItem duplicateMenu = new MenuItem("重複チェック");
        duplicateMenu.setOnAction(e -> showDuplicateReport());
        MenuItem bulkAddMenu = new MenuItem("タグ一括追加");
        bulkAddMenu.setOnAction(e -> addTagsToCanvasSelection());
        MenuItem bulkRemoveMenu = new MenuItem("タグ一括削除");
        bulkRemoveMenu.setOnAction(e -> removeTagsFromCanvasSelection());
        MenuItem changeVaultMenu = new MenuItem("Vault変更…");
        changeVaultMenu.setOnAction(e -> changeVaultPath(stage));
        Menu actionsMenu = new Menu("操作");
        actionsMenu.getItems().addAll(
                addMenu,
                addVideosMenu,
                duplicateMenu,
                new SeparatorMenuItem(),
                bulkAddMenu,
                bulkRemoveMenu,
                new SeparatorMenuItem(),
                changeVaultMenu);
        MenuBar menuBar = new MenuBar(actionsMenu);
        Button selectAll = new Button("全選択");
        selectAll.setOnAction(e -> selectAllVisibleImages());
        Button clearSelection = new Button("クリア");
        clearSelection.setOnAction(e -> clearListCheckedSelection());
        Button canvasHub = new Button("キャンバス");
        canvasHub.setOnAction(e -> showCanvasHubDialog(stage));
        tagFilterPane = new FlowPane(8, 4);
        tagFilterPane.setPrefWrapLength(380);
        ScrollPane tagFilterScroll = new ScrollPane(tagFilterPane);
        tagFilterScroll.setFitToWidth(true);
        tagFilterScroll.setPrefViewportHeight(36);
        tagFilterScroll.setMaxHeight(44);
        tagFilterScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        tagFilterScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tagFilterScroll.setMinViewportWidth(200);
        tagFilterScroll.setMaxWidth(440);
        Button clearTagFiltersBtn = new Button("解除");
        clearTagFiltersBtn.setTooltip(new Tooltip("タグの絞り込みを解除"));
        clearTagFiltersBtn.setOnAction(e -> {
            activeTagFilters.clear();
            for (Node n : tagFilterPane.getChildren()) {
                if (n instanceof CheckBox cb) {
                    cb.setSelected(false);
                }
            }
            refreshGallery();
            refreshVideoList();
        });
        CheckBox fileNameToggle = new CheckBox("ファイル名");
        fileNameToggle.setSelected(true);
        fileNameToggle.selectedProperty().addListener((obs, oldV, newV) -> {
            showFileName = newV;
            refreshGallery();
        });
        CheckBox dateToggle = new CheckBox("日付");
        dateToggle.setSelected(true);
        dateToggle.selectedProperty().addListener((obs, oldV, newV) -> {
            showDate = newV;
            refreshGallery();
        });
        CheckBox tagToggle = new CheckBox("タグ");
        tagToggle.setSelected(true);
        tagToggle.selectedProperty().addListener((obs, oldV, newV) -> {
            showTags = newV;
            refreshGallery();
        });
        ComboBox<String> listSizeCombo = new ComboBox<>();
        listSizeCombo.getItems().setAll(LIST_VIEW_SIZE_OPTIONS);
        listSizeCombo.setValue(listViewSize);
        listSizeCombo.setOnAction(e -> {
            String selected = listSizeCombo.getValue();
            listViewSize = selected == null ? "中" : selected;
            refreshGallery();
        });
        vaultPathLabel = new Label();
        updateVaultPathLabel();

        HBox imageToolbar = new HBox(
                10,
                selectAll,
                clearSelection,
                canvasHub,
                new Label("タグ:"),
                clearTagFiltersBtn,
                tagFilterScroll,
                new Label("表示:"),
                fileNameToggle,
                dateToggle,
                tagToggle,
                new Label("一覧サイズ:"),
                listSizeCombo);
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
        HBox homeCanvasBar = new HBox(12, new Label("キャンバス:"), homeCanvasLayoutLabel, homeCanvasShuffleButton);
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

        HBox top = new HBox(10, menuBar);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10));
        top.setStyle("-fx-background-color: rgba(255,255,255,0.72); -fx-border-color: #d7d0c2; -fx-border-width: 0 0 1 0;");

        HBox statusBar = new HBox(vaultPathLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(6, 10, 6, 10));
        statusBar.setStyle("-fx-background-color: rgba(255,255,255,0.78); -fx-border-color: #d7d0c2; -fx-border-width: 1 0 0 0;");

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(mainTabs);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 920, 680);
        stage.setTitle("Gazo — 暗号化フォルダに保存する写真ビューア (JavaFX)");
        applyAppIcons(stage);
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        enableImageDragAndDrop(root);
        refreshTagFilterOptions();
        refreshGallery();
        refreshVideoList();
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

    private static void applyAppIcons(Stage stage) {
        InputStream in = GazoApp.class.getResourceAsStream(APP_ICON_RESOURCE);
        if (in == null) {
            return;
        }
        try (in) {
            stage.getIcons().add(new Image(in));
        } catch (IOException ignored) {
            // アイコン読み込み失敗時は OS 既定のアイコンのまま
        }
    }

    private static void bindAppIconToDialog(Dialog<?> dialog) {
        dialog.setOnShown(e -> {
            Scene s = dialog.getDialogPane().getScene();
            if (s != null && s.getWindow() instanceof Stage) {
                applyAppIcons((Stage) s.getWindow());
            }
        });
    }

    private void openVault(Stage stage, Path vaultDir) throws IOException, MasterkeyLoadingFailedException {
        GazoVaultService newVault = new GazoVaultService(vaultDir);
        unlockOrCreate(stage, newVault);
        if (vault != null) {
            vault.close();
        }
        vault = newVault;
        saveLastVaultPath(vault.getVaultPath());
        reloadCanvasSelectionFromVault("default");
    }

    private void reloadCanvasSelectionFromVault(String canvasName) {
        if (vault == null) {
            return;
        }
        try {
            List<Path> paths = vault.listCanvasSelectionOrder(canvasName);
            canvasSelection.clear();
            canvasSelection.addAll(paths);
        } catch (IOException e) {
            showWarn("キャンバス選択の読込", e.getMessage());
        }
    }

    /** キャンバス選択の並びを Vault に書き込む（トーストなし）。 */
    private void persistCanvasSelectionToVault(String canvasName) {
        if (vault == null) {
            return;
        }
        try {
            vault.saveCanvasSelectionOrder(canvasName, new ArrayList<>(canvasSelection));
        } catch (IOException e) {
            showError("保存エラー", e.getMessage());
        }
    }

    /** 現在のキャンバス画像一覧を指定キャンバス名で Vault に上書きし、完了を通知する。 */
    private void persistCanvasSelectionToVaultWithFeedback(String canvasName) {
        if (vault == null) {
            return;
        }
        try {
            vault.saveCanvasSelectionOrder(canvasName, new ArrayList<>(canvasSelection));
            showWarn("上書き保存", "「" + canvasName + "」の画像一覧を保存しました。");
        } catch (IOException e) {
            showError("保存エラー", e.getMessage());
        }
    }

    private void showSlideshow(Stage owner) {
        if (canvasSelection.isEmpty()) {
            showWarn("スライドショー", "キャンバスに画像を追加してください。");
            return;
        }
        final List<String> layoutNames;
        try {
            layoutNames = new ArrayList<>(vault.listCanvasLayouts());
        } catch (IOException e) {
            showError("スライドショー", e.getMessage());
            return;
        }
        if (layoutNames.isEmpty()) {
            showWarn("スライドショー", "キャンバスがありません。");
            return;
        }
        List<Path> selectionBackup = new ArrayList<>(canvasSelection);
        AtomicInteger idx = new AtomicInteger(0);
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
        applyAppIcons(slideStage);
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
            showError("Vault 変更エラー", e.getMessage());
        }
    }

    private void unlockOrCreate(Stage stage, GazoVaultService targetVault) throws IOException, MasterkeyLoadingFailedException {
        if (!targetVault.vaultExists()) {
            char[] pass = promptPasswordTwice("新しい Vault を作成", "パスワードを設定してください");
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
            char[] pass = promptPassword("Vault のロックを解除", "パスワードを入力してください");
            if (pass == null) {
                throw new IllegalStateException("キャンセルされました");
            }
            try {
                targetVault.unlock(new String(pass));
                Arrays.fill(pass, '\0');
                return;
            } catch (InvalidPassphraseException e) {
                Arrays.fill(pass, '\0');
                showWarn("解除できません", "パスワードが正しくありません。");
            }
        }
    }

    private Path appConfigDir() {
        return Paths.get(System.getProperty("user.home"), APP_DIR_NAME);
    }

    private Path appConfigFile() {
        return appConfigDir().resolve(CONFIG_FILE_NAME);
    }

    private Path defaultVaultPath() {
        return appConfigDir().resolve(DEFAULT_VAULT_DIR_NAME);
    }

    private Path loadInitialVaultPath() {
        Path defaultPath = defaultVaultPath();
        Path file = appConfigFile();
        if (!Files.exists(file)) {
            return defaultPath;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            String raw = properties.getProperty(CONFIG_KEY_LAST_VAULT_PATH, "").trim();
            if (raw.isEmpty()) {
                return defaultPath;
            }
            return Paths.get(raw);
        } catch (Exception ignored) {
            return defaultPath;
        }
    }

    private void saveLastVaultPath(Path vaultPath) {
        try {
            Files.createDirectories(appConfigDir());
            Path file = appConfigFile();
            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
            properties.setProperty(CONFIG_KEY_LAST_VAULT_PATH, vaultPath.toAbsolutePath().toString());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "gazo settings");
            }
        } catch (IOException e) {
            showWarn("設定保存エラー", "Vault パスの保存に失敗しました: " + e.getMessage());
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
        for (java.io.File f : files) {
            try {
                vault.importImage(f.toPath());
            } catch (IOException e) {
                showError("保存エラー", f.getName() + " の保存に失敗しました: " + e.getMessage());
            }
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
        for (java.io.File f : files) {
            try {
                vault.importVideo(f.toPath());
            } catch (IOException e) {
                showError("保存エラー", f.getName() + " の保存に失敗しました: " + e.getMessage());
            }
        }
        refreshTagFilterOptions();
        refreshVideoList();
    }

    private void refreshGallery() {
        gallery.getChildren().clear();
        try {
            Map<String, Set<String>> tagMap = vault.tagsByFileName();
            List<Path> paths = listFilteredImages(tagMap);
            scheduleGalleryCards(paths, tagMap, 0, 28);
        } catch (IOException e) {
            showError("読み込みエラー", e.getMessage());
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
            showError("動画一覧エラー", e.getMessage());
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
        applyAppIcons(playerStage);
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
                        showError("再生エラー", msg);
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
                        showError("再生エラー", msg);
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
                showError("再生エラー", ex.getMessage());
                playerStage.close();
            }
        });
        copyTask.setOnFailed(ev -> {
            Throwable ex = copyTask.getException();
            Platform.runLater(() -> {
                showError("読み込みエラー", ex != null ? ex.getMessage() : "不明なエラー");
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
        List<Path> filtered = new ArrayList<>();
        for (Path p : vault.listImages()) {
            Set<String> tags = tagsByFile.getOrDefault(p.getFileName().toString(), Set.of());
            if (!matchesTagFilter(tags)) {
                continue;
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
        for (Node n : tagFilterPane.getChildren()) {
            if (n instanceof CheckBox cb && cb.isSelected()) {
                Object data = cb.getUserData();
                if (data instanceof String tag) {
                    activeTagFilters.add(tag);
                }
            }
        }
        refreshGallery();
        refreshVideoList();
    }

    private List<Path> listFilteredImages() throws IOException {
        return listFilteredImages(vault.tagsByFileName());
    }

    private List<Path> listFilteredImagesOrEmpty(String errorTitle) {
        try {
            return listFilteredImages();
        } catch (IOException e) {
            showError(errorTitle, e.getMessage());
            return List.of();
        }
    }

    private void refreshTagFilterOptions() {
        if (tagFilterPane == null || vault == null) {
            return;
        }
        try {
            List<String> allTags = new ArrayList<>(vault.listAllTags());
            Collections.sort(allTags);
            activeTagFilters.retainAll(allTags);
            tagFilterPane.getChildren().clear();
            for (String tag : allTags) {
                CheckBox cb = new CheckBox(tag);
                cb.setUserData(tag);
                cb.setSelected(activeTagFilters.contains(tag));
                cb.setOnAction(e -> onTagFilterCheckboxChanged());
                tagFilterPane.getChildren().add(cb);
            }
        } catch (IOException e) {
            showError("タグ読み込みエラー", e.getMessage());
        }
    }

    private void enableImageDragAndDrop(BorderPane dropTarget) {
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
        for (java.io.File file : files) {
            Path path = file.toPath();
            if (Files.isDirectory(path)) {
                importedAny |= importImagesFromDirectory(path);
            } else if (isImageFile(path)) {
                try {
                    vault.importImage(path);
                    importedAny = true;
                } catch (IOException e) {
                    showError("保存エラー", file.getName() + " の保存に失敗しました: " + e.getMessage());
                }
            } else if (isVideoFile(path)) {
                try {
                    vault.importVideo(path);
                    importedAny = true;
                } catch (IOException e) {
                    showError("保存エラー", file.getName() + " の保存に失敗しました: " + e.getMessage());
                }
            }
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
        String folderTag = directory.getFileName() == null
                ? ""
                : directory.getFileName().toString().trim().toLowerCase();
        try (Stream<Path> stream = Files.walk(directory)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isImageFile(p) || isVideoFile(p))
                    .toList();
            for (Path file : files) {
                try {
                    Path importedPath;
                    if (isImageFile(file)) {
                        importedPath = vault.importImage(file);
                    } else {
                        importedPath = vault.importVideo(file);
                    }
                    importedAny = true;
                    if (!folderTag.isBlank()) {
                        Set<String> tags = new LinkedHashSet<>(vault.getTags(importedPath));
                        tags.add(folderTag);
                        vault.setTags(importedPath, tags);
                    }
                } catch (IOException e) {
                    showError("保存エラー", file.getFileName() + " の保存に失敗しました: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            showError("フォルダー読み込みエラー", directory.getFileName() + " の読み込みに失敗しました: " + e.getMessage());
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
            showError("タグ読み込みエラー", e.getMessage());
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
            showError("タグ保存エラー", e.getMessage());
        }
    }

    private void addTagsToCanvasSelection() {
        if (listCheckedSelection.isEmpty()) {
            showWarn("タグ一括追加", "先に画像をチェックしてください。");
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
            showWarn("タグ一括追加", addTags.size() + " 個のタグを追加しました。");
        } catch (IOException e) {
            showError("タグ一括追加エラー", e.getMessage());
        }
    }

    private void removeTagsFromCanvasSelection() {
        if (listCheckedSelection.isEmpty()) {
            showWarn("タグ一括削除", "先に画像をチェックしてください。");
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
            showWarn("タグ一括削除", removeTags.size() + " 個のタグを削除しました。");
        } catch (IOException e) {
            showError("タグ一括削除エラー", e.getMessage());
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
            showError("全選択エラー", e.getMessage());
        }
    }

    private void clearListCheckedSelection() {
        listCheckedSelection.clear();
        refreshGallerySelectionStyles();
    }

    private char[] promptPassword(String title, String message) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle(title);
        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        Label label = new Label(message);
        PasswordField pass = new PasswordField();
        VBox box = new VBox(8, label, pass);
        box.setPadding(new Insets(10));
        VBox.setVgrow(pass, Priority.NEVER);
        dialog.getDialogPane().setContent(box);

        bindAppIconToDialog(dialog);
        dialog.setResultConverter(bt -> bt == okType ? pass.getText().toCharArray() : null);
        Optional<char[]> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private char[] promptPasswordTwice(String title, String message) {
        while (true) {
            Dialog<List<char[]>> dialog = new Dialog<>();
            dialog.setTitle(title);
            ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

            PasswordField p1 = new PasswordField();
            PasswordField p2 = new PasswordField();
            VBox box = new VBox(8,
                    new Label(message),
                    new Label("パスワード"), p1,
                    new Label("確認"), p2);
            box.setPadding(new Insets(10));
            dialog.getDialogPane().setContent(box);
            bindAppIconToDialog(dialog);
            dialog.setResultConverter(bt -> bt == okType ? new ArrayList<>(List.of(p1.getText().toCharArray(), p2.getText().toCharArray())) : null);

            Optional<List<char[]>> result = dialog.showAndWait();
            if (result.isEmpty()) {
                return null;
            }
            char[] a = result.get().get(0);
            char[] b = result.get().get(1);
            if (a.length == 0) {
                showWarn(title, "パスワードを入力してください。");
                continue;
            }
            if (!Arrays.equals(a, b)) {
                showWarn(title, "確認用パスワードが一致しません。");
                Arrays.fill(a, '\0');
                Arrays.fill(b, '\0');
                continue;
            }
            Arrays.fill(b, '\0');
            return a;
        }
    }

    private void showWarn(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
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
                text.setText(item.getFileName().toString());
                thumb.setImage(loadThumbnail(item, 44, 44));
                setGraphic(box);
            }
        });

        ImageView leftPreview = new ImageView();
        leftPreview.setFitWidth(220);
        leftPreview.setFitHeight(220);
        leftPreview.setPreserveRatio(true);
        ImageView rightPreview = new ImageView();
        rightPreview.setFitWidth(220);
        rightPreview.setFitHeight(220);
        rightPreview.setPreserveRatio(true);
        Label leftLabel = new Label("選択画像");
        Label rightLabel = new Label("比較候補");
        VBox leftBox = new VBox(6, leftLabel, leftPreview);
        VBox rightBox = new VBox(6, rightLabel, rightPreview);
        HBox previewBox = new HBox(16, leftBox, rightBox);
        previewBox.setAlignment(Pos.CENTER_LEFT);
        Button prevCandidateButton = new Button("前候補");
        Button nextCandidateButton = new Button("次候補");
        Button enlargeCompareButton = new Button("2枚拡大比較");
        HBox previewControls = new HBox(8, prevCandidateButton, nextCandidateButton, enlargeCompareButton);
        previewControls.setAlignment(Pos.CENTER_LEFT);

        Button deleteButton = new Button("選択削除");
        Button tagButton = new Button("選択にタグ付与");
        AtomicReference<List<GazoVaultService.SimilarPair>> similarRef = new AtomicReference<>(List.of());
        AtomicReference<Path> selectedRef = new AtomicReference<>(null);
        AtomicReference<List<GazoVaultService.SimilarPair>> selectedPairOptionsRef = new AtomicReference<>(List.of());
        AtomicInteger selectedPairIndexRef = new AtomicInteger(0);

        Runnable refresh = () -> {
            int threshold = parseThreshold(thresholdField.getText(), 8);
            try {
                var exact = vault.findExactDuplicateGroups();
                var similar = vault.findSimilarPairs(threshold);
                similarRef.set(similar);
                reportArea.setText(buildDuplicateReport(exact, similar, threshold));
                candidateList.getItems().setAll(collectDuplicateCandidates(exact, similar));
                Path selected = candidateList.getSelectionModel().getSelectedItem();
                selectedRef.set(selected);
                selectedPairOptionsRef.set(findPairsForSelection(selected, similar));
                selectedPairIndexRef.set(0);
                updateDuplicatePreview(selected, selectedPairOptionsRef.get(), selectedPairIndexRef.get(), leftPreview, rightPreview, leftLabel, rightLabel);
            } catch (IOException e) {
                showError("重複チェックエラー", e.getMessage());
            }
        };

        rerunButton.setOnAction(e -> refresh.run());
        deleteButton.setOnAction(e -> deleteDuplicateSelection(candidateList.getSelectionModel().getSelectedItem(), refresh));
        tagButton.setOnAction(e -> addTagToDuplicateSelection(candidateList.getSelectionModel().getSelectedItem(), refresh));
        candidateList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            selectedRef.set(newV);
            selectedPairOptionsRef.set(findPairsForSelection(newV, similarRef.get()));
            selectedPairIndexRef.set(0);
            updateDuplicatePreview(newV, selectedPairOptionsRef.get(), selectedPairIndexRef.get(), leftPreview, rightPreview, leftLabel, rightLabel);
        });
        prevCandidateButton.setOnAction(e -> {
            List<GazoVaultService.SimilarPair> options = selectedPairOptionsRef.get();
            if (options.isEmpty()) {
                return;
            }
            int current = selectedPairIndexRef.get();
            int next = (current - 1 + options.size()) % options.size();
            selectedPairIndexRef.set(next);
            updateDuplicatePreview(selectedRef.get(), options, next, leftPreview, rightPreview, leftLabel, rightLabel);
        });
        nextCandidateButton.setOnAction(e -> {
            List<GazoVaultService.SimilarPair> options = selectedPairOptionsRef.get();
            if (options.isEmpty()) {
                return;
            }
            int current = selectedPairIndexRef.get();
            int next = (current + 1) % options.size();
            selectedPairIndexRef.set(next);
            updateDuplicatePreview(selectedRef.get(), options, next, leftPreview, rightPreview, leftLabel, rightLabel);
        });
        enlargeCompareButton.setOnAction(e -> showLargeCompareDialog(selectedRef.get(), selectedPairOptionsRef.get(), selectedPairIndexRef.get()));

        HBox controls = new HBox(8, new Label("類似判定距離:"), thresholdField, rerunButton, deleteButton, tagButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(8, controls, new Label("候補画像:"), candidateList, previewBox, previewControls, reportArea);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        refresh.run();
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
                for (Path p : paths) {
                    report.append("  - ").append(p.getFileName()).append('\n');
                }
            }
        }
        report.append("\n【似た画像候補（dHash距離 <= ").append(threshold).append("）】\n");
        if (similar.isEmpty()) {
            report.append("なし\n");
        } else {
            for (GazoVaultService.SimilarPair pair : similar) {
                report.append("  - ")
                        .append(pair.left().getFileName())
                        .append(" <-> ")
                        .append(pair.right().getFileName())
                        .append("  (distance=")
                        .append(pair.distance())
                        .append(")\n");
            }
        }
        return report.toString();
    }

    private List<Path> collectDuplicateCandidates(List<List<Path>> exact, List<GazoVaultService.SimilarPair> similar) {
        Set<Path> candidates = new TreeSet<>(Comparator.comparing(p -> p.getFileName().toString()));
        for (List<Path> group : exact) {
            candidates.addAll(group);
        }
        for (GazoVaultService.SimilarPair pair : similar) {
            candidates.add(pair.left());
            candidates.add(pair.right());
        }
        return new ArrayList<>(candidates);
    }

    private Image loadThumbnail(Path path, int width, int height) {
        try (InputStream in = Files.newInputStream(path)) {
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
            showWarn("画像ビューア", "表示できる画像がありません。");
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
        Label label = new Label();
        label.setStyle("-fx-text-fill: #ddd;");
        Label hint = new Label("← / → または A / D で移動、Esc で閉じる");
        hint.setStyle("-fx-text-fill: #999;");

        StackPane center = new StackPane(view);
        ScrollPane scroll = new ScrollPane(center);
        scroll.setPannable(true);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);

        VBox bottom = new VBox(6, new HBox(10, fitCheck, tagEditButton, label), hint);
        bottom.setPadding(new Insets(8, 14, 12, 14));

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #161616;");
        root.setCenter(scroll);
        root.setBottom(bottom);

        Scene scene = new Scene(root, 1000, 760);
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
            label.setText((i + 1) + "/" + images.size() + "  " + path.getFileName());
        };
        tagEditButton.setOnAction(e -> {
            Path path = images.get(index.get());
            editTags(path);
            render.run();
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

    private List<GazoVaultService.SimilarPair> findPairsForSelection(Path selected, List<GazoVaultService.SimilarPair> similarPairs) {
        if (selected == null) {
            return List.of();
        }
        List<GazoVaultService.SimilarPair> options = new ArrayList<>();
        for (GazoVaultService.SimilarPair pair : similarPairs) {
            if (pair.left().equals(selected) || pair.right().equals(selected)) {
                options.add(pair);
            }
        }
        options.sort(Comparator.comparingInt(GazoVaultService.SimilarPair::distance));
        return options;
    }

    private void updateDuplicatePreview(
            Path selected,
            List<GazoVaultService.SimilarPair> selectedPairs,
            int selectedPairIndex,
            ImageView leftPreview,
            ImageView rightPreview,
            Label leftLabel,
            Label rightLabel) {
        if (selected == null) {
            leftPreview.setImage(null);
            rightPreview.setImage(null);
            leftLabel.setText("選択画像");
            rightLabel.setText("比較候補");
            return;
        }
        leftPreview.setImage(loadThumbnail(selected, 220, 220));
        leftLabel.setText("選択: " + selected.getFileName());

        if (selectedPairs == null || selectedPairs.isEmpty()) {
            rightPreview.setImage(null);
            rightLabel.setText("比較候補: なし");
            return;
        }
        int idx = Math.max(0, Math.min(selectedPairIndex, selectedPairs.size() - 1));
        GazoVaultService.SimilarPair best = selectedPairs.get(idx);
        Path other = best.left().equals(selected) ? best.right() : best.left();
        rightPreview.setImage(loadThumbnail(other, 220, 220));
        rightLabel.setText("比較: " + other.getFileName() + " (d=" + best.distance() + ", " + (idx + 1) + "/" + selectedPairs.size() + ")");
    }

    private void showLargeCompareDialog(Path selected, List<GazoVaultService.SimilarPair> selectedPairs, int selectedPairIndex) {
        if (selected == null || selectedPairs == null || selectedPairs.isEmpty()) {
            showWarn("比較表示", "比較できる候補がありません。");
            return;
        }
        int idx = Math.max(0, Math.min(selectedPairIndex, selectedPairs.size() - 1));
        GazoVaultService.SimilarPair pair = selectedPairs.get(idx);
        Path other = pair.left().equals(selected) ? pair.right() : pair.left();

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("2枚比較");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ImageView left = new ImageView(loadThumbnail(selected, 640, 640));
        left.setPreserveRatio(true);
        left.setFitWidth(640);
        left.setFitHeight(640);
        ImageView right = new ImageView(loadThumbnail(other, 640, 640));
        right.setPreserveRatio(true);
        right.setFitWidth(640);
        right.setFitHeight(640);

        VBox leftBox = new VBox(6, new Label(selected.getFileName().toString()), left);
        VBox rightBox = new VBox(6, new Label(other.getFileName() + "  (d=" + pair.distance() + ")"), right);
        HBox content = new HBox(12, leftBox, rightBox);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void deleteDuplicateSelection(Path selected, Runnable refresh) {
        if (selected == null) {
            showWarn("削除", "候補画像を選択してください。");
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
            showError("削除エラー", e.getMessage());
        }
    }

    private void addTagToDuplicateSelection(Path selected, Runnable refresh) {
        if (selected == null) {
            showWarn("タグ付与", "候補画像を選択してください。");
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
            showError("タグ付与エラー", e.getMessage());
        }
    }

    /**
     * キャンバス機能の統合 UI: 画像選択・ランダムピック・Vault への保存・スライドショー。
     */
    private void showCanvasHubDialog(Stage owner) {
        if (!listCheckedSelection.isEmpty()) {
            canvasSelection.clear();
            canvasSelection.addAll(listCheckedSelection);
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("キャンバス");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        AtomicReference<String> currentLayout = new AtomicReference<>("default");

        Button overwriteSaveButton = new Button("上書き保存");
        overwriteSaveButton.setOnAction(e -> persistCanvasSelectionToVaultWithFeedback(currentLayout.get()));

        Button slideshowButton = new Button("スライドショー");
        slideshowButton.setOnAction(e -> showSlideshow(owner));

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

        HBox topBar = new HBox(12, slideshowButton);
        topBar.setAlignment(Pos.CENTER_LEFT);

        AtomicReference<Runnable> refreshEditor = new AtomicReference<>(() -> {});
        AtomicReference<TabPane> tabsRef = new AtomicReference<>();
        ListView<String> canvasListView = new ListView<>();
        AtomicReference<Runnable> refreshCanvasList = new AtomicReference<>(() -> {});
        AtomicReference<Path> selectedCanvasImage = new AtomicReference<>(null);

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
            for (String name : vault.listCanvasLayouts()) {
                Tab t = new Tab(name);
                t.setClosable(false);
                layoutTabs.getTabs().add(t);
            }
        } catch (IOException e) {
            showError("キャンバス読込エラー", e.getMessage());
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
        presetCombo.getItems().setAll(LAYOUT_PRESETS);
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
                vault.setCanvasSize(currentLayout.get(), editCanvas.getPrefWidth(), editCanvas.getPrefHeight());
            } catch (IOException ex) {
                showWarn("キャンバスサイズ保存エラー", ex.getMessage());
            }
        };
        Runnable applyCanvasSize = () -> {
            try {
                var size = vault.getCanvasSize(currentLayout.get());
                if (size != null) {
                    editCanvas.setPrefSize(Math.max(900, size.width()), Math.max(700, size.height()));
                } else {
                    editCanvas.setPrefSize(2000, 1400);
                }
            } catch (IOException ex) {
                showWarn("キャンバスサイズ読込エラー", ex.getMessage());
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
            renderCanvasItems(
                    editCanvas,
                    currentLayout.get(),
                    true,
                    selectedCanvasImage.get(),
                    path -> {
                        selectedCanvasImage.set(path);
                        refreshEditor.get().run();
                    },
                    path -> {
                        if (path == null || !canvasSelection.contains(path)) {
                            return;
                        }
                        canvasSelection.remove(path);
                        selectedCanvasImage.set(null);
                        try {
                            vault.saveCanvasSelectionOrder(currentLayout.get(), new ArrayList<>(canvasSelection));
                        } catch (IOException ex) {
                            showWarn("除去", "画像一覧の保存に失敗しました: " + ex.getMessage());
                        }
                        refreshGallery();
                        refreshEditor.get().run();
                    });
            Platform.runLater(updateEditCanvasFit);
        };
        refreshEditor.set(render);

        Runnable onEditResizeFinished = () -> {
            saveCanvasSize.run();
            Platform.runLater(updateEditCanvasFit);
        };
        installCanvasResizeHandle(editCanvas, onEditResizeFinished);

        layoutTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (oldTab != null) {
                persistCanvasSelectionToVault(oldTab.getText());
            }
            String selected = newTab == null ? "default" : newTab.getText();
            currentLayout.set(selected == null ? "default" : selected);
            reloadCanvasSelectionFromVault(currentLayout.get());
            selectedCanvasImage.set(null);
            applyCanvasSize.run();
            render.run();
            updateLayoutNameLabel.run();
        });
        newLayoutButton.setOnAction(e -> {
            persistCanvasSelectionToVault(currentLayout.get());
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
                    copyCanvasLayoutState(sourceLayout, layout);
                } catch (IOException ex) {
                    showError("名前を付けて保存エラー", ex.getMessage());
                    return;
                }
                Tab tab = new Tab(layout);
                tab.setClosable(false);
                layoutTabs.getTabs().add(tab);
                refreshCanvasList.get().run();
                showWarn("名前を付けて保存", "「" + layout + "」として保存しました（「" + sourceLayout + "」の内容を複製）。");
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
                showWarn("削除不可", "default キャンバスは削除できません。");
                return;
            }
            try {
                vault.deleteCanvasLayout(selected);
                layoutTabs.getTabs().removeIf(t -> selected.equals(t.getText()));
                refreshCanvasList.get().run();
                layoutTabs.getTabs().stream()
                        .filter(t -> "default".equals(t.getText()))
                        .findFirst()
                        .ifPresent(t -> layoutTabs.getSelectionModel().select(t));
                reloadCanvasSelectionFromVault("default");
            } catch (IOException ex) {
                showError("キャンバス削除エラー", ex.getMessage());
            }
        };
        autoLayoutButton.setOnAction(e -> {
            autoLayoutCanvas(
                    editCanvas,
                    currentLayout.get(),
                    presetCombo.getValue(),
                    overlapSlider.getValue() / 100.0,
                    neatSlider.getValue() / 100.0);
            render.run();
        });

        HBox editToolBarPrimary = new HBox(12, layoutNameLabel, overwriteSaveButton, newLayoutButton);
        editToolBarPrimary.setPadding(new Insets(8, 12, 8, 12));
        editToolBarPrimary.setAlignment(Pos.CENTER_LEFT);
        editToolBarPrimary.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #faf8f3, #f2efe7);"
                        + "-fx-border-color: #d7d0c2; -fx-border-width: 0 0 1 0;");

        HBox editToolBarControls = new HBox(
                8,
                new Label("プリセット:"),
                presetCombo,
                new Label("重なり回避"),
                overlapSlider,
                new Label("整列感"),
                neatSlider,
                autoLayoutButton);
        editToolBarControls.setPadding(new Insets(6, 12, 8, 12));
        editToolBarControls.setAlignment(Pos.CENTER_LEFT);
        editToolBarControls.setStyle(
                "-fx-background-color: rgba(255,255,255,0.65); -fx-border-color: #e7e2d8; -fx-border-width: 0 0 1 0;");

        VBox editorVBox = new VBox(8, layoutTabs, editToolBarPrimary, editToolBarControls, editScroll);
        VBox.setVgrow(editScroll, Priority.ALWAYS);
        editTab.setContent(editorVBox);
        updateLayoutNameLabel.run();

        Tab randomTab = new Tab("ランダムピック");
        randomTab.setClosable(false);

        final List<Path> pool = listFilteredImagesOrEmpty("キャンバス");

        Node randomNode;
        Runnable randomBootstrap = () -> {
        };

        if (pool.isEmpty()) {
            randomNode = new Label("タグ／フィルターに一致する画像がありません。条件を変えてください。");
        } else {
            Pane randomCanvas = new Pane();
            randomCanvas.setStyle("-fx-background-color: linear-gradient(to bottom, #f0ede4, #e4dccb);");
            randomCanvas.setPrefSize(1000, 700);

            TextField countField = new TextField("6");
            countField.setPrefWidth(56);
            Button prevButton = new Button("前のランダムピック");
            Button nextButton = new Button("次のランダムピック");
            Button createLayoutButton = new Button("名前を付けて保存…");
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
                renderRandomPickCanvas(randomCanvas, current);
            };

            Runnable nextPick = () -> {
                int cur = index.get();
                if (cur + 1 < history.size()) {
                    index.incrementAndGet();
                    renderCurrent.run();
                    return;
                }
                int count = parsePickCount(countField.getText(), pool.size());
                List<Path> pick = makeRandomPick(pool, count);
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
                canvasSelection.clear();
                canvasSelection.addAll(pick);
                try {
                    vault.saveCanvasSelectionOrder(newLayout, new ArrayList<>(canvasSelection));
                } catch (IOException ex) {
                    showError("名前を付けて保存エラー", ex.getMessage());
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
                autoLayoutCanvas(
                        editCanvas,
                        newLayout,
                        presetCombo.getValue(),
                        overlapSlider.getValue() / 100.0,
                        neatSlider.getValue() / 100.0);
                refreshGallery();
                refreshEditor.get().run();
                TabPane tp = tabsRef.get();
                if (tp != null) {
                    tp.getSelectionModel().select(editTab);
                }
                showWarn("名前を付けて保存", "「" + newLayout + "」として保存しました（ランダムピックをキャンバスに反映）。");
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
                renderCurrent.run();
            });
            HBox controls = new HBox(8, new Label("枚数"), countField, prevButton, nextButton, createLayoutButton, new Label("履歴"), pageLabel);
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
            List<Path> backup = new ArrayList<>(canvasSelection);
            try {
                List<Path> selectedPaths = vault.listCanvasSelectionOrder(selectedName);
                canvasSelection.clear();
                canvasSelection.addAll(selectedPaths);
                var size = vault.getCanvasSize(selectedName);
                if (size != null) {
                    previewCanvas.setPrefSize(Math.max(400, size.width()), Math.max(260, size.height()));
                } else {
                    previewCanvas.setPrefSize(1200, 840);
                }
                renderCanvasItems(previewCanvas, selectedName, false, null, null, null);
            } catch (IOException ex) {
                previewCanvas.getChildren().clear();
            } finally {
                canvasSelection.clear();
                canvasSelection.addAll(backup);
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

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == editTab) {
                refreshEditor.get().run();
            }
        });

        VBox root = new VBox(8, topBar, hubHint, tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        root.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefSize(1180, 820);

        applyCanvasSize.run();
        render.run();
        randomBootstrap.run();

        dialog.setOnShown(e -> {
            Scene ds = dialog.getDialogPane().getScene();
            if (ds != null && ds.getWindow() instanceof Stage) {
                applyAppIcons((Stage) ds.getWindow());
            }
            Platform.runLater(updateEditCanvasFit);
        });
        dialog.showAndWait();
    }

    private int parsePickCount(String text, int max) {
        try {
            int n = Integer.parseInt(text.trim());
            return Math.max(1, Math.min(max, n));
        } catch (Exception e) {
            return Math.min(6, max);
        }
    }

    private List<Path> makeRandomPick(List<Path> pool, int count) {
        List<Path> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, new Random());
        return new ArrayList<>(copy.subList(0, Math.min(count, copy.size())));
    }

    private void renderRandomPickCanvas(Pane canvas, List<Path> paths) {
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

    private void installCanvasResizeHandle(Pane canvas, Runnable onResizeFinished) {
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
    private void copyCanvasLayoutState(String sourceLayout, String targetLayout) throws IOException {
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
            showError("キャンバス表示", e.getMessage());
        }
    }

    private void renderCanvasItems(Pane canvas, String layoutName, boolean interactive, Path selectedPath, java.util.function.Consumer<Path> onSelect, java.util.function.Consumer<Path> onRemove) {
        canvas.getChildren().removeIf(node -> node instanceof VBox);
        double canvasW = Math.max(1, canvas.getPrefWidth());
        double canvasH = Math.max(1, canvas.getPrefHeight());
        double sizeRatio = Math.min(canvasW / BASE_CANVAS_WIDTH, canvasH / BASE_CANVAS_HEIGHT);
        sizeRatio = Math.max(0.35, Math.min(3.0, sizeRatio));
        int index = 0;
        for (Path path : canvasSelection) {
            VBox item = createCanvasItem(path, layoutName, interactive, sizeRatio);
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

    private void autoLayoutCanvas(Pane canvas, String layoutName, String presetName, double overlapTuning, double neatTuning) {
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
                showWarn("自動レイアウト保存エラー", e.getMessage());
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
        view.setFitWidth(wh[0]);
        view.setFitHeight(wh[1]);
        view.setPreserveRatio(true);
        view.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                List<Path> nav = viewerNavSource != null ? viewerNavSource : new ArrayList<>(canvasSelection);
                showOriginalImageViewer(path, new ArrayList<>(nav));
                e.consume();
            }
        });
        Label label = new Label(path.getFileName().toString());
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
                showWarn("キャンバス保存エラー", e.getMessage());
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
                showWarn("キャンバス変形保存エラー", e.getMessage());
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
