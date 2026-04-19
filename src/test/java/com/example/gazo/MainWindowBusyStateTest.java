package com.example.gazo;

import javafx.scene.control.ProgressBar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainWindowBusyStateTest {

    @Test
    void idle_notBusy() {
        MainWindowBusyState s = MainWindowBusyState.idle();
        assertFalse(s.busy());
        assertEquals("", s.message());
        assertTrue(Double.isNaN(s.progressFraction()));
    }

    @Test
    void busy_withoutProgress_usesNoProgress() {
        MainWindowBusyState s = MainWindowBusyState.busy("working");
        assertTrue(s.busy());
        assertEquals("working", s.message());
        assertTrue(Double.isNaN(s.progressFraction()));
    }

    @Test
    void busyWithCounts_zeroDone_usesIndeterminate() {
        MainWindowBusyState s = MainWindowBusyState.busy("x", 0, 10);
        assertEquals(ProgressBar.INDETERMINATE_PROGRESS, s.progressFraction(), 0.0);
    }

    @Test
    void busyWithCounts_nonPositiveTotal_skipsBar() {
        MainWindowBusyState s = MainWindowBusyState.busy("x", 5, 0);
        assertTrue(Double.isNaN(s.progressFraction()));
    }

    @Test
    void busyWithCounts_clampsFraction() {
        assertEquals(1.0, MainWindowBusyState.busy("x", 99, 10).progressFraction(), 0.0001);
        assertEquals(0.5, MainWindowBusyState.busy("x", 5, 10).progressFraction(), 0.0001);
    }
}
