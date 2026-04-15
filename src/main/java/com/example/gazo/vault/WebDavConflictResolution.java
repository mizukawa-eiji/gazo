package com.example.gazo.vault;

/**
 * WebDAV 同期競合の解決方針。
 */
public enum WebDavConflictResolution {
    KEEP_LOCAL,
    KEEP_REMOTE,
    SAVE_AS_CONFLICT_COPY,
    CANCEL
}
