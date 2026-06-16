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
package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.model.LinesStorage;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;

/**
 * Extracts a terminal's full output (scrollback history followed by the visible screen) as
 * plain text, used by the "Save terminal output to file" action on the session-stopped screen.
 */
final class TerminalBufferText {

    private TerminalBufferText() {
    }

    /** Returns the widget's history + screen as newline-separated text (trailing blanks trimmed). */
    static String collect(JtermJediTermWidget widget) {
        return collect(widget.getTerminalTextBuffer());
    }

    /** Same as {@link #collect(JtermJediTermWidget)} but against a raw buffer (testable). */
    static String collect(TerminalTextBuffer buffer) {
        StringBuilder out = new StringBuilder();
        buffer.lock();
        try {
            appendLines(out, buffer.getHistoryLinesStorage());
            appendLines(out, buffer.getScreenLinesStorage());
        } finally {
            buffer.unlock();
        }
        return trimTrailingBlankLines(out.toString());
    }

    private static void appendLines(StringBuilder out, LinesStorage storage) {
        for (TerminalLine line : storage) {
            out.append(stripTrailing(line.getText())).append('\n');
        }
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static String trimTrailingBlankLines(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return end > 0 ? text.substring(0, end) + "\n" : "";
    }
}
