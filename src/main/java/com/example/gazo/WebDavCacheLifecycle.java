package com.example.gazo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 強制終了などで WebDAV 用ローカルキャッシュが中途半端に残るのを避ける。
 * 正常終了時はセッションマーカーを消し、次回起動ではキャッシュを消さない。
 */
public final class WebDavCacheLifecycle {
    private static final Path GAZO_DIR = Path.of(System.getProperty("user.home"), ".gazo");
    private static final Path WEBDAV_CACHE = GAZO_DIR.resolve("webdav-cache");
    private static final Path WEBDAV_CACHE_META = GAZO_DIR.resolve("webdav-cache-meta");
    private static final Path SESSION_MARKER = GAZO_DIR.resolve(".gazo-session-active");

    private WebDavCacheLifecycle() {}

    /**
     * アプリ起動直後（Vault を開く前）に呼ぶ。前回マーカーが残っていれば異常終了とみなしキャッシュを空にする。
     */
    public static void onStartupBeforeVaultOpen() throws IOException {
        Files.createDirectories(GAZO_DIR);
        if (Files.isRegularFile(SESSION_MARKER)) {
            deleteDirectoryTreeIfExists(WEBDAV_CACHE);
            deleteDirectoryTreeIfExists(WEBDAV_CACHE_META);
            Files.deleteIfExists(SESSION_MARKER);
        }
        Files.writeString(SESSION_MARKER, ProcessHandle.current().pid() + " " + Instant.now().toString());
    }

    /** JVM 終了時に呼び、次回起動でキャッシュを消されないようにする。 */
    public static void onJvmShutdownRemoveMarker() {
        try {
            Files.deleteIfExists(SESSION_MARKER);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteDirectoryTreeIfExists(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
