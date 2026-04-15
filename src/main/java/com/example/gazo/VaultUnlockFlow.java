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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 起動時・Vault 変更時の解錠 UI と非同期オープン。
 */
public final class VaultUnlockFlow {

    /** {@link #prepareStageShellBeforeVaultUnlock} で設定。進捗メッセージ用。 */
    private Label shellHintLabel;

    /** Vault 作成・解錠・WebDAV 同期はブロックするため JavaFX スレッドでは実行しない。 */
    private static final ExecutorService VAULT_IO_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "gazo-vault-io");
                        t.setDaemon(true);
                        return t;
                    });

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
        shellHintLabel = shellHint;
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
        openVaultAsync(stage, VaultOpenRequest.local(VaultConnection.local(vaultDir)), done, adopt, null);
    }

    /**
     * 接続情報を含む Vault オープン。
     */
    public void openVaultAsync(Stage stage, VaultOpenRequest request, Consumer<Exception> done, VaultUnlockSuccess adopt) {
        openVaultAsync(stage, request, done, adopt, null);
    }

    /**
     * 接続情報を含む Vault オープン（必要に応じて接続先切替を許可）。
     */
    public void openVaultAsync(
            Stage stage,
            VaultOpenRequest request,
            Consumer<Exception> done,
            VaultUnlockSuccess adopt,
            Runnable onSwitchConnection) {
        try {
            GazoVaultService newVault = new GazoVaultService(request.connection(), request.webDavPassword());
            unlockOrCreateInline(
                    stage,
                    newVault,
                    onSwitchConnection,
                    () -> {
                        try {
                            adopt.adoptUnlockedVault(newVault);
                            request.clearSecrets();
                            done.accept(null);
                        } catch (Exception e) {
                            request.clearSecrets();
                            done.accept(e);
                        }
                    },
                    () -> {
                        request.clearSecrets();
                        done.accept(new VaultUnlockCancelledException());
                    });
        } catch (Exception e) {
            request.clearSecrets();
            done.accept(e);
        }
    }

    private void showShellProgress(String uiMessage, String logLine) {
        System.err.println("[Gazo] " + logLine);
        Platform.runLater(
                () -> {
                    if (shellHintLabel != null) {
                        shellHintLabel.setText(uiMessage);
                    }
                });
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
            Stage stage,
            String inlineError,
            Consumer<char[]> onSubmit,
            Runnable onCancel,
            Runnable onSwitchConnection) {
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
        Button switchConnection = new Button("接続先切替…");
        switchConnection.setVisible(onSwitchConnection != null);
        switchConnection.setManaged(onSwitchConnection != null);
        HBox row = new HBox(8, ok, cancel, switchConnection);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, title, msg, errLabel, passField, row);
        box.setPadding(new Insets(20));
        box.setMaxWidth(440);
        box.setStyle("-fx-background-color: #f5f2ea; -fx-background-radius: 8;");
        ok.setOnAction(
                e -> {
                    ok.setDisable(true);
                    cancel.setDisable(true);
                    switchConnection.setDisable(true);
                    passField.setDisable(true);
                    char[] pwd = passField.getText().toCharArray();
                    passField.clear();
                    onSubmit.accept(pwd);
                });
        cancel.setOnAction(
                e -> {
                    clearVaultPasswordMount(stage);
                    onCancel.run();
                });
        switchConnection.setOnAction(
                e -> {
                    clearVaultPasswordMount(stage);
                    if (onSwitchConnection != null) {
                        onSwitchConnection.run();
                    }
                });
        passField.setOnAction(e -> ok.fire());
        mountVaultPasswordForm(stage, box);
        Platform.runLater(passField::requestFocus);
    }

    private void showCreateVaultPasswordInline(
            Stage stage,
            Consumer<char[]> onSuccess,
            Runnable onCancel,
            Runnable onSwitchConnection) {
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
        Button switchConnection = new Button("接続先切替…");
        switchConnection.setVisible(onSwitchConnection != null);
        switchConnection.setManaged(onSwitchConnection != null);
        HBox row = new HBox(8, ok, cancel, switchConnection);
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
                    ok.setDisable(true);
                    cancel.setDisable(true);
                    switchConnection.setDisable(true);
                    p1.setDisable(true);
                    p2.setDisable(true);
                    clearVaultPasswordMount(stage);
                    onSuccess.accept(a);
                });
        cancel.setOnAction(
                e -> {
                    clearVaultPasswordMount(stage);
                    onCancel.run();
                });
        switchConnection.setOnAction(
                e -> {
                    clearVaultPasswordMount(stage);
                    if (onSwitchConnection != null) {
                        onSwitchConnection.run();
                    }
                });
        p2.setOnAction(e -> ok.fire());
        mountVaultPasswordForm(stage, box);
        Platform.runLater(p1::requestFocus);
    }

    private void unlockOrCreateInline(
            Stage stage,
            GazoVaultService targetVault,
            Runnable onSwitchConnection,
            Runnable onUnlocked,
            Runnable onCancelled) {
        if (!targetVault.vaultExists()) {
            showCreateVaultPasswordInline(
                    stage,
                    pass -> runVaultCreateInBackground(stage, targetVault, pass, onUnlocked, onCancelled),
                    onCancelled,
                    onSwitchConnection);
            return;
        }
        runUnlockLoop(stage, targetVault, null, onSwitchConnection, onUnlocked, onCancelled);
    }

    private void runVaultCreateInBackground(
            Stage stage,
            GazoVaultService targetVault,
            char[] pass,
            Runnable onUnlocked,
            Runnable onCancelled) {
        char[] copy = Arrays.copyOf(pass, pass.length);
        Arrays.fill(pass, '\0');
        showShellProgress(
                "Vault を作成しています…（WebDAV の場合は同期に時間がかかることがあります）",
                "Vault 作成を開始しました（バックグラウンドで処理中）");
        CompletableFuture.runAsync(
                        () -> {
                            try {
                                targetVault.createVault(new String(copy));
                            } catch (IOException | MasterkeyLoadingFailedException e) {
                                throw new RuntimeException(e);
                            } finally {
                                Arrays.fill(copy, '\0');
                            }
                        },
                        VAULT_IO_EXECUTOR)
                .whenComplete(
                        (unused, err) ->
                                Platform.runLater(
                                        () -> {
                                            if (err == null) {
                                                onUnlocked.run();
                                            } else {
                                                Throwable t = unwrap(err);
                                                String msg =
                                                        t.getMessage() == null || t.getMessage().isBlank()
                                                                ? t.getClass().getSimpleName()
                                                                : t.getMessage();
                                                GazoFx.showError("Vault を作成できませんでした", msg);
                                                onCancelled.run();
                                            }
                                        }));
    }

    private static Throwable unwrap(Throwable err) {
        Throwable t = err;
        for (int i = 0; i < 5 && t != null; i++) {
            if (t.getCause() != null
                    && (t instanceof java.util.concurrent.CompletionException
                            || t instanceof RuntimeException)) {
                t = t.getCause();
            } else {
                break;
            }
        }
        return t;
    }

    private void runUnlockLoop(
            Stage stage,
            GazoVaultService targetVault,
            String inlineError,
            Runnable onSwitchConnection,
            Runnable onUnlocked,
            Runnable onCancelled) {
        showUnlockPasswordInline(
                stage,
                inlineError,
                pass -> runVaultUnlockInBackground(stage, targetVault, pass, onSwitchConnection, onUnlocked, onCancelled),
                onCancelled,
                onSwitchConnection);
    }

    private void runVaultUnlockInBackground(
            Stage stage,
            GazoVaultService targetVault,
            char[] pass,
            Runnable onSwitchConnection,
            Runnable onUnlocked,
            Runnable onCancelled) {
        char[] copy = Arrays.copyOf(pass, pass.length);
        Arrays.fill(pass, '\0');
        showShellProgress("Vault を解錠しています…", "Vault 解錠を開始しました（バックグラウンドで処理中）");
        CompletableFuture.runAsync(
                        () -> {
                            try {
                                targetVault.unlock(new String(copy));
                            } catch (InvalidPassphraseException e) {
                                throw new RuntimeException(e);
                            } catch (IOException | MasterkeyLoadingFailedException e) {
                                throw new RuntimeException(e);
                            } finally {
                                Arrays.fill(copy, '\0');
                            }
                        },
                        VAULT_IO_EXECUTOR)
                .whenComplete(
                        (unused, err) ->
                                Platform.runLater(
                                        () -> {
                                            if (err == null) {
                                                clearVaultPasswordMount(stage);
                                                onUnlocked.run();
                                                return;
                                            }
                                            Throwable t = unwrap(err);
                                            if (t instanceof InvalidPassphraseException) {
                                                clearVaultPasswordMount(stage);
                                                runUnlockLoop(
                                                        stage,
                                                        targetVault,
                                                        "パスワードが正しくありません。",
                                                        onSwitchConnection,
                                                        onUnlocked,
                                                        onCancelled);
                                                return;
                                            }
                                            if (t instanceof IOException || t instanceof MasterkeyLoadingFailedException) {
                                                clearVaultPasswordMount(stage);
                                                String msg =
                                                        t.getMessage() == null || t.getMessage().isBlank()
                                                                ? t.getClass().getSimpleName()
                                                                : t.getMessage();
                                                GazoFx.showError("Vault を開けませんでした", msg);
                                                onCancelled.run();
                                                return;
                                            }
                                            clearVaultPasswordMount(stage);
                                            String msg =
                                                    t.getMessage() == null || t.getMessage().isBlank()
                                                            ? t.getClass().getSimpleName()
                                                            : t.getMessage();
                                            GazoFx.showError("Vault を開けませんでした", msg);
                                            onCancelled.run();
                                        }));
    }
}
