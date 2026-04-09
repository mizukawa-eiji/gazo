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

    private static final String GALLERY_SHOW_FILE_NAME = "gallery.showFileName";
    private static final String GALLERY_SHOW_DATE = "gallery.showDate";
    private static final String GALLERY_SHOW_TAGS = "gallery.showTags";
    private static final String GALLERY_LIST_VIEW_SIZE = "gallery.listViewSize";
    private static final String GALLERY_IMAGE_NAME_QUERY = "gallery.imageNameQuery";
    private static final String GALLERY_TAG_FILTERS = "gallery.tagFilters";

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
