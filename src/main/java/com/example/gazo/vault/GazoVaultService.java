package com.example.gazo.vault;

import com.example.gazo.GazoFx;
import com.example.gazo.VaultConnection;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.cryptomator.cryptofs.CryptoFileSystem;
import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * アプリ専用ディレクトリに Cryptomator 互換 Vault を作成し、画像を暗号化して保存する。
 */
public final class GazoVaultService implements AutoCloseable {
    public record SimilarPair(Path left, Path right, int distance) {}
    public record CopyProgress(int copiedFiles, int totalFiles, String currentRelativePath) {}
    public record RecentlyDeletedImage(String fileName, Instant deletedAt) {}
    public record RestoreDeletedImagesResult(int restoredCount, List<String> failedMessages) {}

    private static final URI DEFAULT_KEY_ID = URI.create(MasterkeyFileKeyLoader.SCHEME + ":masterkey.cryptomator");
    private static final String IMAGES_DIR = "images";
    private static final String VIDEOS_DIR = "videos";
    private static final String THUMBNAILS_DIR = "thumbnails";
    private static final String TAGS_FILE = ".gazo-tags.properties";
    private static final String DISPLAY_FILE = ".gazo-display.properties";
    private static final String CANVAS_FILE = ".gazo-canvas.properties";
    private static final String CANVAS_TRANSFORM_FILE = ".gazo-canvas-transform.properties";
    private static final String TRASH_DIR = ".gazo-trash";
    private static final String TRASH_IMAGES_DIR = "images";
    private static final String TRASH_THUMBS_DIR = "thumbnails";
    private static final String TRASH_INDEX_FILE = "index.properties";
    private static final String DEFAULT_CANVAS_LAYOUT = "default";
    /** 旧形式（単一）のキャンバス選択キー。 */
    private static final String CANVAS_SELECTION_KEY = "__canvas_selection__";
    public record CanvasPosition(double x, double y) {}
    public record CanvasSize(double width, double height) {}
    public record CanvasTransform(double scale, double rotation) {}

    private final VaultStorage storage;
    private final Path vaultPath;
    private final String vaultDisplayLocation;
    private final SecureRandom secureRandom = new SecureRandom();
    private final MasterkeyFileAccess masterkeyFileAccess;
    private CryptoFileSystem cryptoFileSystem;
    private boolean storagePrepared;

    /**
     * アプリが {@link com.example.gazo.GazoApp#start} で登録する。新規 {@link GazoVaultService} 生成時に
     * WebDAV なら進捗リスナーを付け、初回解錠のダウンロードからプログレスバーが効く。
     */
    private static volatile Consumer<GazoVaultService> webDavProgressInstaller;

    public static void setWebDavProgressInstaller(Consumer<GazoVaultService> installer) {
        webDavProgressInstaller = installer;
    }

    public GazoVaultService(Path vaultPath) {
        this(new LocalFsVaultStorage(vaultPath));
    }

    public GazoVaultService(VaultConnection connection, char[] webDavPassword) {
        this(VaultStorageFactory.create(connection, webDavPassword));
    }

    public GazoVaultService(VaultStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.vaultPath = storage.localVaultPath();
        this.vaultDisplayLocation = storage.displayLocation();
        this.masterkeyFileAccess = new MasterkeyFileAccess(new byte[0], secureRandom);
        Consumer<GazoVaultService> hook = webDavProgressInstaller;
        if (hook != null && storage.isRemote()) {
            hook.accept(this);
        }
    }

    public Path getVaultPath() {
        return vaultPath;
    }

    public String getVaultDisplayLocation() {
        return vaultDisplayLocation;
    }

    public boolean isRemoteVault() {
        return storage.isRemote();
    }

    public String syncStatusSummary() {
        return storage.syncStatusSummary();
    }

    /**
     * 起動時にローカルミラーが使える状態のとき、{@link WebDavVaultStorage#prepareForOpen()} は
     * 同期をスキップする。解錠して UI が立ち上がった後、本メソッドを別スレッドから呼んで差分同期する。
     * ローカル Vault では何もしない。例外はリスナー経由で呼び出し側に伝える。
     */
    public void runWebDavBackgroundDownSyncIfNeeded() throws IOException {
        if (storage instanceof WebDavVaultStorage w) {
            try {
                w.runBackgroundDownSyncIfNeeded();
            } finally {
                notifySyncUiCompleteIfWebDav();
            }
        }
    }

    /** WebDAV 等へ保留中の変更を書き出す（移行完了時など）。 */
    public void flushStorageToRemote() throws IOException {
        flushStorageChanges();
    }

    public void clearRemoteSyncMetadataIfPresent() throws IOException {
        if (storage instanceof WebDavVaultStorage w) {
            w.clearSyncMetadataFile();
        }
    }

    /**
     * WebDAV の Vault ルート配下を空にする（ルートのコレクションは残す）。
     * 物理コピー移行で、移行先の初回解錠がリモートに作った {@code masterkey.cryptomator} 等が残ると、
     * 移行元からコピーしたファイルと内容が食い違い毎回同期競合になるため、ミラーへコピーする前に呼ぶ。
     */
    public void clearRemoteVaultTreeForPhysicalMigration() throws IOException {
        if (storage instanceof WebDavVaultStorage w) {
            w.deleteAllRemoteVaultContents();
        }
    }

    public void setPhysicalMirrorSeedPendingForNextOpen(boolean pending) {
        if (storage instanceof WebDavVaultStorage w) {
            w.setPhysicalMirrorSeedPending(pending);
        }
    }

    /** WebDAV ルート配下に 1 ファイルでもあるか（物理移行先が空か）。 */
    public boolean webDavRemoteHasAnyEncryptedFile() throws IOException {
        if (storage instanceof WebDavVaultStorage w) {
            return w.remoteTreeHasAnyFile();
        }
        return false;
    }

    public boolean vaultExists() {
        try {
            prepareStorageIfNeeded();
            return Files.isDirectory(vaultPath) && Files.exists(vaultPath.resolve("vault.cryptomator"));
        } catch (IOException e) {
            return false;
        }
    }

    private CryptoFileSystemProperties propertiesFor(CharSequence passphrase) {
        return CryptoFileSystemProperties.cryptoFileSystemProperties()
                .withKeyLoader(new MasterkeyFileKeyLoader(vaultPath, passphrase, masterkeyFileAccess))
                .withCipherCombo(CryptorProvider.Scheme.SIV_GCM)
                .build();
    }

    /**
     * 新規 Vault を作成する（ディレクトリは空でなくてもよいが、既存の Cryptomator Vault がある場合は失敗する）。
     */
    public void createVault(CharSequence passphrase) throws IOException, MasterkeyLoadingFailedException {
        prepareStorageIfNeeded();
        Files.createDirectories(vaultPath);
        try (Masterkey masterkey = Masterkey.generate(secureRandom)) {
            masterkeyFileAccess.persist(masterkey, vaultPath.resolve("masterkey.cryptomator"), passphrase);
        }
        CryptoFileSystemProvider.initialize(vaultPath, propertiesFor(passphrase), DEFAULT_KEY_ID);
        close();
        cryptoFileSystem = CryptoFileSystemProvider.newFileSystem(vaultPath, propertiesFor(passphrase));
        Files.createDirectories(imagesDirectory());
        Files.createDirectories(videosDirectory());
        Files.createDirectories(thumbnailsDirectory());
        flushStorageChanges();
    }

    private void ensureUnlocked() {
        if (cryptoFileSystem == null || !cryptoFileSystem.isOpen()) {
            throw new IllegalStateException("Album is not unlocked");
        }
    }

    public void unlock(CharSequence passphrase) throws IOException, MasterkeyLoadingFailedException {
        prepareStorageIfNeeded();
        close();
        cryptoFileSystem = CryptoFileSystemProvider.newFileSystem(vaultPath, propertiesFor(passphrase));
        Path root = cryptoFileSystem.getRootDirectories().iterator().next();
        Files.createDirectories(root.resolve(IMAGES_DIR));
        Files.createDirectories(root.resolve(VIDEOS_DIR));
        Files.createDirectories(root.resolve(THUMBNAILS_DIR));
    }

    public Path cleartextRoot() {
        ensureUnlocked();
        return cryptoFileSystem.getRootDirectories().iterator().next();
    }

    public Path imagesDirectory() {
        return cleartextRoot().resolve(IMAGES_DIR);
    }

    public Path videosDirectory() {
        return cleartextRoot().resolve(VIDEOS_DIR);
    }

    public Path thumbnailsDirectory() {
        return cleartextRoot().resolve(THUMBNAILS_DIR);
    }

    public Path importImage(Path sourceFile) throws IOException {
        ensureUnlocked();
        String name = sourceFile.getFileName().toString();
        if (!isVisibleUserMediaName(sourceFile)) {
            throw new IOException(
                    "ファイル名が「.」で始まるファイルは登録できません（._ファイル名.jpg 等の Apple メタデータは実画像ではありません）: "
                            + name);
        }
        Path dest = resolveUniqueImagePath(name);
        Files.copy(sourceFile, dest);
        // 登録時にサムネイルを生成（失敗しても登録自体は継続）。
        try {
            ensureThumbnailExists(dest);
        } catch (Exception ignored) {
            // ignore
        }
        flushStorageChanges();
        return dest;
    }

    public Path importImage(InputStream in, String fileName) throws IOException {
        ensureUnlocked();
        Path namePath = Path.of(fileName).getFileName();
        if (namePath == null || !isVisibleUserMediaName(namePath)) {
            throw new IOException(
                    "ファイル名が「.」で始まるファイルは登録できません（._ファイル名.jpg 等の Apple メタデータは実画像ではありません）: "
                            + fileName);
        }
        Path dest = resolveUniqueImagePath(fileName);
        Files.copy(in, dest);
        // 登録時にサムネイルを生成（失敗しても登録自体は継続）。
        try {
            ensureThumbnailExists(dest);
        } catch (Exception ignored) {
            // ignore
        }
        flushStorageChanges();
        return dest;
    }

    /**
     * 画像のサムネイル（JPEG）パスを返す。存在しなければ生成を試みる。
     * 生成に失敗した場合は null。
     */
    public Path thumbnailFor(Path imagePath) {
        ensureUnlocked();
        try {
            return ensureThumbnailExists(imagePath);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * すべての画像サムネイルを再生成する。
     *
     * @return 再生成に成功したサムネイル数
     */
    public int rebuildAllThumbnails() throws IOException {
        ensureUnlocked();
        int count = 0;
        for (Path image : listImages()) {
            Path thumb = thumbnailPathFor(image);
            try {
                Files.deleteIfExists(thumb);
            } catch (Exception ignored) {
                // ignore
            }
            Path generated = thumbnailFor(image);
            if (generated != null && Files.exists(generated)) {
                count++;
            }
        }
        flushStorageChanges();
        return count;
    }

    private Path thumbnailPathFor(Path imagePath) {
        String key = imagePath.getFileName().toString();
        return thumbnailsDirectory().resolve(key + ".jpg");
    }

    /**
     * サムネイルを生成して返す。すでに存在すればそのまま返す。
     */
    private Path ensureThumbnailExists(Path imagePath) throws IOException {
        ensureUnlocked();
        Path id = imagesDirectory().normalize();
        if (!imagePath.normalize().startsWith(id)) {
            throw new IllegalArgumentException("not under images directory");
        }
        Path thumb = thumbnailPathFor(imagePath);
        if (Files.exists(thumb)) {
            return thumb;
        }
        Files.createDirectories(thumbnailsDirectory());
        createThumbnailJpeg(imagePath, thumb, 640);
        return Files.exists(thumb) ? thumb : null;
    }

    private static void createThumbnailJpeg(Path source, Path dest, int maxEdge) throws IOException {
        BufferedImage src;
        try (InputStream in = Files.newInputStream(source)) {
            src = ImageIO.read(in);
        }
        if (src == null) {
            return;
        }
        int sw = Math.max(1, src.getWidth());
        int sh = Math.max(1, src.getHeight());
        double s = Math.min((double) maxEdge / sw, (double) maxEdge / sh);
        s = Math.min(1.0, Math.max(0.01, s));
        int tw = Math.max(1, (int) Math.round(sw * s));
        int th = Math.max(1, (int) Math.round(sh * s));

        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }

        try (OutputStream os = Files.newOutputStream(dest)) {
            ImageIO.write(out, "jpg", os);
        }
    }

    private Path resolveUniqueImagePath(String originalFileName) throws IOException {
        Path dir = imagesDirectory();
        String baseName = originalFileName;
        String ext = "";
        int dot = originalFileName.lastIndexOf('.');
        if (dot > 0 && dot < originalFileName.length() - 1) {
            baseName = originalFileName.substring(0, dot);
            ext = originalFileName.substring(dot);
        }
        Path candidate = dir.resolve(originalFileName);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(baseName + " (" + counter + ")" + ext);
            counter++;
        }
        return candidate;
    }

    public Path importVideo(Path sourceFile) throws IOException {
        ensureUnlocked();
        String name = sourceFile.getFileName().toString();
        Path dest = resolveUniqueVideoPath(name);
        Files.copy(sourceFile, dest);
        flushStorageChanges();
        return dest;
    }

    /**
     * この Vault の中身（平文ルート配下）を指定先へ丸ごとコピーする。
     * 既存データは先に削除し、同一構成で置き換える。
     */
    public void copyAllContentTo(GazoVaultService destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureUnlocked();
        destination.replaceAllContentFromDirectory(cleartextRoot());
    }

    /**
     * 指定ディレクトリ配下の内容で、この Vault の平文ルートを完全に置き換える。
     */
    public void replaceAllContentFromDirectory(Path sourceRoot) throws IOException {
        replaceAllContentFromDirectory(sourceRoot, null);
    }

    public void replaceAllContentFromDirectory(Path sourceRoot, Consumer<CopyProgress> onProgress) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        ensureUnlocked();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("移行元ディレクトリが見つかりません: " + sourceRoot);
        }
        Path destinationRoot = cleartextRoot();
        clearDirectoryContents(destinationRoot);
        copyDirectoryContents(sourceRoot, destinationRoot, onProgress);
        flushStorageChanges();
    }

    /**
     * 別の解錠済み Vault の平文ルートと同じ構成に、この Vault の平文ルートを置き換える。
     * 一時スナップショットを作らない単純コピー移行用（移行元はこの呼び出し中も開いたまま）。
     */
    public void replaceAllContentFromUnlockedPeer(GazoVaultService sourceVault, Consumer<CopyProgress> onProgress)
            throws IOException {
        Objects.requireNonNull(sourceVault, "sourceVault");
        sourceVault.ensureUnlocked();
        ensureUnlocked();
        Path sourceRoot = sourceVault.cleartextRoot();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("移行元の平文ルートが見つかりません: " + sourceRoot);
        }
        Path destinationRoot = cleartextRoot();
        clearDirectoryContents(destinationRoot);
        copyDirectoryContents(sourceRoot, destinationRoot, onProgress);
        flushStorageChanges();
    }

    private Path resolveUniqueVideoPath(String originalFileName) throws IOException {
        Path dir = videosDirectory();
        String baseName = originalFileName;
        String ext = "";
        int dot = originalFileName.lastIndexOf('.');
        if (dot > 0 && dot < originalFileName.length() - 1) {
            baseName = originalFileName.substring(0, dot);
            ext = originalFileName.substring(dot);
        }
        Path candidate = dir.resolve(originalFileName);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(baseName + " (" + counter + ")" + ext);
            counter++;
        }
        return candidate;
    }

    private static void clearDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void copyDirectoryContents(Path sourceRoot, Path destinationRoot, Consumer<CopyProgress> onProgress)
            throws IOException {
        List<Path> filesToCopy;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            filesToCopy =
                    stream.filter(Files::isRegularFile)
                            .filter(source -> !source.equals(sourceRoot))
                            .toList();
        }
        int totalFiles = filesToCopy.size();
        int copied = 0;
        if (onProgress != null) {
            onProgress.accept(new CopyProgress(0, totalFiles, ""));
        }
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.toList()) {
                if (source.equals(sourceRoot)) {
                    continue;
                }
                String rel = sourceRoot.relativize(source).toString().replace('\\', '/');
                Path target = destinationRoot.resolve(rel);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source)) {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                    if (onProgress != null) {
                        onProgress.accept(new CopyProgress(copied, totalFiles, rel));
                    }
                }
            }
        }
    }

    /**
     * 一覧・インポート対象にしないファイル名。
     * 先頭が {@code .} のもの（.DS_Store、macOS の AppleDouble {@code ._元.jpg} など）は実画像ではないことが多い。
     */
    private static boolean isVisibleUserMediaName(Path path) {
        String name = path.getFileName().toString();
        return !name.isEmpty() && name.charAt(0) != '.';
    }

    public List<Path> listVideos() throws IOException {
        ensureUnlocked();
        Path dir = videosDirectory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(GazoVaultService::isVisibleUserMediaName)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
    }

    public void deleteVideo(Path videoPath) throws IOException {
        ensureUnlocked();
        Path vd = videosDirectory().normalize();
        if (!videoPath.normalize().startsWith(vd)) {
            throw new IllegalArgumentException("not under videos directory");
        }
        Files.deleteIfExists(videoPath);
        String key = videoPath.getFileName().toString();
        Properties tagProps = loadTagProperties();
        if (tagProps.remove(key) != null) {
            saveTagProperties(tagProps);
        }
        Properties displayProps = loadDisplayProperties();
        if (displayProps.remove(key) != null) {
            saveDisplayProperties(displayProps);
        }
        flushStorageChanges();
    }

    public List<Path> listImages() throws IOException {
        ensureUnlocked();
        Path dir = imagesDirectory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(GazoVaultService::isVisibleUserMediaName)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
    }

    public List<List<Path>> findExactDuplicateGroups() throws IOException {
        Map<String, List<Path>> byHash = new HashMap<>();
        for (Path image : listImages()) {
            String hash = sha256(image);
            byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(image);
        }
        return byHash.values().stream()
                .filter(list -> list.size() > 1)
                .sorted(Comparator.comparingInt((List<Path> list) -> list.size()).reversed())
                .toList();
    }

    public List<SimilarPair> findSimilarPairs(int maxDistance) throws IOException {
        List<Path> images = listImages();
        Map<Path, Long> hashes = new HashMap<>();
        for (Path image : images) {
            Long h = dHash64(image);
            if (h != null) {
                hashes.put(image, h);
            }
        }
        List<SimilarPair> pairs = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            for (int j = i + 1; j < images.size(); j++) {
                Path a = images.get(i);
                Path b = images.get(j);
                Long ha = hashes.get(a);
                Long hb = hashes.get(b);
                if (ha == null || hb == null) {
                    continue;
                }
                int distance = Long.bitCount(ha ^ hb);
                if (distance <= maxDistance) {
                    pairs.add(new SimilarPair(a, b, distance));
                }
            }
        }
        pairs.sort(Comparator.comparingInt(SimilarPair::distance));
        return pairs;
    }

    /**
     * 全レイアウトの選択順（{@code …::__canvas_selection__}）と、旧形式の {@link #CANVAS_SELECTION_KEY}、
     * および {@code layout::ファイル名} 形式の位置キーから、ファイル名を取り除く。
     */
    private void purgeImageFromCanvasState(String fileName) throws IOException {
        Properties canvasProps = loadCanvasProperties();
        boolean changed = false;
        for (String key : new ArrayList<>(canvasProps.stringPropertyNames())) {
            if (key.endsWith("::__canvas_selection__")) {
                String raw = canvasProps.getProperty(key, "").trim();
                if (raw.isEmpty()) {
                    continue;
                }
                boolean inSelection = false;
                for (String part : raw.split(",")) {
                    if (part.trim().equals(fileName)) {
                        inSelection = true;
                        break;
                    }
                }
                if (!inSelection) {
                    continue;
                }
                List<String> kept = new ArrayList<>();
                for (String part : raw.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty() && !t.equals(fileName)) {
                        kept.add(t);
                    }
                }
                if (kept.isEmpty()) {
                    canvasProps.remove(key);
                } else {
                    canvasProps.setProperty(key, String.join(",", kept));
                }
                changed = true;
            } else if (key.endsWith("::" + fileName)) {
                canvasProps.remove(key);
                changed = true;
            }
        }
        String legacy = canvasProps.getProperty(CANVAS_SELECTION_KEY, "").trim();
        if (!legacy.isEmpty()) {
            boolean legacyContains = false;
            for (String part : legacy.split(",")) {
                if (part.trim().equals(fileName)) {
                    legacyContains = true;
                    break;
                }
            }
            if (legacyContains) {
                List<String> keptLegacy = new ArrayList<>();
                for (String part : legacy.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty() && !t.equals(fileName)) {
                        keptLegacy.add(t);
                    }
                }
                if (keptLegacy.isEmpty()) {
                    canvasProps.remove(CANVAS_SELECTION_KEY);
                } else {
                    canvasProps.setProperty(CANVAS_SELECTION_KEY, String.join(",", keptLegacy));
                }
                changed = true;
            }
        }
        if (changed) {
            saveCanvasProperties(canvasProps);
        }
    }

    public void deleteImage(Path imagePath) throws IOException {
        ensureUnlocked();
        Path imageDir = imagesDirectory().normalize();
        if (!imagePath.normalize().startsWith(imageDir)) {
            throw new IllegalArgumentException("not under images directory");
        }
        String key = imagePath.getFileName().toString();
        if (!Files.exists(imagePath)) {
            return;
        }
        Path thumbnailPath = thumbnailPathFor(imagePath);
        Properties tagProps = loadTagProperties();
        Properties displayProps = loadDisplayProperties();
        String deletedId = createTrashId();
        String trashImageName = deletedId + "__" + key;
        String trashThumbName = deletedId + "__" + key + ".jpg";
        Files.createDirectories(trashImagesDir());
        Files.move(imagePath, trashImagesDir().resolve(trashImageName), StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(thumbnailPath)) {
            Files.createDirectories(trashThumbnailsDir());
            Files.move(thumbnailPath, trashThumbnailsDir().resolve(trashThumbName), StandardCopyOption.REPLACE_EXISTING);
        } else {
            trashThumbName = "";
        }
        upsertTrashIndexEntry(
                deletedId,
                key,
                Instant.now(),
                trashImageName,
                trashThumbName,
                tagProps.getProperty(key, ""),
                displayProps.getProperty(key, ""));
        tagProps.remove(key);
        saveTagProperties(tagProps);
        displayProps.remove(key);
        saveDisplayProperties(displayProps);
        purgeImageFromCanvasState(key);
        Properties transformProps = loadCanvasTransformProperties();
        List<String> transformKeys = new ArrayList<>(transformProps.stringPropertyNames());
        for (String tKey : transformKeys) {
            if (tKey.endsWith("::" + key)) {
                transformProps.remove(tKey);
            }
        }
        saveCanvasTransformProperties(transformProps);
        flushStorageChanges();
    }

    public List<RecentlyDeletedImage> listRecentlyDeletedImages(int limit) throws IOException {
        ensureUnlocked();
        List<TrashIndexEntry> entries = loadTrashIndexEntries();
        entries.sort(Comparator.comparing(TrashIndexEntry::deletedAt).reversed());
        if (limit > 0 && entries.size() > limit) {
            entries = entries.subList(0, limit);
        }
        List<RecentlyDeletedImage> out = new ArrayList<>(entries.size());
        for (TrashIndexEntry entry : entries) {
            out.add(new RecentlyDeletedImage(entry.originalFileName(), entry.deletedAt()));
        }
        return out;
    }

    public RestoreDeletedImagesResult restoreRecentlyDeletedImages(int limit) throws IOException {
        ensureUnlocked();
        List<TrashIndexEntry> entries = loadTrashIndexEntries();
        entries.sort(Comparator.comparing(TrashIndexEntry::deletedAt).reversed());
        if (limit > 0 && entries.size() > limit) {
            entries = entries.subList(0, limit);
        }
        if (entries.isEmpty()) {
            return new RestoreDeletedImagesResult(0, List.of());
        }
        Properties trashIndex = loadTrashIndexProperties();
        Properties tagProps = loadTagProperties();
        Properties displayProps = loadDisplayProperties();
        int restored = 0;
        List<String> failures = new ArrayList<>();
        for (TrashIndexEntry entry : entries) {
            Path sourceImage = trashImagesDir().resolve(entry.trashImageName());
            Path targetImage = imagesDirectory().resolve(entry.originalFileName());
            if (Files.exists(targetImage)) {
                failures.add(entry.originalFileName() + ": 同名ファイルが既に存在します");
                continue;
            }
            if (!Files.exists(sourceImage)) {
                failures.add(entry.originalFileName() + ": ごみ箱内の画像ファイルが見つかりません");
                trashIndex.remove("entry." + entry.id());
                continue;
            }
            Files.move(sourceImage, targetImage, StandardCopyOption.REPLACE_EXISTING);
            if (entry.trashThumbName() != null && !entry.trashThumbName().isBlank()) {
                Path sourceThumb = trashThumbnailsDir().resolve(entry.trashThumbName());
                if (Files.exists(sourceThumb)) {
                    Files.move(sourceThumb, thumbnailPathFor(targetImage), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (entry.tagRaw() != null && !entry.tagRaw().isBlank()) {
                tagProps.setProperty(entry.originalFileName(), entry.tagRaw());
            }
            if (entry.displayRaw() != null && !entry.displayRaw().isBlank()) {
                displayProps.setProperty(entry.originalFileName(), entry.displayRaw());
            }
            trashIndex.remove("entry." + entry.id());
            restored++;
        }
        saveTrashIndexProperties(trashIndex);
        saveTagProperties(tagProps);
        saveDisplayProperties(displayProps);
        flushStorageChanges();
        return new RestoreDeletedImagesResult(restored, List.copyOf(failures));
    }

    public String sha256(Path path) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public Long dHash64(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                return null;
            }
            BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
            var g = small.createGraphics();
            try {
                g.drawImage(src, 0, 0, 9, 8, null);
            } finally {
                g.dispose();
            }
            long hash = 0L;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int left = small.getRGB(x, y) & 0xff;
                    int right = small.getRGB(x + 1, y) & 0xff;
                    if (left > right) {
                        hash |= (1L << bit);
                    }
                    bit++;
                }
            }
            return hash;
        } catch (IOException e) {
            return null;
        }
    }

    private Path tagsFile() {
        return cleartextRoot().resolve(TAGS_FILE);
    }

    private Path displayFile() {
        return cleartextRoot().resolve(DISPLAY_FILE);
    }

    private Path canvasFile() {
        return cleartextRoot().resolve(CANVAS_FILE);
    }

    private Path canvasTransformFile() {
        return cleartextRoot().resolve(CANVAS_TRANSFORM_FILE);
    }

    private Path trashRoot() {
        return cleartextRoot().resolve(TRASH_DIR);
    }

    private Path trashImagesDir() {
        return trashRoot().resolve(TRASH_IMAGES_DIR);
    }

    private Path trashThumbnailsDir() {
        return trashRoot().resolve(TRASH_THUMBS_DIR);
    }

    private Path trashIndexFile() {
        return trashRoot().resolve(TRASH_INDEX_FILE);
    }

    private String createTrashId() {
        return Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-"
                + Long.toUnsignedString(secureRandom.nextLong(), 36);
    }

    private void upsertTrashIndexEntry(
            String id,
            String originalFileName,
            Instant deletedAt,
            String trashImageName,
            String trashThumbName,
            String tagRaw,
            String displayRaw)
            throws IOException {
        Properties properties = loadTrashIndexProperties();
        String value =
                encodeField(originalFileName)
                        + "|"
                        + deletedAt.toEpochMilli()
                        + "|"
                        + encodeField(trashImageName)
                        + "|"
                        + encodeField(trashThumbName == null ? "" : trashThumbName)
                        + "|"
                        + encodeField(tagRaw == null ? "" : tagRaw)
                        + "|"
                        + encodeField(displayRaw == null ? "" : displayRaw);
        properties.setProperty("entry." + id, value);
        saveTrashIndexProperties(properties);
    }

    private Properties loadTrashIndexProperties() throws IOException {
        ensureUnlocked();
        Properties properties = new Properties();
        Path file = trashIndexFile();
        if (!Files.exists(file)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private void saveTrashIndexProperties(Properties properties) throws IOException {
        ensureUnlocked();
        Files.createDirectories(trashRoot());
        Path file = trashIndexFile();
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "gazo trash index");
        }
    }

    private List<TrashIndexEntry> loadTrashIndexEntries() throws IOException {
        Properties properties = loadTrashIndexProperties();
        List<TrashIndexEntry> out = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("entry.")) {
                continue;
            }
            String id = key.substring("entry.".length());
            String[] parts = properties.getProperty(key, "").split("\\|", 6);
            if (parts.length < 6) {
                continue;
            }
            try {
                String originalFileName = decodeField(parts[0]);
                Instant deletedAt = Instant.ofEpochMilli(Long.parseLong(parts[1]));
                String trashImageName = decodeField(parts[2]);
                String trashThumbName = decodeField(parts[3]);
                String tagRaw = decodeField(parts[4]);
                String displayRaw = decodeField(parts[5]);
                if (originalFileName.isBlank() || trashImageName.isBlank()) {
                    continue;
                }
                out.add(
                        new TrashIndexEntry(
                                id, originalFileName, deletedAt, trashImageName, trashThumbName, tagRaw, displayRaw));
            } catch (Exception ignored) {
                // ignore broken entries
            }
        }
        return out;
    }

    private static String encodeField(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decodeField(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    private Properties loadTagProperties() throws IOException {
        ensureUnlocked();
        Properties properties = new Properties();
        Path file = tagsFile();
        if (!Files.exists(file)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private void saveTagProperties(Properties properties) throws IOException {
        ensureUnlocked();
        Path file = tagsFile();
        try (var out = Files.newOutputStream(file)) {
            properties.store(out, "gazo tags");
        }
    }

    public Set<String> getTags(Path imagePath) throws IOException {
        String key = imagePath.getFileName().toString();
        Properties properties = loadTagProperties();
        return parseTags(properties.getProperty(key, ""));
    }

    /**
     * タグファイルを 1 回だけ読み込み、ファイル名 → タグのマップを返す（画像枚数分の繰り返し読み込みを避ける）。
     */
    public Map<String, Set<String>> tagsByFileName() throws IOException {
        Properties properties = loadTagProperties();
        Map<String, Set<String>> map = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            map.put(key, parseTags(properties.getProperty(key, "")));
        }
        return map;
    }

    public void setTags(Path imagePath, Set<String> tags) throws IOException {
        String key = imagePath.getFileName().toString();
        Properties properties = loadTagProperties();
        String value = String.join(",", normalizeTags(tags));
        if (value.isBlank()) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
        saveTagProperties(properties);
        flushStorageChanges();
    }

    public Set<String> listAllTags() throws IOException {
        Properties properties = loadTagProperties();
        Set<String> all = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            all.addAll(parseTags(properties.getProperty(key, "")));
        }
        return all;
    }

    public String getDisplaySize(Path imagePath) throws IOException {
        String key = imagePath.getFileName().toString();
        Properties properties = loadDisplayProperties();
        String value = properties.getProperty(key, "M").trim().toUpperCase();
        if (value.isEmpty()) {
            return "M";
        }
        return value;
    }

    public void setDisplaySize(Path imagePath, String size) throws IOException {
        String key = imagePath.getFileName().toString();
        Properties properties = loadDisplayProperties();
        if (size == null || size.isBlank()) {
            properties.remove(key);
        } else {
            properties.setProperty(key, size.trim().toUpperCase());
        }
        saveDisplayProperties(properties);
        flushStorageChanges();
    }

    public CanvasPosition getCanvasPosition(Path imagePath) throws IOException {
        return getCanvasPosition(DEFAULT_CANVAS_LAYOUT, imagePath);
    }

    public CanvasPosition getCanvasPosition(String layoutName, Path imagePath) throws IOException {
        String key = imagePath.getFileName().toString();
        Properties properties = loadCanvasProperties();
        String raw = properties.getProperty(canvasKey(layoutName, key), "").trim();
        if (raw.isEmpty() || !raw.contains(",")) {
            return null;
        }
        String[] parts = raw.split(",", 2);
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            return new CanvasPosition(x, y);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setCanvasPosition(Path imagePath, double x, double y) throws IOException {
        setCanvasPosition(DEFAULT_CANVAS_LAYOUT, imagePath, x, y);
    }

    public void setCanvasPosition(String layoutName, Path imagePath, double x, double y) throws IOException {
        String key = imagePath.getFileName().toString();
        Properties properties = loadCanvasProperties();
        properties.setProperty(canvasKey(layoutName, key), x + "," + y);
        saveCanvasProperties(properties);
        flushStorageChanges();
    }

    public CanvasTransform getCanvasTransform(String layoutName, Path imagePath) throws IOException {
        String fileName = imagePath.getFileName().toString();
        Properties properties = loadCanvasTransformProperties();
        String raw = properties.getProperty(canvasKey(layoutName, fileName), "").trim();
        if (raw.isEmpty() || !raw.contains(",")) {
            return null;
        }
        String[] parts = raw.split(",", 2);
        try {
            double scale = Double.parseDouble(parts[0]);
            double rotation = Double.parseDouble(parts[1]);
            return new CanvasTransform(scale, rotation);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setCanvasTransform(String layoutName, Path imagePath, double scale, double rotation) throws IOException {
        String fileName = imagePath.getFileName().toString();
        Properties properties = loadCanvasTransformProperties();
        properties.setProperty(canvasKey(layoutName, fileName), scale + "," + rotation);
        saveCanvasTransformProperties(properties);
        flushStorageChanges();
    }

    public CanvasSize getCanvasSize(String layoutName) throws IOException {
        Properties properties = loadCanvasProperties();
        String raw = properties.getProperty(canvasSizeKey(layoutName), "").trim();
        if (raw.isEmpty() || !raw.contains(",")) {
            return null;
        }
        String[] parts = raw.split(",", 2);
        try {
            double w = Double.parseDouble(parts[0]);
            double h = Double.parseDouble(parts[1]);
            return new CanvasSize(w, h);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setCanvasSize(String layoutName, double width, double height) throws IOException {
        Properties properties = loadCanvasProperties();
        properties.setProperty(canvasSizeKey(layoutName), width + "," + height);
        saveCanvasProperties(properties);
        flushStorageChanges();
    }

    /**
     * キャンバスに載せる画像の一覧（保存された順）。存在しないファイルは除く。
     */
    public List<Path> listCanvasSelectionOrder() throws IOException {
        return listCanvasSelectionOrder(DEFAULT_CANVAS_LAYOUT);
    }

    public List<Path> listCanvasSelectionOrder(String layoutName) throws IOException {
        ensureUnlocked();
        Properties properties = loadCanvasProperties();
        String safeLayout = (layoutName == null || layoutName.isBlank()) ? DEFAULT_CANVAS_LAYOUT : layoutName.trim();
        String raw = properties.getProperty(canvasSelectionKey(safeLayout), "").trim();
        if (raw.isEmpty() && DEFAULT_CANVAS_LAYOUT.equals(safeLayout)) {
            // Backward compatibility: migrate legacy single selection key lazily on read.
            raw = properties.getProperty(CANVAS_SELECTION_KEY, "").trim();
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        Path dir = imagesDirectory();
        List<Path> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            Path path = dir.resolve(name);
            if (Files.isRegularFile(path) && isVisibleUserMediaName(path)) {
                out.add(path);
            }
        }
        return out;
    }

    /**
     * キャンバスに載せる画像の順序を Vault に保存する（位置・変形は従来どおり別キー）。
     */
    public void saveCanvasSelectionOrder(List<Path> paths) throws IOException {
        saveCanvasSelectionOrder(DEFAULT_CANVAS_LAYOUT, paths);
    }

    public void saveCanvasSelectionOrder(String layoutName, List<Path> paths) throws IOException {
        ensureUnlocked();
        Properties properties = loadCanvasProperties();
        String safeLayout = (layoutName == null || layoutName.isBlank()) ? DEFAULT_CANVAS_LAYOUT : layoutName.trim();
        String key = canvasSelectionKey(safeLayout);
        if (paths == null || paths.isEmpty()) {
            properties.remove(key);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < paths.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(paths.get(i).getFileName().toString());
            }
            properties.setProperty(key, sb.toString());
        }
        if (DEFAULT_CANVAS_LAYOUT.equals(safeLayout)) {
            properties.remove(CANVAS_SELECTION_KEY);
        }
        saveCanvasProperties(properties);
        flushStorageChanges();
    }

    public Set<String> listCanvasLayouts() throws IOException {
        Properties properties = loadCanvasProperties();
        Set<String> names = new LinkedHashSet<>();
        names.add(DEFAULT_CANVAS_LAYOUT);
        for (String key : properties.stringPropertyNames()) {
            int idx = key.indexOf("::");
            if (idx > 0) {
                names.add(key.substring(0, idx));
            }
        }
        return names;
    }

    /**
     * 各画像ファイル名が、どのキャンバスレイアウトの選択一覧に含まれるか（保存済みデータに基づく）。
     * 値はレイアウト名の集合（複数キャンバスに同じファイルがある場合あり）。
     */
    public Map<String, Set<String>> mapCanvasLayoutsByFileName() throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        for (String layout : listCanvasLayouts()) {
            for (Path p : listCanvasSelectionOrder(layout)) {
                String fn = p.getFileName().toString();
                out.computeIfAbsent(fn, k -> new LinkedHashSet<>()).add(layout);
            }
        }
        return out;
    }

    /**
     * 類似統合で削除する画像を残す画像に置き換えるとき、全キャンバスで
     * 選択一覧のファイル名を差し替え、位置・拡大・回転・一覧用表示サイズを削除側から引き継ぐ（残す側に既に値がある場合は上書き）。
     * 実ファイル削除の前に呼ぶこと。
     */
    public void substituteCanvasImageReferences(Path replacement, Path removed) throws IOException {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(removed, "removed");
        String rep = replacement.getFileName().toString();
        String rem = removed.getFileName().toString();
        if (rep.equals(rem)) {
            return;
        }
        ensureUnlocked();

        Properties canvasProps = loadCanvasProperties();
        boolean canvasChanged = false;
        for (String key : new ArrayList<>(canvasProps.stringPropertyNames())) {
            if (key.endsWith("::__canvas_selection__")) {
                String raw = canvasProps.getProperty(key, "").trim();
                if (raw.isEmpty() || !csvContainsFilenameToken(raw, rem)) {
                    continue;
                }
                String newRaw = substituteFilenameInCanvasSelectionCsv(raw, rem, rep);
                if (newRaw.isEmpty()) {
                    canvasProps.remove(key);
                } else {
                    canvasProps.setProperty(key, newRaw);
                }
                canvasChanged = true;
            }
        }
        String legacy = canvasProps.getProperty(CANVAS_SELECTION_KEY, "").trim();
        if (!legacy.isEmpty() && csvContainsFilenameToken(legacy, rem)) {
            String newL = substituteFilenameInCanvasSelectionCsv(legacy, rem, rep);
            if (newL.isEmpty()) {
                canvasProps.remove(CANVAS_SELECTION_KEY);
            } else {
                canvasProps.setProperty(CANVAS_SELECTION_KEY, newL);
            }
            canvasChanged = true;
        }

        String remPosSuffix = "::" + rem;
        for (String key : new ArrayList<>(canvasProps.stringPropertyNames())) {
            if (!key.endsWith(remPosSuffix)) {
                continue;
            }
            if (key.endsWith("::__canvas_selection__") || key.endsWith("::__canvas_size__")) {
                continue;
            }
            String val = canvasProps.getProperty(key);
            String layout = key.substring(0, key.length() - remPosSuffix.length());
            canvasProps.setProperty(canvasKey(layout, rep), val);
            canvasProps.remove(key);
            canvasChanged = true;
        }

        if (canvasChanged) {
            saveCanvasProperties(canvasProps);
        }

        Properties transformProps = loadCanvasTransformProperties();
        boolean transformChanged = false;
        String remTfSuffix = "::" + rem;
        for (String key : new ArrayList<>(transformProps.stringPropertyNames())) {
            if (!key.endsWith(remTfSuffix)) {
                continue;
            }
            String val = transformProps.getProperty(key);
            String layout = key.substring(0, key.length() - remTfSuffix.length());
            transformProps.setProperty(canvasKey(layout, rep), val);
            transformProps.remove(key);
            transformChanged = true;
        }
        if (transformChanged) {
            saveCanvasTransformProperties(transformProps);
        }

        Properties displayProps = loadDisplayProperties();
        if (displayProps.containsKey(rem)) {
            displayProps.setProperty(rep, displayProps.getProperty(rem));
            displayProps.remove(rem);
            saveDisplayProperties(displayProps);
        }
        flushStorageChanges();
    }

    private static boolean csvContainsFilenameToken(String raw, String fileName) {
        for (String part : raw.split(",")) {
            if (part.trim().equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CSV の各トークンが rem のものは rep に置換し、同一ファイル名が重複したら先頭の 1 件にまとめる。
     */
    private static String substituteFilenameInCanvasSelectionCsv(String raw, String rem, String rep) {
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.equals(rem)) {
                t = rep;
            }
            parts.add(t);
        }
        List<String> out = new ArrayList<>();
        boolean seenRep = false;
        for (String t : parts) {
            if (t.equals(rep)) {
                if (!seenRep) {
                    out.add(rep);
                    seenRep = true;
                }
            } else {
                out.add(t);
            }
        }
        return String.join(",", out);
    }

    public void deleteCanvasLayout(String layoutName) throws IOException {
        if (layoutName == null || layoutName.isBlank() || DEFAULT_CANVAS_LAYOUT.equals(layoutName)) {
            return;
        }
        Properties properties = loadCanvasProperties();
        String prefix = layoutName + "::";
        List<String> keys = new ArrayList<>(properties.stringPropertyNames());
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                properties.remove(key);
            }
        }
        properties.remove(canvasSizeKey(layoutName));
        properties.remove(canvasSelectionKey(layoutName));
        saveCanvasProperties(properties);
        flushStorageChanges();
    }

    private String canvasKey(String layoutName, String fileName) {
        String safeLayout = (layoutName == null || layoutName.isBlank()) ? DEFAULT_CANVAS_LAYOUT : layoutName.trim();
        return safeLayout + "::" + fileName;
    }

    private String canvasSizeKey(String layoutName) {
        String safeLayout = (layoutName == null || layoutName.isBlank()) ? DEFAULT_CANVAS_LAYOUT : layoutName.trim();
        return safeLayout + "::__canvas_size__";
    }

    private String canvasSelectionKey(String layoutName) {
        String safeLayout = (layoutName == null || layoutName.isBlank()) ? DEFAULT_CANVAS_LAYOUT : layoutName.trim();
        return safeLayout + "::__canvas_selection__";
    }

    private Properties loadDisplayProperties() throws IOException {
        ensureUnlocked();
        Properties properties = new Properties();
        Path file = displayFile();
        if (!Files.exists(file)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private void saveDisplayProperties(Properties properties) throws IOException {
        ensureUnlocked();
        Path file = displayFile();
        try (var out = Files.newOutputStream(file)) {
            properties.store(out, "gazo display");
        }
    }

    private Properties loadCanvasProperties() throws IOException {
        ensureUnlocked();
        Properties properties = new Properties();
        Path file = canvasFile();
        if (!Files.exists(file)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private void saveCanvasProperties(Properties properties) throws IOException {
        ensureUnlocked();
        Path file = canvasFile();
        try (var out = Files.newOutputStream(file)) {
            properties.store(out, "gazo canvas");
        }
    }

    private Properties loadCanvasTransformProperties() throws IOException {
        ensureUnlocked();
        Properties properties = new Properties();
        Path file = canvasTransformFile();
        if (!Files.exists(file)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private void saveCanvasTransformProperties(Properties properties) throws IOException {
        ensureUnlocked();
        Path file = canvasTransformFile();
        try (var out = Files.newOutputStream(file)) {
            properties.store(out, "gazo canvas transform");
        }
    }

    private record TrashIndexEntry(
            String id,
            String originalFileName,
            Instant deletedAt,
            String trashImageName,
            String trashThumbName,
            String tagRaw,
            String displayRaw) {}

    private Set<String> parseTags(String raw) {
        Set<String> tags = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return tags;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            String normalized = part.trim().toLowerCase();
            if (!normalized.isEmpty()) {
                tags.add(normalized);
            }
        }
        return tags;
    }

    private List<String> normalizeTags(Set<String> tags) {
        List<String> list = new ArrayList<>();
        for (String tag : tags) {
            String normalized = tag == null ? "" : tag.trim().toLowerCase();
            if (!normalized.isEmpty()) {
                list.add(normalized);
            }
        }
        list.sort(String::compareTo);
        return list;
    }

    public boolean isUnlocked() {
        return cryptoFileSystem != null && cryptoFileSystem.isOpen();
    }

    @Override
    public void close() {
        if (cryptoFileSystem != null) {
            try {
                cryptoFileSystem.close();
            } catch (IOException ignored) {
                // best effort
            }
            cryptoFileSystem = null;
        }
        try {
            storage.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * 次の {@link #unlock} で {@link VaultStorage#prepareForOpen()} を再度実行する。
     * 同一インスタンスで初回解錠済みのとき {@code storagePrepared} が true のままだと prepare がスキップされ、
     * 物理コピー後にミラー内容や同期メタが更新されても古い前提のまま flush され、WebDAV で誤競合になることがある。
     */
    public void invalidateStoragePreparedForNextUnlock() {
        storagePrepared = false;
    }

    private void prepareStorageIfNeeded() throws IOException {
        if (!storagePrepared) {
            try {
                storage.prepareForOpen();
                storagePrepared = true;
            } finally {
                notifySyncUiCompleteIfWebDav();
            }
        }
    }

    /**
     * WebDAV 同期の進捗（初回ダウンロード・flush 時のアップロード等）を UI へ通知する。
     * ローカル Vault では無視される。
     */
    public void setWebDavSyncProgressListener(Consumer<WebDavSyncProgress> listener) {
        if (storage instanceof WebDavVaultStorage w) {
            w.setSyncProgressListener(listener);
        }
    }

    private void notifySyncUiCompleteIfWebDav() {
        if (storage instanceof WebDavVaultStorage w) {
            w.notifySyncUiComplete();
        }
    }

    private void flushStorageChanges() throws IOException {
        if (!storagePrepared) {
            return;
        }
        try {
            int attempts = 0;
            while (true) {
                try {
                    storage.flushChanges();
                    return;
                } catch (WebDavSyncConflictException conflict) {
                    if (!(storage instanceof WebDavVaultStorage webDavStorage)) {
                        throw conflict;
                    }
                    WebDavConflictResolution resolution = GazoFx.showWebDavConflictDialog(conflict);
                    if (resolution == null || resolution == WebDavConflictResolution.CANCEL) {
                        throw conflict;
                    }
                    webDavStorage.resolveConflict(conflict, resolution);
                    attempts++;
                    if (attempts > 3) {
                        throw new IOException("WebDAV 競合の解決を完了できませんでした。");
                    }
                }
            }
        } finally {
            notifySyncUiCompleteIfWebDav();
        }
    }
}
