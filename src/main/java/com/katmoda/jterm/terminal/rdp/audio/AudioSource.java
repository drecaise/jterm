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

/**
 * Where the local microphone's samples come from: a capture line opened for one {@link PcmFormat}.
 * Implemented by {@link JavaSoundSource}; tests use a fake. Used by one thread at a time.
 */
public interface AudioSource {

    /**
     * Opens the capture line with a buffer of about {@code bufferMs} and starts it.
     *
     * @throws Exception when there is no microphone, it refuses the format, or the OS refuses access
     */
    void open(PcmFormat format, int bufferMs) throws Exception;

    /** Reads exactly {@code len} bytes (whole frames), blocking until they have been captured. */
    int read(byte[] data, int off, int len) throws Exception;

    /** Bytes captured and not read yet. */
    int available();

    /** Discards what was captured and not read yet. */
    void flush();

    /** Releases the line; the OS's "microphone in use" indicator goes off. Safe to call twice. */
    void close();
}
