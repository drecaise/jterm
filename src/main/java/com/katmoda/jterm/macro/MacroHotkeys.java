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

import javax.swing.KeyStroke;
import java.util.List;

/**
 * Whether a global hotkey is already spoken for.
 *
 * <p>Extracted from {@code ui.macro.MacroEditDialog}, which used to own this check privately, so
 * import can apply the same rule: an imported macro can carry a hotkey that collides with a keyboard
 * shortcut or with a macro that already exists here, and silently accepting it would leave two
 * handlers fighting over one stroke (the {@code MainWindow} dispatcher resolves keymap actions
 * first, so the imported macro would simply never fire).</p>
 */
public final class MacroHotkeys {

    private MacroHotkeys() {
    }

    /**
     * A human description of what already owns {@code stroke}, or {@code null} if it is free.
     *
     * @param others the macros to check against — every macro <em>except</em> the one being edited
     */
    public static String conflictFor(KeyStroke stroke, Keymap keymap, List<Macro> others) {
        if (stroke == null) {
            return null;
        }
        if (keymap != null) {
            TermAction action = keymap.actionFor(stroke);
            if (action != null) {
                return "a keyboard shortcut (" + action.label() + ")";
            }
        }
        if (others != null) {
            for (Macro other : others) {
                // Compare parsed strokes, not the stored strings. A hotkey is persisted as
                // KeyStroke.toString() ("shift ctrl pressed F1"), but an imported file may spell the
                // same stroke differently, and string equality would call that free when it is not.
                if (stroke.equals(parse(other.getHotkey()))) {
                    return "macro \"" + other.getName() + "\"";
                }
            }
        }
        return null;
    }

    /** The same check keyed by a stored {@code KeyStroke.toString()} value, as {@link Macro} holds it. */
    public static String conflictFor(String hotkey, Keymap keymap, List<Macro> others) {
        return conflictFor(parse(hotkey), keymap, others);
    }

    /** {@code hotkey} as a {@link KeyStroke}, or {@code null} when absent or unparsable. */
    public static KeyStroke parse(String hotkey) {
        if (hotkey == null || hotkey.isBlank()) {
            return null;
        }
        return KeyStroke.getKeyStroke(hotkey);
    }

    /**
     * {@code hotkey} rewritten in the form the dispatcher matches on, or {@code null} if it does not
     * parse.
     *
     * <p>{@code MacroLibrary.byHotkey} compares stored strings verbatim, so a macro whose hotkey is
     * spelled any other way is bound to a key that can never fire it. Everything jterm writes is
     * already canonical; this exists for values arriving from an import.</p>
     */
    public static String canonical(String hotkey) {
        KeyStroke stroke = parse(hotkey);
        return (stroke != null) ? stroke.toString() : null;
    }
}
