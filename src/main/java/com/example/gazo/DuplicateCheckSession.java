package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 重複/類似チェックのウィンドウ実装（GazoApp から切り出し）。
 */
final class DuplicateCheckSession {
    private final DuplicateCheckHost host;
    /** {@link #run} で Vault から読み込み。プレビュー・一覧セルで参照する。 */
    private Map<String, Set<String>> canvasLayoutsByFileName = Map.of();

    DuplicateCheckSession(DuplicateCheckHost host) {
        this.host = host;
    }

    void run(
            Set<Path> duplicateFocusForNextScan,
            Consumer<Stage> registerOpen,
            Runnable unregisterOnClose) {
        try {
            if (host.vault() != null) {
                canvasLayoutsByFileName = new HashMap<>(host.vault().mapCanvasLayoutsByFileName());
            } else {
                canvasLayoutsByFileName = Map.of();
            }
        } catch (IOException e) {
            canvasLayoutsByFileName = Map.of();
        }
        final AtomicReference<Set<Path>> duplicateNarrowFocusRef = new AtomicReference<>();
        if (duplicateFocusForNextScan != null && !duplicateFocusForNextScan.isEmpty()) {
            duplicateNarrowFocusRef.set(new LinkedHashSet<>(duplicateFocusForNextScan));
        }

        Stage dupStage = new Stage();
        dupStage.setTitle("重複/類似チェック");
        if (host.primaryStage() != null) {
            dupStage.initOwner(host.primaryStage());
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
                                    canvasLayoutsByFileName.getOrDefault(
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
                    thumb.setImage(host.thumbnails().load(rowPath, 44, 44));
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
                    List<Path> images = host.vault().listImages().stream()
                            .filter(p -> !host.isPendingDelete().test(p))
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
                                        return host.vault().sha256(p);
                                    } catch (IOException e) {
                                        return "";
                                    }
                                });
                                Long dhCurrent = exactOnlyScan
                                        ? null
                                        : dHashCache.computeIfAbsent(current, host.vault()::dHash64);
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
                                            return host.vault().sha256(p);
                                        } catch (IOException e) {
                                            return "";
                                        }
                                    });
                                    if (!shaCurrent.isEmpty() && shaCurrent.equals(shaOther)) {
                                        exactMatches.add(other);
                                    }
                                    if (!exactOnlyScan) {
                                        Long dhOther = dHashCache.computeIfAbsent(other, host.vault()::dHash64);
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
                        // ギャラリーから開いたときの注目パスは、このダイアログを閉じるまで維持する（再チェックで getAndSet(null) すると全候補表示に戻ってしまう）。
                        Set<Path> narrow = duplicateNarrowFocusRef.get();
                        List<Path> displayCandidates = finalCandidates;
                        Path selected = null;
                        if (narrow != null && !narrow.isEmpty()) {
                            Map<Path, Set<Path>> graph = buildDuplicateSimilarityGraph(exactFinal, similarFinal);
                            Set<Path> wantedReps = new LinkedHashSet<>();
                            for (Path f : narrow) {
                                Path r = duplicateClusterRepresentativeForPath(f, graph);
                                if (r != null) {
                                    wantedReps.add(r);
                                }
                            }
                            if (wantedReps.isEmpty()) {
                                displayCandidates = finalCandidates;
                                if (!finalCandidates.isEmpty()) {
                                    selected = finalCandidates.get(0);
                                }
                                GazoFx.showWarn(
                                        "重複/類似",
                                        "注目した画像は重複・類似クラスタに含まれませんでした。全候補を表示します。");
                            } else {
                                List<Path> narrowed = new ArrayList<>();
                                for (Path c : finalCandidates) {
                                    if (wantedReps.contains(c)) {
                                        narrowed.add(c);
                                    }
                                }
                                if (!narrowed.isEmpty()) {
                                    displayCandidates = narrowed;
                                    selected = narrowed.get(0);
                                    for (Path f : narrow) {
                                        Path r = duplicateClusterRepresentativeForPath(f, graph);
                                        if (r != null && narrowed.contains(r)) {
                                            selected = r;
                                            break;
                                        }
                                    }
                                } else {
                                    displayCandidates = finalCandidates;
                                    if (!finalCandidates.isEmpty()) {
                                        selected = finalCandidates.get(0);
                                    }
                                    GazoFx.showWarn(
                                            "重複/類似",
                                            "注目画像に対応する候補行が見つかりませんでした。全候補を表示します。");
                                }
                            }
                        } else if (!finalCandidates.isEmpty()) {
                            selected = finalCandidates.get(0);
                        }
                        candidateList.getItems().setAll(displayCandidates);
                        if (selected != null) {
                            candidateList.getSelectionModel().select(selected);
                        } else {
                            candidateList.getSelectionModel().clearSelection();
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
        if (duplicateFocusForNextScan != null && !duplicateFocusForNextScan.isEmpty()) {
            Platform.runLater(refreshAsync);
        }

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
        if (host.primaryStage() != null && host.primaryStage().getScene() != null) {
            dupScene.getStylesheets().setAll(host.primaryStage().getScene().getStylesheets());
        }
        dupStage.setScene(dupScene);
        dupStage.sizeToScene();
        dupStage.setMinWidth(480);
        dupStage.setMinHeight(400);
        registerOpen.accept(dupStage);
        dupStage.setOnHidden(ev -> {
            unregisterOnClose.run();
            host.flushPendingDeletes().accept(true);
            host.refreshTagFilterOptions().run();
            host.refreshGallery().run();
            host.refreshVideoList().run();
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
                keepTagsField = new TextField(String.join(", ", host.vault().getTags(keep)));
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
                    String t = String.join(", ", host.vault().getTags(path));
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
            host.galleryImageLoadExecutor().execute(() -> {
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
                LinkedHashSet<String> merged = new LinkedHashSet<>(host.parseUserTags().apply(keepTagsField.getText()));
                for (CheckBox cb : checkBoxes) {
                    if (!cb.isSelected() || !(cb.getUserData() instanceof Path p)) {
                        continue;
                    }
                    try {
                        merged.addAll(host.vault().getTags(p));
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
                LinkedHashSet<String> mergedForConfirm = new LinkedHashSet<>(host.parseUserTags().apply(keepTagsField.getText()));
                for (Path p : toDelete) {
                    mergedForConfirm.addAll(host.vault().getTags(p));
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
                host.vault().setTags(keep, host.parseUserTags().apply(keepTagsField.getText()));
                LinkedHashSet<String> merged = new LinkedHashSet<>(host.vault().getTags(keep));
                for (Path p : toDelete) {
                    merged.addAll(host.vault().getTags(p));
                }
                host.vault().setTags(keep, merged);
                for (Path p : toDelete) {
                    host.vault().substituteCanvasImageReferences(keep, p);
                }
                host.markPendingDelete().accept(toDelete);
                host.refreshTagFilterOptions().run();
                host.refreshGallery().run();
                host.refreshVideoList().run();
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
        host.vaultHeavySerialExecutor().execute(() -> {
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
        if (host.vault() != null) {
            Path thumb = host.vault().thumbnailFor(path);
            if (thumb != null) {
                source = thumb;
            }
        }
        return Files.readAllBytes(source);
    }

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
        Long dhSel = host.vault().dHash64(selected);
        List<DuplicateOther> out = new ArrayList<>();
        for (DuplicateOther x : base) {
            int d;
            if (dhSel != null) {
                Long dhO = host.vault().dHash64(x.path());
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
        leftPreview.setImage(host.thumbnails().load(sel, 220, 220));
        List<String> selCanvasLayouts =
                new ArrayList<>(canvasLayoutsByFileName.getOrDefault(sel.getFileName().toString(), Set.of()));
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
                new ArrayList<>(canvasLayoutsByFileName.getOrDefault(path.getFileName().toString(), Set.of()));
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
        ImageView iv = new ImageView(host.thumbnails().load(p, 120, 120));
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
        host.galleryImageLoadExecutor().execute(() -> {
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
}
