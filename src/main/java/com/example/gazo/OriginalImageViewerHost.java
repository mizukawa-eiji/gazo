package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.scene.image.Image;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * オリジナル画像ビューアが参照する依存関係。
 */
public record OriginalImageViewerHost(
        GazoVaultService vault,
        Consumer<Path> editTags,
        Function<Path, Image> loadOriginalImage) {}
