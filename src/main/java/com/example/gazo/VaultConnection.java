package com.example.gazo;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Vault の接続先を表す。
 */
public final class VaultConnection {
    public enum Type {
        LOCAL,
        WEBDAV
    }

    private final Type type;
    private final Path localPath;
    private final String webDavEndpoint;
    private final String webDavBasePath;
    private final String webDavUsername;

    private VaultConnection(
            Type type,
            Path localPath,
            String webDavEndpoint,
            String webDavBasePath,
            String webDavUsername) {
        this.type = Objects.requireNonNull(type, "type");
        this.localPath = localPath;
        this.webDavEndpoint = webDavEndpoint;
        this.webDavBasePath = webDavBasePath;
        this.webDavUsername = webDavUsername;
    }

    public static VaultConnection local(Path localPath) {
        return new VaultConnection(Type.LOCAL, Objects.requireNonNull(localPath, "localPath"), null, null, null);
    }

    public static VaultConnection webDav(String endpoint, String basePath, String username) {
        String ep = normalizeText(endpoint);
        String bp = normalizePath(basePath);
        String un = normalizeText(username);
        if (ep == null || bp == null || un == null) {
            throw new IllegalArgumentException("endpoint/basePath/username are required for WebDAV");
        }
        return new VaultConnection(Type.WEBDAV, null, ep, bp, un);
    }

    public Type type() {
        return type;
    }

    public boolean isWebDav() {
        return type == Type.WEBDAV;
    }

    public boolean isLocal() {
        return type == Type.LOCAL;
    }

    public Path localPath() {
        return localPath;
    }

    public String webDavEndpoint() {
        return webDavEndpoint;
    }

    public String webDavBasePath() {
        return webDavBasePath;
    }

    public String webDavUsername() {
        return webDavUsername;
    }

    public String displayLabel() {
        if (isLocal()) {
            return localPath == null ? "(未設定)" : localPath.toString();
        }
        return "webdav://" + webDavUsername + "@" + webDavEndpoint + webDavBasePath;
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizePath(String value) {
        String t = normalizeText(value);
        if (t == null) {
            return null;
        }
        String p = t.replace('\\', '/');
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
