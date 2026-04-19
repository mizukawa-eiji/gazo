package com.example.gazo.vault;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 最小限の WebDAV クライアント。
 */
final class WebDavClient {
    record Entry(String fullPath, boolean directory, long contentLength, Instant lastModified, String etag) {}

    private static final String PROPFIND_BODY =
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:getetag/>
              </d:prop>
            </d:propfind>
            """;

    /**
     * WebDAV は HTTP/2 だとサーバー側の実装不備で応答ヘッダが空のまま切断されることがある（
     * {@code HTTP/1.1 header parser received no bytes}）。互換性のため HTTP/1.1 に固定する。
     */
    /** 接続だけでなく応答本文の受信までの全体タイムアウト（未設定だとサーバーが応答しないとき永遠に待つ）。 */
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String endpoint;
    private final String authHeader;

    WebDavClient(String endpoint, String username, char[] password) {
        this.endpoint = normalizeEndpoint(Objects.requireNonNull(endpoint, "endpoint"));
        String raw = Objects.requireNonNull(username, "username") + ":" + new String(Objects.requireNonNull(password, "password"));
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * リソースの有無。PROPFIND が HTTP 404 ではなく 207 のまま本文で 404 (propstat) を返すサーバーがあるため、
     * HTTP ステータスだけに頼らず {@link #stat} と同じ解釈をする。
     */
    boolean exists(String path) throws IOException {
        return stat(path) != null;
    }

    /**
     * WebDAV コレクション（ディレクトリ）として存在するか。
     * {@link #stat} だけではサーバー差で取れない場合があるため、親に対する depth 1 PROPFIND で子名を照合する。
     */
    boolean directoryExists(String path) throws IOException {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            return true;
        }
        Entry e = stat(normalized);
        if (e != null) {
            return e.directory();
        }
        String parent = parentPath(normalized);
        if (parent == null) {
            return false;
        }
        int slash = normalized.lastIndexOf('/');
        if (slash < 0 || slash >= normalized.length() - 1) {
            return false;
        }
        String childName = normalized.substring(slash + 1);
        if (childName.isBlank()) {
            return false;
        }
        for (Entry ent : list(parent)) {
            if (!ent.directory()) {
                continue;
            }
            String fp = normalizePath(ent.fullPath());
            if (fp.equals(normalized)) {
                return true;
            }
            // Nextcloud 等は href が /remote.php/dav/files/.../vault/d のように論理パスより長い
            if (normalized.length() > 1 && fp.endsWith(normalized)) {
                return true;
            }
            int li = fp.lastIndexOf('/');
            if (li >= 0 && fp.substring(li + 1).equals(childName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * パス上の各セグメントに対して MKCOL する。
     * {@link #exists} だけに頼ってスキップすると、サーバーによっては親が未作成なのに「存在する」と誤判定され、
     * 子の MKCOL が 409 になることがあるため、常に MKCOL を試す。
     * 中間・最終セグメントとも、409 かつ PROPFIND で未確認のときは再試行後に成功扱いで進め、最終パスは {@link WebDavVaultStorage#prepareForOpen} 等で検証する。
     */
    void ensureDirectory(String path) throws IOException {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            return;
        }
        String[] parts = normalized.substring(1).split("/");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                segments.add(part);
            }
        }
        String current = "";
        for (int i = 0; i < segments.size(); i++) {
            current = current + "/" + segments.get(i);
            boolean lastSegment = (i == segments.size() - 1);
            mkcol(current, lastSegment);
        }
    }

    /**
     * MKCOL は {@link HttpClient} の自動リダイレクト対象外のため、301/302/307/308 を手動で追従する。
     *
     * @param lastSegment Vault パスの最後のセグメントのとき true（最終パスは {@link WebDavVaultStorage#prepareForOpen} で再検証しやすい）。
     */
    private void mkcol(String path, boolean lastSegment) throws IOException {
        mkcol(path, 0, new HashSet<>(), true, lastSegment);
    }

    /**
     * MKCOL は {@link HttpClient} の自動リダイレクト対象外のため、301/302/307/308 を手動で追従する。
     */
    private void mkcol(String path, int redirectDepth, Set<String> visitedUris, boolean allow409ParentRetry, boolean lastSegment)
            throws IOException {
        String normalized = normalizePath(path);
        String visitKey = endpoint + encodePath(normalized);
        if (!visitedUris.add(visitKey)) {
            throw new IOException("WebDAV MKCOL redirect loop path=" + normalized);
        }
        if (redirectDepth > 10) {
            throw new IOException("WebDAV MKCOL too many redirects path=" + normalized);
        }
        HttpRequest req = baseRequest(normalized).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<byte[]> resp = send(req);
        int status = resp.statusCode();
        if (status == 201 || status == 200 || status == 204 || status == 405) {
            return;
        }
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = resp.headers().firstValue("Location").orElse(null);
            String nextPath = pathFromRedirectLocation(location);
            if (nextPath != null && !nextPath.equals(normalized)) {
                mkcol(nextPath, redirectDepth + 1, visitedUris, allow409ParentRetry, lastSegment);
                return;
            }
        }
        if (status == 409) {
            Entry e = stat(normalized);
            if (e != null) {
                if (e.directory()) {
                    return;
                }
                throw new IOException(
                        "WebDAV MKCOL failed (path exists but is not a collection): " + normalized + mkcolErrorDetail(resp));
            }
            String parent = parentPath(normalized);
            boolean parentReachable =
                    parent == null
                            || "/".equals(parent)
                            || exists(parent)
                            || directoryExists(parent);
            if (!parentReachable) {
                if (allow409ParentRetry) {
                    visitedUris.remove(visitKey);
                    ensureDirectory(parent);
                    mkcol(normalized, redirectDepth, visitedUris, false, lastSegment);
                    return;
                }
                if (lastSegment) {
                    if (directoryExists(normalized)) {
                        return;
                    }
                    // 親が PROPFIND で見えないサーバーでも MKCOL は成功していることがある。上位で directoryExists を再確認する。
                    return;
                }
                throw new IOException("WebDAV MKCOL failed (parent missing): " + normalized + mkcolErrorDetail(resp));
            }
            // 親が取れている（またはルート直下）の 409 は「既にコレクション」等のサーバー差。応答本文が HTML とは限らない。
            if (directoryExists(normalized)) {
                return;
            }
            if (lastSegment) {
                return;
            }
            // 中間フォルダ: 親パスを再作成して1回だけ再試行する。
            if (allow409ParentRetry) {
                visitedUris.remove(visitKey);
                ensureDirectory(parent);
                mkcol(normalized, redirectDepth, visitedUris, false, lastSegment);
                return;
            }
            // 再試行後も PROPFIND で子コレクションが見えないサーバーがある。409 は多く「既に存在」側。次のセグメントまたは上位の検証に任せる。
            return;
        }
        if (status == 403) {
            throw new IOException(
                    "WebDAV MKCOL forbidden (check folder permissions on the server): " + normalized + mkcolErrorDetail(resp));
        }
        if (status == 401) {
            throw new IOException("WebDAV MKCOL unauthorized: " + normalized + mkcolErrorDetail(resp));
        }
        throw new IOException("WebDAV MKCOL failed: " + status + " path=" + normalized + mkcolErrorDetail(resp));
    }

    /**
     * MKCOL の 409 に HTML が付く場合（Apache 汎用ページや短い Conflict ページ）。
     * 本文が「internal error」だけとは限らず {@code <title>409 Conflict</title>} 程度のこともある。
     */
    private static boolean isLikelyApacheStyleServerError(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        String probe = new String(body, 0, Math.min(body.length, 256), StandardCharsets.UTF_8).trim();
        boolean looksHtml = probe.startsWith("<!DOCTYPE") || probe.toLowerCase().startsWith("<html");
        if (!looksHtml) {
            return false;
        }
        String s = peekUtf8Body(body, 1200).toLowerCase();
        return s.contains("internal error")
                || s.contains("misconfiguration")
                || s.contains("the server encountered")
                || s.contains("409 conflict")
                || s.contains("<h1>conflict</h1>")
                || (s.contains("<title>") && s.contains("conflict"));
    }

    private static String parentPath(String normalized) {
        String p = normalizePath(normalized);
        if ("/".equals(p)) {
            return null;
        }
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return p.substring(0, idx);
    }

    private static String mkcolErrorDetail(HttpResponse<byte[]> resp) {
        byte[] raw = resp.body();
        if (raw == null || raw.length == 0) {
            return "";
        }
        String probe = new String(raw, 0, Math.min(raw.length, 512), StandardCharsets.UTF_8).trim();
        if (probe.startsWith("<!DOCTYPE") || probe.toLowerCase().startsWith("<html")) {
            String title = extractHtmlTitle(raw);
            if (title != null && !title.isBlank()) {
                return " — " + title;
            }
            return " — (HTML エラー応答)";
        }
        String body = peekUtf8Body(raw, 200);
        return body.isEmpty() ? "" : " — " + body;
    }

    private static String extractHtmlTitle(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        String s = new String(body, StandardCharsets.UTF_8);
        int i = s.toLowerCase().indexOf("<title>");
        if (i < 0) {
            return null;
        }
        int j = s.toLowerCase().indexOf("</title>", i);
        if (j <= i) {
            return null;
        }
        return s.substring(i + 7, j).trim();
    }

    private static String peekUtf8Body(byte[] body, int maxChars) {
        if (body == null || body.length == 0) {
            return "";
        }
        int n = Math.min(body.length, maxChars * 4);
        String s = new String(body, 0, n, StandardCharsets.UTF_8);
        if (s.length() > maxChars) {
            s = s.substring(0, maxChars) + "…";
        }
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * {@code Location} をエンドポイント基準で解決し、WebDAV パス（先頭 /）にする。
     */
    private String pathFromRedirectLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            URI base = URI.create(endpoint);
            URI resolved = base.resolve(location.trim());
            if (resolved.getHost() != null
                    && base.getHost() != null
                    && !resolved.getHost().equalsIgnoreCase(base.getHost())) {
                return null;
            }
            String rawPath = resolved.getRawPath();
            if (rawPath == null || rawPath.isBlank()) {
                return "/";
            }
            return normalizePath(URLDecoder.decode(rawPath, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    List<Entry> list(String path) throws IOException {
        String normalized = normalizePath(path);
        HttpResponse<byte[]> resp = sendPropfind(normalized, "1");
        if (resp == null) {
            return List.of();
        }
        if (resp.statusCode() == 404) {
            return List.of();
        }
        if (resp.statusCode() != 207 && (resp.statusCode() < 200 || resp.statusCode() >= 300)) {
            throw new IOException("WebDAV PROPFIND failed: " + resp.statusCode() + " path=" + normalized);
        }
        return parsePropfindResponse(resp.body(), normalized);
    }

    Entry stat(String path) throws IOException {
        String normalized = normalizePath(path);
        HttpResponse<byte[]> resp = sendPropfind(normalized, "0");
        if (resp == null) {
            return null;
        }
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 207 && (resp.statusCode() < 200 || resp.statusCode() >= 300)) {
            throw new IOException("WebDAV PROPFIND failed: " + resp.statusCode() + " path=" + normalized);
        }
        List<Entry> entries = parsePropfindResponse(resp.body(), normalized);
        if (!entries.isEmpty()) {
            return entries.get(0);
        }
        // Depth:0 で対象そのものしか返らないサーバもあるため再取得。
        List<Entry> rootOnly = parseRootPropfind(resp.body(), normalized);
        return rootOnly.isEmpty() ? null : rootOnly.get(0);
    }

    byte[] download(String path) throws IOException {
        String normalized = normalizePath(path);
        HttpRequest req = baseRequest(normalized).GET().build();
        HttpResponse<byte[]> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new IOException("WebDAV GET failed: " + resp.statusCode() + " path=" + normalized);
        }
        return resp.body();
    }

    String upload(String path, byte[] data) throws IOException {
        return upload(path, data, null, false);
    }

    String upload(String path, byte[] data, String ifMatchEtag, boolean createOnly) throws IOException {
        String normalized = normalizePath(path);
        HttpRequest.Builder builder = baseRequest(normalized);
        if (ifMatchEtag != null && !ifMatchEtag.isBlank()) {
            builder.header("If-Match", ifMatchEtag);
        } else if (createOnly) {
            builder.header("If-None-Match", "*");
        }
        HttpRequest req = builder.PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build();
        HttpResponse<byte[]> resp = send(req);
        int status = resp.statusCode();
        if (status == 200 || status == 201 || status == 204) {
            return etagFromSuccessfulPut(resp, normalized);
        }
        if (status == 412) {
            throw new IOException("WebDAV PUT precondition failed (etag conflict): " + normalized);
        }
        if (status == 409) {
            return handlePut409(normalized, data, resp);
        }
        throw new IOException("WebDAV PUT failed: " + status + " path=" + normalized + mkcolErrorDetail(resp));
    }

    private String etagFromSuccessfulPut(HttpResponse<byte[]> resp, String normalized) throws IOException {
        String etag = resp.headers().firstValue("ETag").orElse(null);
        if (etag != null && !etag.isBlank()) {
            return etag;
        }
        Entry entry = stat(normalized);
        return entry == null ? null : entry.etag();
    }

    /**
     * MKCOL と同様、409 は「既にリソースがある」等で返すサーバーがある。Cryptomator の小さなメタファイルで多い。
     * PROPFIND が効かない場合は GET で内容一致なら成功扱いする。
     */
    private String handlePut409(String normalized, byte[] data, HttpResponse<byte[]> resp) throws IOException {
        Entry e = stat(normalized);
        if (e != null) {
            if (e.directory()) {
                throw new IOException("WebDAV PUT conflict (path is a collection): " + normalized + mkcolErrorDetail(resp));
            }
            String etag = resp.headers().firstValue("ETag").orElse(null);
            if (etag != null && !etag.isBlank()) {
                return etag;
            }
            return e.etag();
        }
        if (data.length <= 2_000_000) {
            byte[] remote = tryDownloadBytes(normalized);
            if (remote != null && remote.length == data.length && Arrays.equals(remote, data)) {
                Entry e2 = stat(normalized);
                if (e2 != null) {
                    return e2.etag();
                }
            }
        }
        if (isLikelyApacheStyleServerError(resp.body())) {
            return null;
        }
        throw new IOException("WebDAV PUT failed: 409 path=" + normalized + mkcolErrorDetail(resp));
    }

    private byte[] tryDownloadBytes(String normalized) {
        try {
            return download(normalized);
        } catch (IOException e) {
            return null;
        }
    }

    void delete(String path) throws IOException {
        String normalized = normalizePath(path);
        HttpRequest req = baseRequest(normalized).DELETE().build();
        HttpResponse<byte[]> resp = send(req);
        int status = resp.statusCode();
        if (status == 200 || status == 202 || status == 204 || status == 404) {
            return;
        }
        // SabreDAV / Nextcloud 等はコレクションや一括削除で HTTP 207 Multi-Status + XML を返すことがある。
        if (status == 207) {
            verifyDeleteMultiStatus(resp.body(), normalized);
            return;
        }
        throw new IOException("WebDAV DELETE failed: " + status + " path=" + normalized);
    }

    /**
     * DELETE の 207 応答を解釈する。各 response の status が 2xx または 404（既に無い）なら成功。
     */
    private static void verifyDeleteMultiStatus(byte[] body, String path) throws IOException {
        if (body == null || body.length == 0) {
            return;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
            if (responses.getLength() == 0) {
                return;
            }
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                String statusLine = findDavStatusLine(response);
                if (statusLine == null || statusLine.isBlank()) {
                    continue;
                }
                int code = parseHttpStatusCodeFromDavLine(statusLine);
                if (code >= 200 && code < 300) {
                    continue;
                }
                if (code == 404) {
                    continue;
                }
                throw new IOException("WebDAV DELETE multistatus failure: " + statusLine + " path=" + path);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse WebDAV DELETE Multi-Status response", e);
        }
    }

    private static String findDavStatusLine(Element response) {
        String s = text(response, "DAV:", "status");
        if (s != null && !s.isBlank()) {
            return s;
        }
        NodeList propstats = response.getElementsByTagNameNS("DAV:", "propstat");
        for (int i = 0; i < propstats.getLength(); i++) {
            Element propstat = (Element) propstats.item(i);
            s = text(propstat, "DAV:", "status");
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** {@code HTTP/1.1 204 No Content} 形式の行からステータスコードを取り出す。 */
    private static int parseHttpStatusCodeFromDavLine(String statusLine) {
        if (statusLine == null) {
            return -1;
        }
        String[] parts = statusLine.trim().split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].startsWith("HTTP/")) {
                try {
                    return Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * 存在しないパスに対してサーバーが HTTP 応答を返さずに切断する実装がある。
     * その場合 {@link #send} は {@code HTTP/1.1 header parser received no bytes} などを投げる。
     * MKCOL 等は成功するため、PROPFIND については「存在しない」と同義として {@code null} を返す。
     */
    private HttpResponse<byte[]> sendPropfind(String path, String depth) throws IOException {
        try {
            HttpRequest req = baseRequest(path)
                    .header("Depth", depth)
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY, StandardCharsets.UTF_8))
                    .build();
            return send(req);
        } catch (IOException e) {
            if (isConnectionClosedWithoutResponse(e)) {
                return null;
            }
            throw e;
        }
    }

    /** 応答ヘッダが一切来ないまま切断された場合（メッセージは JDK 依存）。 */
    private static boolean isConnectionClosedWithoutResponse(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            String m = x.getMessage();
            if (m != null) {
                if (m.contains("header parser received no bytes")) {
                    return true;
                }
                if (m.contains("EOF reached while reading HTTP response")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 大量同期直後など、サーバーが接続だけ切る・空応答で落とすことがある。数回だけ待って再送する。
     */
    private static boolean isTransientWebDavSendFailure(Throwable t) {
        if (isConnectionClosedWithoutResponse(t)) {
            return true;
        }
        for (Throwable x = t; x != null; x = x.getCause()) {
            String m = x.getMessage();
            if (m == null) {
                continue;
            }
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("connection reset")) {
                return true;
            }
            if (lower.contains("broken pipe")) {
                return true;
            }
            if (lower.contains("connection timed out") || lower.contains("read timed out")) {
                return true;
            }
            if (lower.contains("unexpected end of file")) {
                return true;
            }
        }
        return false;
    }

    private static final int WEBDAV_SEND_MAX_ATTEMPTS = 5;

    private static void sleepQuiet(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("WebDAV request interrupted", e);
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(absoluteUri(path))
                .header("Authorization", authHeader)
                .timeout(HTTP_REQUEST_TIMEOUT);
    }

    private HttpResponse<byte[]> send(HttpRequest req) throws IOException {
        IOException lastIo = null;
        for (int attempt = 0; attempt < WEBDAV_SEND_MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                sleepQuiet(Math.min(4000L, 200L * (1L << (attempt - 1))));
            }
            try {
                return httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("WebDAV request interrupted", e);
            } catch (IOException e) {
                lastIo = e;
                if (attempt < WEBDAV_SEND_MAX_ATTEMPTS - 1 && isTransientWebDavSendFailure(e)) {
                    continue;
                }
                throw e;
            }
        }
        throw lastIo != null ? lastIo : new IOException("WebDAV send failed");
    }

    private URI absoluteUri(String path) {
        return URI.create(endpoint + encodePath(path));
    }

    private static String normalizeEndpoint(String endpoint) {
        String ep = endpoint.trim();
        while (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        return ep;
    }

    static String normalizePath(String path) {
        String p = path == null ? "/" : path.trim().replace('\\', '/');
        if (p.isEmpty()) {
            p = "/";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String encodePath(String path) {
        String normalized = normalizePath(path);
        String[] parts = normalized.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                sb.append('/');
                continue;
            }
            String enc = URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
            sb.append(enc).append('/');
        }
        if (sb.length() > 1 && !normalized.endsWith("/")) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static List<Entry> parsePropfindResponse(byte[] body, String queryPath) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
            List<Entry> out = new ArrayList<>();
            String normalizedQuery = normalizePath(queryPath);
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                String href = text(response, "DAV:", "href");
                if (href == null || href.isBlank()) {
                    continue;
                }
                String fullPath = hrefToPath(href);
                if (normalizePath(fullPath).equals(normalizedQuery)) {
                    continue;
                }
                Element prop = firstProp(response);
                if (prop == null) {
                    continue;
                }
                boolean directory = hasCollection(prop);
                long contentLength = parseLong(text(prop, "DAV:", "getcontentlength"));
                Instant lastModified = parseHttpDate(text(prop, "DAV:", "getlastmodified"));
                String etag = normalizeEtag(text(prop, "DAV:", "getetag"));
                out.add(new Entry(normalizePath(fullPath), directory, contentLength, lastModified, etag));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Failed to parse WebDAV PROPFIND response", e);
        }
    }

    private static List<Entry> parseRootPropfind(byte[] body, String queryPath) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
            List<Entry> out = new ArrayList<>();
            String normalizedQuery = normalizePath(queryPath);
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                String href = text(response, "DAV:", "href");
                if (href == null || href.isBlank()) {
                    continue;
                }
                String fullPath = normalizePath(hrefToPath(href));
                if (!fullPath.equals(normalizedQuery)) {
                    continue;
                }
                Element prop = firstProp(response);
                if (prop == null) {
                    continue;
                }
                boolean directory = hasCollection(prop);
                long contentLength = parseLong(text(prop, "DAV:", "getcontentlength"));
                Instant lastModified = parseHttpDate(text(prop, "DAV:", "getlastmodified"));
                String etag = normalizeEtag(text(prop, "DAV:", "getetag"));
                out.add(new Entry(fullPath, directory, contentLength, lastModified, etag));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Failed to parse WebDAV PROPFIND response", e);
        }
    }

    private static Element firstProp(Element response) {
        NodeList propstats = response.getElementsByTagNameNS("DAV:", "propstat");
        for (int i = 0; i < propstats.getLength(); i++) {
            Element propstat = (Element) propstats.item(i);
            String status = text(propstat, "DAV:", "status");
            if (status == null || !status.contains("200")) {
                continue;
            }
            NodeList props = propstat.getElementsByTagNameNS("DAV:", "prop");
            if (props.getLength() > 0) {
                return (Element) props.item(0);
            }
        }
        return null;
    }

    private static boolean hasCollection(Element prop) {
        NodeList resourceTypes = prop.getElementsByTagNameNS("DAV:", "resourcetype");
        if (resourceTypes.getLength() == 0) {
            return false;
        }
        Node resourceType = resourceTypes.item(0);
        NodeList children = resourceType.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && "collection".equals(element.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private static String text(Element parent, String ns, String localName) {
        NodeList list = parent.getElementsByTagNameNS(ns, localName);
        if (list.getLength() == 0) {
            return null;
        }
        String t = list.item(0).getTextContent();
        return t == null ? null : t.trim();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static Instant parseHttpDate(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private static String normalizeEtag(String etag) {
        if (etag == null) {
            return null;
        }
        String t = etag.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 同期状態と PROPFIND の ETag を比較するとき用。PUT 応答ヘッダと XML の表記差・弱タグ接頭辞を吸収する。
     */
    static String canonicalEtagForCompare(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.regionMatches(true, 0, "W/", 0, 2)) {
            t = t.substring(2).trim();
        }
        while (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t.isEmpty() ? null : t;
    }

    private static String hrefToPath(String href) {
        URI uri = URI.create(href);
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            path = href;
        }
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        return normalizePath(decoded);
    }
}
