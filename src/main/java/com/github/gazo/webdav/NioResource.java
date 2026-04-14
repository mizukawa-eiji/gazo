package com.github.gazo.webdav;

import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.Request.Method;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;

/**
 * Abstract base for NIO-backed Milton resources.
 */
public abstract class NioResource implements Resource, MoveableResource, CopyableResource, DeletableResource, DigestResource {

    private static final Logger log = LoggerFactory.getLogger(NioResource.class);

    protected Path path;
    protected final NioResourceFactory factory;
    protected final String host;

    protected NioResource(String host, NioResourceFactory factory, Path path) {
        this.host = host;
        this.factory = factory;
        this.path = path;
    }

    @Override
    public String getUniqueId() {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return (attrs.lastModifiedTime().toMillis() + "_" + attrs.size() + "_" + path).hashCode() + "";
        } catch (IOException e) {
            return path.toString().hashCode() + "";
        }
    }

    @Override
    public String getName() {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "";
        }
        return fileName.toString();
    }

    @Override
    public Object authenticate(String user, String password) {
        return factory.getSecurityManager().authenticate(user, password);
    }

    @Override
    public Object authenticate(DigestResponse digestRequest) {
        return factory.getSecurityManager().authenticate(digestRequest);
    }

    @Override
    public boolean isDigestAllowed() {
        return true;
    }

    @Override
    public boolean authorise(Request request, Method method, Auth auth) {
        return factory.getSecurityManager().authorise(request, method, auth, this);
    }

    @Override
    public String getRealm() {
        return factory.getRealm(host);
    }

    @Override
    public Date getModifiedDate() {
        try {
            return new Date(Files.getLastModifiedTime(path).toMillis());
        } catch (IOException e) {
            return new Date();
        }
    }

    public Date getCreateDate() {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new Date(attrs.creationTime().toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void delete() {
        try {
            if (Files.isDirectory(path)) {
                deleteRecursive(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete: " + path, e);
        }
    }

    private void deleteRecursive(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            stream.forEach(child -> {
                try {
                    if (Files.isDirectory(child)) {
                        deleteRecursive(child);
                    } else {
                        Files.delete(child);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        Files.delete(dir);
    }

    @Override
    public void moveTo(CollectionResource newParent, String newName) {
        if (newParent instanceof NioDirectoryResource nioDir) {
            Path dest = nioDir.getPath().resolve(newName);
            try {
                Files.move(path, dest);
                this.path = dest;
            } catch (IOException e) {
                throw new RuntimeException("Failed to move to: " + dest, e);
            }
        } else {
            throw new RuntimeException("Destination must be a NioDirectoryResource, got: " + newParent.getClass());
        }
    }

    @Override
    public void copyTo(CollectionResource newParent, String newName) {
        if (newParent instanceof NioDirectoryResource nioDir) {
            Path dest = nioDir.getPath().resolve(newName);
            doCopy(dest);
        } else {
            throw new RuntimeException("Destination must be a NioDirectoryResource, got: " + newParent.getClass());
        }
    }

    protected abstract void doCopy(Path dest);

    public Path getPath() {
        return path;
    }
}
