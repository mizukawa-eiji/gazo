package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import javafx.scene.image.Image;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Vault サムネ優先の一覧用サムネイルと、元ファイルの {@link Image} 読み込み。
 */
public final class VaultImageLoader {
    private final Supplier<GazoVaultService> vault;

    public VaultImageLoader(Supplier<GazoVaultService> vault) {
        this.vault = vault;
    }

    /** 重複チェック用サムネコールバック（{@code ThumbnailLoader#load} と同じ引数）。 */
    public Image load(Path path, int width, int height) {
        return loadThumbnail(path, width, height);
    }

    public Image loadThumbnail(Path path, int width, int height) {
        Path source = path;
        GazoVaultService v = vault.get();
        if (v != null) {
            Path thumb = v.thumbnailFor(path);
            if (thumb != null) {
                source = thumb;
            }
        }
        try (InputStream in = Files.newInputStream(source)) {
            return new Image(in, width, height, true, true);
        } catch (IOException e) {
            return null;
        }
    }

    public Image loadOriginalImage(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return new Image(in);
        } catch (IOException e) {
            return null;
        }
    }
}
