package com.example.gazo;

import com.example.gazo.vault.WebDavConnectionProbe;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * Vault 接続設定ダイアログ。
 */
public final class VaultConnectionDialogs {
    private VaultConnectionDialogs() {
    }

    public static Optional<VaultOpenRequest> promptForOpenRequest(Stage owner, VaultConnection initial) {
        Dialog<VaultOpenRequest> dialog = new Dialog<>();
        dialog.setTitle("アルバムの場所を選択");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        ButtonType ok = new ButtonType("開く", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("キャンセル", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);

        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton localRadio = new RadioButton("ローカルフォルダ");
        RadioButton webDavRadio = new RadioButton("WebDAV");
        localRadio.setToggleGroup(typeGroup);
        webDavRadio.setToggleGroup(typeGroup);

        TextField localPathField = new TextField();
        localPathField.setPromptText("C:\\Users\\...\\vault");
        Path defaultLocalVaultPath =
                Path.of(System.getProperty("user.home"), ".gazo", "vault")
                        .toAbsolutePath()
                        .normalize();
        javafx.scene.control.Button homeButton = new javafx.scene.control.Button("ホーム");
        homeButton.setOnAction(e -> localPathField.setText(defaultLocalVaultPath.toString()));
        javafx.scene.control.Button browseButton = new javafx.scene.control.Button("参照...");
        HBox localButtons = new HBox(6, homeButton, browseButton);
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("アルバム ディレクトリを選択");
            String current = localPathField.getText().trim();
            if (!current.isEmpty()) {
                Path p = Path.of(current);
                java.io.File f = p.toFile();
                java.io.File initialDir = f.isDirectory() ? f : f.getParentFile();
                if (initialDir != null && initialDir.exists()) {
                    chooser.setInitialDirectory(initialDir);
                }
            }
            java.io.File selected = chooser.showDialog(owner);
            if (selected != null) {
                localPathField.setText(selected.getAbsolutePath());
            }
        });

        TextField endpointField = new TextField();
        endpointField.setPromptText("https://example.com/remote.php/dav/files/user");
        TextField basePathField = new TextField();
        basePathField.setPromptText("/gazo-vault");
        TextField usernameField = new TextField();
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("WebDAV パスワード");
        javafx.scene.control.Button testConnectionButton = new javafx.scene.control.Button("接続テスト");
        Label webDavTestStatusLabel = new Label();
        webDavTestStatusLabel.setStyle("-fx-text-fill: #4a5560; -fx-font-size: 11px;");
        testConnectionButton.setOnAction(e -> {
            webDavTestStatusLabel.setText("");
            String endpoint = endpointField.getText().trim();
            String basePath = basePathField.getText().trim();
            String username = usernameField.getText().trim();
            if (endpoint.isEmpty() || basePath.isEmpty() || username.isEmpty()) {
                webDavTestStatusLabel.setText("WebDAV URL / アルバム パス / ユーザー名を入力してください。");
                return;
            }
            VaultConnection probeConnection;
            try {
                probeConnection = VaultConnection.webDav(endpoint, basePath, username);
            } catch (IllegalArgumentException ex) {
                webDavTestStatusLabel.setText("入力値を確認してください。");
                return;
            }
            char[] probePassword;
            String typedPassword = passwordField.getText();
            if (!typedPassword.isEmpty()) {
                probePassword = typedPassword.toCharArray();
            } else {
                Optional<char[]> stored = VaultPathStore.loadStoredWebDavPassword(probeConnection);
                if (stored.isEmpty()) {
                    webDavTestStatusLabel.setText("接続テストにはパスワード入力または保存済みパスワードが必要です。");
                    return;
                }
                probePassword = stored.get();
            }
            testConnectionButton.setDisable(true);
            testConnectionButton.setText("テスト中...");
            webDavTestStatusLabel.setText("接続を確認しています…");
            Task<Void> testTask =
                    new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            WebDavConnectionProbe.test(probeConnection, probePassword);
                            return null;
                        }
                    };
            testTask.setOnSucceeded(ev -> {
                Arrays.fill(probePassword, '\0');
                testConnectionButton.setText("接続テスト");
                testConnectionButton.setDisable(!webDavRadio.isSelected());
                webDavTestStatusLabel.setText("接続に成功しました。");
            });
            testTask.setOnFailed(ev -> {
                Arrays.fill(probePassword, '\0');
                testConnectionButton.setText("接続テスト");
                testConnectionButton.setDisable(!webDavRadio.isSelected());
                Throwable ex = testTask.getException();
                String msg =
                        ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
                                ? "接続に失敗しました。"
                                : ex.getMessage();
                webDavTestStatusLabel.setText("接続に失敗しました: " + msg);
            });
            Thread t = new Thread(testTask, "gazo-webdav-connection-test");
            t.setDaemon(true);
            t.start();
        });
        CheckBox rememberPassword =
                new CheckBox("WebDAV パスワードを保存する（ホームの .gazo フォルダに暗号化して保存）");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.add(localRadio, 0, 0, 2, 1);
        grid.add(new Label("ローカルアルバム"), 0, 1);
        grid.add(localPathField, 1, 1);
        grid.add(localButtons, 2, 1);
        grid.add(webDavRadio, 0, 2, 2, 1);
        grid.add(new Label("WebDAV URL"), 0, 3);
        grid.add(endpointField, 1, 3, 2, 1);
        grid.add(new Label("アルバム パス"), 0, 4);
        grid.add(basePathField, 1, 4, 2, 1);
        grid.add(new Label("ユーザー名"), 0, 5);
        grid.add(usernameField, 1, 5, 2, 1);
        grid.add(new Label("WebDAV パスワード"), 0, 6);
        grid.add(passwordField, 1, 6, 2, 1);
        grid.add(rememberPassword, 1, 7, 2, 1);
        grid.add(testConnectionButton, 1, 8);
        grid.add(webDavTestStatusLabel, 2, 8);
        dialog.getDialogPane().setContent(grid);

        if (initial != null && initial.isWebDav()) {
            webDavRadio.setSelected(true);
            endpointField.setText(initial.webDavEndpoint());
            basePathField.setText(initial.webDavBasePath());
            usernameField.setText(initial.webDavUsername());
        } else {
            localRadio.setSelected(true);
            if (initial != null && initial.localPath() != null) {
                localPathField.setText(initial.localPath().toString());
            }
        }
        Runnable updateEnabled = () -> {
            boolean local = localRadio.isSelected();
            localPathField.setDisable(!local);
            homeButton.setDisable(!local);
            browseButton.setDisable(!local);
            endpointField.setDisable(local);
            basePathField.setDisable(local);
            usernameField.setDisable(local);
            passwordField.setDisable(local);
            rememberPassword.setDisable(local);
            testConnectionButton.setDisable(local);
        };
        localRadio.selectedProperty().addListener((obs, oldV, newV) -> updateEnabled.run());
        webDavRadio.selectedProperty().addListener((obs, oldV, newV) -> updateEnabled.run());
        updateEnabled.run();

        Runnable refreshOk =
                () -> {
                    Node okNode = dialog.getDialogPane().lookupButton(ok);
                    if (!(okNode instanceof javafx.scene.control.Button okButton)) {
                        return;
                    }
                    boolean local = localRadio.isSelected();
                    if (local) {
                        okButton.setDisable(localPathField.getText().trim().isEmpty());
                        return;
                    }
                    if (endpointField.getText().trim().isEmpty()
                            || basePathField.getText().trim().isEmpty()
                            || usernameField.getText().trim().isEmpty()) {
                        okButton.setDisable(true);
                        return;
                    }
                    String pw = passwordField.getText();
                    if (!pw.isEmpty()) {
                        okButton.setDisable(false);
                        return;
                    }
                    try {
                        VaultConnection c =
                                VaultConnection.webDav(
                                        endpointField.getText().trim(),
                                        basePathField.getText().trim(),
                                        usernameField.getText().trim());
                        okButton.setDisable(VaultPathStore.loadStoredWebDavPassword(c).isEmpty());
                    } catch (IllegalArgumentException e) {
                        okButton.setDisable(true);
                    }
                };
        javafx.beans.InvalidationListener refreshListener = obs -> refreshOk.run();
        refreshOk.run();
        localRadio.selectedProperty().addListener(refreshListener);
        webDavRadio.selectedProperty().addListener(refreshListener);
        localPathField.textProperty().addListener(refreshListener);
        endpointField.textProperty().addListener(refreshListener);
        basePathField.textProperty().addListener(refreshListener);
        usernameField.textProperty().addListener(refreshListener);
        passwordField.textProperty().addListener(refreshListener);
        endpointField.textProperty().addListener((obs, oldV, newV) -> webDavTestStatusLabel.setText(""));
        basePathField.textProperty().addListener((obs, oldV, newV) -> webDavTestStatusLabel.setText(""));
        usernameField.textProperty().addListener((obs, oldV, newV) -> webDavTestStatusLabel.setText(""));
        passwordField.textProperty().addListener((obs, oldV, newV) -> webDavTestStatusLabel.setText(""));

        dialog.setResultConverter(btn -> {
            if (btn != ok) {
                return null;
            }
            if (localRadio.isSelected()) {
                return VaultOpenRequest.local(VaultConnection.local(Path.of(localPathField.getText().trim())));
            }
            char[] pwdChars;
            String pwText = passwordField.getText();
            if (pwText.isEmpty()) {
                VaultConnection c =
                        VaultConnection.webDav(
                                endpointField.getText().trim(),
                                basePathField.getText().trim(),
                                usernameField.getText().trim());
                Optional<char[]> stored = VaultPathStore.loadStoredWebDavPassword(c);
                if (stored.isEmpty()) {
                    return null;
                }
                pwdChars = stored.get();
            } else {
                pwdChars = pwText.toCharArray();
            }
            VaultOpenRequest.WebDavPasswordPersistence persistence =
                    rememberPassword.isSelected()
                            ? VaultOpenRequest.WebDavPasswordPersistence.STORE
                            : VaultOpenRequest.WebDavPasswordPersistence.CLEAR;
            VaultOpenRequest out =
                    VaultOpenRequest.webDav(
                            VaultConnection.webDav(
                                    endpointField.getText().trim(),
                                    basePathField.getText().trim(),
                                    usernameField.getText().trim()),
                            pwdChars,
                            persistence);
            if (pwText.isEmpty()) {
                Arrays.fill(pwdChars, '\0');
            }
            return out;
        });
        return dialog.showAndWait();
    }

    public static Optional<char[]> promptForWebDavPassword(Stage owner, VaultConnection connection) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle("WebDAV パスワード");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        ButtonType ok = new ButtonType("続行", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("キャンセル", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);

        Label label = new Label("WebDAV パスワードを入力してください: " + connection.webDavUsername() + " @ " + connection.webDavEndpoint());
        PasswordField field = new PasswordField();
        field.setPromptText("WebDAV パスワード");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.add(label, 0, 0);
        grid.add(field, 0, 1);
        dialog.getDialogPane().setContent(grid);
        Node okButton = dialog.getDialogPane().lookupButton(ok);
        okButton.disableProperty().bind(field.textProperty().isEmpty());
        dialog.setResultConverter(btn -> btn == ok ? field.getText().toCharArray() : null);
        return dialog.showAndWait();
    }
}
