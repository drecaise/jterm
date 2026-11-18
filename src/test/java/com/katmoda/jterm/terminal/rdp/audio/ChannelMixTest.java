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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ChannelMixTest {

    private static byte[] s16(int... samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[2 * i] = (byte) samples[i];
            b[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return b;
    }

    @Test
    void monoBecomesTheSameOnBothSides() {
        byte[] out = new byte[12];
        ChannelMix.monoToStereo(s16(1, -2, 32767), 0, 6, out, 0);
        assertArrayEquals(s16(1, 1, -2, -2, 32767, 32767), out);
    }

    @Test
    void stereoIsAveragedWithoutOverflow() {
        byte[] out = new byte[8];
        ChannelMix.stereoToMono(s16(100, 300, 32767, 32767, -32768, -32768, -32768, 32767), 0, 16, out, 0);
        assertArrayEquals(s16(200, 32767, -32768, -1), out);
    }

    @Test
    void offsetsAreHonoured() {
        byte[] src = new byte[2 + 4];
        System.arraycopy(s16(5, 7), 0, src, 2, 4);
        byte[] out = new byte[1 + 2];
        ChannelMix.stereoToMono(src, 2, 4, out, 1);
        assertArrayEquals(new byte[]{0, 6, 0}, out);
    }
}
