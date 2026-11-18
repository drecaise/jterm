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
 * Converts signed 16-bit little-endian PCM between mono and stereo. Used when the microphone can't
 * deliver the channel count the server asked for: a headset's mono microphone on macOS refuses a
 * stereo line outright (measured), and the server picks the format, not jterm.
 */
final class ChannelMix {

    private ChannelMix() {
    }

    /** Each mono sample becomes the same left and right sample. Writes {@code 2 * srcLen} bytes. */
    static void monoToStereo(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) {
        for (int i = 0; i + 1 < srcLen; i += 2) {
            byte lo = src[srcOff + i];
            byte hi = src[srcOff + i + 1];
            int d = dstOff + 2 * i;
            dst[d] = lo;
            dst[d + 1] = hi;
            dst[d + 2] = lo;
            dst[d + 3] = hi;
        }
    }

    /** Left and right are averaged. Writes {@code srcLen / 2} bytes. */
    static void stereoToMono(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) {
        for (int i = 0; i + 3 < srcLen; i += 4) {
            int l = (short) ((src[srcOff + i] & 0xFF) | (src[srcOff + i + 1] << 8));
            int r = (short) ((src[srcOff + i + 2] & 0xFF) | (src[srcOff + i + 3] << 8));
            int m = (l + r) >> 1; // always within 16 bits
            int d = dstOff + i / 2;
            dst[d] = (byte) m;
            dst[d + 1] = (byte) (m >> 8);
        }
    }
}
