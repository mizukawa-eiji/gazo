package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
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
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Vault の保存フォルダを選択");
        GazoVaultService v = host.vault.get();
        if (v != null) {
            java.io.File current = v.getVaultPath().toFile();
            java.io.File initial = current.isDirectory() ? current : current.getParentFile();
            if (initial != null && initial.exists()) {
                chooser.setInitialDirectory(initial);
            }
        }
        java.io.File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        host.vaultUnlock.openVaultAsync(
                stage,
                selected.toPath(),
                err -> {
                    if (err instanceof VaultUnlockCancelledException) {
                        return;
                    }
                    if (err != null) {
                        GazoFx.showError("Vault 変更エラー", err.getMessage());
                        return;
                    }
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
