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
package com.katmoda.jterm.ui.rdp;

import com.katmoda.jterm.terminal.rdp.audio.MicGate.Consent;
import com.katmoda.jterm.terminal.rdp.audio.PcmFormat;
import com.katmoda.jterm.terminal.rdp.audio.RdpMicrophone.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicButtonLookTest {

    private static final PcmFormat F = new PcmFormat(44100, 2);

    @Test
    void onlyAnOpenLineIsRecording() {
        assertTrue(MicButtonLook.of(new Status(Consent.ALLOWED, true, true, false, false, F)).recording());
        for (Consent c : Consent.values()) {
            for (boolean muted : new boolean[]{false, true}) {
                assertFalse(MicButtonLook.of(new Status(c, true, false, muted, false, F)).recording(), c + " " + muted);
            }
        }
    }

    @Test
    void eachStateSaysWhatTheServerHears() {
        assertEquals("Mic on", MicButtonLook.of(new Status(Consent.ALLOWED, true, true, false, false, F)).text().substring(2));
        assertEquals("Mic?", MicButtonLook.of(new Status(Consent.ASKING, true, false, false, false, F)).text());
        assertEquals("Mic blocked", MicButtonLook.of(new Status(Consent.DENIED, true, false, false, false, F)).text());
        assertEquals("Mic muted", MicButtonLook.of(new Status(Consent.ALLOWED, true, false, true, false, F)).text());
        assertEquals("Mic", MicButtonLook.of(new Status(Consent.UNASKED, false, false, false, false, null)).text());
        assertTrue(MicButtonLook.of(new Status(Consent.ALLOWED, true, false, false, true, F)).tip()
                .contains("could not be opened"));
    }

    @Test
    void theMenuOffersTheOtherChoice() {
        assertTrue(MicButtonLook.allowItem(Consent.ALLOWED).startsWith("Block"));
        for (Consent c : new Consent[]{Consent.UNASKED, Consent.ASKING, Consent.DENIED}) {
            assertTrue(MicButtonLook.allowItem(c).startsWith("Allow"));
        }
    }
}
