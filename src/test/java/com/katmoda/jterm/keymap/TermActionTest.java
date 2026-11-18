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
package com.katmoda.jterm.keymap;

import com.katmoda.jterm.terminal.rdp.RdpKeyMap;
import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermActionTest {

    @Test
    void everyDefaultParsesAndNoTwoActionsShareOne() {
        Map<KeyStroke, TermAction> seen = new HashMap<>();
        for (TermAction action : TermAction.values()) {
            KeyStroke ks = KeyStroke.getKeyStroke(action.defaultStroke());
            assertNotNull(ks, action + " has an unparseable default: " + action.defaultStroke());
            assertNull(seen.put(ks, action), action + " shares its default with " + seen.get(ks));
        }
    }

    @Test
    void sendCtrlAltDelDefaultsToAStrokeJtermMayClaimInAnRdpPane() {
        // Inside an RDP pane the dispatcher hands every other stroke to the remote desktop, so a
        // default without Ctrl+Shift would never fire where it is needed.
        KeyStroke ks = KeyStroke.getKeyStroke(TermAction.SEND_CTRL_ALT_DEL.defaultStroke());
        assertTrue(RdpKeyMap.jtermMayClaim(ks.getModifiers()), "modifiers: " + ks.getModifiers());
    }

    @Test
    void toggleFullScreenDefaultsToAStrokeJtermMayClaimInAnRdpPane() {
        KeyStroke ks = KeyStroke.getKeyStroke(TermAction.TOGGLE_FULL_SCREEN.defaultStroke());
        assertTrue(RdpKeyMap.jtermMayClaim(ks.getModifiers()), "modifiers: " + ks.getModifiers());
    }

    @Test
    void onlyTheExitAndCtrlAltDelAreClaimedInFullScreen() {
        for (TermAction action : TermAction.values()) {
            boolean expected = action == TermAction.TOGGLE_FULL_SCREEN || action == TermAction.SEND_CTRL_ALT_DEL;
            assertEquals(expected, action.availableInRdpFullScreen(), action.toString());
        }
    }
}
