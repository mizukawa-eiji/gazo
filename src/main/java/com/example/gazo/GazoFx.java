package com.example.gazo;

import com.example.gazo.vault.WebDavConflictResolution;
import com.example.gazo.vault.WebDavSyncConflictException;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JavaFX の共通ダイアログ・ウィンドウアイコン。
 */
public final class GazoFx {
    private static final String APP_ICON_RESOURCE = "/com/example/gazo/app-icon.png";
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Image cachedAppIcon;

    private GazoFx() {
    }

    public static void applyAppIcons(Stage stage) {
        Image img = loadAppIconImage();
        if (img != null && !img.isError()) {
            stage.getIcons().add(img);
        }
    }

    /** ウィンドウタイトルバー用と同じアプリアイコン画像。読めなければ null（1 プロセスで 1 回だけ読み込み）。 */
    public static Image loadAppIconImage() {
        Image c = cachedAppIcon;
        if (c != null) {
            return c;
        }
        synchronized (GazoFx.class) {
            if (cachedAppIcon != null) {
                return cachedAppIcon;
            }
            InputStream in = GazoFx.class.getResourceAsStream(APP_ICON_RESOURCE);
            if (in == null) {
                return null;
            }
            try (in) {
                cachedAppIcon = new Image(in);
                return cachedAppIcon;
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    /** 起動プレースホルダなどに使う {@link ImageView}。画像が無ければ null。 */
    public static ImageView createAppIconView(double fitSize) {
        Image img = loadAppIconImage();
        if (img == null || img.isError()) {
            return null;
        }
        ImageView iv = new ImageView(img);
        iv.setFitWidth(fitSize);
        iv.setFitHeight(fitSize);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        return iv;
    }

    public static void bindAppIconToDialog(Dialog<?> dialog) {
        dialog.setOnShown(e -> {
            Scene s = dialog.getDialogPane().getScene();
            if (s != null && s.getWindow() instanceof Stage) {
                applyAppIcons((Stage) s.getWindow());
            }
        });
    }

    public static void showWarn(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public static void showConflictThresholdSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("競合比較しきい値設定");
        bindAppIconToDialog(dialog);
        ButtonType save = new ButtonType("保存", ButtonType.OK.getButtonData());
        ButtonType cancel = new ButtonType("キャンセル", ButtonType.CANCEL.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(save, cancel);

        VaultPathStore.ConflictDHashThresholds thresholds = VaultPathStore.loadConflictDHashThresholds();
        Spinner<Integer> sameSpinner = new Spinner<>();
        sameSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 63, thresholds.sameMax()));
        Spinner<Integer> nearSpinner = new Spinner<>();
        nearSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, thresholds.nearMax()));
        sameSpinner.setEditable(true);
        nearSpinner.setEditable(true);
        sameSpinner.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && nearSpinner.getValue() < newV) {
                nearSpinner.getValueFactory().setValue(newV);
            }
        });
        nearSpinner.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && sameSpinner.getValue() > newV) {
                sameSpinner.getValueFactory().setValue(newV);
            }
        });
        Button resetButton = new Button("デフォルトに戻す");
        resetButton.setOnAction(e -> {
            VaultPathStore.ConflictDHashThresholds defaults = VaultPathStore.ConflictDHashThresholds.defaults();
            sameSpinner.getValueFactory().setValue(defaults.sameMax());
            nearSpinner.getValueFactory().setValue(defaults.nearMax());
        });
        Label desc = new Label("WebDAV 競合時の画像比較で使う d値(dHash距離)の判定しきい値です。");
        desc.setWrapText(true);
        HBox row = new HBox(8, new Label("ほぼ同一<="), sameSpinner, new Label("近い<="), nearSpinner, resetButton);
        VBox root = new VBox(10, desc, row);
        root.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(root);
        dialog.showAndWait().ifPresent(btn -> {
            if (btn == save) {
                VaultPathStore.saveConflictDHashThresholds(
                        new VaultPathStore.ConflictDHashThresholds(sameSpinner.getValue(), nearSpinner.getValue()));
                showWarn("設定保存", "競合比較しきい値を保存しました。");
            }
        });
    }

    public static WebDavConflictResolution showWebDavConflictDialog(WebDavSyncConflictException conflict) {
        if (!Platform.isFxApplicationThread()) {
            AtomicReference<WebDavConflictResolution> out = new AtomicReference<>(WebDavConflictResolution.SAVE_AS_CONFLICT_COPY);
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    out.set(showWebDavConflictDialogOnFxThread(conflict));
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return WebDavConflictResolution.CANCEL;
            }
            return out.get();
        }
        return showWebDavConflictDialogOnFxThread(conflict);
    }

    private static WebDavConflictResolution showWebDavConflictDialogOnFxThread(WebDavSyncConflictException conflict) {
        Dialog<WebDavConflictResolution> dialog = new Dialog<>();
        dialog.setTitle("WebDAV 同期競合");
        bindAppIconToDialog(dialog);
        ButtonType keepLocal = new ButtonType("ローカルを優先");
        ButtonType keepRemote = new ButtonType("リモートを優先");
        ButtonType saveCopy = new ButtonType("競合コピーを保存");
        ButtonType cancel = new ButtonType("キャンセル");
        dialog.getDialogPane().getButtonTypes().addAll(keepLocal, keepRemote, saveCopy, cancel);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        Label header = new Label("競合ファイル: " + conflict.relativePath());
        root.getChildren().add(header);
        Label hint = new Label("ローカルとリモートの両方で更新が検出されました。解決方法を選択してください。");
        hint.setWrapText(true);
        root.getChildren().add(hint);
        ImageInfo localImage = decodeImage(conflict.localBytes());
        ImageInfo remoteImage = decodeImage(conflict.remoteBytes());
        boolean imageComparable = localImage != null && remoteImage != null;
        if (imageComparable) {
            VaultPathStore.ConflictDHashThresholds thresholds = VaultPathStore.loadConflictDHashThresholds();
            Spinner<Integer> sameSpinner = new Spinner<>();
            sameSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 63, thresholds.sameMax()));
            Spinner<Integer> nearSpinner = new Spinner<>();
            nearSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, thresholds.nearMax()));
            sameSpinner.setEditable(true);
            nearSpinner.setEditable(true);
            sameSpinner.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV != null && nearSpinner.getValue() < newV) {
                    nearSpinner.getValueFactory().setValue(newV);
                }
            });
            nearSpinner.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV != null && sameSpinner.getValue() > newV) {
                    sameSpinner.getValueFactory().setValue(newV);
                }
            });
            HBox thresholdRow = new HBox(8, new Label("しきい値"), new Label("ほぼ同一<="), sameSpinner, new Label("近い<="), nearSpinner);
            root.getChildren().add(thresholdRow);

            VBox localPane = buildImagePane("ローカル", localImage, conflict.localBytes().length);
            VBox remotePane =
                    buildImagePane(
                            "リモート",
                            remoteImage,
                            conflict.remoteBytes().length,
                            conflict.remoteLastModified() == null
                                    ? null
                                    : DATE_TIME_FORMAT.format(conflict.remoteLastModified().atZone(ZoneId.systemDefault()).toLocalDateTime()),
                            conflict.remoteEtag());
            HBox compare = new HBox(8, localPane, remotePane);
            HBox.setHgrow(localPane, Priority.ALWAYS);
            HBox.setHgrow(remotePane, Priority.ALWAYS);
            compare.setMinHeight(360);
            root.getChildren().add(compare);
            Long localHash = localImage.dHash;
            Long remoteHash = remoteImage.dHash;
            if (localHash != null && remoteHash != null) {
                int d = Long.bitCount(localHash ^ remoteHash);
                Label dLabel = new Label();
                Runnable updateDLabel = () -> {
                    int sameMax = sameSpinner.getValue();
                    int nearMax = nearSpinner.getValue();
                    String level = d <= sameMax ? "ほぼ同一" : (d <= nearMax ? "近い" : "差分大");
                    dLabel.setText("d値 (dHash距離): " + d + " / 64  (" + level + ")");
                };
                sameSpinner.valueProperty().addListener((obs, oldV, newV) -> updateDLabel.run());
                nearSpinner.valueProperty().addListener((obs, oldV, newV) -> updateDLabel.run());
                updateDLabel.run();
                root.getChildren().add(dLabel);
                dialog.setResultConverter(btn -> {
                    VaultPathStore.saveConflictDHashThresholds(
                            new VaultPathStore.ConflictDHashThresholds(sameSpinner.getValue(), nearSpinner.getValue()));
                    if (btn == keepLocal) {
                        return WebDavConflictResolution.KEEP_LOCAL;
                    }
                    if (btn == keepRemote) {
                        return WebDavConflictResolution.KEEP_REMOTE;
                    }
                    if (btn == saveCopy) {
                        return WebDavConflictResolution.SAVE_AS_CONFLICT_COPY;
                    }
                    return WebDavConflictResolution.CANCEL;
                });
            } else {
                root.getChildren().add(new Label("d値: 画像ハッシュを計算できませんでした。"));
                dialog.setResultConverter(btn -> {
                    VaultPathStore.saveConflictDHashThresholds(
                            new VaultPathStore.ConflictDHashThresholds(sameSpinner.getValue(), nearSpinner.getValue()));
                    if (btn == keepLocal) {
                        return WebDavConflictResolution.KEEP_LOCAL;
                    }
                    if (btn == keepRemote) {
                        return WebDavConflictResolution.KEEP_REMOTE;
                    }
                    if (btn == saveCopy) {
                        return WebDavConflictResolution.SAVE_AS_CONFLICT_COPY;
                    }
                    return WebDavConflictResolution.CANCEL;
                });
            }
        } else if (conflict.isTextLike()) {
            TextArea local = new TextArea(conflict.localTextPreview());
            local.setEditable(false);
            local.setWrapText(false);
            TextArea remote = new TextArea(conflict.remoteTextPreview());
            remote.setEditable(false);
            remote.setWrapText(false);
            VBox left = new VBox(4, new Label("ローカル"), local);
            VBox right = new VBox(4, new Label("リモート"), remote);
            HBox.setHgrow(left, Priority.ALWAYS);
            HBox.setHgrow(right, Priority.ALWAYS);
            VBox.setVgrow(local, Priority.ALWAYS);
            VBox.setVgrow(remote, Priority.ALWAYS);
            HBox compare = new HBox(8, left, right);
            compare.setMinHeight(320);
            root.getChildren().add(compare);
        } else {
            root.getChildren().add(new Label("バイナリファイルのためテキスト差分は表示できません。"));
        }
        dialog.getDialogPane().setContent(root);
        if (!imageComparable) {
            dialog.setResultConverter(btn -> {
                if (btn == keepLocal) {
                    return WebDavConflictResolution.KEEP_LOCAL;
                }
                if (btn == keepRemote) {
                    return WebDavConflictResolution.KEEP_REMOTE;
                }
                if (btn == saveCopy) {
                    return WebDavConflictResolution.SAVE_AS_CONFLICT_COPY;
                }
                return WebDavConflictResolution.CANCEL;
            });
        }
        return dialog.showAndWait().orElse(WebDavConflictResolution.CANCEL);
    }

    private static VBox buildImagePane(String title, ImageInfo info, long byteSize) {
        return buildImagePane(title, info, byteSize, null, null);
    }

    private static VBox buildImagePane(String title, ImageInfo info, long byteSize, String modifiedAt, String etag) {
        ImageView view = new ImageView(info.image);
        view.setFitWidth(360);
        view.setFitHeight(260);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Label titleLabel = new Label(title);
        Label dim = new Label("解像度: " + info.width + " x " + info.height);
        Label size = new Label("容量: " + humanBytes(byteSize));
        double ratio = info.height <= 0 ? 0.0 : ((double) info.width / (double) info.height);
        Label aspect = new Label("比率: " + String.format(Locale.ROOT, "%.3f", ratio));
        VBox box = new VBox(4, titleLabel, view, dim, size, aspect);
        if (modifiedAt != null) {
            box.getChildren().add(new Label("更新時刻: " + modifiedAt));
        }
        if (etag != null && !etag.isBlank()) {
            box.getChildren().add(new Label("ETag: " + etag));
        }
        VBox.setVgrow(view, Priority.ALWAYS);
        return box;
    }

    private static String humanBytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        double kb = size / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(Locale.ROOT, "%.2f MB", kb / 1024.0);
    }

    private static ImageInfo decodeImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Image image = new Image(new ByteArrayInputStream(bytes), 0, 0, true, true);
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return null;
        }
        int w = (int) Math.round(image.getWidth());
        int h = (int) Math.round(image.getHeight());
        Long hash = computeDHash64(image);
        return new ImageInfo(image, Math.max(1, w), Math.max(1, h), hash);
    }

    /**
     * 9x8 サンプリングの dHash を JavaFX 画像から計算する。
     */
    private static Long computeDHash64(Image image) {
        PixelReader pr = image.getPixelReader();
        if (pr == null) {
            return null;
        }
        int srcW = Math.max(1, (int) Math.round(image.getWidth()));
        int srcH = Math.max(1, (int) Math.round(image.getHeight()));
        double[][] gray = new double[8][9];
        for (int y = 0; y < 8; y++) {
            int sy = Math.min(srcH - 1, (int) Math.round((y + 0.5) * srcH / 8.0 - 0.5));
            for (int x = 0; x < 9; x++) {
                int sx = Math.min(srcW - 1, (int) Math.round((x + 0.5) * srcW / 9.0 - 0.5));
                var c = pr.getColor(sx, sy);
                gray[y][x] = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
            }
        }
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (gray[y][x] > gray[y][x + 1]) {
                    hash |= (1L << bit);
                }
                bit++;
            }
        }
        return hash;
    }

    private record ImageInfo(Image image, int width, int height, Long dHash) {}
}
