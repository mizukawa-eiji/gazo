package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.application.Platform;
import org.cryptomator.cryptolib.api.InvalidPassphraseException;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * メニュー「ファイル」から呼ぶ Vault 操作（画像追加・Vault 変更・サムネ再作成）。
 */
public final class MainWindowVaultActions {
    private static final DateTimeFormatter MIGRATION_REPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DELETED_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ExecutorService MIGRATION_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "gazo-vault-migration");
                        t.setDaemon(true);
                        return t;
                    });

    private record MediaCounts(int images, int videos) {}

    private record SnapshotPrep(MediaCounts counts, Path snapshot) {}

    private enum VaultSwitchMode {
        SWITCH_ONLY,
        /** 平文を一時スナップショット経由でコピーし、画像・動画件数で検証する。 */
        COPY_AND_MIGRATE,
        /** 一時領域を使わず平文を直接コピー。件数検証はしない（Vault 単純コピー移行）。 */
        COPY_VAULT_SIMPLE,
        /** 暗号化された Vault ディレクトリをバイトごとコピーしてから接続先へ切替（WebDAV は空フォルダのみ）。 */
        COPY_PHYSICAL_MIGRATE,
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
            Consumer<MainWindowBusyState> setMainWindowBusy,
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

    /**
     * 現在の Vault（Cryptomator の暗号化ディレクトリ）を、選択したフォルダ配下に日時付きサブフォルダへ丸ごとコピーする。
     */
    public void backupVaultPhysically(Stage stage) {
        GazoVaultService v = host.vault.get();
        if (v == null) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("物理バックアップの保存先（親フォルダを選択）");
        java.io.File parent = chooser.showDialog(stage);
        if (parent == null) {
            return;
        }
        Path destRoot =
                parent.toPath()
                        .resolve("gazo-vault-physical-backup-" + MIGRATION_REPORT_STAMP.format(LocalDateTime.now()));
        Alert confirm =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "暗号化された Vault フォルダを次へコピーします。\n"
                                + destRoot
                                + "\n\n容量が大きい場合は時間がかかります。続行しますか？",
                        ButtonType.OK,
                        ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("物理バックアップ");
        Optional<ButtonType> ok = confirm.showAndWait();
        if (ok.isEmpty() || ok.get() != ButtonType.OK) {
            return;
        }
        Path sourceRoot = v.getVaultPath();
        host.setMainWindowBusy.accept(MainWindowBusyState.busy("物理バックアップを準備…"));
        CompletableFuture.runAsync(
                        () -> {
                            try {
                                Files.createDirectories(destRoot);
                                copyDirectoryContents(
                                        sourceRoot,
                                        destRoot,
                                        progress ->
                                                Platform.runLater(
                                                        () ->
                                                                host.setMainWindowBusy.accept(
                                                                        MainWindowBusyState.busy(
                                                                                formatCopyProgressMessage(
                                                                                        "Vaultをコピー中",
                                                                                        progress),
                                                                                progress.copiedFiles(),
                                                                                progress.totalFiles()))));
                            } catch (IOException e) {
                                throw new CompletionException(e);
                            }
                        },
                        MIGRATION_EXECUTOR)
                .whenComplete(
                        (unused, err) ->
                                Platform.runLater(
                                        () -> {
                                            host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                                            if (err != null) {
                                                Throwable t = unwrapAsync(err);
                                                GazoFx.showError(
                                                        "物理バックアップ",
                                                        t.getMessage() == null ? "" : t.getMessage());
                                                try {
                                                    deletePhysicalCopyTree(destRoot);
                                                } catch (IOException ignored) {
                                                    // best effort
                                                }
                                                return;
                                            }
                                            GazoFx.showWarn("物理バックアップ", "完了しました:\n" + destRoot);
                                        }));
    }

    /**
     * 現在の Vault に対し、差分同期の宛先となるローカルフォルダを登録する（settings.properties）。
     */
    public void registerPhysicalBackupMirror(Stage stage) {
        GazoVaultService v = host.vault.get();
        VaultConnection conn = host.currentConnection.get();
        if (v == null || conn == null) {
            GazoFx.showError("物理バックアップ先", "アルバムが開かれていません。");
            return;
        }
        Path vaultRoot = v.getVaultPath().toAbsolutePath().normalize();
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("物理バックアップ先（ミラー先のルートフォルダを選択）");
        java.io.File chosen = chooser.showDialog(stage);
        if (chosen == null) {
            return;
        }
        Path mirrorRoot = chosen.toPath().toAbsolutePath().normalize();
        if (isUnsafePhysicalBackupMirrorPair(vaultRoot, mirrorRoot)) {
            GazoFx.showError(
                    "物理バックアップ先",
                    "アルバムの場所と同じ、または一方が他方の内側にあるフォルダは選べません。");
            return;
        }
        VaultPathStore.savePhysicalBackupMirrorRoot(conn, mirrorRoot);
        GazoFx.showWarn("物理バックアップ先", "登録しました:\n" + mirrorRoot);
    }

    /** 登録済みの物理バックアップ先を解除する。 */
    public void clearPhysicalBackupMirrorRegistration(Stage stage) {
        VaultConnection conn = host.currentConnection.get();
        if (conn == null) {
            GazoFx.showError("物理バックアップ先", "接続情報がありません。");
            return;
        }
        Optional<Path> existing = VaultPathStore.loadPhysicalBackupMirrorRoot(conn);
        if (existing.isEmpty()) {
            GazoFx.showWarn("物理バックアップ先", "登録されたバックアップ先はありません。");
            return;
        }
        Alert confirm =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "登録を解除しますか？\n" + existing.get(),
                        ButtonType.OK,
                        ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("物理バックアップ先");
        confirm.setHeaderText("登録の解除");
        Optional<ButtonType> ok = confirm.showAndWait();
        if (ok.isEmpty() || ok.get() != ButtonType.OK) {
            return;
        }
        VaultPathStore.clearPhysicalBackupMirrorRoot(conn);
        GazoFx.showWarn("物理バックアップ先", "登録を解除しました。");
    }

    /**
     * 登録済みフォルダへ、暗号化 Vault ツリーの差分コピー（任意でバックアップ側のみにあるファイルを削除）を行う。
     */
    public void syncPhysicalBackupMirror(Stage stage) {
        GazoVaultService v = host.vault.get();
        VaultConnection conn = host.currentConnection.get();
        if (v == null || conn == null) {
            GazoFx.showError("バックアップと同期", "アルバムが開かれていません。");
            return;
        }
        Optional<Path> mirrorOpt = VaultPathStore.loadPhysicalBackupMirrorRoot(conn);
        if (mirrorOpt.isEmpty()) {
            GazoFx.showWarn(
                    "バックアップと同期",
                    "物理バックアップ先が未登録です。\nファイルメニューから「物理バックアップ先を登録…」を実行してください。");
            return;
        }
        Path vaultRoot = v.getVaultPath().toAbsolutePath().normalize();
        Path mirrorRoot = mirrorOpt.get().toAbsolutePath().normalize();
        if (isUnsafePhysicalBackupMirrorPair(vaultRoot, mirrorRoot)) {
            GazoFx.showError(
                    "バックアップと同期",
                    "登録先がアルバムと重なっています。登録を解除し、別フォルダを登録してください。");
            return;
        }
        CheckBox deleteOrphans = new CheckBox("Vault に無いファイルをバックアップから削除する（完全ミラー・危険）");
        deleteOrphans.setSelected(false);
        Label msg =
                new Label(
                        "次のフォルダへ、現在の暗号化アルバムを差分コピーします。\n"
                                + mirrorRoot
                                + "\n\n"
                                + "サイズまたは更新日時が異なるファイルだけ上書きします。");
        msg.setWrapText(true);
        msg.setMaxWidth(480);
        VBox box = new VBox(10, msg, deleteOrphans);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle("バックアップと同期");
        confirm.setHeaderText("同期の実行");
        confirm.getDialogPane().setContent(box);
        confirm.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        boolean doDelete = deleteOrphans.isSelected();
        host.setMainWindowBusy.accept(MainWindowBusyState.busy("バックアップと同期を準備…"));
        CompletableFuture.runAsync(
                        () -> {
                            try {
                                syncVaultMirrorContents(
                                        vaultRoot,
                                        mirrorRoot,
                                        doDelete,
                                        progress ->
                                                Platform.runLater(
                                                        () ->
                                                                host.setMainWindowBusy.accept(
                                                                        MainWindowBusyState.busy(
                                                                                formatCopyProgressMessage(
                                                                                        "バックアップ同期",
                                                                                        progress),
                                                                                progress.copiedFiles(),
                                                                                progress.totalFiles()))));
                            } catch (IOException e) {
                                throw new CompletionException(e);
                            }
                        },
                        MIGRATION_EXECUTOR)
                .whenComplete(
                        (unused, err) ->
                                Platform.runLater(
                                        () -> {
                                            host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                                            if (err != null) {
                                                Throwable t = unwrapAsync(err);
                                                GazoFx.showError(
                                                        "バックアップと同期",
                                                        t.getMessage() == null ? "" : t.getMessage());
                                                return;
                                            }
                                            GazoFx.showWarn("バックアップと同期", "完了しました:\n" + mirrorRoot);
                                        }));
    }

    /** アルバムルートとミラー先が同じツリー内に入れ子になっている場合は true（登録・同期とも拒否）。 */
    static boolean isUnsafePhysicalBackupMirrorPair(Path vaultRoot, Path mirrorRoot) {
        Path v = vaultRoot.toAbsolutePath().normalize();
        Path m = mirrorRoot.toAbsolutePath().normalize();
        if (v.equals(m)) {
            return true;
        }
        return m.startsWith(v) || v.startsWith(m);
    }

    private static String mirrorRelativeKey(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 暗号化ツリーをミラーへ反映する。{@code deleteOrphansNotInVault} が true のとき、ミラーにだけある通常ファイルを削除し、空ディレクトリを掃除する。
     */
    static void syncVaultMirrorContents(
            Path sourceRoot,
            Path mirrorRoot,
            boolean deleteOrphansNotInVault,
            Consumer<GazoVaultService.CopyProgress> onProgress)
            throws IOException {
        Path src = sourceRoot.toAbsolutePath().normalize();
        Path mir = mirrorRoot.toAbsolutePath().normalize();
        Files.createDirectories(mir);
        List<Path> sourceFiles;
        try (Stream<Path> stream = Files.walk(src)) {
            sourceFiles =
                    stream.filter(Files::isRegularFile)
                            .filter(p -> !p.equals(src))
                            .toList();
        }
        Set<String> sourceRels = new HashSet<>();
        for (Path p : sourceFiles) {
            sourceRels.add(mirrorRelativeKey(src, p));
        }
        int totalWork = sourceFiles.size();
        List<Path> orphanDeletes = new ArrayList<>();
        if (deleteOrphansNotInVault) {
            try (Stream<Path> stream = Files.walk(mir)) {
                for (Path mf : stream.toList()) {
                    if (!Files.isRegularFile(mf) || mf.equals(mir)) {
                        continue;
                    }
                    String rel = mirrorRelativeKey(mir, mf);
                    if (!sourceRels.contains(rel)) {
                        orphanDeletes.add(mf);
                    }
                }
            }
            totalWork += orphanDeletes.size();
        }
        int total = Math.max(1, totalWork);
        int done = 0;
        if (onProgress != null) {
            onProgress.accept(new GazoVaultService.CopyProgress(0, total, ""));
        }
        for (Path sourceFile : sourceFiles) {
            String rel = mirrorRelativeKey(src, sourceFile);
            Path dest = mir.resolve(rel);
            boolean copy =
                    !Files.exists(dest)
                            || !Files.isRegularFile(dest)
                            || Files.size(sourceFile) != Files.size(dest)
                            || !Files.getLastModifiedTime(sourceFile)
                                    .equals(Files.getLastModifiedTime(dest));
            if (copy) {
                Path parent = dest.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            done++;
            if (onProgress != null) {
                onProgress.accept(new GazoVaultService.CopyProgress(done, total, rel));
            }
        }
        if (deleteOrphansNotInVault) {
            for (Path mf : orphanDeletes) {
                String delRel = mirrorRelativeKey(mir, mf);
                Files.deleteIfExists(mf);
                done++;
                if (onProgress != null) {
                    onProgress.accept(new GazoVaultService.CopyProgress(done, total, "削除 " + delRel));
                }
            }
            try (Stream<Path> stream = Files.walk(mir)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                    if (p.equals(mir)) {
                        continue;
                    }
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> inner = Files.list(p)) {
                            if (inner.findAny().isEmpty()) {
                                Files.deleteIfExists(p);
                            }
                        }
                    }
                }
            }
        }
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
        boolean simpleVaultCopy = switchMode == VaultSwitchMode.COPY_VAULT_SIMPLE;
        boolean physicalVaultCopy = switchMode == VaultSwitchMode.COPY_PHYSICAL_MIGRATE;
        boolean copyAndMigrate =
                switchMode == VaultSwitchMode.COPY_AND_MIGRATE
                        || switchMode == VaultSwitchMode.COPY_VAULT_SIMPLE
                        || switchMode == VaultSwitchMode.COPY_PHYSICAL_MIGRATE;
        StringBuilder migrationReport = copyAndMigrate ? new StringBuilder() : null;
        char[] pwdCopy = request.connection().isWebDav() ? request.webDavPassword() : null;
        VaultOpenRequest.WebDavPasswordPersistence persistence = request.webDavPasswordPersistence();
        GazoVaultService sourceVault = v;
        MediaCounts[] destinationCountsHolder = new MediaCounts[1];
        if (migrationReport != null) {
            migrationReport.append("開始: ").append(DELETED_TIME_FORMAT.format(LocalDateTime.now())).append('\n');
            migrationReport.append("移行元: ")
                    .append(sourceVault == null ? "(なし)" : sourceVault.getVaultDisplayLocation())
                    .append('\n');
            migrationReport.append("移行先: ").append(request.connection().displayLabel()).append('\n');
        }
        if (copyAndMigrate) {
            if (sourceVault == null) {
                if (pwdCopy != null) {
                    Arrays.fill(pwdCopy, '\0');
                }
                if (migrationReport != null) {
                    migrationReport.append("結果: 失敗 (移行元アルバムが見つかりません)\n");
                    saveMigrationReportQuietly(migrationReport);
                }
                GazoFx.showError("アルバム変更エラー", "移行元アルバムが見つかりません。");
                return;
            }
            if (!confirmMigrationBackup(stage, sourceVault, request.connection(), simpleVaultCopy, physicalVaultCopy)) {
                if (pwdCopy != null) {
                    Arrays.fill(pwdCopy, '\0');
                }
                if (migrationReport != null) {
                    migrationReport.append("結果: キャンセル (バックアップ確認で中断)\n");
                    saveMigrationReportQuietly(migrationReport);
                }
                return;
            }
            if (physicalVaultCopy) {
                if (migrationReport != null) {
                    migrationReport.append("移行モード: 物理コピー（暗号化ツリーそのまま）\n");
                }
                host.setMainWindowBusy.accept(MainWindowBusyState.busy("移行準備中…"));
                final GazoVaultService srcPhysical = sourceVault;
                CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return readMediaCounts(srcPhysical);
                                    } catch (IOException e) {
                                        throw new CompletionException(e);
                                    }
                                },
                                MIGRATION_EXECUTOR)
                        .whenComplete(
                                (counts, err) ->
                                        Platform.runLater(
                                                () -> {
                                                    if (err != null) {
                                                        if (pwdCopy != null) {
                                                            Arrays.fill(pwdCopy, '\0');
                                                        }
                                                        host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                                                        Throwable t = unwrapAsync(err);
                                                        if (migrationReport != null) {
                                                            migrationReport
                                                                    .append("結果: 失敗 (移行元の件数取得失敗) ")
                                                                    .append(t.getMessage() == null ? "" : t.getMessage())
                                                                    .append('\n');
                                                            saveMigrationReportQuietly(migrationReport);
                                                        }
                                                        GazoFx.showError(
                                                                "アルバム変更エラー",
                                                                "移行元の読み取りに失敗しました: "
                                                                        + (t.getMessage() == null ? "" : t.getMessage()));
                                                        return;
                                                    }
                                                    if (migrationReport != null) {
                                                        migrationReport
                                                                .append("移行元件数: images=")
                                                                .append(counts.images())
                                                                .append(", videos=")
                                                                .append(counts.videos())
                                                                .append('\n');
                                                    }
                                                    startPhysicalVaultMigration(
                                                            stage,
                                                            request,
                                                            counts,
                                                            migrationReport,
                                                            pwdCopy,
                                                            persistence,
                                                            destinationCountsHolder,
                                                            srcPhysical);
                                                }));
                return;
            }
            if (simpleVaultCopy) {
                if (migrationReport != null) {
                    migrationReport.append("移行モード: Vault単純コピー（平文を直接コピー・件数検証なし）\n");
                }
                host.setMainWindowBusy.accept(MainWindowBusyState.busy("移行準備中…"));
                final GazoVaultService src = sourceVault;
                CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return readMediaCounts(src);
                                    } catch (IOException e) {
                                        throw new CompletionException(e);
                                    }
                                },
                                MIGRATION_EXECUTOR)
                        .whenComplete(
                                (counts, err) ->
                                        Platform.runLater(
                                                () -> {
                                                    if (err != null) {
                                                        if (pwdCopy != null) {
                                                            Arrays.fill(pwdCopy, '\0');
                                                        }
                                                        host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                                                        Throwable t = unwrapAsync(err);
                                                        if (migrationReport != null) {
                                                            migrationReport
                                                                    .append("結果: 失敗 (移行元の件数取得失敗) ")
                                                                    .append(t.getMessage() == null ? "" : t.getMessage())
                                                                    .append('\n');
                                                            saveMigrationReportQuietly(migrationReport);
                                                        }
                                                        GazoFx.showError(
                                                                "アルバム変更エラー",
                                                                "移行元の読み取りに失敗しました: "
                                                                        + (t.getMessage() == null ? "" : t.getMessage()));
                                                        return;
                                                    }
                                                    if (migrationReport != null) {
                                                        migrationReport
                                                                .append("移行元件数: images=")
                                                                .append(counts.images())
                                                                .append(", videos=")
                                                                .append(counts.videos())
                                                                .append('\n');
                                                    }
                                                    openVaultAfterMigrationPrep(
                                                            stage,
                                                            request,
                                                            null,
                                                            counts,
                                                            migrationReport,
                                                            pwdCopy,
                                                            persistence,
                                                            destinationCountsHolder,
                                                            src,
                                                            true);
                                                }));
                return;
            }
            host.setMainWindowBusy.accept(MainWindowBusyState.busy("移行準備中…"));
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    MediaCounts sc = readMediaCounts(sourceVault);
                                    Path snap =
                                            createMigrationSnapshot(
                                                    sourceVault,
                                                    progress ->
                                                            Platform.runLater(
                                                                    () ->
                                                                            host.setMainWindowBusy.accept(
                                                                                    MainWindowBusyState.busy(
                                                                                            formatCopyProgressMessage(
                                                                                                    "スナップショット作成中",
                                                                                                    progress),
                                                                                            progress.copiedFiles(),
                                                                                            progress.totalFiles()))));
                                    return new SnapshotPrep(sc, snap);
                                } catch (IOException e) {
                                    throw new CompletionException(e);
                                }
                            },
                            MIGRATION_EXECUTOR)
                    .whenComplete(
                            (prep, err) ->
                                    Platform.runLater(
                                            () -> {
                                                if (err != null) {
                                                    if (pwdCopy != null) {
                                                        Arrays.fill(pwdCopy, '\0');
                                                    }
                                                    host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                                                    Throwable t = unwrapAsync(err);
                                                    if (migrationReport != null) {
                                                        migrationReport
                                                                .append("結果: 失敗 (スナップショット作成失敗) ")
                                                                .append(t.getMessage() == null ? "" : t.getMessage())
                                                                .append('\n');
                                                        saveMigrationReportQuietly(migrationReport);
                                                    }
                                                    GazoFx.showError(
                                                            "アルバム変更エラー",
                                                            "コピー元の読み取りに失敗しました: "
                                                                    + (t.getMessage() == null ? "" : t.getMessage()));
                                                    return;
                                                }
                                                if (migrationReport != null) {
                                                    migrationReport
                                                            .append("移行モード: コピー移行（検証あり）\n");
                                                    migrationReport
                                                            .append("移行元件数: images=")
                                                            .append(prep.counts().images())
                                                            .append(", videos=")
                                                            .append(prep.counts().videos())
                                                            .append('\n');
                                                }
                                                openVaultAfterMigrationPrep(
                                                        stage,
                                                        request,
                                                        prep.snapshot(),
                                                        prep.counts(),
                                                        migrationReport,
                                                        pwdCopy,
                                                        persistence,
                                                        destinationCountsHolder,
                                                        sourceVault,
                                                        false);
                                            }));
            return;
        }
        openVaultAfterMigrationPrep(
                stage,
                request,
                null,
                null,
                migrationReport,
                pwdCopy,
                persistence,
                destinationCountsHolder,
                null,
                false);
    }

    /**
     * 物理コピー移行は {@link VaultUnlockFlow#openVaultAsync} を使わない。
     * 先に空 Vault を作らせず、移行元と同じ Vault パスワードを一度だけ聞いてから暗号ツリーをコピーし解錠する。
     */
    private void startPhysicalVaultMigration(
            Stage stage,
            VaultOpenRequest request,
            MediaCounts sourceCountsForMigration,
            StringBuilder migrationReport,
            char[] pwdCopy,
            VaultOpenRequest.WebDavPasswordPersistence persistence,
            MediaCounts[] destinationCountsHolder,
            GazoVaultService migrationSourceVault) {
        Optional<char[]> vaultPassOpt =
                GazoFx.promptVaultPassphrase(
                        stage,
                        "物理コピー移行",
                        "Vault パスワード",
                        "移行元アルバムと同じ Vault パスワードを入力してください。\n"
                                + "コピーする暗号化ツリー（masterkey.cryptomator を含む）は、このパスワードで解錠できる必要があります。");
        if (vaultPassOpt.isEmpty()) {
            if (pwdCopy != null) {
                Arrays.fill(pwdCopy, '\0');
            }
            if (migrationReport != null) {
                migrationReport.append("結果: キャンセル (Vault パスワード未入力)\n");
                saveMigrationReportQuietly(migrationReport);
            }
            host.setMainWindowBusy.accept(MainWindowBusyState.idle());
            return;
        }
        char[] vaultPass = vaultPassOpt.get();
        host.setMainWindowBusy.accept(MainWindowBusyState.busy("物理コピー移行中…"));
        final GazoVaultService[] destHolder = new GazoVaultService[1];
        CompletableFuture.runAsync(
                        () -> {
                            GazoVaultService dest = null;
                            try {
                                dest = new GazoVaultService(request.connection(), pwdCopy);
                                destHolder[0] = dest;
                                Path srcRoot = migrationSourceVault.getVaultPath();
                                Path dstRoot = dest.getVaultPath();
                                dest.close();
                                dest.clearRemoteVaultTreeForPhysicalMigration();
                                clearPhysicalVaultDestination(dstRoot);
                                dest.clearRemoteSyncMetadataIfPresent();
                                copyDirectoryContents(
                                        srcRoot,
                                        dstRoot,
                                        progress ->
                                                Platform.runLater(
                                                        () ->
                                                                host.setMainWindowBusy.accept(
                                                                        MainWindowBusyState.busy(
                                                                                formatCopyProgressMessage(
                                                                                        "暗号化Vaultをコピー中",
                                                                                        progress),
                                                                                progress.copiedFiles(),
                                                                                progress.totalFiles()))));
                                dest.setPhysicalMirrorSeedPendingForNextOpen(dest.isRemoteVault());
                                dest.invalidateStoragePreparedForNextUnlock();
                                dest.unlock(new String(vaultPass));
                                dest.flushStorageToRemote();
                            } catch (Exception e) {
                                if (dest != null) {
                                    try {
                                        dest.close();
                                    } catch (Exception ignored) {
                                        // best effort
                                    }
                                    destHolder[0] = null;
                                }
                                throw new CompletionException(e);
                            } finally {
                                Arrays.fill(vaultPass, '\0');
                            }
                        },
                        MIGRATION_EXECUTOR)
                .thenCompose(
                        unused -> {
                            GazoVaultService d = destHolder[0];
                            if (d == null) {
                                return CompletableFuture.failedFuture(
                                        new IOException("移行先 Vault の準備に失敗しました。"));
                            }
                            try {
                                destinationCountsHolder[0] = readMediaCounts(d);
                            } catch (IOException e) {
                                try {
                                    d.close();
                                } catch (Exception ignored) {
                                    // best effort
                                }
                                destHolder[0] = null;
                                return CompletableFuture.failedFuture(e);
                            }
                            return host.adoptUnlockedVault.adoptUnlockedVault(d);
                        })
                .whenComplete(
                        (unused, err) ->
                                Platform.runLater(
                                        () -> {
                                            host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                                            GazoVaultService adopted = destHolder[0];
                                            if (err != null) {
                                                if (pwdCopy != null) {
                                                    Arrays.fill(pwdCopy, '\0');
                                                }
                                                if (adopted != null) {
                                                    try {
                                                        adopted.close();
                                                    } catch (Exception ignored) {
                                                        // best effort
                                                    }
                                                    destHolder[0] = null;
                                                }
                                                Throwable t = unwrapAsync(err);
                                                if (migrationReport != null) {
                                                    migrationReport
                                                            .append("結果: 失敗 (物理コピー) ")
                                                            .append(t.getMessage() == null ? "" : t.getMessage())
                                                            .append('\n');
                                                    saveMigrationReportQuietly(migrationReport);
                                                }
                                                String msg = physicalMigrationPassphraseErrorMessage(t);
                                                GazoFx.showError("アルバム変更エラー", msg);
                                                return;
                                            }
                                            VaultPathStore.saveLastVaultConnection(
                                                    request.connection(), pwdCopy, persistence);
                                            if (pwdCopy != null) {
                                                Arrays.fill(pwdCopy, '\0');
                                            }
                                            host.setCurrentConnection.accept(request.connection());
                                            host.updateVaultPathLabel.run();
                                            host.refreshAfterVaultChanged.run();
                                            if (sourceCountsForMigration != null) {
                                                Path reportPath = null;
                                                if (migrationReport != null && destinationCountsHolder[0] != null) {
                                                    migrationReport.append("移行先件数: images=")
                                                            .append(destinationCountsHolder[0].images())
                                                            .append(", videos=")
                                                            .append(destinationCountsHolder[0].videos())
                                                            .append('\n');
                                                    migrationReport.append("結果: 成功（物理コピー）\n");
                                                    reportPath = saveMigrationReportQuietly(migrationReport);
                                                }
                                                GazoFx.showWarn(
                                                        "移行完了",
                                                        "物理コピー移行が完了しました。WebDAV の場合はリモートへの同期・アップロードが続くことがあります。\n"
                                                                + "移行元: 画像 "
                                                                + sourceCountsForMigration.images()
                                                                + " / 動画 "
                                                                + sourceCountsForMigration.videos()
                                                                + "\n移行先: 画像 "
                                                                + (destinationCountsHolder[0] == null
                                                                        ? "?"
                                                                        : Integer.toString(
                                                                                destinationCountsHolder[0].images()))
                                                                + " / 動画 "
                                                                + (destinationCountsHolder[0] == null
                                                                        ? "?"
                                                                        : Integer.toString(
                                                                                destinationCountsHolder[0].videos()))
                                                                + (reportPath == null ? "" : "\nレポート: " + reportPath));
                                            }
                                        }));
    }

    private static String physicalMigrationPassphraseErrorMessage(Throwable t) {
        if (t instanceof InvalidPassphraseException
                || hasThrowableCause(t, InvalidPassphraseException.class)
                || t instanceof MasterkeyLoadingFailedException
                || hasThrowableCause(t, MasterkeyLoadingFailedException.class)) {
            return "Vault パスワードが正しくないか、移行元アルバムのパスワードと一致しません。";
        }
        return t.getMessage() == null ? "" : t.getMessage();
    }

    private static boolean hasThrowableCause(Throwable t, Class<?> type) {
        for (int i = 0; i < 10 && t != null; i++) {
            if (type.isInstance(t)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void openVaultAfterMigrationPrep(
            Stage stage,
            VaultOpenRequest request,
            Path snapshotForMigration,
            MediaCounts sourceCountsForMigration,
            StringBuilder migrationReport,
            char[] pwdCopy,
            VaultOpenRequest.WebDavPasswordPersistence persistence,
            MediaCounts[] destinationCountsHolder,
            GazoVaultService migrationSourceVault,
            boolean simpleVaultCopy) {
        boolean copyAndMigrate = snapshotForMigration != null || simpleVaultCopy;
        host.vaultUnlock.openVaultAsync(
                stage,
                request,
                err -> {
                    cleanupMigrationSnapshot(snapshotForMigration);
                    if (copyAndMigrate) {
                        host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                    }
                    if (err instanceof VaultUnlockCancelledException) {
                        if (pwdCopy != null) {
                            Arrays.fill(pwdCopy, '\0');
                        }
                        if (migrationReport != null) {
                            migrationReport.append("結果: キャンセル (解錠キャンセル)\n");
                            saveMigrationReportQuietly(migrationReport);
                        }
                        return;
                    }
                    if (err != null) {
                        if (pwdCopy != null) {
                            Arrays.fill(pwdCopy, '\0');
                        }
                        if (migrationReport != null) {
                            migrationReport.append("結果: 失敗 (接続切替) ").append(err.getMessage()).append('\n');
                            saveMigrationReportQuietly(migrationReport);
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
                        Path reportPath = null;
                        if (migrationReport != null && destinationCountsHolder[0] != null) {
                            migrationReport.append("移行先件数: images=")
                                    .append(destinationCountsHolder[0].images())
                                    .append(", videos=")
                                    .append(destinationCountsHolder[0].videos())
                                    .append('\n');
                            migrationReport
                                    .append(
                                            simpleVaultCopy
                                                    ? "結果: 成功（単純コピー・件数検証スキップ）\n"
                                                    : "結果: 成功\n");
                            reportPath = saveMigrationReportQuietly(migrationReport);
                        }
                        if (simpleVaultCopy) {
                            GazoFx.showWarn(
                                    "移行完了",
                                    "Vault単純コピー移行が完了しました（件数の厳密検証はしていません）。\n"
                                            + "移行元: 画像 "
                                            + sourceCountsForMigration.images()
                                            + " / 動画 "
                                            + sourceCountsForMigration.videos()
                                            + "\n移行先: 画像 "
                                            + (destinationCountsHolder[0] == null
                                                    ? "?"
                                                    : Integer.toString(destinationCountsHolder[0].images()))
                                            + " / 動画 "
                                            + (destinationCountsHolder[0] == null
                                                    ? "?"
                                                    : Integer.toString(destinationCountsHolder[0].videos()))
                                            + (reportPath == null ? "" : "\nレポート: " + reportPath));
                        } else {
                            GazoFx.showWarn(
                                    "移行検証",
                                    "画像 " + sourceCountsForMigration.images() + " 件 / 動画 "
                                            + sourceCountsForMigration.videos()
                                            + " 件で一致しました。"
                                            + (reportPath == null ? "" : "\nレポート: " + reportPath));
                        }
                    }
                },
                newVault -> {
                    if (copyAndMigrate && snapshotForMigration != null) {
                        return CompletableFuture.runAsync(
                                        () -> {
                                            try {
                                                newVault.replaceAllContentFromDirectory(
                                                        snapshotForMigration,
                                                        progress ->
                                                                Platform.runLater(
                                                                        () ->
                                                                                host.setMainWindowBusy.accept(
                                                                                        MainWindowBusyState.busy(
                                                                                                formatCopyProgressMessage(
                                                                                                        "新しいアルバムへコピー中",
                                                                                                        progress),
                                                                                                progress.copiedFiles(),
                                                                                                progress.totalFiles()))));
                                            } catch (IOException e) {
                                                throw new CompletionException(e);
                                            }
                                        },
                                        MIGRATION_EXECUTOR)
                                .thenCompose(
                                        unused -> {
                                            try {
                                                MediaCounts destinationCounts = readMediaCounts(newVault);
                                                if (sourceCountsForMigration != null
                                                        && !sourceCountsForMigration.equals(destinationCounts)) {
                                                    if (migrationReport != null) {
                                                        migrationReport
                                                                .append("移行先件数: images=")
                                                                .append(destinationCounts.images())
                                                                .append(", videos=")
                                                                .append(destinationCounts.videos())
                                                                .append('\n');
                                                        migrationReport.append("結果: 失敗 (件数不一致)\n");
                                                        saveMigrationReportQuietly(migrationReport);
                                                    }
                                                    return CompletableFuture.failedFuture(
                                                            new IOException(
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
                                                                            + ")"));
                                                }
                                                destinationCountsHolder[0] = destinationCounts;
                                            } catch (IOException e) {
                                                return CompletableFuture.failedFuture(e);
                                            }
                                            return host.adoptUnlockedVault.adoptUnlockedVault(newVault);
                                        });
                    }
                    if (simpleVaultCopy && migrationSourceVault != null) {
                        return CompletableFuture.runAsync(
                                        () -> {
                                            try {
                                                newVault.replaceAllContentFromUnlockedPeer(
                                                        migrationSourceVault,
                                                        progress ->
                                                                Platform.runLater(
                                                                        () ->
                                                                                host.setMainWindowBusy.accept(
                                                                                        MainWindowBusyState.busy(
                                                                                                formatCopyProgressMessage(
                                                                                                        "新しいアルバムへコピー中",
                                                                                                        progress),
                                                                                                progress.copiedFiles(),
                                                                                                progress.totalFiles()))));
                                            } catch (IOException e) {
                                                throw new CompletionException(e);
                                            }
                                        },
                                        MIGRATION_EXECUTOR)
                                .thenCompose(
                                        unused -> {
                                            try {
                                                destinationCountsHolder[0] = readMediaCounts(newVault);
                                            } catch (IOException e) {
                                                return CompletableFuture.failedFuture(e);
                                            }
                                            return host.adoptUnlockedVault.adoptUnlockedVault(newVault);
                                        });
                    }
                    return host.adoptUnlockedVault.adoptUnlockedVault(newVault);
                },
                null,
                null);
    }

    private static Throwable unwrapAsync(Throwable err) {
        Throwable t = err;
        for (int i = 0; i < 6 && t != null; i++) {
            if (t instanceof CompletionException && t.getCause() != null) {
                t = t.getCause();
                continue;
            }
            if (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) {
                t = t.getCause();
                continue;
            }
            break;
        }
        return t;
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

        host.setMainWindowBusy.accept(MainWindowBusyState.busy("サムネイルを再作成しています…"));

        Task<Integer> task =
                new Task<>() {
                    @Override
                    protected Integer call() throws Exception {
                        return v.rebuildAllThumbnails();
                    }
                };
        task.setOnSucceeded(
                e -> {
                    host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                    Integer count = task.getValue();
                    GazoFx.showWarn("サムネイル再作成", (count == null ? 0 : count) + " 件を再作成しました。");
                    host.refreshGallery.run();
                });
        task.setOnFailed(
                e -> {
                    host.setMainWindowBusy.accept(MainWindowBusyState.idle());
                    Throwable ex = task.getException();
                    GazoFx.showError("サムネイル再作成エラー", ex != null ? ex.getMessage() : "不明なエラー");
                });

        Thread t = new Thread(task, "gazo-rebuild-thumbnails");
        t.setDaemon(true);
        t.start();
    }

    public void restoreRecentlyDeletedImages(Stage stage) {
        GazoVaultService v = host.vault.get();
        if (v == null) {
            return;
        }
        List<GazoVaultService.RecentlyDeletedImage> recent;
        try {
            recent = v.listRecentlyDeletedImages(20);
        } catch (IOException e) {
            GazoFx.showError("復元エラー", e.getMessage());
            return;
        }
        if (recent.isEmpty()) {
            GazoFx.showWarn("画像を復元", "復元できる最近削除した画像はありません。");
            return;
        }
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            GazoVaultService.RecentlyDeletedImage one = recent.get(i);
            String when = DELETED_TIME_FORMAT.format(one.deletedAt().atZone(ZoneId.systemDefault()));
            preview.append("・ ").append(one.fileName()).append(" (").append(when).append(")\n");
        }
        ButtonType restoreOne = new ButtonType("最新1件を復元");
        ButtonType restoreFive = new ButtonType("最新5件を復元");
        ButtonType restoreAll = new ButtonType("表示中をすべて復元");
        Alert confirm =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "最近削除した画像を復元します。\n\n" + preview,
                        restoreOne,
                        restoreFive,
                        restoreAll,
                        ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("画像を復元");
        confirm.setHeaderText("復元する件数を選択してください");
        Optional<ButtonType> selected = confirm.showAndWait();
        if (selected.isEmpty() || selected.get() == ButtonType.CANCEL) {
            return;
        }
        int limit = selected.get() == restoreOne ? 1 : (selected.get() == restoreFive ? 5 : recent.size());
        try {
            GazoVaultService.RestoreDeletedImagesResult result = v.restoreRecentlyDeletedImages(limit);
            host.refreshAfterVaultChanged.run();
            if (result.failedMessages().isEmpty()) {
                GazoFx.showWarn("画像を復元", result.restoredCount() + " 件を復元しました。");
            } else {
                GazoFx.showError(
                        "復元エラー",
                        result.restoredCount()
                                + " 件を復元しましたが、一部失敗しました。\n"
                                + String.join("\n", result.failedMessages()));
            }
        } catch (IOException e) {
            GazoFx.showError("復元エラー", e.getMessage());
        }
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
        Dialog<VaultSwitchMode> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("アルバム切替方法");
        dialog.setHeaderText("接続先を変更します");

        Label hint =
                new Label(
                        "各モードの概要:\n"
                                + "・検証あり: 一時スナップショット経由で平文をコピーし、画像・動画の件数を照合します。\n"
                                + "・単純コピー: 一時領域を使わず平文を直接コピーします（件数検証なし・速い）。\n"
                                + "・物理コピー: 暗号化された Vault ディレクトリをそのままコピーします（移行元と同じ Vault パスワードを1回だけ入力）。\n"
                                + "・接続のみ: データのコピーは行わず、接続先だけ切り替えます。");
        hint.setWrapText(true);
        hint.setMaxWidth(520);

        ToggleGroup group = new ToggleGroup();
        RadioButton rMigrateFull = new RadioButton("コピー移行（検証あり）");
        rMigrateFull.setUserData(VaultSwitchMode.COPY_AND_MIGRATE);
        rMigrateFull.setToggleGroup(group);
        RadioButton rMigrateSimple = new RadioButton("Vault単純コピー移行");
        rMigrateSimple.setUserData(VaultSwitchMode.COPY_VAULT_SIMPLE);
        rMigrateSimple.setToggleGroup(group);
        RadioButton rMigratePhysical = new RadioButton("物理コピーで移行");
        rMigratePhysical.setUserData(VaultSwitchMode.COPY_PHYSICAL_MIGRATE);
        rMigratePhysical.setToggleGroup(group);
        RadioButton rSwitchOnly = new RadioButton("接続のみ切替（コピーしない）");
        rSwitchOnly.setUserData(VaultSwitchMode.SWITCH_ONLY);
        rSwitchOnly.setToggleGroup(group);
        rMigrateFull.setSelected(true);

        VBox content = new VBox(12, hint, rMigrateFull, rMigrateSimple, rMigratePhysical, rSwitchOnly);
        content.setPadding(new Insets(8, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        ButtonType ok = new ButtonType("続行", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        dialog.setResultConverter(
                buttonType -> {
                    if (buttonType != ok) {
                        return VaultSwitchMode.CANCEL;
                    }
                    Toggle sel = group.getSelectedToggle();
                    if (sel instanceof RadioButton rb && rb.getUserData() instanceof VaultSwitchMode mode) {
                        return mode;
                    }
                    return VaultSwitchMode.CANCEL;
                });

        return dialog.showAndWait().orElse(VaultSwitchMode.CANCEL);
    }

    private static boolean confirmMigrationBackup(
            Stage stage,
            GazoVaultService sourceVault,
            VaultConnection destinationConnection,
            boolean simpleVaultCopy,
            boolean physicalVaultCopy) {
        String sourceLabel = sourceVault.getVaultDisplayLocation();
        String destinationLabel = destinationConnection == null ? "(未設定)" : destinationConnection.displayLabel();
        String modeLine =
                physicalVaultCopy
                        ? "モード: 物理コピー移行（暗号化ツリー・移行元と同じ Vault パスワードが必要）\n"
                        : simpleVaultCopy
                                ? "モード: Vault単純コピー移行（件数検証なし）\n"
                                : "モード: コピー移行（検証あり）\n";
        Alert confirm =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        modeLine
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

    private static void clearPhysicalVaultDestination(Path vaultRoot) throws IOException {
        if (!Files.exists(vaultRoot)) {
            Files.createDirectories(vaultRoot);
            return;
        }
        try (Stream<Path> stream = Files.walk(vaultRoot)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(vaultRoot)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void deletePhysicalCopyTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static Path createMigrationSnapshot(
            GazoVaultService sourceVault, Consumer<GazoVaultService.CopyProgress> onProgress) throws IOException {
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

    private static String formatCopyProgressMessage(String phase, GazoVaultService.CopyProgress progress) {
        int remaining = Math.max(0, progress.totalFiles() - progress.copiedFiles());
        String name = progress.currentRelativePath();
        if (name == null || name.isBlank()) {
            name = "-";
        }
        return phase + " " + progress.copiedFiles() + "/" + progress.totalFiles() + " (残り " + remaining + ") " + name;
    }

    private static void copyDirectoryContents(
            Path sourceRoot, Path destinationRoot, Consumer<GazoVaultService.CopyProgress> onProgress)
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
            onProgress.accept(new GazoVaultService.CopyProgress(0, totalFiles, ""));
        }
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.toList()) {
                if (source.equals(sourceRoot)) {
                    continue;
                }
                String rel = sourceRoot.relativize(source).toString().replace('\\', '/');
                Path target = destinationRoot.resolve(rel);
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
                        onProgress.accept(new GazoVaultService.CopyProgress(copied, totalFiles, rel));
                    }
                }
            }
        }
    }

    private static Path saveMigrationReportQuietly(StringBuilder report) {
        if (report == null || report.isEmpty()) {
            return null;
        }
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".gazo", "reports");
            Files.createDirectories(dir);
            String stamp = MIGRATION_REPORT_STAMP.format(LocalDateTime.now());
            Path file = dir.resolve("migration-" + stamp + ".txt");
            Files.writeString(file, report.toString());
            return file;
        } catch (Exception ignored) {
            return null;
        }
    }
}
