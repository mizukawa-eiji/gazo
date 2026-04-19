package com.example.gazo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VaultOpenRequestTest {

    @Test
    void local_hasNoPasswordAndUnchangedPersistence() {
        VaultConnection c = VaultConnection.local(Path.of("x", "vault"));
        VaultOpenRequest r = VaultOpenRequest.local(c);
        assertEquals(c, r.connection());
        assertNull(r.webDavPassword());
        assertEquals(VaultOpenRequest.WebDavPasswordPersistence.UNCHANGED, r.webDavPasswordPersistence());
    }

    @Test
    void webDav_copiesPasswordBuffer() {
        VaultConnection c = VaultConnection.webDav("https://ex.example", "/base", "user");
        char[] secret = "p4ss".toCharArray();
        VaultOpenRequest r = VaultOpenRequest.webDav(c, secret, VaultOpenRequest.WebDavPasswordPersistence.STORE);
        Arrays.fill(secret, 'x');

        char[] fromGetter = r.webDavPassword();
        try {
            assertArrayEquals("p4ss".toCharArray(), fromGetter);
        } finally {
            Arrays.fill(fromGetter, '\0');
        }
    }

    @Test
    void webDavPassword_returnsIndependentCopyEachCall() {
        VaultConnection c = VaultConnection.webDav("https://ex.example", "/base", "user");
        VaultOpenRequest r = VaultOpenRequest.webDav(c, "ab".toCharArray());
        char[] a = r.webDavPassword();
        char[] b = r.webDavPassword();
        try {
            a[0] = 'z';
            assertEquals('a', b[0]);
        } finally {
            Arrays.fill(a, '\0');
            Arrays.fill(b, '\0');
        }
    }

    @Test
    void clearSecrets_zeroesInternalPassword() {
        VaultConnection c = VaultConnection.webDav("https://ex.example", "/base", "user");
        VaultOpenRequest r = VaultOpenRequest.webDav(c, "secret".toCharArray());
        r.clearSecrets();
        char[] after = r.webDavPassword();
        try {
            assertArrayEquals(new char[] {'\0', '\0', '\0', '\0', '\0', '\0'}, after);
        } finally {
            Arrays.fill(after, '\0');
        }
    }
}
