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
 * Where decoded remote sound goes: a sound output opened for one {@link PcmFormat}. Implemented by
 * {@link JavaSoundSink}; tests use a fake. Used by one thread at a time.
 */
public interface AudioSink {

    /**
     * Opens the output with a buffer of about {@code bufferMs} and starts it.
     *
     * @throws Exception when there is no sound device or it refuses the format
     */
    void open(PcmFormat format, int bufferMs) throws Exception;

    /** Writes whole frames, blocking while the output's buffer is full. */
    void write(byte[] data, int off, int len) throws Exception;

    /**
     * Roughly how much written audio has not been heard yet, in milliseconds; 0 means the output has
     * run dry. The default (always dry) suits outputs that cannot tell.
     */
    default int queuedMillis() {
        return 0;
    }

    /** Discards what is buffered but not yet heard. */
    void flush();

    /** Releases the output; the stream leaves the system mixer. Safe to call twice. */
    void close();
}
