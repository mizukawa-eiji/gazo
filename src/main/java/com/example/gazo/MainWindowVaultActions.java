package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * メニュー「ファイル」から呼ぶ Vault 操作（画像追加・Vault 変更・サムネ再作成）。
 */
public final class MainWindowVaultActions {
    public record Host(
            Supplier<GazoVaultService> vault,
            Supplier<VaultConnection> currentConnection,
            Consumer<VaultConnection> setCurrentConnection,
            VaultUnlockFlow vaultUnlock,
            VaultUnlockSuccess adoptUnlockedVault,
            Runnable updateVaultPathLabel,
            Runnable refreshAfterVaultChanged,
            Consumer<String> setImportStatusLabel,
            Runnable clearImportStatusLabel,
            BiConsumer<Boolean, String> setMainWindowBusy,
            Runnable refreshGallery) {}

    private final Host host;

    public MainWindowVaultActions(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    public void addImages(Stage stage) {
        GazoVaultService v = host.vault.get();
        if (v == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("画像を選択");
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("画像", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.webp"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        try {
            for (java.io.File f : files) {
                host.setImportStatusLabel.accept(f.getName());
                try {
                    v.importImage(f.toPath());
                } catch (IOException e) {
                    GazoFx.showError("保存エラー", f.getName() + " の保存に失敗しました: " + e.getMessage());
                }
            }
        } finally {
            host.clearImportStatusLabel.run();
        }
        host.refreshGallery.run();
    }

    public void changeVaultPath(Stage stage) {
        GazoVaultService v = host.vault.get();
        VaultConnection currentConnection = host.currentConnection.get();
        VaultConnection initialConnection = currentConnection;
        if (initialConnection == null && v != null) {
            initialConnection = VaultConnection.local(v.getVaultPath());
        }
        java.util.Optional<VaultOpenRequest> selectedRequest = VaultConnectionDialogs.promptForOpenRequest(stage, initialConnection);
        if (selectedRequest.isEmpty()) {
            return;
        }
        VaultOpenRequest request = selectedRequest.get();
        char[] pwdCopy = request.connection().isWebDav() ? request.webDavPassword() : null;
        VaultOpenRequest.WebDavPasswordPersistence persistence = request.webDavPasswordPersistence();
        host.vaultUnlock.openVaultAsync(
                stage,
                request,
                err -> {
                    if (err instanceof VaultUnlockCancelledException) {
                        if (pwdCopy != null) {
                            Arrays.fill(pwdCopy, '\0');
                        }
                        return;
                    }
                    if (err != null) {
                        if (pwdCopy != null) {
                            Arrays.fill(pwdCopy, '\0');
                        }
                        GazoFx.showError("Vault 変更エラー", err.getMessage());
                        return;
                    }
                    VaultPathStore.saveLastVaultConnection(request.connection(), pwdCopy, persistence);
                    if (pwdCopy != null) {
                        Arrays.fill(pwdCopy, '\0');
                    }
                    host.setCurrentConnection.accept(request.connection());
                    host.updateVaultPathLabel.run();
                    host.refreshAfterVaultChanged.run();
                },
                host.adoptUnlockedVault);
    }

    public void rebuildAllThumbnails() {
        GazoVaultService v = host.vault.get();
        if (v == null) {
            return;
        }
        Alert confirm =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "登録済み画像のサムネイルを再作成します。画像数が多い場合は時間がかかります。実行しますか？",
                        ButtonType.OK,
                        ButtonType.CANCEL);
        confirm.setTitle("サムネイル再作成");
        confirm.setHeaderText(null);
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        host.setMainWindowBusy.accept(true, "サムネイルを再作成しています…");

        Task<Integer> task =
                new Task<>() {
                    @Override
                    protected Integer call() throws Exception {
                        return v.rebuildAllThumbnails();
                    }
                };
        task.setOnSucceeded(
                e -> {
                    host.setMainWindowBusy.accept(false, "");
                    Integer count = task.getValue();
                    GazoFx.showWarn("サムネイル再作成", (count == null ? 0 : count) + " 件を再作成しました。");
                    host.refreshGallery.run();
                });
        task.setOnFailed(
                e -> {
                    host.setMainWindowBusy.accept(false, "");
                    Throwable ex = task.getException();
                    GazoFx.showError("サムネイル再作成エラー", ex != null ? ex.getMessage() : "不明なエラー");
                });

        Thread t = new Thread(task, "gazo-rebuild-thumbnails");
        t.setDaemon(true);
        t.start();
    }
}
