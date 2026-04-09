package com.example.gazo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * ユーザーホームの設定ファイルに Vault パスを保存・復元する。
 */
public final class VaultPathStore {
    private static final String APP_DIR_NAME = ".gazo";
    private static final String DEFAULT_VAULT_DIR_NAME = "vault";
    private static final String CONFIG_FILE_NAME = "settings.properties";
    private static final String CONFIG_KEY_LAST_VAULT_PATH = "lastVaultPath";

    private VaultPathStore() {
    }

    private static Path appConfigDir() {
        return Paths.get(System.getProperty("user.home"), APP_DIR_NAME);
    }

    private static Path appConfigFile() {
        return appConfigDir().resolve(CONFIG_FILE_NAME);
    }

    private static Path defaultVaultPath() {
        return appConfigDir().resolve(DEFAULT_VAULT_DIR_NAME);
    }

    public static Path loadInitialVaultPath() {
        Path defaultPath = defaultVaultPath();
        Path file = appConfigFile();
        if (!Files.exists(file)) {
            return defaultPath;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            String raw = properties.getProperty(CONFIG_KEY_LAST_VAULT_PATH, "").trim();
            if (raw.isEmpty()) {
                return defaultPath;
            }
            return Paths.get(raw);
        } catch (Exception ignored) {
            return defaultPath;
        }
    }

    public static void saveLastVaultPath(Path vaultPath) {
        try {
            Files.createDirectories(appConfigDir());
            Path file = appConfigFile();
            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
            properties.setProperty(CONFIG_KEY_LAST_VAULT_PATH, vaultPath.toAbsolutePath().toString());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "gazo settings");
            }
        } catch (IOException e) {
            GazoFx.showWarn("設定保存エラー", "Vault パスの保存に失敗しました: " + e.getMessage());
        }
    }
}
