/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.cwd;

import com.katmoda.jterm.terminal.cwd.CwdTracker.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Precedence between the directory sources, which is sticky on purpose. */
class CwdTrackerTest {

    private final CwdTracker tracker = new CwdTracker();

    @Test
    void aBetterSourceTakesOver() {
        tracker.report(Source.TITLE, "/title/says");
        tracker.report(Source.PROC, "/proc/says");
        tracker.report(Source.OSC99, "/osc99/says");
        tracker.report(Source.OSC7, "/osc7/says");

        assertEquals("/osc7/says", tracker.current());
    }

    @Test
    void theKernelOutranksAParsedWindowTitle() {
        tracker.report(Source.PROC, "/home/mark/git/jterm");

        // A local pane must not follow a title set by vim, or by an ssh to somewhere else.
        tracker.report(Source.TITLE, "/etc/hosts");

        assertEquals("/home/mark/git/jterm", tracker.current());
    }

    @Test
    void aWeakerSourceCanNeverClobberABetterOne() {
        tracker.report(Source.OSC7, "/osc7/says");

        // A shell that emits OSC 7 almost always sets the window title too; without stickiness the
        // two would fight over every prompt.
        tracker.report(Source.PROC, "/proc/says");
        tracker.report(Source.TITLE, "/title/says");

        assertEquals("/osc7/says", tracker.current());
    }

    @Test
    void theSameSourceKeepsUpdating() {
        tracker.report(Source.OSC7, "/first");
        tracker.report(Source.OSC7, "/second");

        assertEquals("/second", tracker.current());
    }

    @Test
    void nothingIsKnownUntilSomethingReports() {
        assertNull(tracker.current());

        tracker.report(Source.OSC7, null);
        tracker.report(Source.OSC7, "   ");

        assertNull(tracker.current());
    }

    @Test
    void resetClearsThePathAndThePrecedenceItEarned() {
        tracker.report(Source.OSC7, "/osc7/says");

        tracker.reset();
        assertNull(tracker.current());

        // A reconnected session on a host that only sets a title must not be locked out.
        tracker.report(Source.TITLE, "/title/says");
        assertEquals("/title/says", tracker.current());
    }

    @Test
    void anAbsurdlyLongPathIsCapped() {
        tracker.report(Source.OSC7, "/" + "a".repeat(4000));

        assertTrue(tracker.current().length() <= 512, "length " + tracker.current().length());
    }
}
