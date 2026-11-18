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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JitterBufferTest {

    // 8 kHz mono: 16 bytes per ms.
    private static final PcmFormat MONO = new PcmFormat(8000, 1);

    private static byte[] ms(int n, int fill) {
        byte[] b = new byte[(int) MONO.bytesForMillis(n)];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    private static final long MS = 1_000_000L;

    /** The default policy with no start delay, for the byte-threshold cases. */
    private static JitterBuffer noDelay() {
        return new JitterBuffer(MONO, JitterBuffer.START_MS, JitterBuffer.MAX_MS, JitterBuffer.TRIM_TO_MS);
    }

    @Test
    void defaultsMatchPolicyConstants() {
        JitterBuffer b = new JitterBuffer(MONO);
        b.offer(ms(59, 1), 0, ms(59, 1).length, 0);
        assertEquals(0, b.poll(new byte[4096], 0, 4096, 1000 * MS));
        assertFalse(b.playing());
    }

    @Test
    void waitsForStartThreshold() {
        JitterBuffer b = noDelay();
        byte[] dst = new byte[100000];
        byte[] below = ms(59, 1);
        b.offer(below, 0, below.length);
        assertEquals(0, b.poll(dst, 0, dst.length));
        byte[] more = ms(1, 1);
        b.offer(more, 0, more.length);
        assertEquals(960, b.poll(dst, 0, dst.length));
        assertTrue(b.playing());
        assertEquals(0, b.underruns());
    }

    /** One large wave (Windows 11: ~186 ms) must wait out the delay, so there is margin before the next. */
    @Test
    void aLargeWaveWaitsForTheStartDelay() {
        JitterBuffer b = new JitterBuffer(MONO);
        byte[] dst = new byte[100000];
        byte[] wave = ms(186, 1);
        b.offer(wave, 0, wave.length, 10 * MS);
        assertEquals(0, b.poll(dst, 0, dst.length, 10 * MS), "far above the byte threshold, but just arrived");
        assertEquals(0, b.poll(dst, 0, dst.length, 69 * MS));
        assertEquals(wave.length, b.poll(dst, 0, dst.length, 70 * MS), "60 ms after the first byte");
    }

    /** Small waves reach the byte threshold and the delay at about the same time. */
    @Test
    void smallWavesStartWhenBothConditionsHold() {
        JitterBuffer b = new JitterBuffer(MONO);
        byte[] dst = new byte[100000];
        byte[] w = ms(20, 1);
        b.offer(w, 0, w.length, 0);
        b.offer(w, 0, w.length, 20 * MS);
        assertEquals(0, b.poll(dst, 0, dst.length, 40 * MS), "40 ms queued, 40 ms waited");
        b.offer(w, 0, w.length, 40 * MS);
        assertEquals(0, b.poll(dst, 0, dst.length, 59 * MS), "60 ms queued, 59 ms waited");
        assertEquals(960, b.poll(dst, 0, dst.length, 60 * MS));
    }

    /** An empty queue while playing is not an underrun: the output may still hold audio. */
    @Test
    void anEmptyQueueStaysPlayingUntilTheOutputRunsDry() {
        JitterBuffer b = noDelay();
        byte[] dst = new byte[100000];
        byte[] a = ms(60, 1);
        b.offer(a, 0, a.length);
        assertEquals(a.length, b.poll(dst, 0, dst.length));
        assertEquals(0, b.poll(dst, 0, dst.length));
        assertTrue(b.playing(), "no underrun until the player says the output is dry");
        assertEquals(0, b.underruns());
        byte[] small = ms(10, 2);
        b.offer(small, 0, small.length);
        assertEquals(small.length, b.poll(dst, 0, dst.length), "still playing: no new threshold to reach");
    }

    @Test
    void underrunReturnsToBufferingAndCounts() {
        JitterBuffer b = noDelay();
        byte[] dst = new byte[100000];
        byte[] a = ms(60, 1);
        b.offer(a, 0, a.length);
        b.markUnderrun();
        assertEquals(0, b.underruns(), "audio still queued: not an underrun");
        assertEquals(a.length, b.poll(dst, 0, dst.length));
        b.markUnderrun();
        assertEquals(1, b.underruns());
        assertFalse(b.playing());
        b.markUnderrun(); // buffering: no second count
        assertEquals(1, b.underruns());
        byte[] c = ms(30, 2);
        b.offer(c, 0, c.length);
        assertEquals(0, b.poll(dst, 0, dst.length)); // below threshold again
        b.offer(c, 0, c.length);
        assertEquals(960, b.poll(dst, 0, dst.length));
    }

    @Test
    void afterAnUnderrunTheDelayStartsAgainFromTheNextByte() {
        JitterBuffer b = new JitterBuffer(MONO);
        byte[] dst = new byte[100000];
        byte[] wave = ms(186, 1);
        b.offer(wave, 0, wave.length, 0);
        assertEquals(wave.length, b.poll(dst, 0, dst.length, 60 * MS));
        b.markUnderrun();
        b.offer(wave, 0, wave.length, 500 * MS);
        assertEquals(0, b.poll(dst, 0, dst.length, 559 * MS));
        assertEquals(wave.length, b.poll(dst, 0, dst.length, 560 * MS));
    }

    @Test
    void overflowKeepsNewestAudio() {
        JitterBuffer b = noDelay();
        byte[] old = ms(400, 1);
        byte[] mid = ms(100, 2);
        byte[] fresh = ms(100, 3);
        b.offer(old, 0, old.length);
        b.offer(mid, 0, mid.length);
        assertEquals(500, b.queuedMillis());
        assertEquals(0, b.droppedBytes());
        b.offer(fresh, 0, fresh.length); // 600 ms > 500: trim to 60 ms
        assertEquals(60, b.queuedMillis());
        assertEquals(540, b.droppedMillis());
        assertEquals(960, b.queuedBytes());
        byte[] dst = new byte[2000];
        assertEquals(960, b.poll(dst, 0, dst.length));
        for (int i = 0; i < 960; i++) {
            assertEquals(3, dst[i]);
        }
    }

    @Test
    void wholeFramesOnly() {
        PcmFormat st = new PcmFormat(44100, 2);
        JitterBuffer b = new JitterBuffer(st, 0, 500, 60);
        byte[] data = new byte[11];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        b.offer(data, 0, data.length);
        assertEquals(8, b.queuedBytes());
        assertEquals(3, b.droppedBytes());
        byte[] dst = new byte[16];
        assertEquals(4, b.poll(dst, 0, 6)); // 6 rounds down to 4
        assertEquals(4, b.queuedBytes());
        assertEquals(0, dst[0]);
        assertEquals(3, dst[3]);
        assertEquals(0, b.poll(dst, 0, 3));
        assertEquals(4, b.poll(dst, 0, 16));
        assertEquals(4, dst[0]);
    }

    @Test
    void wrapAroundKeepsOrder() {
        JitterBuffer b = new JitterBuffer(MONO, 0, 500, 60);
        byte[] dst = new byte[64];
        int next = 0;
        int expect = 0;
        for (int round = 0; round < 5000; round++) {
            byte[] chunk = new byte[34];
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) next++;
            }
            b.offer(chunk, 0, chunk.length);
            int n = b.poll(dst, 0, 34);
            for (int i = 0; i < n; i++) {
                assertEquals((byte) expect++, dst[i]);
            }
        }
        assertEquals(0, b.droppedBytes());
    }

    @Test
    void clearEmptiesAndRebuffers() {
        JitterBuffer b = noDelay();
        byte[] a = ms(100, 1);
        b.offer(a, 0, a.length);
        b.poll(new byte[16], 0, 16);
        assertTrue(b.playing());
        b.offer(new byte[5], 0, 5);
        long dropped = b.droppedBytes();
        b.clear();
        assertEquals(0, b.queuedBytes());
        assertFalse(b.playing());
        assertEquals(0, b.underruns());
        assertEquals(dropped, b.droppedBytes());
        assertEquals(0, b.poll(new byte[16], 0, 16));
    }
}
