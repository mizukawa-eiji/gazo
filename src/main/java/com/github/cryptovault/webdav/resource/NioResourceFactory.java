package com.github.cryptovault.webdav.resource;

import io.milton.http.Auth;
import io.milton.http.ResourceFactory;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A Milton {@link ResourceFactory} that maps WebDAV request paths to entries
 * inside an arbitrary {@link java.nio.file.FileSystem}.
 * <p>
 * Because CryptoFS exposes a standard {@code java.nio.file.FileSystem},
 * plugging it in here transparently exposes the decrypted vault contents
 * over WebDAV.
 */
public class NioResourceFactory implements ResourceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(NioResourceFactory.class);

    private final FileSystem fileSystem;
    private final String realm;
    private final String expectedUser;
    private final String expectedPassword;

    /**
     * @param fileSystem       the NIO FileSystem whose root will be served
     * @param realm            HTTP Basic auth realm name
     * @param expectedUser     accepted username (null to disable auth)
     * @param expectedPassword accepted password (null to disable auth)
     */
    public NioResourceFactory(FileSystem fileSystem, String realm,
                              String expectedUser, String expectedPassword) {
        this.fileSystem = fileSystem;
        this.realm = realm;
        this.expectedUser = expectedUser;
        this.expectedPassword = expectedPassword;
    }

    @Override
    public Resource getResource(String host, String path)
            throws NotAuthorizedException, BadRequestException {

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        String normalised = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;

        Path nioPath = fileSystem.getPath(normalised);

        if (!Files.exists(nioPath)) {
            LOG.debug("Resource not found: {}", normalised);
            return null;
        }

        return wrapPath(nioPath);
    }

    /**
     * Wrap a NIO {@link Path} as the appropriate Milton resource type.
     */
    public Resource wrapPath(Path nioPath) {
        if (Files.isDirectory(nioPath)) {
            return new NioDirectoryResource(nioPath, this);
        } else {
            return new NioFileResource(nioPath, this);
        }
    }

    public String getRealm() {
        return realm;
    }

    /**
     * Validate credentials. Returns a non-null object on success.
     */
    public Object authenticate(String user, String password) {
        if (expectedUser == null) {
            return "anonymous";
        }
        if (expectedUser.equals(user) && expectedPassword != null && expectedPassword.equals(password)) {
            return user;
        }
        return null;
    }

    /**
     * Authorise every authenticated request (or all if auth is disabled).
     */
    public boolean authorise(Auth auth) {
        if (expectedUser == null) {
            return true;
        }
        return auth != null && auth.getTag() != null;
    }
}
