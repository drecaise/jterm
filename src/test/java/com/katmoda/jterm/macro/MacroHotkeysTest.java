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
package com.katmoda.jterm.macro;

import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.keymap.TermAction;
import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroHotkeysTest {

    private static Macro withHotkey(String name, String hotkey) {
        Macro m = new Macro();
        m.setName(name);
        m.setHotkey(hotkey);
        return m;
    }

    @Test
    void anUnboundStrokeIsFree() {
        assertNull(MacroHotkeys.conflictFor("shift ctrl pressed F9", null,
                List.of(withHotkey("One", "shift ctrl pressed F1"))));
    }

    @Test
    void aStrokeOwnedByAnotherMacroIsReported() {
        String conflict = MacroHotkeys.conflictFor("shift ctrl pressed F1", null,
                List.of(withHotkey("One", "shift ctrl pressed F1")));
        assertTrue(conflict.contains("One"), conflict);
    }

    @Test
    void differentSpellingsOfTheSameStrokeStillCollide() {
        // "ctrl shift F1" and "shift ctrl pressed F1" are the same key; comparing the stored strings
        // would call this free and leave the newcomer bound to a stroke it can never win.
        String conflict = MacroHotkeys.conflictFor("ctrl shift F1", null,
                List.of(withHotkey("One", "shift ctrl pressed F1")));
        assertTrue(conflict.contains("One"), String.valueOf(conflict));
    }

    @Test
    void aStrokeOwnedByAKeyboardShortcutIsReported() {
        Keymap keymap = Keymap.loadOrDefaults();
        TermAction action = TermAction.NEW_TAB;
        KeyStroke stroke = keymap.strokeFor(action);
        String conflict = MacroHotkeys.conflictFor(stroke, keymap, List.of());
        assertTrue(conflict.contains(action.label()), String.valueOf(conflict));
    }

    @Test
    void canonicalRewritesIntoTheDispatchersForm() {
        assertEquals(KeyStroke.getKeyStroke("ctrl shift F1").toString(),
                MacroHotkeys.canonical("ctrl shift F1"));
    }

    @Test
    void canonicalRejectsGibberish() {
        assertNull(MacroHotkeys.canonical("not a keystroke"));
        assertNull(MacroHotkeys.canonical(null));
        assertNull(MacroHotkeys.canonical("  "));
    }
}
