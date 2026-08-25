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
package com.katmoda.jterm.terminal.cwd;

import com.jediterm.terminal.TerminalDataStream;

import java.io.IOException;

/**
 * Wraps the stream JediTerm's emulator reads from, so an {@link OscCwdScanner} sees the characters
 * on their way past.
 *
 * <p>This is the right layer for two reasons. {@code getChar()} already spans the underlying
 * connector's buffer refills, so a sequence split across two reads needs no carry buffer here — the
 * problem a {@code TtyConnector.read} hook would have simply does not arise. And it is cheap:
 * {@code JediEmulator} pulls runs of printable text in bulk through
 * {@link #readNonControlCharacters}, which passes through untouched, so on a large {@code cat} the
 * scanner sees only the control characters, not every byte.</p>
 *
 * <p>The push-back methods need no compensation. Every call site that pushes a character back
 * ({@code JediEmulator} after a lookahead, {@code ControlSequence} at the end of a sequence) pushes a
 * plain character, and none of them can run while {@code SystemCommandSequence} is reading an OSC
 * body — so a pushed-back character can never be one the scanner has already counted.</p>
 */
public final class OscSniffingDataStream implements TerminalDataStream {

    private final TerminalDataStream delegate;
    private final OscCwdScanner scanner;

    public OscSniffingDataStream(TerminalDataStream delegate, OscCwdScanner scanner) {
        this.delegate = delegate;
        this.scanner = scanner;
    }

    @Override
    public char getChar() throws IOException {
        char c = delegate.getChar();
        scanner.feed(c);
        return c;
    }

    @Override
    public void pushChar(char c) throws IOException {
        delegate.pushChar(c);
    }

    @Override
    public String readNonControlCharacters(int maxChars) throws IOException {
        // Printable runs cannot contain an escape sequence, so there is nothing here to scan.
        return delegate.readNonControlCharacters(maxChars);
    }

    @Override
    public void pushBackBuffer(char[] chars, int length) throws IOException {
        delegate.pushBackBuffer(chars, length);
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }
}
