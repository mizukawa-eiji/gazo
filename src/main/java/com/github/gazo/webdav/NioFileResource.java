package com.github.gazo.webdav;

import io.milton.common.ContentTypeUtils;
import io.milton.common.RangeUtils;
import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.resource.GetableResource;
import io.milton.resource.PropFindableResource;
import io.milton.resource.ReplaceableResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Represents a file in a java.nio.file.FileSystem, exposed via WebDAV.
 */
public class NioFileResource extends NioResource
        implements GetableResource, PropFindableResource, ReplaceableResource {

    private static final Logger log = LoggerFactory.getLogger(NioFileResource.class);

    public NioFileResource(String host, NioResourceFactory factory, Path path) {
        super(host, factory, path);
    }

    @Override
    public Long getContentLength() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("Could not read file size: {}", path, e);
            return null;
        }
    }

    @Override
    public String getContentType(String preferredList) {
        String name = getName();
        String mime = ContentTypeUtils.findContentTypes(name);
        return ContentTypeUtils.findAcceptableContentType(mime, preferredList);
    }

    @Override
    public String checkRedirect(Request request) {
        return null;
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType)
            throws IOException, NotFoundException {
        try (InputStream in = Files.newInputStream(path)) {
            if (range != null) {
                log.debug("sendContent: ranged content: {}", path);
                RangeUtils.writeRange(in, range, out);
            } else {
                log.debug("sendContent: whole file: {}", path);
                in.transferTo(out);
            }
            out.flush();
        } catch (java.nio.file.NoSuchFileException e) {
            throw new NotFoundException("File not found: " + path);
        }
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return factory.getMaxAgeSeconds();
    }

    @Override
    public void replaceContent(InputStream in, Long length)
            throws BadRequestException, ConflictException, NotAuthorizedException {
        try (OutputStream out = Files.newOutputStream(path)) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new BadRequestException("Could not write to: " + path, e);
        }
    }

    @Override
    protected void doCopy(Path dest) {
        try {
            Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy to: " + dest, e);
        }
    }
}
