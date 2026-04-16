package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * メニュー「ファイル」から呼ぶ Vault 操作（画像追加・Vault 変更・サムネ再作成）。
 */
public final class MainWindowVaultActions {
    private record MediaCounts(int images, int videos) {}

    private record CopyProgress(int copiedFiles, int totalFiles, String currentRelativePath) {}

    private enum VaultSwitchMode {
        SWITCH_ONLY,
        COPY_AND_MIGRATE,
        CANCEL
    }

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
        VaultSwitchMode switchMode =
                shouldOfferMigration(v, currentConnection, request.connection())
                        ? promptVaultSwitchMode(stage)
                        : VaultSwitchMode.SWITCH_ONLY;
        if (switchMode == VaultSwitchMode.CANCEL) {
            return;
        }
        boolean copyAndMigrate = switchMode == VaultSwitchMode.COPY_AND_MIGRATE;
        char[] pwdCopy = request.connection().isWebDav() ? request.webDavPassword() : null;
        VaultOpenRequest.WebDavPasswordPersistence persistence = request.webDavPasswordPersistence();
        GazoVaultService sourceVault = v;
        Path migrationSnapshot = null;
        MediaCounts sourceCounts = null;
        if (copyAndMigrate) {
            if (sourceVault == null) {
                if (pwdCopy != null) {
                    Arrays.fill(pwdCopy, '\0');
                }
                GazoFx.showError("アルバム変更エラー", "移行元アルバムが見つかりません。");
                return;
            }
            if (!confirmMigrationBackup(stage, sourceVault, request.connection())) {
                if (pwdCopy != null) {
                    Arrays.fill(pwdCopy, '\0');
                }
                return;
            }
            host.setMainWindowBusy.accept(true, "移行準備中…");
            try {
                sourceCounts = readMediaCounts(sourceVault);
                migrationSnapshot =
                        createMigrationSnapshot(
                                sourceVault,
                                progress ->
                                        host.setMainWindowBusy.accept(
                                                true, formatCopyProgressMessage("スナップショット作成中", progress)));
            } catch (Exception e) {
                if (pwdCopy != null) {
                    Arrays.fill(pwdCopy, '\0');
                }
                host.setMainWindowBusy.accept(false, "");
                GazoFx.showError("アルバム変更エラー", "コピー元の読み取りに失敗しました: " + e.getMessage());
                return;
            }
        }
        Path snapshotForMigration = migrationSnapshot;
        MediaCounts sourceCountsForMigration = sourceCounts;
        host.vaultUnlock.openVaultAsync(
                stage,
                request,
                err -> {
                    cleanupMigrationSnapshot(snapshotForMigration);
                    if (copyAndMigrate) {
                        host.setMainWindowBusy.accept(false, "");
                    }
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
                        GazoFx.showError("アルバム変更エラー", err.getMessage());
                        return;
                    }
                    VaultPathStore.saveLastVaultConnection(request.connection(), pwdCopy, persistence);
                    if (pwdCopy != null) {
                        Arrays.fill(pwdCopy, '\0');
                    }
                    host.setCurrentConnection.accept(request.connection());
                    host.updateVaultPathLabel.run();
                    host.refreshAfterVaultChanged.run();
                    if (copyAndMigrate && sourceCountsForMigration != null) {
                        GazoFx.showWarn(
                                "移行検証",
                                "画像 " + sourceCountsForMigration.images() + " 件 / 動画 "
                                        + sourceCountsForMigration.videos() + " 件で一致しました。");
                    }
                },
                newVault -> {
                    if (copyAndMigrate && snapshotForMigration != null) {
                        newVault.replaceAllContentFromDirectory(
                                snapshotForMigration,
                                progress ->
                                        host.setMainWindowBusy.accept(
                                                true,
                                                formatCopyProgressMessage(
                                                        "新しいアルバムへコピー中",
                                                        new CopyProgress(
                                                                progress.copiedFiles(),
                                                                progress.totalFiles(),
                                                                progress.currentRelativePath()))));
                        MediaCounts destinationCounts = readMediaCounts(newVault);
                        if (sourceCountsForMigration != null
                                && !sourceCountsForMigration.equals(destinationCounts)) {
                            throw new IOException(
                                    "移行後の件数が一致しません。"
                                            + " source(images="
                                            + sourceCountsForMigration.images()
                                            + ", videos="
                                            + sourceCountsForMigration.videos()
                                            + ")"
                                            + " / destination(images="
                                            + destinationCounts.images()
                                            + ", videos="
                                            + destinationCounts.videos()
                                            + ")");
                        }
                    }
                    host.adoptUnlockedVault.adoptUnlockedVault(newVault);
                });
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

    private static boolean shouldOfferMigration(
            GazoVaultService currentVault, VaultConnection currentConnection, VaultConnection selectedConnection) {
        if (currentVault == null || selectedConnection == null || currentConnection == null) {
            return false;
        }
        return !isSameConnection(currentConnection, selectedConnection);
    }

    private static boolean isSameConnection(VaultConnection a, VaultConnection b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.type() != b.type()) {
            return false;
        }
        if (a.isLocal()) {
            return a.localPath() != null
                    && b.localPath() != null
                    && a.localPath().toAbsolutePath().normalize().equals(b.localPath().toAbsolutePath().normalize());
        }
        return Objects.equals(a.webDavEndpoint(), b.webDavEndpoint())
                && Objects.equals(a.webDavBasePath(), b.webDavBasePath())
                && Objects.equals(a.webDavUsername(), b.webDavUsername());
    }

    private static VaultSwitchMode promptVaultSwitchMode(Stage stage) {
        ButtonType migrate = new ButtonType("コピーして移行");
        ButtonType switchOnly = new ButtonType("接続のみ切替");
        Alert alert =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "現在のアルバム内容を新しい接続先へコピーしますか？\n"
                                + "「コピーして移行」は画像・動画・タグ・キャンバス設定を含めて移行します。",
                        migrate,
                        switchOnly,
                        ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setTitle("アルバム切替方法");
        alert.setHeaderText("接続先を変更します");
        Optional<ButtonType> selected = alert.showAndWait();
        if (selected.isEmpty() || selected.get() == ButtonType.CANCEL) {
            return VaultSwitchMode.CANCEL;
        }
        return selected.get() == migrate ? VaultSwitchMode.COPY_AND_MIGRATE : VaultSwitchMode.SWITCH_ONLY;
    }

    private static boolean confirmMigrationBackup(
            Stage stage, GazoVaultService sourceVault, VaultConnection destinationConnection) {
        String sourceLabel = sourceVault.getVaultDisplayLocation();
        String destinationLabel = destinationConnection == null ? "(未設定)" : destinationConnection.displayLabel();
        Alert confirm =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "コピー移行を開始します。\n"
                                + "移行元: "
                                + sourceLabel
                                + "\n移行先: "
                                + destinationLabel
                                + "\n\n"
                                + "先にバックアップを取ってから実行することを推奨します。\n"
                                + "バックアップ済みであれば続行してください。",
                        ButtonType.OK,
                        ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("バックアップ確認");
        confirm.setHeaderText("コピー移行の前に確認");
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private static Path createMigrationSnapshot(GazoVaultService sourceVault, Consumer<CopyProgress> onProgress)
            throws IOException {
        Path sourceRoot = sourceVault.cleartextRoot();
        Path snapshot = Files.createTempDirectory("gazo-vault-migrate-");
        copyDirectoryContents(sourceRoot, snapshot, onProgress);
        return snapshot;
    }

    private static void cleanupMigrationSnapshot(Path snapshot) {
        if (snapshot == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(snapshot)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static MediaCounts readMediaCounts(GazoVaultService vault) throws IOException {
        return new MediaCounts(vault.listImages().size(), vault.listVideos().size());
    }

    private static String formatCopyProgressMessage(String phase, CopyProgress progress) {
        int remaining = Math.max(0, progress.totalFiles() - progress.copiedFiles());
        String name = progress.currentRelativePath();
        if (name == null || name.isBlank()) {
            name = "-";
        }
        return phase + " " + progress.copiedFiles() + "/" + progress.totalFiles() + " (残り " + remaining + ") " + name;
    }

    private static void copyDirectoryContents(Path sourceRoot, Path destinationRoot, Consumer<CopyProgress> onProgress)
            throws IOException {
        List<Path> filesToCopy;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            filesToCopy =
                    stream.filter(Files::isRegularFile)
                            .filter(source -> !source.equals(sourceRoot))
                            .toList();
        }
        int totalFiles = filesToCopy.size();
        int copied = 0;
        if (onProgress != null) {
            onProgress.accept(new CopyProgress(0, totalFiles, ""));
        }
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.toList()) {
                if (source.equals(sourceRoot)) {
                    continue;
                }
                Path relative = sourceRoot.relativize(source);
                Path target = destinationRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source)) {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                    if (onProgress != null) {
                        String rel = relative.toString().replace('\\', '/');
                        onProgress.accept(new CopyProgress(copied, totalFiles, rel));
                    }
                }
            }
        }
    }
}
