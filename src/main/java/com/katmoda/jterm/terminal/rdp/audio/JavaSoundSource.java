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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * An {@link AudioSource} over a Java Sound {@link TargetDataLine} of the default mixer.
 *
 * <p>When the microphone refuses the channel count asked for, the line is opened with the other one
 * and converted ({@link ChannelMix}): on macOS a headset's mono microphone has no stereo line at all
 * ({@code IllegalArgumentException}, measured), and the server, not jterm, picks the stream's format.
 * A device with no line at all throws {@code IllegalArgumentException} rather than
 * {@code LineUnavailableException}; callers catch {@code Exception}.</p>
 */
public final class JavaSoundSource implements AudioSource {

    private static final Logger LOG = LoggerFactory.getLogger(JavaSoundSource.class);

    private TargetDataLine line;
    private int wanted;      // channels the caller reads
    private int device;      // channels the line delivers
    private byte[] tmp = new byte[0];

    @Override
    public void open(PcmFormat format, int bufferMs) throws Exception {
        wanted = format.channels();
        try {
            line = openLine(format.sampleRate(), wanted, bufferMs);
            device = wanted;
        } catch (IllegalArgumentException | LineUnavailableException first) {
            int other = wanted == 1 ? 2 : 1;
            try {
                line = openLine(format.sampleRate(), other, bufferMs);
            } catch (IllegalArgumentException | LineUnavailableException second) {
                first.addSuppressed(second);
                throw first;
            }
            device = other;
            LOG.info("RDP microphone: no {} line at {} Hz, capturing {} and converting", channels(wanted),
                    format.sampleRate(), channels(other));
        }
    }

    private static String channels(int n) {
        return n == 1 ? "mono" : "stereo";
    }

    private static TargetDataLine openLine(int rate, int channels, int bufferMs) throws LineUnavailableException {
        AudioFormat af = new AudioFormat(rate, 16, channels, true, false);
        TargetDataLine l = AudioSystem.getTargetDataLine(af);
        try {
            l.open(af, (int) (new PcmFormat(rate, channels).bytesForMillis(bufferMs)));
            l.start();
        } catch (LineUnavailableException | RuntimeException e) {
            l.close();
            throw e;
        }
        return l;
    }

    @Override
    public int read(byte[] data, int off, int len) throws LineUnavailableException {
        TargetDataLine l = line;
        if (l == null) {
            throw new LineUnavailableException("microphone closed");
        }
        if (device == wanted) {
            return checked(l, l.read(data, off, len), len);
        }
        int need = device == 1 ? len / 2 : len * 2;
        if (tmp.length < need) {
            tmp = new byte[need];
        }
        int n = checked(l, l.read(tmp, 0, need), need);
        if (device == 1) {
            n -= n % 2;
            ChannelMix.monoToStereo(tmp, 0, n, data, off);
            return n * 2;
        }
        n -= n % 4;
        ChannelMix.stereoToMono(tmp, 0, n, data, off);
        return n / 2;
    }

    private static int checked(TargetDataLine l, int n, int len) throws LineUnavailableException {
        if (n < len && !l.isOpen()) {
            throw new LineUnavailableException("microphone closed");
        }
        return n;
    }

    @Override
    public int available() {
        TargetDataLine l = line;
        if (l == null) {
            return 0;
        }
        int a = l.available();
        return device == wanted ? a : device == 1 ? a * 2 : a / 2;
    }

    @Override
    public void flush() {
        TargetDataLine l = line;
        if (l != null) {
            l.flush();
        }
    }

    @Override
    public void close() {
        TargetDataLine l = line;
        line = null;
        if (l != null) {
            l.stop();
            l.close();
        }
    }
}
