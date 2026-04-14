package com.github.cryptovault.webdav;

import com.github.cryptovault.webdav.resource.NioResourceFactory;

import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.Request;
import io.milton.http.Response;

import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;
import org.cryptomator.cryptolib.api.MasterkeyLoader;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Console;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.EnumSet;

/**
 * Standalone server that unlocks a Cryptomator vault and serves its
 * decrypted contents over WebDAV using Milton + embedded Jetty.
 *
 * <pre>
 * Usage:  java -jar cryptomator-webdav-bridge.jar &lt;vault-path&gt; [options]
 *
 * Options:
 *   --port &lt;port&gt;          WebDAV listen port           (default: 8080)
 *   --host &lt;bind-addr&gt;     Bind address                  (default: 127.0.0.1)
 *   --user &lt;user&gt;          HTTP Basic auth username      (default: none / open)
 *   --password &lt;pass&gt;      HTTP Basic auth password      (default: none / open)
 *   --passphrase &lt;phrase&gt;  Vault passphrase (insecure – prefer interactive prompt)
 *   --readonly             Mount the vault in read-only mode
 * </pre>
 */
public class CryptoVaultWebDavServer {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoVaultWebDavServer.class);
    private static final String MASTERKEY_FILENAME = "masterkey.cryptomator";

    public static void main(String[] args) throws Exception {

        // --- Parse CLI ---
        String vaultPath = null;
        int port = 8080;
        String host = "127.0.0.1";
        String webdavUser = null;
        String webdavPassword = null;
        String passphrase = null;
        boolean readonly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--host":
                    host = args[++i];
                    break;
                case "--user":
                    webdavUser = args[++i];
                    break;
                case "--password":
                    webdavPassword = args[++i];
                    break;
                case "--passphrase":
                    passphrase = args[++i];
                    break;
                case "--readonly":
                    readonly = true;
                    break;
                default:
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        printUsage();
                        System.exit(1);
                    }
                    vaultPath = args[i];
                    break;
            }
        }

        if (vaultPath == null) {
            printUsage();
            System.exit(1);
            return;
        }

        Path vault = Paths.get(vaultPath).toAbsolutePath();
        if (!Files.isDirectory(vault)) {
            System.err.println("Vault directory does not exist: " + vault);
            System.exit(1);
        }

        Path masterkeyFile = vault.resolve(MASTERKEY_FILENAME);
        if (!Files.exists(masterkeyFile)) {
            System.err.println("Masterkey file not found: " + masterkeyFile);
            System.err.println("Is this a valid Cryptomator vault?");
            System.exit(1);
        }

        // --- Obtain passphrase ---
        if (passphrase == null) {
            Console console = System.console();
            if (console != null) {
                char[] pw = console.readPassword("Vault passphrase: ");
                passphrase = new String(pw);
            } else {
                System.err.println("No console available. Supply --passphrase on the command line.");
                System.exit(1);
            }
        }

        // --- Unlock vault via CryptoFS ---
        LOG.info("Unlocking vault at {} ...", vault);

        SecureRandom csprng = new SecureRandom();
        MasterkeyFileAccess masterkeyFileAccess = new MasterkeyFileAccess(new byte[0], csprng);
        final String vaultPassphrase = passphrase;

        MasterkeyLoader loader = keyId -> masterkeyFileAccess.load(masterkeyFile, vaultPassphrase);

        CryptoFileSystemProperties.Builder propsBuilder = CryptoFileSystemProperties
                .cryptoFileSystemProperties()
                .withKeyLoader(loader);

        if (readonly) {
            propsBuilder.withFlags(CryptoFileSystemProperties.FileSystemFlags.READONLY);
        }

        FileSystem cryptoFs = CryptoFileSystemProvider.newFileSystem(vault, propsBuilder.build());
        LOG.info("Vault unlocked successfully.");

        // --- Build Milton HttpManager ---
        NioResourceFactory resourceFactory = new NioResourceFactory(
                cryptoFs, "CryptomatorVault", webdavUser, webdavPassword);

        HttpManagerBuilder builder = new HttpManagerBuilder();
        builder.setResourceFactory(resourceFactory);
        builder.setEnableCompression(false);
        HttpManager httpManager = builder.buildHttpManager();

        // --- Start Jetty with a filter that delegates to Milton ---
        Server server = new Server(new java.net.InetSocketAddress(host, port));
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        FilterHolder miltonFilter = new FilterHolder(new MiltonDelegateFilter(httpManager));
        context.addFilter(miltonFilter, "/*", EnumSet.of(DispatcherType.REQUEST));

        server.setHandler(context);
        server.start();

        LOG.info("WebDAV server listening on http://{}:{}/", host, port);
        LOG.info("Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down...");
            try {
                server.stop();
                httpManager.shutdown();
                cryptoFs.close();
            } catch (Exception e) {
                LOG.warn("Error during shutdown", e);
            }
        }));

        server.join();
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar cryptomator-webdav-bridge.jar <vault-path> [options]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --port <port>          WebDAV listen port            (default: 8080)");
        System.err.println("  --host <bind-addr>     Bind address                   (default: 127.0.0.1)");
        System.err.println("  --user <user>          HTTP Basic auth username       (default: none)");
        System.err.println("  --password <pass>      HTTP Basic auth password       (default: none)");
        System.err.println("  --passphrase <phrase>  Vault passphrase");
        System.err.println("  --readonly             Mount vault in read-only mode");
    }

    /**
     * Thin servlet {@link Filter} that hands every request to Milton's
     * {@link HttpManager} for WebDAV processing.
     */
    static class MiltonDelegateFilter implements Filter {

        private final HttpManager httpManager;

        MiltonDelegateFilter(HttpManager httpManager) {
            this.httpManager = httpManager;
        }

        @Override
        public void init(FilterConfig filterConfig) { }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            HttpServletResponse httpResp = (HttpServletResponse) response;

            Request miltonRequest = new io.milton.servlet.ServletRequest(httpReq, null);
            Response miltonResponse = new io.milton.servlet.ServletResponse(httpResp);

            try {
                httpManager.process(miltonRequest, miltonResponse);
            } catch (Exception e) {
                LOG.error("Milton processing error", e);
                httpResp.sendError(500, "Internal Server Error");
            }

            httpResp.getOutputStream().flush();
            httpResp.flushBuffer();
        }

        @Override
        public void destroy() { }
    }
}
