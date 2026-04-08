package com.example.gazo.vault;

import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.api.MasterkeyLoader;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;

import java.net.URI;
import java.nio.file.Path;

/**
 * Cryptomator デスクトップと同じ {@code masterkeyfile:masterkey.cryptomator} 形式のキー ID を扱うローダー。
 */
public final class MasterkeyFileKeyLoader implements MasterkeyLoader {

    public static final String SCHEME = "masterkeyfile";

    private final Path vaultRoot;
    private final CharSequence passphrase;
    private final MasterkeyFileAccess fileAccess;

    public MasterkeyFileKeyLoader(Path vaultRoot, CharSequence passphrase, MasterkeyFileAccess fileAccess) {
        this.vaultRoot = vaultRoot;
        this.passphrase = passphrase;
        this.fileAccess = fileAccess;
    }

    @Override
    public Masterkey loadKey(URI keyId) throws MasterkeyLoadingFailedException {
        if (!SCHEME.equalsIgnoreCase(keyId.getScheme())) {
            throw new MasterkeyLoadingFailedException("Unsupported key scheme: " + keyId.getScheme());
        }
        Path path = vaultRoot.resolve(keyId.getSchemeSpecificPart());
        return fileAccess.load(path, passphrase);
    }
}
