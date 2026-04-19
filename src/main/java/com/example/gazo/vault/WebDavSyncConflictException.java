package com.example.gazo.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * WebDAV 同期時にローカルとリモートの更新が衝突したことを示す。
 */
public final class WebDavSyncConflictException extends IOException {
    private final String relativePath;
    private final byte[] localBytes;
    private final byte[] remoteBytes;
    private final String remoteEtag;
    private final Instant remoteLastModified;
    /** ローカルミラー上の最終更新（取得できなければ null） */
    private final Instant localLastModified;
    /** PROPFIND で得たリモートの Content-Length（ダウンロード失敗時の表示用）。不明は -1 */
    private final long remoteListedContentLength;

    public WebDavSyncConflictException(
            String relativePath,
            byte[] localBytes,
            byte[] remoteBytes,
            String remoteEtag,
            Instant remoteLastModified,
            Instant localLastModified,
            long remoteListedContentLength,
            String message) {
        super(message);
        this.relativePath = relativePath;
        this.localBytes = localBytes == null ? new byte[0] : localBytes.clone();
        this.remoteBytes = remoteBytes == null ? new byte[0] : remoteBytes.clone();
        this.remoteEtag = remoteEtag;
        this.remoteLastModified = remoteLastModified;
        this.localLastModified = localLastModified;
        this.remoteListedContentLength = remoteListedContentLength;
    }

    public String relativePath() {
        return relativePath;
    }

    public byte[] localBytes() {
        return localBytes.clone();
    }

    public byte[] remoteBytes() {
        return remoteBytes.clone();
    }

    public String remoteEtag() {
        return remoteEtag;
    }

    public Instant remoteLastModified() {
        return remoteLastModified;
    }

    public Instant localLastModified() {
        return localLastModified;
    }

    /** PROPFIND の getcontentlength。{@code -1} は不明。 */
    public long remoteListedContentLength() {
        return remoteListedContentLength;
    }

    public boolean isTextLike() {
        return looksLikeUtf8Text(localBytes) && looksLikeUtf8Text(remoteBytes);
    }

    public String localTextPreview() {
        return decodeUtf8(localBytes);
    }

    public String remoteTextPreview() {
        return decodeUtf8(remoteBytes);
    }

    private static boolean looksLikeUtf8Text(byte[] data) {
        if (data == null || data.length == 0) {
            return true;
        }
        int suspicious = 0;
        for (byte b : data) {
            int c = b & 0xff;
            if (c == 0) {
                return false;
            }
            if (c < 0x09) {
                suspicious++;
            }
        }
        return suspicious < Math.max(2, data.length / 20);
    }

    private static String decodeUtf8(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
