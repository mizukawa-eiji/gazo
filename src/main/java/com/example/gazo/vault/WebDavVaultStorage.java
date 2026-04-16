package com.example.gazo.vault;

import com.example.gazo.VaultConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * WebDAV 上の Vault をローカル作業ディレクトリへ同期して利用する。
 */
public final class WebDavVaultStorage implements VaultStorage {
    private static final String META_FILE_NAME = "sync-state.properties";
    private static final DateTimeFormatter SYNC_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final VaultConnection connection;
    private final WebDavClient client;
    private final Path localMirrorPath;
    private final Path metadataFilePath;
    private final String remoteRootPath;
    private Instant lastSyncAt;

    public WebDavVaultStorage(VaultConnection connection, char[] webDavPassword) {
        this.connection = connection;
        this.client = new WebDavClient(connection.webDavEndpoint(), connection.webDavUsername(), webDavPassword);
        this.remoteRootPath = WebDavClient.normalizePath(connection.webDavBasePath());
        this.localMirrorPath = buildMirrorPath(connection);
        this.metadataFilePath = buildMetadataFilePath(connection);
        this.lastSyncAt = loadLastSyncFromMetadata();
    }

    @Override
    public Path localVaultPath() {
        return localMirrorPath;
    }

    @Override
    public String displayLocation() {
        return connection.displayLabel();
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    @Override
    public String syncStatusSummary() {
        Instant t = lastSyncAt;
        if (t == null || Instant.EPOCH.equals(t)) {
            return "同期: 未完了";
        }
        String formatted = SYNC_TIME_FORMAT.format(t.atZone(ZoneId.systemDefault()));
        return "最終同期: " + formatted;
    }

    @Override
    public void prepareForOpen() throws IOException {
        Files.createDirectories(localMirrorPath);
        // exists() はファイルでも true になり得る。コレクションとして無ければ MKCOL し、実在を directoryExists で確認する。
        if (!client.directoryExists(remoteRootPath)) {
            client.ensureDirectory(remoteRootPath);
            if (!client.directoryExists(remoteRootPath)) {
                throw new IOException(
                        "WebDAV 上にアルバム用フォルダを作成できませんでした。エンドポイント・ベースパス・サーバーの WebDAV 設定を確認してください: "
                                + remoteRootPath);
            }
            saveSyncState(new HashMap<>());
            return;
        }
        syncDownFromRemote();
    }

    @Override
    public void flushChanges() throws IOException {
        syncUpToRemote();
    }

    private void syncDownFromRemote() throws IOException {
        clearLocalMirror();
        Files.createDirectories(localMirrorPath);
        Map<String, SyncStateEntry> syncState = new HashMap<>();
        syncRemoteDirectory(remoteRootPath, localMirrorPath, syncState);
        saveSyncState(syncState);
    }

    private void syncRemoteDirectory(String remoteDirPath, Path localDirPath, Map<String, SyncStateEntry> syncState)
            throws IOException {
        Files.createDirectories(localDirPath);
        for (WebDavClient.Entry entry : client.list(remoteDirPath)) {
            String rel = relativeRemotePath(entry.fullPath());
            if (rel.isBlank()) {
                continue;
            }
            Path target = localMirrorPath.resolve(rel);
            if (entry.directory()) {
                syncRemoteDirectory(entry.fullPath(), target, syncState);
            } else {
                Files.createDirectories(target.getParent());
                byte[] data = client.download(entry.fullPath());
                Files.write(target, data);
                if (entry.lastModified() != null && !Instant.EPOCH.equals(entry.lastModified())) {
                    Files.setLastModifiedTime(target, FileTime.from(entry.lastModified()));
                }
                syncState.put(
                        rel,
                        new SyncStateEntry(
                                entry.etag(),
                                Files.size(target),
                                Files.getLastModifiedTime(target).toInstant()));
            }
        }
    }

    private void syncUpToRemote() throws IOException {
        client.ensureDirectory(remoteRootPath);
        Map<String, RemoteNode> remoteNodes = listRemoteNodes(remoteRootPath);
        LocalSnapshot local = listLocalNodes();
        Map<String, SyncStateEntry> syncState = loadSyncState();

        // ディレクトリを浅い順で作成
        List<String> dirs = new ArrayList<>(local.directories);
        dirs.sort(Comparator.comparingInt(WebDavVaultStorage::depth));
        for (String rel : dirs) {
            if (rel.isBlank()) {
                continue;
            }
            String remotePath = joinRemote(rel);
            if (!remoteNodes.containsKey(rel) || !remoteNodes.get(rel).directory) {
                client.ensureDirectory(remotePath);
            }
        }

        // ファイルアップロード（ETag + ローカル状態で更新判定）
        for (Map.Entry<String, LocalFileNode> entry : local.files.entrySet()) {
            String rel = entry.getKey();
            LocalFileNode localFile = entry.getValue();
            RemoteNode remote = remoteNodes.get(rel);
            SyncStateEntry previous = syncState.get(rel);
            boolean localChanged = hasLocalChangedSinceLastSync(localFile, previous);
            boolean remoteChanged = hasRemoteChangedSinceLastSync(remote, previous);

            if (!localChanged && !remoteChanged && remote != null && !remote.directory) {
                continue;
            }
            if (localChanged && remoteChanged) {
                throw buildConflict(rel, remote, "WebDAV conflict detected for: " + rel);
            }
            if (!localChanged && remoteChanged) {
                // リモート更新を優先して、次回の down で取り込む。
                continue;
            }
            if (remote != null && remote.directory) {
                client.delete(joinRemote(rel));
                remote = null;
            }
            if (localChanged || remote == null) {
                byte[] data = Files.readAllBytes(localMirrorPath.resolve(rel));
                String newEtag;
                try {
                    newEtag = client.upload(joinRemote(rel), data, remote == null ? null : remote.etag, remote == null);
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("precondition failed")) {
                        throw buildConflict(rel, remoteNodes.get(rel), "WebDAV conflict detected for: " + rel);
                    }
                    throw e;
                }
                syncState.put(rel, new SyncStateEntry(newEtag, localFile.size, localFile.lastModified));
            }
        }

        // リモート削除（ファイル先、ディレクトリ後）
        List<String> remoteOnlyFiles = new ArrayList<>();
        List<String> remoteOnlyDirs = new ArrayList<>();
        for (Map.Entry<String, RemoteNode> entry : remoteNodes.entrySet()) {
            String rel = entry.getKey();
            if (rel.isBlank()) {
                continue;
            }
            if (entry.getValue().directory) {
                if (!local.directories.contains(rel)) {
                    remoteOnlyDirs.add(rel);
                }
            } else if (!local.files.containsKey(rel)) {
                remoteOnlyFiles.add(rel);
            }
        }
        for (String rel : remoteOnlyFiles) {
            client.delete(joinRemote(rel));
            syncState.remove(rel);
        }
        remoteOnlyDirs.sort((a, b) -> Integer.compare(depth(b), depth(a)));
        for (String rel : remoteOnlyDirs) {
            client.delete(joinRemote(rel));
        }
        syncState.entrySet().removeIf(e -> !local.files.containsKey(e.getKey()));
        saveSyncState(syncState);
    }

    private Map<String, RemoteNode> listRemoteNodes(String remoteDir) throws IOException {
        Map<String, RemoteNode> out = new HashMap<>();
        walkRemote(remoteDir, out);
        return out;
    }

    private void walkRemote(String remoteDir, Map<String, RemoteNode> out) throws IOException {
        for (WebDavClient.Entry entry : client.list(remoteDir)) {
            String rel = relativeRemotePath(entry.fullPath());
            if (rel.isBlank()) {
                continue;
            }
            out.put(rel, new RemoteNode(entry.directory(), entry.contentLength(), entry.lastModified(), entry.etag()));
            if (entry.directory()) {
                walkRemote(entry.fullPath(), out);
            }
        }
    }

    private LocalSnapshot listLocalNodes() throws IOException {
        Set<String> directories = new HashSet<>();
        Map<String, LocalFileNode> files = new HashMap<>();
        directories.add("");
        try (Stream<Path> stream = Files.walk(localMirrorPath)) {
            for (Path p : stream.toList()) {
                if (p.equals(localMirrorPath)) {
                    continue;
                }
                String rel = localMirrorPath.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    directories.add(rel);
                } else if (Files.isRegularFile(p)) {
                    files.put(
                            rel,
                            new LocalFileNode(
                                    Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant()));
                }
            }
        }
        return new LocalSnapshot(directories, files);
    }

    private void clearLocalMirror() throws IOException {
        if (!Files.exists(localMirrorPath)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(localMirrorPath)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                if (!p.equals(localMirrorPath)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private String relativeRemotePath(String fullRemotePath) {
        String full = WebDavClient.normalizePath(fullRemotePath);
        String root = WebDavClient.normalizePath(remoteRootPath);
        if (full.equals(root)) {
            return "";
        }
        if (!full.startsWith(root + "/")) {
            // サーバによっては endpoint のパス全体が返るため、末尾一致を試す。
            int idx = full.indexOf(root + "/");
            if (idx >= 0) {
                full = full.substring(idx);
            } else {
                return "";
            }
        }
        return full.substring(root.length() + 1);
    }

    private String joinRemote(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return remoteRootPath;
        }
        return remoteRootPath + "/" + relPath.replace('\\', '/');
    }

    private static int depth(String path) {
        if (path == null || path.isBlank()) {
            return 0;
        }
        int depth = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static Path buildMirrorPath(VaultConnection connection) {
        Path root = Path.of(System.getProperty("user.home"), ".gazo", "webdav-cache");
        String key = connection.webDavEndpoint() + "|" + connection.webDavBasePath() + "|" + connection.webDavUsername();
        String hash = sha256Hex(key);
        return root.resolve(hash);
    }

    private static Path buildMetadataFilePath(VaultConnection connection) {
        Path root = Path.of(System.getProperty("user.home"), ".gazo", "webdav-cache-meta");
        String key = connection.webDavEndpoint() + "|" + connection.webDavBasePath() + "|" + connection.webDavUsername();
        String hash = sha256Hex(key);
        return root.resolve(hash).resolve(META_FILE_NAME);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Map<String, SyncStateEntry> loadSyncState() throws IOException {
        Map<String, SyncStateEntry> out = new HashMap<>();
        if (!Files.exists(metadataFilePath)) {
            return out;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(metadataFilePath)) {
            p.load(in);
        }
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("file.")) {
                continue;
            }
            String rel = decodeKey(key.substring("file.".length()));
            String raw = p.getProperty(key, "");
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            String etag = emptyToNull(parts[0]);
            long size = parseLong(parts[1], -1L);
            long mtime = parseLong(parts[2], -1L);
            Instant instant = mtime >= 0 ? Instant.ofEpochMilli(mtime) : Instant.EPOCH;
            out.put(rel, new SyncStateEntry(etag, size, instant));
        }
        return out;
    }

    private void saveSyncState(Map<String, SyncStateEntry> syncState) throws IOException {
        Files.createDirectories(metadataFilePath.getParent());
        Properties p = new Properties();
        for (Map.Entry<String, SyncStateEntry> entry : syncState.entrySet()) {
            SyncStateEntry s = entry.getValue();
            String value = nullToEmpty(s.etag) + "|" + s.size + "|" + s.lastModified.toEpochMilli();
            p.setProperty("file." + encodeKey(entry.getKey()), value);
        }
        try (var out = Files.newOutputStream(metadataFilePath)) {
            p.store(out, "webdav sync state");
        }
        lastSyncAt = Instant.now();
    }

    private Instant loadLastSyncFromMetadata() {
        try {
            if (!Files.exists(metadataFilePath)) {
                return null;
            }
            return Files.getLastModifiedTime(metadataFilePath).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasLocalChangedSinceLastSync(LocalFileNode localFile, SyncStateEntry previous) {
        if (previous == null) {
            return true;
        }
        if (localFile.size != previous.size) {
            return true;
        }
        return !Objects.equals(localFile.lastModified, previous.lastModified);
    }

    private static boolean hasRemoteChangedSinceLastSync(RemoteNode remote, SyncStateEntry previous) {
        if (remote == null) {
            return previous != null;
        }
        if (remote.directory) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        if (remote.etag != null && previous.etag != null) {
            return !remote.etag.equals(previous.etag);
        }
        if (remote.contentLength != previous.size) {
            return true;
        }
        return remote.lastModified != null && !remote.lastModified.equals(previous.lastModified);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String encodeKey(String relPath) {
        return relPath.replace("%", "%25").replace("=", "%3D").replace(":", "%3A");
    }

    private static String decodeKey(String encoded) {
        return encoded.replace("%3D", "=").replace("%3A", ":").replace("%25", "%");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    public void resolveConflict(WebDavSyncConflictException conflict, WebDavConflictResolution resolution) throws IOException {
        if (resolution == null || resolution == WebDavConflictResolution.CANCEL) {
            throw conflict;
        }
        String rel = conflict.relativePath();
        Path localPath = localMirrorPath.resolve(rel);
        Files.createDirectories(localPath.getParent());
        Map<String, SyncStateEntry> syncState = loadSyncState();
        switch (resolution) {
            case KEEP_REMOTE -> {
                Files.write(localPath, conflict.remoteBytes());
                FileTime mtime = conflict.remoteLastModified() == null ? FileTime.from(Instant.now()) : FileTime.from(conflict.remoteLastModified());
                Files.setLastModifiedTime(localPath, mtime);
                syncState.put(rel, new SyncStateEntry(conflict.remoteEtag(), Files.size(localPath), Files.getLastModifiedTime(localPath).toInstant()));
            }
            case KEEP_LOCAL -> {
                byte[] localBytes = conflict.localBytes();
                String etag = client.upload(joinRemote(rel), localBytes, conflict.remoteEtag(), false);
                Files.write(localPath, localBytes);
                syncState.put(rel, new SyncStateEntry(etag, Files.size(localPath), Files.getLastModifiedTime(localPath).toInstant()));
            }
            case SAVE_AS_CONFLICT_COPY -> {
                Path conflictPath = createConflictCopyPath(localPath);
                Files.write(conflictPath, conflict.localBytes());
                Files.write(localPath, conflict.remoteBytes());
                if (conflict.remoteLastModified() != null) {
                    Files.setLastModifiedTime(localPath, FileTime.from(conflict.remoteLastModified()));
                }
                syncState.put(rel, new SyncStateEntry(conflict.remoteEtag(), Files.size(localPath), Files.getLastModifiedTime(localPath).toInstant()));
                String conflictRel = localMirrorPath.relativize(conflictPath).toString().replace('\\', '/');
                // 競合コピーは次回 flush 時に createOnly としてアップロードされる
                syncState.remove(conflictRel);
            }
            default -> throw new IOException("Unsupported resolution: " + resolution);
        }
        saveSyncState(syncState);
    }

    private WebDavSyncConflictException buildConflict(String relPath, RemoteNode remote, String message) throws IOException {
        byte[] localBytes = Files.exists(localMirrorPath.resolve(relPath)) ? Files.readAllBytes(localMirrorPath.resolve(relPath)) : new byte[0];
        byte[] remoteBytes;
        String etag = remote == null ? null : remote.etag;
        Instant remoteLastModified = remote == null ? null : remote.lastModified;
        try {
            remoteBytes = client.download(joinRemote(relPath));
        } catch (IOException e) {
            remoteBytes = new byte[0];
        }
        return new WebDavSyncConflictException(relPath, localBytes, remoteBytes, etag, remoteLastModified, message);
    }

    private static Path createConflictCopyPath(Path original) {
        String fileName = original.getFileName().toString();
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = original.getParent();
        Path candidate = dir.resolve(base + " (conflict " + stamp + ")" + ext);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + " (conflict " + stamp + "-" + counter + ")" + ext);
            counter++;
        }
        return candidate;
    }

    private record RemoteNode(boolean directory, long contentLength, Instant lastModified, String etag) {}

    private record LocalFileNode(long size, Instant lastModified) {}

    private record LocalSnapshot(Set<String> directories, Map<String, LocalFileNode> files) {}

    private record SyncStateEntry(String etag, long size, Instant lastModified) {}
}
