package com.github.cryptovault.webdav.resource;

import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.resource.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Milton resource backed by a directory in a {@link java.nio.file.FileSystem}.
 */
public class NioDirectoryResource implements CollectionResource, PutableResource,
        MakeCollectionableResource, GetableResource, PropFindableResource,
        DeletableResource, MoveableResource, CopyableResource {

    private static final Logger LOG = LoggerFactory.getLogger(NioDirectoryResource.class);

    private final Path path;
    private final NioResourceFactory factory;

    public NioDirectoryResource(Path path, NioResourceFactory factory) {
        this.path = path;
        this.factory = factory;
    }

    public Path getNioPath() {
        return path;
    }

    // --- Resource ---

    @Override
    public String getUniqueId() {
        return path.toAbsolutePath().toString();
    }

    @Override
    public String getName() {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : "";
    }

    @Override
    public Object authenticate(String user, String password) {
        return factory.authenticate(user, password);
    }

    @Override
    public boolean authorise(Request request, Request.Method method, Auth auth) {
        return factory.authorise(auth);
    }

    @Override
    public String getRealm() {
        return factory.getRealm();
    }

    @Override
    public Date getModifiedDate() {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new Date(attrs.lastModifiedTime().toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String checkRedirect(Request request) {
        return null;
    }

    // --- PropFindableResource ---

    @Override
    public Date getCreateDate() {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new Date(attrs.creationTime().toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    // --- CollectionResource ---

    @Override
    public Resource child(String childName) throws NotAuthorizedException, BadRequestException {
        Path childPath = path.resolve(childName);
        if (!Files.exists(childPath)) {
            return null;
        }
        return factory.wrapPath(childPath);
    }

    @Override
    public List<? extends Resource> getChildren() throws NotAuthorizedException, BadRequestException {
        List<Resource> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                children.add(factory.wrapPath(entry));
            }
        } catch (IOException e) {
            LOG.warn("Failed to list directory {}: {}", path, e.getMessage());
        }
        return children;
    }

    // --- PutableResource ---

    @Override
    public Resource createNew(String newName, InputStream inputStream, Long length, String contentType)
            throws IOException, ConflictException, NotAuthorizedException, BadRequestException {
        Path newFile = path.resolve(newName);
        Files.copy(inputStream, newFile, StandardCopyOption.REPLACE_EXISTING);
        return new NioFileResource(newFile, factory);
    }

    // --- MakeCollectionableResource ---

    @Override
    public CollectionResource createCollection(String newName)
            throws NotAuthorizedException, ConflictException, BadRequestException {
        Path newDir = path.resolve(newName);
        try {
            Files.createDirectories(newDir);
        } catch (IOException e) {
            throw new ConflictException(this, "Failed to create directory: " + e.getMessage());
        }
        return new NioDirectoryResource(newDir, factory);
    }

    // --- GetableResource (for directory listing) ---

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType)
            throws IOException, NotAuthorizedException, BadRequestException, NotFoundException {
        // WebDAV PROPFIND handles directory listing; GET on a folder can return empty
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        return "httpd/unix-directory";
    }

    @Override
    public Long getContentLength() {
        return null;
    }

    // --- DeletableResource ---

    @Override
    public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new BadRequestException(this, "Failed to delete directory: " + e.getMessage());
        }
    }

    // --- MoveableResource ---

    @Override
    public void moveTo(CollectionResource rDest, String name)
            throws ConflictException, NotAuthorizedException, BadRequestException {
        if (!(rDest instanceof NioDirectoryResource)) {
            throw new BadRequestException(this, "Destination is not a NIO directory");
        }
        Path destDir = ((NioDirectoryResource) rDest).getNioPath();
        try {
            Files.move(path, destDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ConflictException(this, "Move failed: " + e.getMessage());
        }
    }

    // --- CopyableResource ---

    @Override
    public void copyTo(CollectionResource toCollection, String name)
            throws NotAuthorizedException, BadRequestException, ConflictException {
        if (!(toCollection instanceof NioDirectoryResource)) {
            throw new BadRequestException(this, "Destination is not a NIO directory");
        }
        Path destDir = ((NioDirectoryResource) toCollection).getNioPath();
        try {
            copyRecursive(path, destDir.resolve(name));
        } catch (IOException e) {
            throw new ConflictException(this, "Copy failed: " + e.getMessage());
        }
    }

    private void copyRecursive(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path entry : stream) {
                    Path entryName = entry.getFileName();
                    if (entryName != null) {
                        copyRecursive(entry, target.resolve(entryName.toString()));
                    }
                }
            }
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
