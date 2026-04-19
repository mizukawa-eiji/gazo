package com.example.gazo;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDavPasswordCodecTest {

    @Test
    void encodeDecode_roundTrip() throws GeneralSecurityException {
        char[] original = "vault-パスワード-42".toCharArray();
        String encoded = WebDavPasswordCodec.encode(original);
        assertTrue(encoded.length() > 16, "Base64 payload should be non-trivial");
        char[] decoded = WebDavPasswordCodec.decode(encoded);
        try {
            assertArrayEquals(original, decoded);
        } finally {
            Arrays.fill(decoded, '\0');
        }
    }

    @Test
    void decode_rejectsTooShortPayload() {
        assertThrows(GeneralSecurityException.class, () -> WebDavPasswordCodec.decode("AAAA"));
    }
}
