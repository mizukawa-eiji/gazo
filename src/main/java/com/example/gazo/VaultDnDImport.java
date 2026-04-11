package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.scene.Node;
import javafx.scene.input.TransferMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * メインウィンドウへのファイル／フォルダーのドラッグ＆ドロップで Vault に画像・動画を取り込む。
 */
public final class VaultDnDImport {
    private static final String GALLERY_NORMAL_STYLE =
            "-fx-background-color: linear-gradient(to bottom, #f2efe7, #ebe5d8);";
    private static final String GALLERY_DRAG_HIGHLIGHT_STYLE =
            "-fx-background-color: linear-gradient(to bottom, #e9e5da, #ddd5c3); -fx-border-color: #b9ae97; -fx-border-width: 2; -fx-border-style: segments(8, 6);";

    private final ImportDropHost host;

    public VaultDnDImport(ImportDropHost host) {
        this.host = Objects.requireNonNull(host);
    }

    private GazoVaultService vault() {
        return host.vault().get();
    }

    public void attachTo(Node dropTarget) {
        dropTarget.setOnDragOver(event -> {
            if (event.getGestureSource() != dropTarget
                    && event.getDragboard().hasFiles()
                    && hasImportableEntries(event.getDragboard().getFiles())) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        dropTarget.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles() && hasImportableEntries(event.getDragboard().getFiles())) {
                host.imageGalleryFlow().setStyle(GALLERY_DRAG_HIGHLIGHT_STYLE);
                host.videoGalleryFlow().get().setStyle(GALLERY_DRAG_HIGHLIGHT_STYLE);
            }
            event.consume();
        });

        dropTarget.setOnDragExited(event -> {
            host.imageGalleryFlow().setStyle(GALLERY_NORMAL_STYLE);
            host.videoGalleryFlow().get().setStyle(GALLERY_NORMAL_STYLE);
            event.consume();
        });

        dropTarget.setOnDragDropped(event -> {
            var db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                success = importDroppedFiles(db.getFiles());
            }
            event.setDropCompleted(success);
            host.imageGalleryFlow().setStyle(GALLERY_NORMAL_STYLE);
            host.videoGalleryFlow().get().setStyle(GALLERY_NORMAL_STYLE);
            event.consume();
        });
    }

    private boolean importDroppedFiles(List<java.io.File> files) {
        boolean importedAny = false;
        try {
            for (java.io.File file : files) {
                host.setImportStatusLabel().accept(file.getName());
                Path path = file.toPath();
                if (Files.isDirectory(path)) {
                    importedAny |= importImagesFromDirectory(path);
                } else if (ImportFileType.isImage(path)) {
                    try {
                        vault().importImage(path);
                        importedAny = true;
                    } catch (IOException e) {
                        GazoFx.showError("保存エラー", file.getName() + " の保存に失敗しました: " + e.getMessage());
                    }
                } else if (ImportFileType.isVideo(path)) {
                    try {
                        vault().importVideo(path);
                        importedAny = true;
                    } catch (IOException e) {
                        GazoFx.showError("保存エラー", file.getName() + " の保存に失敗しました: " + e.getMessage());
                    }
                }
            }
        } finally {
            host.clearImportStatusLabel().run();
        }
        if (importedAny) {
            host.onImportCompleteRefresh().run();
        }
        return importedAny;
    }

    private boolean hasImportableEntries(List<java.io.File> files) {
        for (java.io.File file : files) {
            Path path = file.toPath();
            if (Files.isDirectory(path) || ImportFileType.isImage(path) || ImportFileType.isVideo(path)) {
                return true;
            }
        }
        return false;
    }

    private boolean importImagesFromDirectory(Path directory) {
        boolean importedAny = false;
        try (Stream<Path> stream = Files.walk(directory)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> ImportFileType.isImage(p) || ImportFileType.isVideo(p))
                    .toList();
            try {
                for (Path file : files) {
                    String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
                    host.setImportStatusLabel().accept(name);
                    try {
                        Path importedPath;
                        if (ImportFileType.isImage(file)) {
                            importedPath = vault().importImage(file);
                        } else {
                            importedPath = vault().importVideo(file);
                        }
                        importedAny = true;
                        Set<String> folderTags = ImportFolderTagging.folderTagsForPathUnderRoot(directory, file);
                        if (!folderTags.isEmpty()) {
                            Set<String> tags = new LinkedHashSet<>(vault().getTags(importedPath));
                            tags.addAll(folderTags);
                            vault().setTags(importedPath, tags);
                        }
                    } catch (IOException e) {
                        GazoFx.showError("保存エラー", file.getFileName() + " の保存に失敗しました: " + e.getMessage());
                    }
                }
            } finally {
                host.clearImportStatusLabel().run();
            }
        } catch (IOException e) {
            GazoFx.showError("フォルダー読み込みエラー", directory.getFileName() + " の読み込みに失敗しました: " + e.getMessage());
        }
        return importedAny;
    }
}
