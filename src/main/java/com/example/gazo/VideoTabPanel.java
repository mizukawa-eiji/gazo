package com.example.gazo;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * メインウィンドウの「動画」タブ: 一覧グリッド・追加・再生。
 */
public final class VideoTabPanel {

    private static final String GALLERY_BG =
            "-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);";

    private final VideoTabHost host;
    private final FlowPane gallery;
    private final ScrollPane scroll;

    public VideoTabPanel(VideoTabHost host) {
        this.host = Objects.requireNonNull(host);
        this.gallery = new FlowPane();
        gallery.setHgap(16);
        gallery.setVgap(16);
        gallery.setPadding(new Insets(22));
        gallery.setStyle(GALLERY_BG);
        this.scroll = new ScrollPane(gallery);
        scroll.setFitToWidth(true);
    }

    public ScrollPane getScrollPane() {
        return scroll;
    }

    /** ドラッグ＆ドロップ時のハイライト対象（画像ギャラリーと同じ見た目に合わせる）。 */
    public FlowPane getGallery() {
        return gallery;
    }

    public void refreshList() {
        gallery.getChildren().clear();
        try {
            Map<String, Set<String>> tagMap = host.vault().tagsByFileName();
            for (Path p : listFilteredVideos(tagMap)) {
                gallery.getChildren().add(createVideoCard(p));
            }
        } catch (IOException e) {
            GazoFx.showError("動画一覧エラー", e.getMessage());
        }
    }

    private List<Path> listFilteredVideos(Map<String, Set<String>> tagsByFile) throws IOException {
        Set<String> active = host.activeTagFilters().get();
        List<Path> filtered = new ArrayList<>();
        for (Path p : host.vault().listVideos()) {
            if (host.isPendingDelete().test(p)) {
                continue;
            }
            Set<String> tags = tagsByFile.getOrDefault(p.getFileName().toString(), Set.of());
            if (!TagFilter.matches(active, tags)) {
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
        card.setStyle(
                "-fx-background-color: #fffdf8; -fx-border-color: #d5cec0; -fx-border-width: 2; "
                        + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 10, 0.2, 2, 3);");
        Label title = new Label(videoPath.getFileName().toString());
        title.setWrapText(true);
        Label hint = new Label("ダブルクリックで再生");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #6a6355;");
        card.getChildren().addAll(title, hint);
        card.setUserData(videoPath);
        card.setOnMouseClicked(
                e -> {
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
        playerStage.initOwner(host.primaryStage());
        Label status = new Label("動画を準備しています…");
        status.setPadding(new Insets(16));
        StackPane waitRoot = new StackPane(status);
        playerStage.setScene(new Scene(waitRoot, 420, 100));
        playerStage.setTitle(vaultPath.getFileName().toString());
        playerStage.show();

        Task<Path> copyTask =
                new Task<>() {
                    @Override
                    protected Path call() throws IOException {
                        String suffix = extensionForTempFile(vaultPath);
                        Path temp = Files.createTempFile("gazo-play-", suffix);
                        Files.copy(vaultPath, temp, StandardCopyOption.REPLACE_EXISTING);
                        return temp;
                    }
                };
        copyTask.setOnSucceeded(
                ev -> {
                    Path temp = copyTask.getValue();
                    try {
                        Media media = new Media(temp.toUri().toString());
                        media.setOnError(
                                () -> {
                                    try {
                                        Files.deleteIfExists(temp);
                                    } catch (IOException ignored) {
                                        // ignore
                                    }
                                    javafx.scene.media.MediaException mex = media.getError();
                                    String msg =
                                            mex != null && mex.getMessage() != null
                                                    ? mex.getMessage()
                                                    : "メディアを読み取れませんでした";
                                    Platform.runLater(
                                            () -> {
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
                        Runnable cleanup =
                                () -> {
                                    mediaPlayer.stop();
                                    mediaPlayer.dispose();
                                    try {
                                        Files.deleteIfExists(temp);
                                    } catch (IOException ignored) {
                                        // ignore
                                    }
                                };
                        playerStage.setOnCloseRequest(e -> cleanup.run());
                        mediaPlayer.setOnError(
                                () -> {
                                    String msg =
                                            mediaPlayer.getError() != null
                                                    ? mediaPlayer.getError().getMessage()
                                                    : "不明なエラー";
                                    cleanup.run();
                                    Platform.runLater(
                                            () -> {
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
        copyTask.setOnFailed(
                ev -> {
                    Throwable ex = copyTask.getException();
                    Platform.runLater(
                            () -> {
                                GazoFx.showError(
                                        "読み込みエラー", ex != null ? ex.getMessage() : "不明なエラー");
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

    public void addVideos(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("動画を選択");
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("動画", "*.mp4", "*.webm", "*.m4v", "*.mov", "*.mkv"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            for (java.io.File f : files) {
                host.setImportStatusLabel().accept(f.getName());
                try {
                    host.vault().importVideo(f.toPath());
                } catch (IOException e) {
                    GazoFx.showError("保存エラー", f.getName() + " の保存に失敗しました: " + e.getMessage());
                }
            }
        } finally {
            host.clearImportStatusLabel().run();
        }
        host.refreshTagFilterOptions().run();
        refreshList();
    }
}
