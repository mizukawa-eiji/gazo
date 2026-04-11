package com.example.gazo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@link ImageGalleryCards} がモデル・表示オプション・サムネ読み込み・カード操作にアクセスするための依存関係。
 */
public record ImageGalleryCardsHost(
        Supplier<List<Path>> modelPaths,
        Supplier<Map<String, Set<String>>> tagsByFileName,
        Supplier<Map<String, Set<String>>> canvasLayoutsByFileName,
        Supplier<String> listViewSize,
        Set<Path> listCheckedSelection,
        BooleanSupplier showFileName,
        BooleanSupplier showDate,
        BooleanSupplier showTags,
        CanvasThumbnailLoader thumbnailLoader,
        ImageGalleryCardActions cardActions) {}
