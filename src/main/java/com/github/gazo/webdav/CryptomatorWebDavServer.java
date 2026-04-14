package com.github.gazo.webdav;

import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.ResourceFactory;
import io.milton.http.fs.NullSecurityManager;
import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Standalone server that mounts a Cryptomator vault and exposes it via WebDAV.
 *
 * <pre>
 * Usage: java -jar cryptomator-webdav-bridge.jar [options]
 *   --vault   &lt;path&gt;     Path to the Cryptomator vault directory (required)
 *   --port    &lt;number&gt;   WebDAV server port (default: 8080)
 *   --host    &lt;address&gt;  Bind address (default: 0.0.0.0)
 *   --readonly            Mount vault in read-only mode
 *   --password &lt;pass&gt;    Vault password (if omitted, will prompt on stdin)
 * </pre>
 */
public class CryptomatorWebDavServer {

    private static final Logger log = LoggerFactory.getLogger(CryptomatorWebDavServer.class);

    public static void main(String[] args) throws Exception {
        Config config = parseArgs(args);

        if (config.vaultPath == null) {
            System.err.println("Error: --vault <path> is required");
            printUsage();
            System.exit(1);
        }

        Path vaultPath = Paths.get(config.vaultPath).toAbsolutePath();
        if (!Files.isDirectory(vaultPath)) {
            System.err.println("Error: Vault path does not exist or is not a directory: " + vaultPath);
            System.exit(1);
        }

        String password = config.password;
        if (password == null) {
            password = promptPassword();
        }

        log.info("Opening Cryptomator vault: {}", vaultPath);
        log.info("WebDAV will be available at http://{}:{}/", config.host, config.port);

        FileSystem cryptoFs = openVault(vaultPath, password, config.readOnly);
        Path rootPath = cryptoFs.getPath("/");

        ResourceFactory resourceFactory = new NioResourceFactory(
                rootPath,
                new NullSecurityManager(),
                ""
        );

        HttpManagerBuilder builder = new HttpManagerBuilder();
        builder.setMainResourceFactory(resourceFactory);
        builder.setEnableCompression(false);
        HttpManager httpManager = builder.buildHttpManager();

        Server jettyServer = new Server(config.port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");

        WebDavServlet webDavServlet = new WebDavServlet(httpManager);
        ServletHolder holder = new ServletHolder("webdav", webDavServlet);
        context.addServlet(holder, "/*");

        jettyServer.setHandler(context);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                jettyServer.stop();
                cryptoFs.close();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }));

        jettyServer.start();
        log.info("WebDAV server started on port {}", config.port);
        jettyServer.join();
    }

    static FileSystem openVault(Path vaultPath, String password, boolean readOnly) throws Exception {
        CryptoFileSystemProperties.Builder propsBuilder = CryptoFileSystemProperties.cryptoFileSystemProperties()
                .withKeyLoader(keyId -> {
                    return VaultKeyLoader.loadMasterkey(vaultPath, password);
                });

        if (readOnly) {
            propsBuilder.withFlags(CryptoFileSystemProperties.FileSystemFlags.READONLY);
        }

        return CryptoFileSystemProvider.newFileSystem(vaultPath, propsBuilder.build());
    }

    private static String promptPassword() {
        Console console = System.console();
        if (console != null) {
            char[] pw = console.readPassword("Vault password: ");
            return new String(pw);
        } else {
            System.out.print("Vault password: ");
            Scanner scanner = new Scanner(System.in);
            return scanner.nextLine();
        }
    }

    private static Config parseArgs(String[] args) {
        Config config = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--vault" -> config.vaultPath = args[++i];
                case "--port" -> config.port = Integer.parseInt(args[++i]);
                case "--host" -> config.host = args[++i];
                case "--readonly" -> config.readOnly = true;
                case "--password" -> config.password = args[++i];
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }
        return config;
    }

    private static void printUsage() {
        System.err.println("""
                Usage: java -jar cryptomator-webdav-bridge.jar [options]
                
                Options:
                  --vault    <path>     Path to the Cryptomator vault directory (required)
                  --port     <number>   WebDAV server port (default: 8080)
                  --host     <address>  Bind address (default: 0.0.0.0)
                  --readonly            Mount vault in read-only mode
                  --password <pass>     Vault password (omit to prompt interactively)
                  --help, -h            Show this help message
                """);
    }

    static class Config {
        String vaultPath;
        int port = 8080;
        String host = "0.0.0.0";
        boolean readOnly = false;
        String password;
    }
}
