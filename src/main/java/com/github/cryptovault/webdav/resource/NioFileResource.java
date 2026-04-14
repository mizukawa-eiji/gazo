package com.github.cryptovault.webdav.resource;

import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.resource.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.Map;

/**
 * Milton resource backed by a regular file in a {@link java.nio.file.FileSystem}
 * (typically a CryptoFS instance).
 */
public class NioFileResource implements GetableResource, PropFindableResource,
        DeletableResource, ReplaceableResource, MoveableResource, CopyableResource {

    private final Path path;
    private final NioResourceFactory factory;

    public NioFileResource(Path path, NioResourceFactory factory) {
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

    // --- GetableResource ---

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType)
            throws IOException, NotAuthorizedException, BadRequestException, NotFoundException {
        if (range != null) {
            try (InputStream in = Files.newInputStream(path)) {
                long skipped = in.skip(range.getStart());
                long remaining = range.getFinish() - range.getStart() + 1;
                byte[] buf = new byte[8192];
                int read;
                while (remaining > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                    out.write(buf, 0, read);
                    remaining -= read;
                }
            }
        } else {
            Files.copy(path, out);
        }
        out.flush();
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        try {
            String ct = Files.probeContentType(path);
            return ct != null ? ct : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Long getContentLength() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    // --- DeletableResource ---

    @Override
    public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new BadRequestException(this, "Failed to delete: " + e.getMessage());
        }
    }

    // --- ReplaceableResource ---

    @Override
    public void replaceContent(InputStream in, Long length)
            throws BadRequestException, ConflictException, NotAuthorizedException {
        try {
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BadRequestException(this, "Failed to replace content: " + e.getMessage());
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
            Files.copy(path, destDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ConflictException(this, "Copy failed: " + e.getMessage());
        }
    }
}
