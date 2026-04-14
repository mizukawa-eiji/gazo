package com.github.gazo.webdav;

import io.milton.http.LockManager;
import io.milton.http.ResourceFactory;
import io.milton.http.SecurityManager;
import io.milton.http.fs.NullSecurityManager;
import io.milton.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Milton ResourceFactory backed by java.nio.file.Path.
 * Works with any NIO FileSystem, including CryptoFS.
 */
public class NioResourceFactory implements ResourceFactory {

    private static final Logger log = LoggerFactory.getLogger(NioResourceFactory.class);

    private final Path root;
    private final SecurityManager securityManager;
    private final String contextPath;
    private LockManager lockManager;

    public NioResourceFactory(Path root) {
        this(root, new NullSecurityManager(), "");
    }

    public NioResourceFactory(Path root, SecurityManager securityManager, String contextPath) {
        this.root = root;
        this.securityManager = securityManager;
        this.contextPath = contextPath != null ? contextPath : "";
        log.info("NioResourceFactory initialised: root={}", root);
    }

    @Override
    public Resource getResource(String host, String url) {
        log.debug("getResource host={} url={}", host, url);
        String stripped = stripContext(url);
        Path resolved = resolvePath(stripped);
        return resolveFile(host, resolved);
    }

    Resource resolveFile(String host, Path path) {
        try {
            if (!Files.exists(path)) {
                log.debug("Path does not exist: {}", path);
                return null;
            }
            if (Files.isDirectory(path)) {
                return new NioDirectoryResource(host, this, path);
            } else {
                return new NioFileResource(host, this, path);
            }
        } catch (Exception e) {
            log.error("Error resolving path: {}", path, e);
            return null;
        }
    }

    Path resolvePath(String url) {
        if (url == null || url.isEmpty() || url.equals("/")) {
            return root;
        }
        String cleaned = url;
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            return root;
        }
        String[] parts = cleaned.split("/");
        Path current = root;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                continue;
            }
            current = current.resolve(part);
        }
        return current;
    }

    String toResourcePath(Path path) {
        try {
            return "/" + root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return "/" + path.getFileName();
        }
    }

    private String stripContext(String url) {
        if (contextPath != null && !contextPath.isEmpty() && url.startsWith("/" + contextPath)) {
            url = url.substring(contextPath.length() + 1);
            if (url.isEmpty()) {
                url = "/";
            }
        }
        return url;
    }

    public Path getRoot() {
        return root;
    }

    public SecurityManager getSecurityManager() {
        return securityManager;
    }

    public String getRealm(String host) {
        return securityManager.getRealm(host);
    }

    public LockManager getLockManager() {
        return lockManager;
    }

    public void setLockManager(LockManager lockManager) {
        this.lockManager = lockManager;
    }

    String getContextPath() {
        return contextPath;
    }

    long getMaxAgeSeconds() {
        return 3600;
    }
}
