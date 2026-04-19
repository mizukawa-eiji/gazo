package com.example.gazo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalBackupMirrorTest {

    @AfterEach
    void tearDown() {
        VaultPathStore.configFileOverrideForTests = null;
    }

    @Test
    void stableVaultKey_local_normalizes() throws Exception {
        Path a = Files.createTempDirectory("gazo-vault-a").toAbsolutePath().normalize();
        Path b = a.resolve("..").resolve(a.getFileName()).normalize();
        VaultConnection ca = VaultConnection.local(a);
        VaultConnection cb = VaultConnection.local(b);
        assertEquals(
                VaultPathStore.stableVaultKeyForPhysicalBackup(ca),
                VaultPathStore.stableVaultKeyForPhysicalBackup(cb));
    }

    @Test
    void stableVaultKey_webDav_includesTriple() {
        VaultConnection c = VaultConnection.webDav("https://x.example", "/vault", "u1");
        assertEquals(
                "webdav:https://x.example|/vault|u1", VaultPathStore.stableVaultKeyForPhysicalBackup(c));
    }

    @Test
    void physicalBackupMirror_saveLoadClear_roundTrip(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("settings.properties");
        VaultPathStore.configFileOverrideForTests = cfg;
        VaultConnection conn = VaultConnection.local(tmp.resolve("vault"));
        Path mirror = tmp.resolve("mirror").toAbsolutePath().normalize();

        assertTrue(VaultPathStore.loadPhysicalBackupMirrorRoot(conn).isEmpty());

        VaultPathStore.savePhysicalBackupMirrorRoot(conn, mirror);
        assertEquals(mirror, VaultPathStore.loadPhysicalBackupMirrorRoot(conn).orElseThrow());

        VaultPathStore.clearPhysicalBackupMirrorRoot(conn);
        assertTrue(VaultPathStore.loadPhysicalBackupMirrorRoot(conn).isEmpty());
    }

    @Test
    void physicalBackupMirror_propertyKey_deterministic() {
        VaultConnection c = VaultConnection.webDav("https://a", "/b", "c");
        assertEquals(
                VaultPathStore.physicalBackupMirrorPropertyKey(c),
                VaultPathStore.physicalBackupMirrorPropertyKey(c));
    }

    @Test
    void isUnsafe_samePath() throws Exception {
        Path p = Files.createTempDirectory("v").toAbsolutePath().normalize();
        assertTrue(MainWindowVaultActions.isUnsafePhysicalBackupMirrorPair(p, p));
    }

    @Test
    void isUnsafe_mirrorInsideVault() throws Exception {
        Path vault = Files.createTempDirectory("vault").toAbsolutePath().normalize();
        Path mirror = vault.resolve("backup").toAbsolutePath().normalize();
        Files.createDirectories(mirror);
        assertTrue(MainWindowVaultActions.isUnsafePhysicalBackupMirrorPair(vault, mirror));
    }

    @Test
    void isUnsafe_vaultInsideMirror() throws Exception {
        Path mirror = Files.createTempDirectory("mirror").toAbsolutePath().normalize();
        Path vault = mirror.resolve("album").toAbsolutePath().normalize();
        Files.createDirectories(vault);
        assertTrue(MainWindowVaultActions.isUnsafePhysicalBackupMirrorPair(vault, mirror));
    }

    @Test
    void isUnsafe_disjointSiblings_ok() throws Exception {
        Path base = Files.createTempDirectory("base").toAbsolutePath().normalize();
        Path vault = base.resolve("vault").toAbsolutePath().normalize();
        Path mirror = base.resolve("mirror").toAbsolutePath().normalize();
        Files.createDirectories(vault);
        Files.createDirectories(mirror);
        assertFalse(MainWindowVaultActions.isUnsafePhysicalBackupMirrorPair(vault, mirror));
    }

    @Test
    void syncVaultMirrorContents_copiesAndSkipsIdentical(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path mir = tmp.resolve("mir");
        Files.createDirectories(src.resolve("d"));
        Files.writeString(src.resolve("d/a.txt"), "x");
        Files.writeString(src.resolve("vault.cryptomator"), "{}");
        MainWindowVaultActions.syncVaultMirrorContents(src, mir, false, null);
        assertEquals("x", Files.readString(mir.resolve("d/a.txt")));
        assertEquals("{}", Files.readString(mir.resolve("vault.cryptomator")));
        // second run: no content change — still readable
        MainWindowVaultActions.syncVaultMirrorContents(src, mir, false, null);
        assertEquals("x", Files.readString(mir.resolve("d/a.txt")));
    }

    @Test
    void syncVaultMirrorContents_deleteOrphan(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path mir = tmp.resolve("mir");
        Files.createDirectories(src);
        Files.writeString(src.resolve("keep.txt"), "k");
        Files.createDirectories(mir);
        Files.writeString(mir.resolve("keep.txt"), "k");
        Files.writeString(mir.resolve("old.txt"), "gone");
        MainWindowVaultActions.syncVaultMirrorContents(src, mir, true, null);
        assertTrue(Files.exists(mir.resolve("keep.txt")));
        assertFalse(Files.exists(mir.resolve("old.txt")));
    }
}
