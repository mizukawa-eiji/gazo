package com.example.gazo;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * JavaFX の共通ダイアログ・ウィンドウアイコン。
 */
public final class GazoFx {
    private static final String APP_ICON_RESOURCE = "/com/example/gazo/app-icon.png";

    private GazoFx() {
    }

    public static void applyAppIcons(Stage stage) {
        InputStream in = GazoFx.class.getResourceAsStream(APP_ICON_RESOURCE);
        if (in == null) {
            return;
        }
        try (in) {
            stage.getIcons().add(new Image(in));
        } catch (IOException ignored) {
            // アイコン読み込み失敗時は OS 既定のアイコンのまま
        }
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

    public static char[] promptPassword(String title, String message) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle(title);
        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        Label label = new Label(message);
        PasswordField pass = new PasswordField();
        VBox box = new VBox(8, label, pass);
        box.setPadding(new javafx.geometry.Insets(10));
        VBox.setVgrow(pass, Priority.NEVER);
        dialog.getDialogPane().setContent(box);

        bindAppIconToDialog(dialog);
        dialog.setResultConverter(bt -> bt == okType ? pass.getText().toCharArray() : null);
        Optional<char[]> result = dialog.showAndWait();
        return result.orElse(null);
    }

    public static char[] promptPasswordTwice(String title, String message) {
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
            box.setPadding(new javafx.geometry.Insets(10));
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
}
