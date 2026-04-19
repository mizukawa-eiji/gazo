package com.example.gazo;

import com.example.gazo.vault.GazoVaultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PendingDeletePathsTest {

    @Mock
    GazoVaultService vault;

    @Test
    void contains_nullReturnsFalse() {
        PendingDeletePaths pending = new PendingDeletePaths();
        assertFalse(pending.contains(null));
    }

    @Test
    void addAll_nullOrEmpty_doesNothing() {
        PendingDeletePaths pending = new PendingDeletePaths();
        pending.addAll(null);
        pending.addAll(List.of());
        pending.flushAll(vault, false);
        verifyNoInteractions(vault);
    }

    @Test
    void flushAll_whenNothingPending_doesNotTouchVault() {
        PendingDeletePaths pending = new PendingDeletePaths();
        pending.flushAll(vault, false);
        verifyNoInteractions(vault);
    }

    @Test
    void flushAll_nullVault_doesNotCallDelete() {
        PendingDeletePaths pending = new PendingDeletePaths();
        Path p = Path.of("a", "b.jpg");
        pending.addAll(List.of(p));
        pending.flushAll(null, false);
        assertTrue(pending.contains(p));
        verifyNoInteractions(vault);
    }

    @Test
    void flushAll_success_removesEachPath() throws Exception {
        PendingDeletePaths pending = new PendingDeletePaths();
        Path p1 = Path.of("vault", "1.jpg");
        Path p2 = Path.of("vault", "2.jpg");
        pending.addAll(List.of(p1, p2));
        pending.flushAll(vault, false);
        verify(vault).deleteImage(p1);
        verify(vault).deleteImage(p2);
        assertFalse(pending.contains(p1));
        assertFalse(pending.contains(p2));
    }

    @Test
    void flushAll_deleteFailure_leavesThatPath() throws Exception {
        PendingDeletePaths pending = new PendingDeletePaths();
        Path bad = Path.of("vault", "bad.jpg");
        Path ok = Path.of("vault", "ok.jpg");
        pending.addAll(List.of(bad, ok));
        doThrow(new IOException("denied")).when(vault).deleteImage(bad);
        pending.flushAll(vault, false);
        verify(vault).deleteImage(bad);
        verify(vault).deleteImage(ok);
        assertTrue(pending.contains(bad));
        assertFalse(pending.contains(ok));
    }
}
