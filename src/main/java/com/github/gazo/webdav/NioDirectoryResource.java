package com.github.gazo.webdav;

import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.XmlWriter;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Represents a directory in a java.nio.file.FileSystem, exposed via WebDAV.
 */
public class NioDirectoryResource extends NioResource
        implements MakeCollectionableResource, PutableResource, GetableResource, PropFindableResource {

    private static final Logger log = LoggerFactory.getLogger(NioDirectoryResource.class);

    public NioDirectoryResource(String host, NioResourceFactory factory, Path path) {
        super(host, factory, path);
    }

    @Override
    public CollectionResource createCollection(String name) {
        Path newDir = path.resolve(name);
        try {
            Files.createDirectories(newDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + newDir, e);
        }
        return new NioDirectoryResource(host, factory, newDir);
    }

    @Override
    public Resource child(String name) {
        Path child = path.resolve(name);
        return factory.resolveFile(host, child);
    }

    @Override
    public List<? extends Resource> getChildren() {
        List<Resource> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(path)) {
            stream.sorted((a, b) -> {
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }).forEach(child -> {
                Resource res = factory.resolveFile(host, child);
                if (res != null) {
                    children.add(res);
                }
            });
        } catch (IOException e) {
            log.error("Error listing directory: {}", path, e);
        }
        return children;
    }

    @Override
    public Resource createNew(String name, InputStream in, Long length, String contentType) throws IOException {
        Path dest = path.resolve(name);
        try (OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
        return factory.resolveFile(host, dest);
    }

    @Override
    public String checkRedirect(Request request) {
        return null;
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType)
            throws IOException, NotAuthorizedException {
        XmlWriter w = new XmlWriter(out);
        w.open("html");
        w.open("body");
        w.begin("h1").open().writeText(getName()).close();
        w.open("table");
        for (Resource r : getChildren()) {
            w.open("tr");
            w.open("td");
            String href = factory.toResourcePath(
                    r instanceof NioResource nioRes ? nioRes.getPath() : path.resolve(r.getName()));
            w.begin("a").writeAtt("href", href).open().writeText(r.getName()).close();
            w.close("td");
            w.begin("td").open().writeText(String.valueOf(r.getModifiedDate())).close();
            w.close("tr");
        }
        w.close("table");
        w.close("body");
        w.close("html");
        w.flush();
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        return "text/html";
    }

    @Override
    public Long getContentLength() {
        return null;
    }

    @Override
    protected void doCopy(Path dest) {
        try {
            copyRecursive(path, dest);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy directory to: " + dest, e);
        }
    }

    private void copyRecursive(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (Stream<Path> stream = Files.list(src)) {
            stream.forEach(child -> {
                Path target = dst.resolve(child.getFileName().toString());
                try {
                    if (Files.isDirectory(child)) {
                        copyRecursive(child, target);
                    } else {
                        Files.copy(child, target);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
