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

/**
 * A named key a macro can press, paired with the byte/char sequence written to the terminal.
 * {@code \033} below is the escape byte {@code 0x1B}. Arrow/navigation keys use the common
 * xterm sequences; control keys send their literal control byte.
 */
public enum MacroKey {

    RETURN("Return / Enter", "\r"),
    TAB("Tab", "\t"),
    ESC("Escape", "\033"),
    BACKSPACE("Backspace", "\177"),
    DELETE("Delete", "\033[3~"),
    UP("Up arrow", "\033[A"),
    DOWN("Down arrow", "\033[B"),
    RIGHT("Right arrow", "\033[C"),
    LEFT("Left arrow", "\033[D"),
    HOME("Home", "\033[H"),
    END("End", "\033[F"),
    PAGE_UP("Page Up", "\033[5~"),
    PAGE_DOWN("Page Down", "\033[6~"),
    CTRL_C("Ctrl+C", "\003"),
    CTRL_D("Ctrl+D", "\004"),
    CTRL_Z("Ctrl+Z", "\032"),
    CTRL_L("Ctrl+L", "\014"),
    F1("F1", "\033OP"),
    F2("F2", "\033OQ"),
    F3("F3", "\033OR"),
    F4("F4", "\033OS"),
    F5("F5", "\033[15~"),
    F6("F6", "\033[17~"),
    F7("F7", "\033[18~"),
    F8("F8", "\033[19~"),
    F9("F9", "\033[20~"),
    F10("F10", "\033[21~"),
    F11("F11", "\033[23~"),
    F12("F12", "\033[24~");

    private final String label;
    private final String sequence;

    MacroKey(String label, String sequence) {
        this.label = label;
        this.sequence = sequence;
    }

    /** The bytes (as a String) sent to the terminal for this key. */
    public String sequence() {
        return sequence;
    }

    /** Human-readable label for the key-press dropdown. */
    public String displayLabel() {
        return label;
    }
}
