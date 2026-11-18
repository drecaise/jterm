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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Sample format of RDP audio: signed 16-bit little-endian, interleaved. */
public record PcmFormat(int sampleRate, int channels) {

    public static final List<Integer> RATES = List.of(8000, 11025, 16000, 22050, 32000, 44100, 48000);

    public PcmFormat {
        if (!RATES.contains(sampleRate)) {
            throw new IllegalArgumentException("unsupported sample rate " + sampleRate);
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("unsupported channel count " + channels);
        }
    }

    public static Optional<PcmFormat> of(int sampleRate, int channels) {
        if (!RATES.contains(sampleRate) || (channels != 1 && channels != 2)) {
            return Optional.empty();
        }
        return Optional.of(new PcmFormat(sampleRate, channels));
    }

    public int frameBytes() {
        return channels * 2;
    }

    public int bytesPerSecond() {
        return sampleRate * frameBytes();
    }

    /** Bytes for {@code ms} milliseconds, rounded down to whole frames. */
    public long bytesForMillis(long ms) {
        return (ms * sampleRate / 1000) * frameBytes();
    }

    public long millisForBytes(long bytes) {
        return bytes * 1000 / bytesPerSecond();
    }

    public int wholeFrames(int bytes) {
        return bytes - (bytes % frameBytes());
    }

    public boolean isWholeFrames(int bytes) {
        return bytes % frameBytes() == 0;
    }

    /** For example "44.1 kHz stereo". */
    public String describe() {
        String khz = String.format(Locale.ROOT, "%.3f", sampleRate / 1000.0);
        khz = khz.replaceAll("0+$", "").replaceAll("\\.$", "");
        return khz + " kHz " + (channels == 1 ? "mono" : "stereo");
    }
}
