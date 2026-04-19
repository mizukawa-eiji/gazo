package com.example.gazo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultConnectionTest {

    @Test
    void local_flagsAndDisplayLabel() {
        Path p = Path.of("C:", "vaults", "mine");
        VaultConnection c = VaultConnection.local(p);
        assertTrue(c.isLocal());
        assertFalse(c.isWebDav());
        assertEquals(VaultConnection.Type.LOCAL, c.type());
        assertEquals(p, c.localPath());
        assertEquals(p.toString(), c.displayLabel());
    }

    @Test
    void webDav_flagsAndDisplayLabel() {
        VaultConnection c = VaultConnection.webDav("https://dav.example", "base/sub", "alice");
        assertTrue(c.isWebDav());
        assertFalse(c.isLocal());
        assertEquals(VaultConnection.Type.WEBDAV, c.type());
        assertEquals("https://dav.example", c.webDavEndpoint());
        assertEquals("/base/sub", c.webDavBasePath());
        assertEquals("alice", c.webDavUsername());
        assertEquals("webdav://alice@https://dav.example/base/sub", c.displayLabel());
    }

    @Test
    void webDav_normalizesBasePathToLeadingSlash() {
        VaultConnection c = VaultConnection.webDav("https://dav.example", "relative", "u");
        assertEquals("/relative", c.webDavBasePath());
    }

    @Test
    void webDav_rejectsBlankParts() {
        assertThrows(IllegalArgumentException.class, () -> VaultConnection.webDav("", "/x", "u"));
        assertThrows(IllegalArgumentException.class, () -> VaultConnection.webDav("https://x", "", "u"));
        assertThrows(IllegalArgumentException.class, () -> VaultConnection.webDav("https://x", "/x", "  "));
    }
}
