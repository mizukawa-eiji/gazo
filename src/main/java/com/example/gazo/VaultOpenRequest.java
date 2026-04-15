package com.example.gazo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Vault オープン時の接続情報（WebDAV 認証情報を含む）。
 */
public final class VaultOpenRequest {
    /**
     * WebDAV パスワードを {@code settings.properties} にどう扱うか（ローカル Vault では未使用）。
     */
    public enum WebDavPasswordPersistence {
        /** チェック「パスワードを保存」がオンのとき */
        STORE,
        /** オフのとき（保存済みがあれば削除） */
        CLEAR,
        /** 保存済みパスワードで自動接続したときなど、ファイル上の値は変更しない */
        UNCHANGED
    }

    private final VaultConnection connection;
    private final char[] webDavPassword;
    private final WebDavPasswordPersistence webDavPasswordPersistence;

    private VaultOpenRequest(
            VaultConnection connection, char[] webDavPassword, WebDavPasswordPersistence webDavPasswordPersistence) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.webDavPassword = webDavPassword == null ? null : Arrays.copyOf(webDavPassword, webDavPassword.length);
        this.webDavPasswordPersistence = Objects.requireNonNull(webDavPasswordPersistence, "webDavPasswordPersistence");
    }

    public static VaultOpenRequest local(VaultConnection connection) {
        return new VaultOpenRequest(connection, null, WebDavPasswordPersistence.UNCHANGED);
    }

    public static VaultOpenRequest webDav(VaultConnection connection, char[] webDavPassword) {
        return webDav(connection, webDavPassword, WebDavPasswordPersistence.UNCHANGED);
    }

    public static VaultOpenRequest webDav(
            VaultConnection connection, char[] webDavPassword, WebDavPasswordPersistence persistence) {
        return new VaultOpenRequest(connection, webDavPassword, persistence);
    }

    public VaultConnection connection() {
        return connection;
    }

    public char[] webDavPassword() {
        return webDavPassword == null ? null : Arrays.copyOf(webDavPassword, webDavPassword.length);
    }

    public WebDavPasswordPersistence webDavPasswordPersistence() {
        return webDavPasswordPersistence;
    }

    public void clearSecrets() {
        if (webDavPassword != null) {
            Arrays.fill(webDavPassword, '\0');
        }
    }
}
