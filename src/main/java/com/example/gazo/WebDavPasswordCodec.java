package com.example.gazo;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * WebDAV パスワードを {@code settings.properties} に載せるためのローカル暗号化（
 * ユーザーホームを材料にした鍵。同一 PC ユーザーのみ復号可能で、平文保存よりはマシ）。
 */
final class WebDavPasswordCodec {
    private static final byte[] SALT = "gazo.webdav.pwd.v1".getBytes(StandardCharsets.UTF_8);
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private WebDavPasswordCodec() {}

    static String encode(char[] password) throws GeneralSecurityException {
        byte[] plain = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            SecretKey key = deriveKey();
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain);
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    static char[] decode(String encoded) throws GeneralSecurityException {
        byte[] combined = Base64.getDecoder().decode(encoded);
        if (combined.length < GCM_IV_LENGTH + 16) {
            throw new GeneralSecurityException("invalid payload length");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] cipherBytes = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
        SecretKey key = deriveKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(cipherBytes);
        try {
            return new String(plain, StandardCharsets.UTF_8).toCharArray();
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static SecretKey deriveKey() throws GeneralSecurityException {
        String home = System.getProperty("user.home", "");
        char[] material = home.toCharArray();
        try {
            PBEKeySpec spec = new PBEKeySpec(material, SALT, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = skf.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(material, '\0');
        }
    }
}
