/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.terminal.rdp.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicGateTest {

    @Test
    void startsIdleWithNothingToCaptureOrSend() {
        MicGate g = new MicGate(false);
        assertEquals(MicGate.Consent.UNASKED, g.consent());
        assertFalse(g.mayCapture());
        assertFalse(g.sendsSilence());
    }

    @Test
    void theFirstStreamAsksAndSendsSilenceUntilAnswered() {
        MicGate g = new MicGate(false);
        assertTrue(g.streamOpened());
        assertEquals(MicGate.Consent.ASKING, g.consent());
        assertFalse(g.mayCapture());
        assertTrue(g.sendsSilence());
    }

    @Test
    void theServerCanCauseOneQuestionPerConnection() {
        MicGate g = new MicGate(false);
        assertTrue(g.streamOpened());
        assertFalse(g.streamOpened(), "a second stream while asking");
        g.streamClosed();
        assertFalse(g.streamOpened(), "a stream after closing, still unanswered");
        g.answer(false);
        g.streamClosed();
        assertFalse(g.streamOpened(), "after a refusal");
        g.setAllowed(true);
        g.streamClosed();
        assertFalse(g.streamOpened(), "after the user allowed");
    }

    @Test
    void allowCapturesOnlyWhileAStreamIsOpenAndNotMuted() {
        MicGate g = new MicGate(false);
        g.streamOpened();
        g.answer(true);
        assertTrue(g.mayCapture());
        assertFalse(g.sendsSilence());
        g.setMuted(true);
        assertFalse(g.mayCapture());
        assertTrue(g.sendsSilence());
        g.setMuted(false);
        assertTrue(g.mayCapture());
        g.streamClosed();
        assertFalse(g.mayCapture());
        assertFalse(g.sendsSilence());
    }

    @Test
    void refusalSendsSilenceAndOnlyTheUserCanChangeIt() {
        MicGate g = new MicGate(false);
        g.streamOpened();
        g.answer(false);
        assertEquals(MicGate.Consent.DENIED, g.consent());
        assertFalse(g.mayCapture());
        assertTrue(g.sendsSilence());
        g.answer(true); // a stale dialog's answer
        assertEquals(MicGate.Consent.DENIED, g.consent());
        g.setAllowed(true);
        assertTrue(g.mayCapture());
        g.setAllowed(false);
        assertFalse(g.mayCapture());
    }

    @Test
    void answerIsIgnoredUnlessAsking() {
        MicGate g = new MicGate(false);
        g.answer(true);
        assertEquals(MicGate.Consent.UNASKED, g.consent());
    }

    @Test
    void aChoiceMadeBeforeTheServerAsksMeansNoQuestion() {
        MicGate g = new MicGate(false);
        g.setAllowed(false);
        assertFalse(g.streamOpened());
        assertTrue(g.sendsSilence());
        MicGate h = new MicGate(false);
        h.setAllowed(true);
        assertFalse(h.streamOpened());
        assertTrue(h.mayCapture());
    }

    @Test
    void mutedFromTheStartNeverCaptures() {
        MicGate g = new MicGate(true);
        g.streamOpened();
        g.answer(true);
        assertFalse(g.mayCapture());
        assertTrue(g.sendsSilence());
    }

    @Test
    void noStateButAllowedOpenUnmutedCaptures() {
        for (MicGate.Consent c : MicGate.Consent.values()) {
            for (boolean open : new boolean[]{false, true}) {
                for (boolean muted : new boolean[]{false, true}) {
                    if (c == MicGate.Consent.UNASKED && open) {
                        continue; // can't exist: opening a stream asks
                    }
                    MicGate g = new MicGate(muted);
                    if (open) {
                        g.streamOpened();
                    }
                    switch (c) {
                        case UNASKED -> { }
                        case ASKING -> { if (!open) { g.streamOpened(); g.streamClosed(); } }
                        case ALLOWED -> g.setAllowed(true);
                        case DENIED -> g.setAllowed(false);
                    }
                    assertEquals(c, g.consent());
                    assertEquals(c == MicGate.Consent.ALLOWED && open && !muted, g.mayCapture(),
                            c + " open=" + open + " muted=" + muted);
                    assertEquals(open && !g.mayCapture(), g.sendsSilence());
                }
            }
        }
    }
}
