package com.example.gazo.vault;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 既存のローカルディレクトリをそのまま利用する実装。
 */
public final class LocalFsVaultStorage implements VaultStorage {
    private final Path vaultPath;

    public LocalFsVaultStorage(Path vaultPath) {
        this.vaultPath = Objects.requireNonNull(vaultPath, "vaultPath");
    }

    @Override
    public Path localVaultPath() {
        return vaultPath;
    }

    @Override
    public String displayLocation() {
        return vaultPath.toString();
    }
}
