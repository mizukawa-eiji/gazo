package com.example.gazo.vault;

import com.example.gazo.VaultConnection;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 接続情報から VaultStorage を生成する。
 */
public final class VaultStorageFactory {
    private VaultStorageFactory() {
    }

    public static VaultStorage create(VaultConnection connection, char[] webDavPassword) {
        Objects.requireNonNull(connection, "connection");
        if (connection.isWebDav()) {
            if (webDavPassword == null || webDavPassword.length == 0) {
                throw new IllegalArgumentException("WebDAV password is required.");
            }
            return new WebDavVaultStorage(connection, webDavPassword);
        }
        Path localPath = Objects.requireNonNull(connection.localPath(), "localPath");
        return new LocalFsVaultStorage(localPath);
    }
}
