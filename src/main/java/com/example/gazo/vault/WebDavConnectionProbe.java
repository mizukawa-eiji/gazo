package com.example.gazo.vault;

import com.example.gazo.VaultConnection;

import java.io.IOException;
import java.util.Objects;

/**
 * WebDAV 接続設定の疎通確認専用プローブ。
 */
public final class WebDavConnectionProbe {
    private WebDavConnectionProbe() {
    }

    public static void test(VaultConnection connection, char[] webDavPassword) throws IOException {
        Objects.requireNonNull(connection, "connection");
        if (!connection.isWebDav()) {
            throw new IllegalArgumentException("WebDAV connection is required.");
        }
        if (webDavPassword == null || webDavPassword.length == 0) {
            throw new IllegalArgumentException("WebDAV password is required.");
        }
        WebDavClient client = new WebDavClient(connection.webDavEndpoint(), connection.webDavUsername(), webDavPassword);
        String remoteRootPath = WebDavClient.normalizePath(connection.webDavBasePath());
        if (!client.directoryExists(remoteRootPath)) {
            client.ensureDirectory(remoteRootPath);
        }
        if (!client.directoryExists(remoteRootPath)) {
            throw new IOException("WebDAV 上の保存先フォルダにアクセスできません: " + remoteRootPath);
        }
        // 取得可能かの最終確認（認証ミスやアクセス権不足を検知しやすくする）。
        client.list(remoteRootPath);
    }
}
