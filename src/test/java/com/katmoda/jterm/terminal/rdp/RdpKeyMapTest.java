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
package com.katmoda.jterm.terminal.rdp;

import org.junit.jupiter.api.Test;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpKeyMapTest {

    private static final int STD = KeyEvent.KEY_LOCATION_STANDARD;

    private static RdpKeyMap.WinKey map(int vk, int loc) {
        return RdpKeyMap.toWindows(vk, loc);
    }

    @Test
    void lettersDigitsAndFunctionKeysKeepTheirCodes() {
        assertEquals(new RdpKeyMap.WinKey('A', 0), map(KeyEvent.VK_A, STD));
        assertEquals(new RdpKeyMap.WinKey('7', 0), map(KeyEvent.VK_7, STD));
        assertEquals(new RdpKeyMap.WinKey(0x70, 0), map(KeyEvent.VK_F1, STD));
        assertEquals(new RdpKeyMap.WinKey(0x87, 0), map(KeyEvent.VK_F24, STD), "F13..F24 are not contiguous in Java");
    }

    @Test
    void javaCodesThatDifferFromWindowsAreTranslated() {
        assertEquals(0x0D, map(KeyEvent.VK_ENTER, STD).vk());
        assertEquals(0x2E, map(KeyEvent.VK_DELETE, STD).vk());
        assertEquals(0x2D, map(KeyEvent.VK_INSERT, STD).vk());
        assertEquals(0xBA, map(KeyEvent.VK_SEMICOLON, STD).vk());
        assertEquals(0xDE, map(KeyEvent.VK_QUOTE, STD).vk());
        assertEquals(0x5B, map(KeyEvent.VK_WINDOWS, KeyEvent.KEY_LOCATION_LEFT).vk());
    }

    @Test
    void leftAndRightModifiersAreDistinct() {
        assertEquals(0xA0, map(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_LEFT).vk());
        assertEquals(0xA1, map(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT).vk());
        assertEquals(0xA3, map(KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_RIGHT).vk());
        assertEquals(0xA5, map(KeyEvent.VK_ALT_GRAPH, STD).vk(), "AltGr is right Alt");
    }

    @Test
    void numpadKeysGetTheRightExtendedFlag() {
        assertEquals(RdpProtocol.KEY_FORCE_EXTENDED, map(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_NUMPAD).flags(),
                "numpad Enter is the extended Return");
        assertEquals(RdpProtocol.KEY_FORCE_NOT_EXTENDED, map(KeyEvent.VK_LEFT, KeyEvent.KEY_LOCATION_NUMPAD).flags(),
                "numpad arrow with NumLock off is not the extended arrow");
        assertEquals(0, map(KeyEvent.VK_LEFT, STD).flags(), "the arrow cluster: WinPR adds the extended bit");
        assertEquals(new RdpKeyMap.WinKey(0x26, RdpProtocol.KEY_FORCE_NOT_EXTENDED), map(KeyEvent.VK_KP_UP, STD));
    }

    @Test
    void unknownKeysAreUnmapped() {
        assertFalse(map(KeyEvent.VK_UNDEFINED, STD).mapped());
        assertFalse(map(KeyEvent.VK_DEAD_ACUTE, STD).mapped());
    }

    @Test
    void printableAndChordClassification() {
        assertTrue(RdpKeyMap.printable('a'));
        assertTrue(RdpKeyMap.printable('€'));
        assertFalse(RdpKeyMap.printable('\n'));
        assertFalse(RdpKeyMap.printable(KeyEvent.CHAR_UNDEFINED));
        assertTrue(RdpKeyMap.chord(InputEvent.CTRL_DOWN_MASK));
        assertTrue(RdpKeyMap.chord(InputEvent.ALT_DOWN_MASK));
        assertFalse(RdpKeyMap.chord(InputEvent.SHIFT_DOWN_MASK), "Shift alone still types characters");
    }

    @Test
    void jtermClaimsOnlyCtrlShiftStrokesInAnRdpPane() {
        int ctrl = InputEvent.CTRL_DOWN_MASK, shift = InputEvent.SHIFT_DOWN_MASK;
        assertTrue(RdpKeyMap.jtermMayClaim(ctrl | shift));
        assertTrue(RdpKeyMap.jtermMayClaim(ctrl | shift | InputEvent.ALT_DOWN_MASK));
        assertFalse(RdpKeyMap.jtermMayClaim(ctrl), "Ctrl+T / Ctrl+W go to the remote desktop");
        assertFalse(RdpKeyMap.jtermMayClaim(shift));
        assertFalse(RdpKeyMap.jtermMayClaim(InputEvent.ALT_DOWN_MASK));
        assertFalse(RdpKeyMap.jtermMayClaim(0), "bare keys and F-keys go to the remote desktop");
    }
}
