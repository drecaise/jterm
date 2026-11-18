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

/** Attenuates S16LE interleaved samples in place. Levels are 0..0xFFFF; never amplifies. */
public final class Gain {

    public static final int UNITY = 0xFFFF;

    private Gain() {
    }

    public static void apply(byte[] buf, int off, int len, int channels, int left, int right) {
        int l = Math.max(0, Math.min(UNITY, left));
        int r = Math.max(0, Math.min(UNITY, right));
        boolean stereo = channels == 2;
        if (l == UNITY && (!stereo || r == UNITY)) {
            return;
        }
        int frameBytes = Math.max(1, channels) * 2;
        int end = off + (len - len % frameBytes);
        int idx = 0;
        for (int i = off; i < end; i += 2, idx++) {
            int level = (stereo && (idx & 1) == 1) ? r : l;
            int sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            int scaled = (int) ((long) sample * level / UNITY);
            buf[i] = (byte) scaled;
            buf[i + 1] = (byte) (scaled >> 8);
        }
    }
}
