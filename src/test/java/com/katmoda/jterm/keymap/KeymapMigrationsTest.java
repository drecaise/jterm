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

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeymapMigrationsTest {

    private static final String SFTP = TermAction.OPEN_SFTP.id();
    private static final KeyStroke CTRL_SHIFT_F = KeyStroke.getKeyStroke("control shift F");

    private static Map<String, String> map(String... pairs) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            raw.put(pairs[i], pairs[i + 1]);
        }
        return raw;
    }

    private static KeyStroke sftp(Map<String, String> raw) {
        return KeyStroke.getKeyStroke(raw.get(SFTP));
    }

    @Test
    void theNewDefaultIsCtrlShiftF() {
        assertEquals(CTRL_SHIFT_F, KeyStroke.getKeyStroke(TermAction.OPEN_SFTP.defaultStroke()));
    }

    @Test
    void movesTheFirstRunDefault() {
        Map<String, String> raw = map(SFTP, "control F");
        assertTrue(KeymapMigrations.migrate(raw));
        assertEquals(CTRL_SHIFT_F, sftp(raw));
        assertEquals("1", raw.get(KeymapMigrations.VERSION_KEY));
    }

    @Test
    void movesTheDefaultAsTheShortcutEditorSpellsIt() {
        Map<String, String> raw = map(SFTP, KeyStroke.getKeyStroke("control F").toString());
        assertTrue(KeymapMigrations.migrate(raw));
        assertEquals(CTRL_SHIFT_F, sftp(raw));
    }

    @Test
    void leavesACustomBindingAloneButStillStamps() {
        Map<String, String> raw = map(SFTP, "control shift G");
        assertTrue(KeymapMigrations.migrate(raw));
        assertEquals("control shift G", raw.get(SFTP));
        assertEquals("1", raw.get(KeymapMigrations.VERSION_KEY));
    }

    @Test
    void staysPutWhenAnotherActionAlreadyHoldsCtrlShiftF() {
        Map<String, String> raw = map(SFTP, "control F", TermAction.TOGGLE_THEME.id(), "control shift F");
        assertTrue(KeymapMigrations.migrate(raw));
        assertEquals("control F", raw.get(SFTP));
    }

    @Test
    void aLaterDeliberateCtrlFSurvives() {
        Map<String, String> raw = map(SFTP, "control F", KeymapMigrations.VERSION_KEY, "1");
        assertFalse(KeymapMigrations.migrate(raw));
        assertEquals("control F", raw.get(SFTP));
    }

    @Test
    void anUnpinnedBindingIsLeftToTheCompiledDefault() {
        Map<String, String> raw = map();
        assertTrue(KeymapMigrations.migrate(raw));
        assertFalse(raw.containsKey(SFTP));
    }

    @Test
    void noDefaultStrokeIsSharedByTwoActions() {
        Map<KeyStroke, TermAction> seen = new LinkedHashMap<>();
        for (TermAction action : TermAction.values()) {
            TermAction clash = seen.put(KeyStroke.getKeyStroke(action.defaultStroke()), action);
            assertEquals(null, clash, action + " shares its default with " + clash);
        }
    }
}
