package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.cryptomator.cryptolib.api.InvalidPassphraseException;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 起動時・Vault 変更時の解錠 UI と非同期オープン。
 */
public final class VaultUnlockFlow {

    private static final String VAULT_FORM_LABEL_TEXT = "-fx-text-fill: #33312d;";
    private static final String VAULT_FORM_FIELD_STYLE = "-fx-text-fill: #33312d; -fx-prompt-text-fill: #6a6355;";

    private Node vaultPasswordMountNode;

    /**
     * Vault 解錠の前にメイン {@link Stage} を表示する（パスワードは {@link #mountVaultPasswordForm} でこのウィンドウ内に出す）。
     */
    public void prepareStageShellBeforeVaultUnlock(Stage stage) {
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

    /**
     * 別スレッドではなくイベントスレッドから呼ぶ。解錠後に {@code adopt} で Vault をアプリへ取り込み、{@code done} で完了を通知する。
     */
    public void openVaultAsync(Stage stage, Path vaultDir, Consumer<Exception> done, VaultUnlockSuccess adopt) {
        try {
            GazoVaultService newVault = new GazoVaultService(vaultDir);
            unlockOrCreateInline(
                    stage,
                    newVault,
                    () -> {
                        try {
                            adopt.adoptUnlockedVault(newVault);
                            done.accept(null);
                        } catch (Exception e) {
                            done.accept(e);
                        }
                    },
                    () -> done.accept(new VaultUnlockCancelledException()));
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

    private void showUnlockPasswordInline(
            Stage stage, String inlineError, Consumer<char[]> onSubmit, Runnable onCancel) {
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
        cancel.setOnAction(
                e -> {
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
        ok.setOnAction(
                e -> {
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
        cancel.setOnAction(
                e -> {
                    clearVaultPasswordMount(stage);
                    onCancel.run();
                });
        p2.setOnAction(e -> ok.fire());
        mountVaultPasswordForm(stage, box);
        Platform.runLater(p1::requestFocus);
    }

    private void unlockOrCreateInline(
            Stage stage, GazoVaultService targetVault, Runnable onUnlocked, Runnable onCancelled) {
        if (!targetVault.vaultExists()) {
            showCreateVaultPasswordInline(
                    stage,
                    pass -> {
                        try {
                            targetVault.createVault(new String(pass));
                            Arrays.fill(pass, '\0');
                            onUnlocked.run();
                        } catch (IOException | MasterkeyLoadingFailedException e) {
                            Arrays.fill(pass, '\0');
                            GazoFx.showError("Vault を作成できませんでした", e.getMessage());
                            onCancelled.run();
                        }
                    },
                    onCancelled);
            return;
        }
        runUnlockLoop(stage, targetVault, null, onUnlocked, onCancelled);
    }

    private void runUnlockLoop(
            Stage stage,
            GazoVaultService targetVault,
            String inlineError,
            Runnable onUnlocked,
            Runnable onCancelled) {
        showUnlockPasswordInline(
                stage,
                inlineError,
                pass -> {
                    try {
                        targetVault.unlock(new String(pass));
                        Arrays.fill(pass, '\0');
                        clearVaultPasswordMount(stage);
                        onUnlocked.run();
                    } catch (InvalidPassphraseException e) {
                        Arrays.fill(pass, '\0');
                        clearVaultPasswordMount(stage);
                        runUnlockLoop(
                                stage,
                                targetVault,
                                "パスワードが正しくありません。",
                                onUnlocked,
                                onCancelled);
                    } catch (IOException | MasterkeyLoadingFailedException e) {
                        Arrays.fill(pass, '\0');
                        clearVaultPasswordMount(stage);
                        GazoFx.showError("Vault を開けませんでした", e.getMessage());
                        onCancelled.run();
                    }
                },
                onCancelled);
    }
}
