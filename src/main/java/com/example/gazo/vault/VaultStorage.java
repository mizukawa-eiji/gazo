package com.example.gazo.vault;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Vault の実体ストレージへのアクセス。
 */
public interface VaultStorage extends AutoCloseable {
    Path localVaultPath();

    String displayLocation();

    default boolean isRemote() {
        return false;
    }

    /**
     * Vault を開く前にローカル作業ディレクトリを準備する。
     */
    default void prepareForOpen() throws IOException {
        // no-op
    }

    /**
     * ローカル作業ディレクトリの変更を永続ストレージへ反映する。
     */
    default void flushChanges() throws IOException {
        // no-op
    }

    /**
     * UI 表示用の同期ステータス要約。
     */
    default String syncStatusSummary() {
        return "ローカル保存";
    }

    @Override
    default void close() throws IOException {
        // no-op
    }
}
