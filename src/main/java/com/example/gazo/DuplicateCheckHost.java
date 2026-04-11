package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 重複/類似チェックウィンドウがメインアプリから参照する依存関係。
 */
public record DuplicateCheckHost(
        GazoVaultService vault,
        Stage primaryStage,
        ExecutorService galleryImageLoadExecutor,
        ExecutorService vaultHeavySerialExecutor,
        Predicate<Path> isPendingDelete,
        Consumer<List<Path>> markPendingDelete,
        Consumer<Boolean> flushPendingDeletes,
        Runnable refreshTagFilterOptions,
        Runnable refreshGallery,
        Runnable refreshVideoList,
        Function<String, Set<String>> parseUserTags,
        ThumbnailLoader thumbnails) {

    @FunctionalInterface
    public interface ThumbnailLoader {
        Image load(Path path, int width, int height);
    }
}
