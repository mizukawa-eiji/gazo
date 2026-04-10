package com.example.gazo;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;

/**
 * JavaFX の共通ダイアログ・ウィンドウアイコン。
 */
public final class GazoFx {
    private static final String APP_ICON_RESOURCE = "/com/example/gazo/app-icon.png";
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
}
