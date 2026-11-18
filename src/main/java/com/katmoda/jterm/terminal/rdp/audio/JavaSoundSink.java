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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** An {@link AudioSink} over a Java Sound {@link SourceDataLine} of the default mixer. */
public final class JavaSoundSink implements AudioSink {

    private SourceDataLine line;
    private PcmFormat format;

    @Override
    public void open(PcmFormat format, int bufferMs) throws LineUnavailableException {
        AudioFormat af = new AudioFormat(format.sampleRate(), 16, format.channels(), true, false);
        SourceDataLine l = AudioSystem.getSourceDataLine(af);
        try {
            l.open(af, (int) format.bytesForMillis(bufferMs));
            l.start();
        } catch (LineUnavailableException | RuntimeException e) {
            l.close();
            throw e;
        }
        line = l;
        this.format = format;
    }

    @Override
    public int queuedMillis() {
        SourceDataLine l = line;
        PcmFormat f = format;
        if (l == null || f == null) {
            return 0;
        }
        return (int) f.millisForBytes(Math.max(0, l.getBufferSize() - l.available()));
    }

    @Override
    public void write(byte[] data, int off, int len) throws LineUnavailableException {
        SourceDataLine l = line;
        if (l == null || l.write(data, off, len) < len && !l.isOpen()) {
            throw new LineUnavailableException("sound output closed");
        }
    }

    @Override
    public void flush() {
        SourceDataLine l = line;
        if (l != null) {
            l.flush();
        }
    }

    @Override
    public void close() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            l.close();
        }
    }
}
