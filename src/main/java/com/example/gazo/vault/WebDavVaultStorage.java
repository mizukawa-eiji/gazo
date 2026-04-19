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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
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
    private volatile Consumer<WebDavSyncProgress> syncProgressListener;
    /**
     * 物理コピーでミラーに Vault を詰めた直後の再解錠時、{@link #syncDownFromRemote} でミラーを消さない。
     */
    private volatile boolean physicalMirrorSeedPending;

    private record FileDown(String remotePath, String rel, String etag, Instant lastModified) {}

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
        if (physicalMirrorSeedPending) {
            physicalMirrorSeedPending = false;
            Path vaultMarker = localMirrorPath.resolve("vault.cryptomator");
            if (Files.isRegularFile(vaultMarker)) {
                // 初回解錠でリモートに Cryptomator の骨格が既にある場合でも、ローカルミラーを消さずに続行する。
                saveSyncState(new HashMap<>());
                return;
            }
        }
        syncDownFromRemote();
    }

    void setPhysicalMirrorSeedPending(boolean pending) {
        this.physicalMirrorSeedPending = pending;
    }

    void clearSyncMetadataFile() throws IOException {
        Files.deleteIfExists(metadataFilePath);
    }

    /**
     * 接続ルート（{@code remoteRootPath}）の直下および配下のリソースをすべて削除する。
     * コレクション {@code remoteRootPath} 自体は残す。
     * <p>
     * 物理コピー移行で、移行先を一度解錠したときにリモートへできた Cryptomator の骨格（別の
     * {@code masterkey.cryptomator} 等）が残ると、コピーした Vault と二重になり WebDAV 競合になるため、
     * ローカルへ暗号ツリーを詰める直前に呼ぶ。
     */
    void deleteAllRemoteVaultContents() throws IOException {
        if (!client.directoryExists(remoteRootPath)) {
            return;
        }
        deleteRemoteDirectoryChildrenRecursive(remoteRootPath);
    }

    private void deleteRemoteDirectoryChildrenRecursive(String remoteDir) throws IOException {
        for (WebDavClient.Entry entry : client.list(remoteDir)) {
            String path = entry.fullPath();
            if (entry.directory()) {
                deleteRemoteDirectoryChildrenRecursive(path);
                client.delete(path);
            } else {
                client.delete(path);
            }
        }
    }

    /** リモートに 1 ファイルでもあれば true（物理コピー先が空かの判定）。 */
    boolean remoteTreeHasAnyFile() throws IOException {
        List<FileDown> tmp = new ArrayList<>();
        collectRemoteFiles(remoteRootPath, tmp);
        return !tmp.isEmpty();
    }

    @Override
    public void flushChanges() throws IOException {
        syncUpToRemote();
    }

    public void setSyncProgressListener(Consumer<WebDavSyncProgress> listener) {
        this.syncProgressListener = listener;
    }

    /** UI の進捗オーバーレイを閉じるなど。{@link GazoVaultService} から finally で呼ぶ。 */
    void notifySyncUiComplete() {
        Consumer<WebDavSyncProgress> c = syncProgressListener;
        if (c != null) {
            c.accept(new WebDavSyncProgress(1, 1, "同期完了", ""));
        }
    }

    private void reportProgress(int completed, int total, String phase, String path) {
        Consumer<WebDavSyncProgress> c = syncProgressListener;
        if (c != null) {
            c.accept(new WebDavSyncProgress(completed, total, phase, path == null ? "" : path));
        }
    }

    private void syncDownFromRemote() throws IOException {
        clearLocalMirror();
        Files.createDirectories(localMirrorPath);
        List<FileDown> downloads = new ArrayList<>();
        collectRemoteFiles(remoteRootPath, downloads);
        int total = Math.max(1, downloads.size());
        Map<String, SyncStateEntry> syncState = new HashMap<>();
        int step = 0;
        for (FileDown d : downloads) {
            reportProgress(++step, total, "取得", d.rel());
            Path target = localMirrorPath.resolve(d.rel());
            Files.createDirectories(target.getParent());
            byte[] data = client.download(d.remotePath());
            Files.write(target, data);
            if (d.lastModified() != null && !Instant.EPOCH.equals(d.lastModified())) {
                Files.setLastModifiedTime(target, FileTime.from(d.lastModified()));
            }
            syncState.put(
                    d.rel(),
                    new SyncStateEntry(
                            d.etag(),
                            Files.size(target),
                            Files.getLastModifiedTime(target).toInstant()));
        }
        saveSyncState(syncState);
    }

    private void collectRemoteFiles(String remoteDir, List<FileDown> out) throws IOException {
        for (WebDavClient.Entry entry : client.list(remoteDir)) {
            String rel = relativeRemotePath(entry.fullPath());
            if (rel.isBlank()) {
                continue;
            }
            if (entry.directory()) {
                collectRemoteFiles(entry.fullPath(), out);
            } else {
                Instant lm = entry.lastModified() == null ? Instant.EPOCH : entry.lastModified();
                out.add(new FileDown(entry.fullPath(), rel, entry.etag(), lm));
            }
        }
    }

    private void syncUpToRemote() throws IOException {
        client.ensureDirectory(remoteRootPath);
        Map<String, RemoteNode> remoteNodes = listRemoteNodes(remoteRootPath);
        LocalSnapshot local = listLocalNodes();
        Map<String, SyncStateEntry> syncState = loadSyncState();

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

        List<String> dirs = new ArrayList<>(local.directories);
        dirs.sort(Comparator.comparingInt(WebDavVaultStorage::depth));
        int mkdirSteps = 0;
        for (String rel : dirs) {
            if (rel.isBlank()) {
                continue;
            }
            if (!remoteNodes.containsKey(rel) || !remoteNodes.get(rel).directory) {
                mkdirSteps++;
            }
        }
        int total = Math.max(1, mkdirSteps + local.files.size() + remoteOnlyFiles.size() + remoteOnlyDirs.size());
        int step = 0;

        for (String rel : dirs) {
            if (rel.isBlank()) {
                continue;
            }
            String remotePath = joinRemote(rel);
            if (!remoteNodes.containsKey(rel) || !remoteNodes.get(rel).directory) {
                reportProgress(++step, total, "フォルダ作成", rel);
                client.ensureDirectory(remotePath);
            }
        }

        for (Map.Entry<String, LocalFileNode> entry : local.files.entrySet()) {
            String rel = entry.getKey();
            reportProgress(++step, total, "アップロード", rel);
            LocalFileNode localFile = entry.getValue();
            RemoteNode remote = remoteNodes.get(rel);
            SyncStateEntry previous = syncState.get(rel);
            boolean localChanged = hasLocalChangedSinceLastSync(localFile, previous);
            boolean remoteChanged = hasRemoteChangedSinceLastSync(remote, previous);

            if (!localChanged && !remoteChanged && remote != null && !remote.directory) {
                continue;
            }
            if (localChanged && remoteChanged) {
                if (tryResolveTwinChangeByContentEquality(rel, localFile, remote, syncState)) {
                    continue;
                }
                throw buildConflict(rel, remote, "WebDAV conflict detected for: " + rel);
            }
            if (!localChanged && remoteChanged) {
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

        for (String rel : remoteOnlyFiles) {
            reportProgress(++step, total, "削除", rel);
            client.delete(joinRemote(rel));
            syncState.remove(rel);
        }
        remoteOnlyDirs.sort((a, b) -> Integer.compare(depth(b), depth(a)));
        for (String rel : remoteOnlyDirs) {
            reportProgress(++step, total, "削除", rel);
            client.delete(joinRemote(rel));
        }
        syncState.entrySet().removeIf(e -> !local.files.containsKey(e.getKey()));
        saveSyncState(syncState);
    }

    /**
     * Cryptomator の {@code vault.cryptomator*.bkup} など、メタファイルは ETag / 時刻だけ食い違って
     * 「両方更新」と出ることがある。実バイトが同一なら競合にせず同期状態だけ合わせる。
     */
    private boolean tryResolveTwinChangeByContentEquality(
            String rel, LocalFileNode localFile, RemoteNode remote, Map<String, SyncStateEntry> syncState)
            throws IOException {
        if (remote == null || remote.directory()) {
            return false;
        }
        if (!shouldCompareBytesForTwinMetadata(rel, localFile.size)) {
            return false;
        }
        Path localPath = localMirrorPath.resolve(rel);
        if (!Files.isRegularFile(localPath)) {
            return false;
        }
        byte[] localBytes = Files.readAllBytes(localPath);
        byte[] remoteBytes;
        try {
            remoteBytes = client.download(joinRemote(rel));
        } catch (IOException e) {
            return false;
        }
        if (!Arrays.equals(localBytes, remoteBytes)) {
            return false;
        }
        syncState.put(rel, new SyncStateEntry(remote.etag(), localFile.size, localFile.lastModified));
        return true;
    }

    private static boolean shouldCompareBytesForTwinMetadata(String rel, long size) {
        if (size < 0 || size > 5_000_000L) {
            return false;
        }
        String name = rel;
        int slash = rel.lastIndexOf('/');
        if (slash >= 0 && slash < rel.length() - 1) {
            name = rel.substring(slash + 1);
        }
        return name.endsWith(".bkup")
                || name.equals("vault.cryptomator")
                || name.equals("masterkey.cryptomator");
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

    /**
     * 同期メタデータは {@link #saveSyncState} で最終更新をミリ秒のみ保存するため、
     * ファイルシステムの分解能の高い {@link Instant} と常に一致しない。比較はミリ秒に揃える。
     */
    private static boolean sameInstantEpochMillis(Instant a, Instant b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.toEpochMilli() == b.toEpochMilli();
    }

    private static boolean hasLocalChangedSinceLastSync(LocalFileNode localFile, SyncStateEntry previous) {
        if (previous == null) {
            return true;
        }
        if (localFile.size != previous.size) {
            return true;
        }
        return !sameInstantEpochMillis(localFile.lastModified, previous.lastModified);
    }

    private static boolean hasRemoteChangedSinceLastSync(RemoteNode remote, SyncStateEntry previous) {
        if (remote == null) {
            return previous != null;
        }
        if (remote.directory) {
            return false;
        }
        // 前回 flush 後の記録がないときは「サーバーがその後に変わった」とはみなさない。
        // さもないと初回 push（ローカル→空の WebDAV 等）で local/remote 両方「更新」と判定され誤競合になる。
        if (previous == null) {
            return false;
        }
        if (remote.etag != null && previous.etag != null) {
            String rc = WebDavClient.canonicalEtagForCompare(remote.etag);
            String pc = WebDavClient.canonicalEtagForCompare(previous.etag);
            if (rc != null && pc != null) {
                return !rc.equals(pc);
            }
        }
        if (remote.contentLength != previous.size) {
            return true;
        }
        return remote.lastModified != null && !sameInstantEpochMillis(remote.lastModified, previous.lastModified);
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
                // 競合ダイアログ表示時点の ETag は古いことが多い。PUT 直前に取り直し、412 なら再試行・最後は If-Match なし。
                String uploadedEtag = uploadKeepLocalWithFreshEtag(rel, localBytes, conflict.remoteEtag());
                Files.write(localPath, localBytes);
                // PUT 応答の ETag と次回 PROPFIND の表記がサーバーによってずれ、直後に再競合することがある。
                // 同期状態には list と同じ経路の stat を優先する。lastModified はメタがミリ秒保存なので揃える。
                WebDavClient.Entry live = client.stat(joinRemote(rel));
                String etagForState =
                        live != null && live.etag() != null && !live.etag().isBlank()
                                ? live.etag()
                                : uploadedEtag;
                Instant localMtime = Files.getLastModifiedTime(localPath).toInstant();
                syncState.put(
                        rel,
                        new SyncStateEntry(
                                etagForState,
                                Files.size(localPath),
                                Instant.ofEpochMilli(localMtime.toEpochMilli())));
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

    /**
     * 「ローカル優先」で上書き PUT する。解決処理のあいだにリモートが変わると If-Match が外れるため、
     * 毎回 PROPFIND で ETag を取り直し、412 のときは再 stat / If-Match なしでリトライする。
     */
    private String uploadKeepLocalWithFreshEtag(String rel, byte[] localBytes, String conflictRemoteEtag)
            throws IOException {
        String remotePath = joinRemote(rel);
        IOException lastPrecondition = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            String ifMatch;
            if (attempt == 2) {
                ifMatch = null;
            } else {
                WebDavClient.Entry live = client.stat(remotePath);
                if (live != null && live.etag() != null && !live.etag().isBlank()) {
                    ifMatch = live.etag();
                } else if (conflictRemoteEtag != null && !conflictRemoteEtag.isBlank()) {
                    ifMatch = conflictRemoteEtag;
                } else {
                    ifMatch = null;
                }
            }
            try {
                return client.upload(remotePath, localBytes, ifMatch, false);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("precondition failed")) {
                    lastPrecondition = e;
                    continue;
                }
                throw e;
            }
        }
        if (lastPrecondition != null) {
            throw lastPrecondition;
        }
        throw new IOException("WebDAV PUT failed after retries: " + remotePath);
    }

    private WebDavSyncConflictException buildConflict(String relPath, RemoteNode remote, String message) throws IOException {
        Path localPath = localMirrorPath.resolve(relPath);
        byte[] localBytes = Files.exists(localPath) ? Files.readAllBytes(localPath) : new byte[0];
        Instant localLastModified = null;
        if (Files.exists(localPath)) {
            try {
                localLastModified = Files.getLastModifiedTime(localPath).toInstant();
            } catch (IOException ignored) {
                // leave null
            }
        }
        byte[] remoteBytes;
        String etag = remote == null ? null : remote.etag();
        Instant remoteLastModified = remote == null ? null : remote.lastModified();
        long remoteListedLen = remote == null ? -1L : remote.contentLength();
        try {
            remoteBytes = client.download(joinRemote(relPath));
        } catch (IOException e) {
            remoteBytes = new byte[0];
        }
        return new WebDavSyncConflictException(
                relPath, localBytes, remoteBytes, etag, remoteLastModified, localLastModified, remoteListedLen, message);
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
