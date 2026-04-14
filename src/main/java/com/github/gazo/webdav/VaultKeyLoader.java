package com.github.gazo.webdav;

import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * Loads a Cryptomator vault's masterkey from its masterkey.cryptomator file
 * using the user-supplied passphrase. This is the standard Cryptomator key
 * derivation path: passphrase -> scrypt -> KEK -> AES Key Unwrap -> masterkey.
 */
public class VaultKeyLoader {

    private static final Logger log = LoggerFactory.getLogger(VaultKeyLoader.class);

    private static final String MASTERKEY_FILENAME = "masterkey.cryptomator";
    private static final byte[] PEPPER = new byte[0];

    private VaultKeyLoader() {
    }

    /**
     * Load the vault's masterkey by decrypting the masterkey.cryptomator file with the given passphrase.
     *
     * @param vaultPath  the root directory of the Cryptomator vault
     * @param passphrase the vault passphrase
     * @return a Masterkey instance (caller takes ownership)
     * @throws MasterkeyLoadingFailedException if the key file can't be read or the passphrase is wrong
     */
    public static Masterkey loadMasterkey(Path vaultPath, CharSequence passphrase) throws MasterkeyLoadingFailedException {
        Path masterkeyFile = vaultPath.resolve(MASTERKEY_FILENAME);
        if (!Files.exists(masterkeyFile)) {
            throw new MasterkeyLoadingFailedException("Masterkey file not found: " + masterkeyFile);
        }
        log.info("Loading masterkey from: {}", masterkeyFile);

        MasterkeyFileAccess access = new MasterkeyFileAccess(PEPPER, new SecureRandom());
        return access.load(masterkeyFile, passphrase);
    }
}
