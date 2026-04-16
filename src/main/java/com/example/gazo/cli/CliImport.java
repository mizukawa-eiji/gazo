package com.example.gazo.cli;

import com.example.gazo.ImportFolderTagging;
import com.example.gazo.VaultConnection;
import com.example.gazo.VaultPathStore;
import com.example.gazo.vault.GazoVaultService;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * コマンドラインから Vault に画像を取り込む。
 */
public final class CliImport {

    private CliImport() {
    }

    /**
     * 先頭が {@code import} のときだけ Vault に取り込み、{@link System#exit(int)} で終了する。
     * それ以外は何もせず戻る（GUI 起動用）。
     */
    public static void tryRun(String[] args) {
        if (args.length >= 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printHelp();
            System.exit(0);
        }
        if (args.length < 1 || !"import".equals(args[0])) {
            return;
        }

        boolean recursive = false;
        Path vaultOpt = null;
        String passwordOpt = null;
        String webDavEndpointOpt = null;
        String webDavBasePathOpt = null;
        String webDavUsernameOpt = null;
        String webDavPasswordOpt = null;
        List<String> pathStrings = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--".equals(a)) {
                while (++i < args.length) {
                    pathStrings.add(args[i]);
                }
                break;
            }
            if ("-r".equals(a) || "--recursive".equals(a)) {
                recursive = true;
                continue;
            }
            if ("--vault".equals(a)) {
                if (++i >= args.length) {
                    err("オプション --vault にはパスが必要です。");
                    System.exit(1);
                }
                vaultOpt = Paths.get(args[i]);
                continue;
            }
            if ("--password".equals(a)) {
                if (++i >= args.length) {
                    err("オプション --password には値が必要です。");
                    System.exit(1);
                }
                passwordOpt = args[i];
                continue;
            }
            if ("--webdav-endpoint".equals(a)) {
                if (++i >= args.length) {
                    err("オプション --webdav-endpoint には値が必要です。");
                    System.exit(1);
                }
                webDavEndpointOpt = args[i];
                continue;
            }
            if ("--webdav-base-path".equals(a)) {
                if (++i >= args.length) {
                    err("オプション --webdav-base-path には値が必要です。");
                    System.exit(1);
                }
                webDavBasePathOpt = args[i];
                continue;
            }
            if ("--webdav-username".equals(a)) {
                if (++i >= args.length) {
                    err("オプション --webdav-username には値が必要です。");
                    System.exit(1);
                }
                webDavUsernameOpt = args[i];
                continue;
            }
            if ("--webdav-password".equals(a)) {
                if (++i >= args.length) {
                    err("オプション --webdav-password には値が必要です。");
                    System.exit(1);
                }
                webDavPasswordOpt = args[i];
                continue;
            }
            if (a.startsWith("-")) {
                err("不明なオプション: " + a);
                System.exit(1);
            }
            pathStrings.add(a);
        }

        if (pathStrings.isEmpty()) {
            err("取り込むパスを 1 つ以上指定してください。");
            printImportUsage();
            System.exit(1);
        }

        VaultConnection configuredConnection = VaultPathStore.loadInitialVaultConnection();
        boolean webDavSpecified =
                webDavEndpointOpt != null || webDavBasePathOpt != null || webDavUsernameOpt != null;
        if (webDavSpecified && vaultOpt != null) {
            err("--vault と --webdav-* オプションは同時に指定できません。");
            System.exit(1);
        }
        VaultConnection connection;
        if (webDavSpecified) {
            if (webDavEndpointOpt == null || webDavBasePathOpt == null || webDavUsernameOpt == null) {
                err("--webdav-endpoint / --webdav-base-path / --webdav-username をすべて指定してください。");
                System.exit(1);
            }
            connection = VaultConnection.webDav(webDavEndpointOpt, webDavBasePathOpt, webDavUsernameOpt);
        } else if (vaultOpt != null) {
            connection = VaultConnection.local(vaultOpt);
        } else {
            connection = configuredConnection;
        }

        char[] webDavPassword = null;
        if (connection.isWebDav()) {
            webDavPassword = resolveWebDavPassword(webDavPasswordOpt);
            if (webDavPassword == null || webDavPassword.length == 0) {
                err("WebDAV パスワードを取得できませんでした（GAZO_WEBDAV_PASSWORD、--webdav-password、または対話入力）。");
                System.exit(1);
            }
        }
        char[] passphrase = resolvePassphrase(passwordOpt);
        if (passphrase == null) {
            err("パスフレーズを取得できませんでした（GAZO_PASSPHRASE、--password、または対話入力）。");
            System.exit(1);
        }

        GazoVaultService vault = new GazoVaultService(connection, webDavPassword);
        if (!vault.vaultExists()) {
            err("アルバムが見つかりません: " + connection.displayLabel());
            System.exit(2);
        }

        try {
            vault.unlock(new String(passphrase));
        } catch (MasterkeyLoadingFailedException | IOException e) {
            err("アルバムのロック解除に失敗しました（パスフレーズの誤りなど）: " + e.getMessage());
            System.exit(2);
        } finally {
            Arrays.fill(passphrase, '\0');
            if (webDavPassword != null) {
                Arrays.fill(webDavPassword, '\0');
            }
        }

        int failures = 0;
        try {
            for (String ps : pathStrings) {
                Path p = Paths.get(ps);
                if (!Files.exists(p)) {
                    err("存在しません: " + p);
                    failures++;
                    continue;
                }
                if (Files.isRegularFile(p)) {
                    if (isImageFile(p)) {
                        failures += importOne(vault, p, null);
                    } else {
                        err("画像ではありません（スキップ）: " + p);
                        failures++;
                    }
                } else if (Files.isDirectory(p)) {
                    failures += importDirectory(vault, p, recursive);
                } else {
                    err("ファイルでもディレクトリでもありません: " + p);
                    failures++;
                }
            }
        } finally {
            vault.close();
        }

        System.exit(failures > 0 ? 3 : 0);
    }

    /**
     * 取り込み後、{@code tagsToAdd} が非空なら Vault 内ファイルにタグをマージする。
     *
     * @param tagsToAdd 単一ファイル取り込み時は {@code null} または空でタグなし。
     */
    private static int importOne(GazoVaultService vault, Path sourceFile, Set<String> tagsToAdd) {
        try {
            Path dest = vault.importImage(sourceFile);
            if (tagsToAdd != null && !tagsToAdd.isEmpty()) {
                Set<String> tags = new LinkedHashSet<>(vault.getTags(dest));
                tags.addAll(tagsToAdd);
                vault.setTags(dest, tags);
            }
            out("取り込み: " + sourceFile + " -> " + dest.getFileName());
            return 0;
        } catch (IOException e) {
            err("失敗: " + sourceFile + " — " + e.getMessage());
            return 1;
        }
    }

    private static int importDirectory(GazoVaultService vault, Path directory, boolean recursive) {
        int failures = 0;
        try {
            if (recursive) {
                try (Stream<Path> stream = Files.walk(directory)) {
                    List<Path> images = stream
                            .filter(Files::isRegularFile)
                            .filter(CliImport::isImageFile)
                            .sorted()
                            .toList();
                    for (Path image : images) {
                        failures += importOne(vault, image, ImportFolderTagging.folderTagsForPathUnderRoot(directory, image));
                    }
                }
            } else {
                try (Stream<Path> stream = Files.list(directory)) {
                    List<Path> images = stream
                            .filter(Files::isRegularFile)
                            .filter(CliImport::isImageFile)
                            .sorted()
                            .toList();
                    for (Path image : images) {
                        failures += importOne(vault, image, ImportFolderTagging.folderTagsForPathUnderRoot(directory, image));
                    }
                }
            }
        } catch (IOException e) {
            err("ディレクトリの読み込みに失敗しました: " + directory + " — " + e.getMessage());
            failures++;
        }
        return failures;
    }

    private static boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (!name.isEmpty() && name.charAt(0) == '.') {
            return false;
        }
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".gif")
                || name.endsWith(".bmp")
                || name.endsWith(".webp");
    }

    private static char[] resolvePassphrase(String passwordOpt) {
        if (passwordOpt != null && !passwordOpt.isEmpty()) {
            return passwordOpt.toCharArray();
        }
        String env = System.getenv("GAZO_PASSPHRASE");
        if (env != null && !env.isEmpty()) {
            return env.toCharArray();
        }
        Console console = System.console();
        if (console != null) {
            return console.readPassword("アルバム パスフレーズ: ");
        }
        return null;
    }

    private static char[] resolveWebDavPassword(String passwordOpt) {
        if (passwordOpt != null && !passwordOpt.isEmpty()) {
            return passwordOpt.toCharArray();
        }
        String env = System.getenv("GAZO_WEBDAV_PASSWORD");
        if (env != null && !env.isEmpty()) {
            return env.toCharArray();
        }
        Console console = System.console();
        if (console != null) {
            return console.readPassword("WebDAV パスワード: ");
        }
        return null;
    }

    private static void printHelp() {
        out("Gazo — コマンドライン");
        out("");
        out("  java -jar gazo.jar import [オプション] <パス>...");
        out("  java -jar gazo.jar --help");
        out("");
        out("import のオプション:");
        out("  --vault <dir>     アルバムのディレクトリ（省略時は設定の最後に使ったパス、なければ ~/.gazo/vault）");
        out("  -r, --recursive   ディレクトリ指定時、サブフォルダも再帰的に取り込む");
        out("  --password <str>  パスフレーズ（非推奨。環境変数 GAZO_PASSPHRASE の利用を推奨）");
        out("  --webdav-endpoint <url>   WebDAV 接続 URL（例: https://host/remote.php/dav/files/user）");
        out("  --webdav-base-path <path> アルバムを置く WebDAV 内パス（例: /gazo-vault）");
        out("  --webdav-username <name>  WebDAV ユーザー名");
        out("  --webdav-password <str>   WebDAV パスワード（非推奨。環境変数 GAZO_WEBDAV_PASSWORD 推奨）");
        out("  --                以降をすべてパスとして扱う");
        out("");
        out("パスフレーズ: 環境変数 GAZO_PASSPHRASE、--password、または対話入力の順で使用します。");
        out("WebDAV パスワード: 環境変数 GAZO_WEBDAV_PASSWORD、--webdav-password、または対話入力の順で使用します。");
        out("");
        out("ディレクトリを -r なしで指定した場合、その直下の画像ファイルのみ取り込みます。");
    }

    private static void printImportUsage() {
        out("使用例: java -jar gazo.jar import -r C:\\Photos\\trip");
        out("        java -jar gazo.jar import --vault ~/.gazo/vault photo1.jpg folder/");
    }

    private static void out(String s) {
        System.out.println(s);
    }

    private static void err(String s) {
        System.err.println(s);
    }
}
