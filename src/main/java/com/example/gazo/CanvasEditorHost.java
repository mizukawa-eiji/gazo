package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@link CanvasEditor} が Vault・選択・サムネ読み込み・オリジナル表示にアクセスするための依存関係。
 */
public record CanvasEditorHost(
        Supplier<GazoVaultService> vault,
        Set<Path> canvasSelection,
        BooleanSupplier showFileName,
        CanvasThumbnailLoader thumbnailLoader,
        BiConsumer<Path, List<Path>> showOriginalImageViewer) {}
