package com.example.gazo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * WebDAV 用ローカルキャッシュのライフサイクル管理。
 * <p>
 * かつては強制終了時にキャッシュを丸ごと消していたが、差分同期（{@code WebDavVaultStorage}）で
 * etag/size 不一致を検出して再ダウンロードできるため、不整合は同期側で回復させる方が起動が圧倒的に速い。
 * 本クラスはセッションマーカーの記録のみを行い、診断目的だけで残す。
 */
public final class WebDavCacheLifecycle {
    private static final Path GAZO_DIR = Path.of(System.getProperty("user.home"), ".gazo");
    private static final Path SESSION_MARKER = GAZO_DIR.resolve(".gazo-session-active");

    private WebDavCacheLifecycle() {}

    /**
     * アプリ起動直後（Vault を開く前）に呼ぶ。セッションマーカーを記録するだけで、
     * キャッシュは温存する（差分同期で整合性が取り戻される前提）。
     */
    public static void onStartupBeforeVaultOpen() throws IOException {
        Files.createDirectories(GAZO_DIR);
        Files.writeString(SESSION_MARKER, ProcessHandle.current().pid() + " " + Instant.now().toString());
    }

    /** JVM 終了時に呼び、セッションマーカーを片付ける（次回起動時の診断用途のみ）。 */
    public static void onJvmShutdownRemoveMarker() {
        try {
            Files.deleteIfExists(SESSION_MARKER);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
