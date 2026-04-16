package com.example.gazo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * ユーザーホームの設定ファイルに Vault パスを保存・復元する。
 */
public final class VaultPathStore {
    private static final String APP_DIR_NAME = ".gazo";
    private static final String DEFAULT_VAULT_DIR_NAME = "vault";
    private static final String CONFIG_FILE_NAME = "settings.properties";
    private static final String CONFIG_KEY_LAST_VAULT_PATH = "lastVaultPath";
    private static final String CONFIG_KEY_LAST_VAULT_TYPE = "lastVaultType";
    private static final String CONFIG_KEY_WEBDAV_ENDPOINT = "lastWebDavEndpoint";
    private static final String CONFIG_KEY_WEBDAV_BASE_PATH = "lastWebDavBasePath";
    private static final String CONFIG_KEY_WEBDAV_USERNAME = "lastWebDavUsername";
    private static final String CONFIG_KEY_WEBDAV_PASSWORD_ENC = "lastWebDavPasswordEnc";

    private static final String GALLERY_SHOW_FILE_NAME = "gallery.showFileName";
    private static final String GALLERY_SHOW_DATE = "gallery.showDate";
    private static final String GALLERY_SHOW_TAGS = "gallery.showTags";
    private static final String GALLERY_LIST_VIEW_SIZE = "gallery.listViewSize";
    private static final String GALLERY_IMAGE_NAME_QUERY = "gallery.imageNameQuery";
    private static final String GALLERY_TAG_FILTERS = "gallery.tagFilters";
    private static final String CONFLICT_DHASH_SAME_MAX = "conflict.dhash.sameMax";
    private static final String CONFLICT_DHASH_NEAR_MAX = "conflict.dhash.nearMax";

    private static final Set<String> VALID_LIST_VIEW_SIZES = Set.of("小", "中", "大");

    private VaultPathStore() {
    }

    /** 画像一覧タブの表示・絞り込み設定（settings.properties に保存）。 */
    public record GallerySettings(
            boolean showFileName,
            boolean showDate,
            boolean showTags,
            String listViewSize,
            String imageNameQuery,
            List<String> tagFilters) {

        public static GallerySettings defaults() {
            return new GallerySettings(true, true, true, "中", "", List.of());
        }
    }

    /**
     * 競合ダイアログで使う dHash 距離しきい値。
     *
     * @param sameMax 「ほぼ同一」とみなす上限（0..63）
     * @param nearMax 「近い」とみなす上限（1..64, sameMax以上）
     */
    public record ConflictDHashThresholds(int sameMax, int nearMax) {
        public static ConflictDHashThresholds defaults() {
            return new ConflictDHashThresholds(3, 8);
        }
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
        VaultConnection connection = loadInitialVaultConnection();
        if (connection.isLocal() && connection.localPath() != null) {
            return connection.localPath();
        }
        return defaultVaultPath();
    }

    public static VaultConnection loadInitialVaultConnection() {
        Path defaultPath = defaultVaultPath();
        Path file = appConfigFile();
        if (!Files.exists(file)) {
            return VaultConnection.local(defaultPath);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            String typeRaw = properties.getProperty(CONFIG_KEY_LAST_VAULT_TYPE, "local").trim().toLowerCase();
            String endpoint = properties.getProperty(CONFIG_KEY_WEBDAV_ENDPOINT, "").trim();
            String basePath = properties.getProperty(CONFIG_KEY_WEBDAV_BASE_PATH, "").trim();
            String username = properties.getProperty(CONFIG_KEY_WEBDAV_USERNAME, "").trim();
            String encPwd = properties.getProperty(CONFIG_KEY_WEBDAV_PASSWORD_ENC, "").trim();
            boolean webDavFieldsComplete = !endpoint.isEmpty() && !basePath.isEmpty() && !username.isEmpty();
            // 保存済み WebDAV パスワードがあるのに lastVaultType だけ未更新のときも、起動時に WebDAV へつなぐ。
            if (webDavFieldsComplete && ("webdav".equals(typeRaw) || !encPwd.isEmpty())) {
                return VaultConnection.webDav(endpoint, basePath, username);
            }
            String raw = properties.getProperty(CONFIG_KEY_LAST_VAULT_PATH, "").trim();
            if (raw.isEmpty()) {
                return VaultConnection.local(defaultPath);
            }
            return VaultConnection.local(Paths.get(raw));
        } catch (Exception ignored) {
            return VaultConnection.local(defaultPath);
        }
    }

    public static void saveLastVaultPath(Path vaultPath) {
        saveLastVaultConnection(VaultConnection.local(vaultPath));
    }

    /**
     * 接続先のみ保存。WebDAV パスワードは、ファイル内のアカウントと同一ならそのまま残す。
     */
    public static void saveLastVaultConnection(VaultConnection connection) {
        saveLastVaultConnection(connection, null, VaultOpenRequest.WebDavPasswordPersistence.UNCHANGED);
    }

    /**
     * 接続先と WebDAV パスワードの保存方針をまとめて保存する（Vault 解錠成功後など）。
     */
    public static void saveLastVaultConnection(
            VaultConnection connection,
            char[] webDavPassword,
            VaultOpenRequest.WebDavPasswordPersistence persistence) {
        try {
            Files.createDirectories(appConfigDir());
            Path file = appConfigFile();
            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
            String oldEp = properties.getProperty(CONFIG_KEY_WEBDAV_ENDPOINT, "").trim();
            String oldBase = properties.getProperty(CONFIG_KEY_WEBDAV_BASE_PATH, "").trim();
            String oldUser = properties.getProperty(CONFIG_KEY_WEBDAV_USERNAME, "").trim();
            boolean sameWebDavAccount =
                    connection != null
                            && connection.isWebDav()
                            && connection.webDavEndpoint().equals(oldEp)
                            && connection.webDavBasePath().equals(oldBase)
                            && connection.webDavUsername().equals(oldUser);

            if (connection != null && connection.isWebDav()) {
                properties.setProperty(CONFIG_KEY_LAST_VAULT_TYPE, "webdav");
                properties.remove(CONFIG_KEY_LAST_VAULT_PATH);
                properties.setProperty(CONFIG_KEY_WEBDAV_ENDPOINT, connection.webDavEndpoint());
                properties.setProperty(CONFIG_KEY_WEBDAV_BASE_PATH, connection.webDavBasePath());
                properties.setProperty(CONFIG_KEY_WEBDAV_USERNAME, connection.webDavUsername());
                applyWebDavPasswordPersistence(properties, webDavPassword, persistence, sameWebDavAccount);
            } else {
                Path vaultPath = connection == null ? defaultVaultPath() : connection.localPath();
                properties.setProperty(CONFIG_KEY_LAST_VAULT_TYPE, "local");
                properties.setProperty(CONFIG_KEY_LAST_VAULT_PATH, vaultPath.toAbsolutePath().toString());
                properties.remove(CONFIG_KEY_WEBDAV_ENDPOINT);
                properties.remove(CONFIG_KEY_WEBDAV_BASE_PATH);
                properties.remove(CONFIG_KEY_WEBDAV_USERNAME);
                properties.remove(CONFIG_KEY_WEBDAV_PASSWORD_ENC);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "gazo settings");
            }
        } catch (IOException e) {
            GazoFx.showWarn("設定保存エラー", "アルバム パスの保存に失敗しました: " + e.getMessage());
        }
    }

    private static void applyWebDavPasswordPersistence(
            Properties properties,
            char[] webDavPassword,
            VaultOpenRequest.WebDavPasswordPersistence persistence,
            boolean sameWebDavAccountAsFileBeforeUpdate) {
        switch (persistence) {
            case STORE -> {
                if (webDavPassword != null && webDavPassword.length > 0) {
                    try {
                        properties.setProperty(CONFIG_KEY_WEBDAV_PASSWORD_ENC, WebDavPasswordCodec.encode(webDavPassword));
                    } catch (Exception e) {
                        GazoFx.showWarn("設定保存エラー", "WebDAV パスワードの暗号化に失敗しました: " + e.getMessage());
                    }
                } else {
                    properties.remove(CONFIG_KEY_WEBDAV_PASSWORD_ENC);
                }
            }
            case CLEAR -> properties.remove(CONFIG_KEY_WEBDAV_PASSWORD_ENC);
            case UNCHANGED -> {
                if (!sameWebDavAccountAsFileBeforeUpdate) {
                    properties.remove(CONFIG_KEY_WEBDAV_PASSWORD_ENC);
                }
            }
        }
    }

    /**
     * 保存済み WebDAV パスワード（接続先がファイルと一致するときのみ）。
     */
    public static Optional<char[]> loadStoredWebDavPassword(VaultConnection connection) {
        if (connection == null || !connection.isWebDav()) {
            return Optional.empty();
        }
        Path file = appConfigFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (Exception e) {
            return Optional.empty();
        }
        String endpoint = properties.getProperty(CONFIG_KEY_WEBDAV_ENDPOINT, "").trim();
        String basePath = properties.getProperty(CONFIG_KEY_WEBDAV_BASE_PATH, "").trim();
        String username = properties.getProperty(CONFIG_KEY_WEBDAV_USERNAME, "").trim();
        if (!connection.webDavEndpoint().equals(endpoint)
                || !connection.webDavBasePath().equals(basePath)
                || !connection.webDavUsername().equals(username)) {
            return Optional.empty();
        }
        String enc = properties.getProperty(CONFIG_KEY_WEBDAV_PASSWORD_ENC, "").trim();
        if (enc.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(WebDavPasswordCodec.decode(enc));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static GallerySettings loadGallerySettings() {
        Path file = appConfigFile();
        if (!Files.exists(file)) {
            return GallerySettings.defaults();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (Exception ignored) {
            return GallerySettings.defaults();
        }
        boolean showFile = Boolean.parseBoolean(properties.getProperty(GALLERY_SHOW_FILE_NAME, "true"));
        boolean showDate = Boolean.parseBoolean(properties.getProperty(GALLERY_SHOW_DATE, "true"));
        boolean showTags = Boolean.parseBoolean(properties.getProperty(GALLERY_SHOW_TAGS, "true"));
        String size = properties.getProperty(GALLERY_LIST_VIEW_SIZE, "中").trim();
        if (!VALID_LIST_VIEW_SIZES.contains(size)) {
            size = "中";
        }
        String query = properties.getProperty(GALLERY_IMAGE_NAME_QUERY, "");
        if (query == null) {
            query = "";
        }
        List<String> tags = parseTagFilters(properties.getProperty(GALLERY_TAG_FILTERS, ""));
        return new GallerySettings(showFile, showDate, showTags, size, query, tags);
    }

    public static void saveGallerySettings(GallerySettings settings) {
        try {
            Files.createDirectories(appConfigDir());
            Path file = appConfigFile();
            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
            properties.setProperty(GALLERY_SHOW_FILE_NAME, Boolean.toString(settings.showFileName()));
            properties.setProperty(GALLERY_SHOW_DATE, Boolean.toString(settings.showDate()));
            properties.setProperty(GALLERY_SHOW_TAGS, Boolean.toString(settings.showTags()));
            String size = settings.listViewSize() == null ? "中" : settings.listViewSize().trim();
            if (!VALID_LIST_VIEW_SIZES.contains(size)) {
                size = "中";
            }
            properties.setProperty(GALLERY_LIST_VIEW_SIZE, size);
            String q = settings.imageNameQuery() == null ? "" : settings.imageNameQuery();
            properties.setProperty(GALLERY_IMAGE_NAME_QUERY, q);
            properties.setProperty(GALLERY_TAG_FILTERS, formatTagFilters(settings.tagFilters()));
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "gazo settings");
            }
        } catch (IOException e) {
            GazoFx.showWarn("設定保存エラー", "画像一覧の設定の保存に失敗しました: " + e.getMessage());
        }
    }

    public static ConflictDHashThresholds loadConflictDHashThresholds() {
        Path file = appConfigFile();
        if (!Files.exists(file)) {
            return ConflictDHashThresholds.defaults();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (Exception ignored) {
            return ConflictDHashThresholds.defaults();
        }
        int same = parseIntOrDefault(properties.getProperty(CONFLICT_DHASH_SAME_MAX), 3);
        int near = parseIntOrDefault(properties.getProperty(CONFLICT_DHASH_NEAR_MAX), 8);
        return normalizeThresholds(same, near);
    }

    public static void saveConflictDHashThresholds(ConflictDHashThresholds thresholds) {
        ConflictDHashThresholds normalized = normalizeThresholds(
                thresholds == null ? 3 : thresholds.sameMax(),
                thresholds == null ? 8 : thresholds.nearMax());
        try {
            Files.createDirectories(appConfigDir());
            Path file = appConfigFile();
            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
            properties.setProperty(CONFLICT_DHASH_SAME_MAX, Integer.toString(normalized.sameMax()));
            properties.setProperty(CONFLICT_DHASH_NEAR_MAX, Integer.toString(normalized.nearMax()));
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "gazo settings");
            }
        } catch (IOException e) {
            GazoFx.showWarn("設定保存エラー", "競合比較のしきい値の保存に失敗しました: " + e.getMessage());
        }
    }

    private static ConflictDHashThresholds normalizeThresholds(int same, int near) {
        int s = Math.max(0, Math.min(63, same));
        int n = Math.max(1, Math.min(64, near));
        if (n < s) {
            n = s;
        }
        return new ConflictDHashThresholds(s, n);
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<String> parseTagFilters(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            int i = line.indexOf('\r');
            if (i >= 0) {
                line = line.substring(0, i);
            }
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String formatTagFilters(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String t : tags) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String one = t.replace("\r", "").replace("\n", " ").trim();
            if (one.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(one);
        }
        return sb.toString();
    }
}
