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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The delay-keeping half of {@link JitterBuffer}: what is cut, where, and how. 8 kHz mono, 16 bytes/ms. */
class JitterBufferSteeringTest {

    private static final PcmFormat MONO = new PcmFormat(8000, 1);
    private static final long MS = 1_000_000L;
    private static final int FRAMES_PER_MS = 8;

    private static JitterBuffer steered() {
        return new JitterBuffer(MONO, 0, JitterBuffer.MAX_MS, JitterBuffer.TRIM_TO_MS, 0, true);
    }

    /** {@code ms} of samples, each {@code value(frame)}. */
    private static byte[] samples(int ms, java.util.function.IntUnaryOperator value) {
        byte[] b = new byte[ms * FRAMES_PER_MS * 2];
        for (int f = 0; f < b.length / 2; f++) {
            int v = value.applyAsInt(f);
            b[2 * f] = (byte) v;
            b[2 * f + 1] = (byte) (v >> 8);
        }
        return b;
    }

    private static int sampleAt(byte[] b, int frame) {
        return (short) ((b[2 * frame] & 0xFF) | (b[2 * frame + 1] << 8));
    }

    /** Starts playback, then reports a whole window with {@code ms} queued and a dry output. */
    private static void measureSurplus(JitterBuffer b, long at) {
        byte[] one = new byte[2];
        assertEquals(2, b.poll(one, 0, 2, at));
        b.steer(0, at);
        b.steer(0, at + LatencySteering.WINDOW_MS * MS);
    }

    @Test
    void steeringOffNeverCuts() {
        JitterBuffer b = new JitterBuffer(MONO, 0, JitterBuffer.MAX_MS, JitterBuffer.TRIM_TO_MS, 0);
        byte[] quiet = samples(300, f -> 0);
        b.offer(quiet, 0, quiet.length, 0);
        measureSurplus(b, 0);
        b.poll(new byte[2], 0, 2, 2000 * MS);
        assertEquals(0, b.trimmedMillis());
        assertEquals(-1, b.lowestWaitingMillis());
    }

    @Test
    void aQuietSurplusIsCutAtOnce() {
        JitterBuffer b = steered();
        byte[] quiet = samples(200, f -> (f % 2 == 0 ? 1 : -1) * (JitterBuffer.QUIET_PEAK - 1));
        b.offer(quiet, 0, quiet.length, 0);
        measureSurplus(b, 0);
        long queued = b.queuedMillis(); // 200 ms less one frame
        long surplus = queued - LatencySteering.START_MARGIN_MS;
        byte[] one = new byte[2];
        for (int i = 0; i < 20; i++) {
            b.poll(one, 0, 2, (1000 + i) * MS);
        }
        assertEquals(surplus, b.trimmedMillis(), 1);
        assertEquals(0, b.droppedBytes());
        assertEquals(LatencySteering.START_MARGIN_MS, b.queuedMillis(), 4);
    }

    /**
     * GNOME and Windows' AAC send ~23 ms frames at a steady pace: the queue is empty at the low point
     * and the surplus sits in the output (measured: lowest 78 ms, 0 queued, with a 100 ms output).
     * It is cut from the frames that arrive next.
     */
    @Test
    void aSurplusInTheOutputIsCutFromTheFramesStillToCome() {
        JitterBuffer b = steered();
        byte[] frame = samples(23, f -> 0);
        b.offer(frame, 0, frame.length, 0);
        byte[] out = new byte[frame.length];
        assertEquals(frame.length, b.poll(out, 0, out.length, 0)); // straight into the output
        b.steer(78, 0);
        b.steer(78, LatencySteering.WINDOW_MS * MS);
        long t = LatencySteering.WINDOW_MS;
        long passed = 0;
        for (int i = 0; i < 4; i++) {
            b.offer(frame, 0, frame.length, (t + 23L * i) * MS);
            passed += b.poll(out, 0, out.length, (t + 23L * i) * MS);
        }
        assertEquals(78 - LatencySteering.START_MARGIN_MS, b.trimmedMillis(), 1);
        assertEquals(4 * 23 - (78 - LatencySteering.START_MARGIN_MS), passed / 16, 1);
    }

    @Test
    void loudSoundWaitsForQuietThenIsCutInSmallCrossfadedSteps() {
        JitterBuffer b = steered();
        byte[] loud = samples(400, f -> 5000);
        b.offer(loud, 0, loud.length, 0);
        measureSurplus(b, 0);
        long pendingFrom = LatencySteering.WINDOW_MS;
        byte[] one = new byte[2];
        b.poll(one, 0, 2, (pendingFrom + JitterBuffer.FORCE_AFTER_MS - 1) * MS);
        assertEquals(0, b.trimmedMillis(), "waits for a quiet stretch first");
        long t = pendingFrom + JitterBuffer.FORCE_AFTER_MS;
        b.poll(one, 0, 2, t * MS);
        assertEquals(JitterBuffer.SPLICE_MAX_MS, b.trimmedMillis());
        b.poll(one, 0, 2, (t + JitterBuffer.SPLICE_GAP_MS - 1) * MS);
        assertEquals(JitterBuffer.SPLICE_MAX_MS, b.trimmedMillis(), "cuts are spaced out");
        b.poll(one, 0, 2, (t + JitterBuffer.SPLICE_GAP_MS) * MS);
        assertEquals(2 * JitterBuffer.SPLICE_MAX_MS, b.trimmedMillis());
    }

    /** The cut leaves no step: it starts on what would have played next and ends on what follows the cut. */
    @Test
    void theCrossfadeJoinsBothSidesOfTheCut() {
        JitterBuffer b = steered();
        byte[] ramp = samples(400, f -> 1000 + f);
        b.offer(ramp, 0, ramp.length, 0);
        measureSurplus(b, 0); // played frame 0
        byte[] out = new byte[2];
        b.poll(out, 0, 2, (LatencySteering.WINDOW_MS + JitterBuffer.FORCE_AFTER_MS) * MS);
        assertEquals(1001, sampleAt(out, 0), "the cut starts with what would have played next");
        int cut = JitterBuffer.SPLICE_MAX_MS * FRAMES_PER_MS;
        int fade = JitterBuffer.FADE_MS * FRAMES_PER_MS;
        byte[] rest = new byte[fade * 2 + 2];
        assertEquals(rest.length, b.poll(rest, 0, rest.length, (LatencySteering.WINDOW_MS + JitterBuffer.FORCE_AFTER_MS) * MS));
        for (int f = 1; f < fade; f++) {
            int from = 1001 + f;
            int to = 1001 + cut + f;
            assertEquals((from * (fade - f) + to * f) / fade, sampleAt(rest, f - 1), "frame " + f);
        }
        assertEquals(1001 + cut + fade, sampleAt(rest, fade - 1), "and ends on what follows the cut");
        assertEquals(1001 + cut + fade + 1, sampleAt(rest, fade));
    }

    /** Windows starts each new sound with more than 500 ms at once; silence skipped then is not a loss. */
    @Test
    void silenceAnOverflowSkipsIsCountedAsCutSoundAsDropped() {
        JitterBuffer b = new JitterBuffer(MONO, 0, JitterBuffer.MAX_MS, JitterBuffer.TRIM_TO_MS, 0);
        byte[] silence = samples(450, f -> 0);
        byte[] sound = samples(100, f -> 5000);
        b.offer(silence, 0, silence.length, 0);
        b.offer(sound, 0, sound.length, 0);
        assertEquals(JitterBuffer.TRIM_TO_MS, b.queuedMillis());
        assertEquals(450, b.trimmedMillis(), "the silence");
        assertEquals(40, b.droppedMillis(), "the start of the sound after it");

        b.offer(samples(500, f -> 5000), 0, 500 * FRAMES_PER_MS * 2, 0);
        assertEquals(40 + 500, b.droppedMillis(), "sound that was meant to be heard");
        assertEquals(450, b.trimmedMillis());
    }

    @Test
    void aLateWaveRaisesTheMarginAPauseDoesNot() {
        JitterBuffer b = steered();
        byte[] a = samples(10, f -> 0);
        b.offer(a, 0, a.length, 0);
        b.poll(new byte[a.length], 0, a.length, 0);
        b.markUnderrun(5 * MS);
        assertEquals(1, b.underruns());
        assertEquals(LatencySteering.START_MARGIN_MS, b.marginMillis(), "a pause, until sound resumes");
        b.offer(a, 0, a.length, (5 + JitterBuffer.LATE_LIMIT_MS) * MS);
        assertEquals(LatencySteering.START_MARGIN_MS + LatencySteering.UNDERRUN_STEP_MS, b.marginMillis(),
                "it resumed soon: a late wave");

        b.poll(new byte[a.length], 0, a.length, 1000 * MS);
        b.markUnderrun(1001 * MS);
        b.offer(a, 0, a.length, (1002 + JitterBuffer.LATE_LIMIT_MS) * MS);
        assertEquals(LatencySteering.START_MARGIN_MS + LatencySteering.UNDERRUN_STEP_MS, b.marginMillis(),
                "a pause in the sound says nothing about the network");
    }

    /**
     * Windows 11's ~186 ms waves, a stall, and the burst after it. Without steering the burst's
     * surplus stays queued for good; with it, the low point comes back to the margin within seconds,
     * in loud sound too.
     */
    @Test
    void aBurstsSurplusIsPlayedAwayAndTheDelayComesBack() {
        long withoutLow = simulate(new JitterBuffer(MONO, JitterBuffer.START_MS, JitterBuffer.MAX_MS,
                JitterBuffer.TRIM_TO_MS, JitterBuffer.START_DELAY_MS, false));
        JitterBuffer steered = new JitterBuffer(MONO, JitterBuffer.START_MS, JitterBuffer.MAX_MS,
                JitterBuffer.TRIM_TO_MS, JitterBuffer.START_DELAY_MS, true);
        long withLow = simulate(steered);
        assertTrue(withoutLow > 200, "without steering the surplus stays: " + withoutLow);
        assertTrue(withLow <= steered.marginMillis() + LatencySteering.HYSTERESIS_MS + 5,
                "steered back to the margin: " + withLow + " (margin " + steered.marginMillis() + ")");
        assertTrue(steered.trimmedMillis() > 0);
        assertEquals(0, steered.droppedBytes());
    }

    /** 30 s at 5 ms steps; the wave due at 3.16 s comes 200 ms late, with the next. Returns the low point of the last 5 s. */
    private static long simulate(JitterBuffer b) {
        byte[] wave = samples(186, f -> (f % 16 < 8) ? 6000 : -6000);
        byte[] tick = new byte[5 * FRAMES_PER_MS * 2];
        long nextWave = 0;
        long low = Long.MAX_VALUE;
        for (long t = 0; t < 30_000; t += 5) {
            boolean stalled = t >= 3000 && t < 3360;
            while (!stalled && nextWave <= t) {
                b.offer(wave, 0, wave.length, t * MS);
                nextWave += 186;
            }
            b.steer(0, t * MS);
            int n = b.poll(tick, 0, tick.length, t * MS);
            if (n == 0 && b.playing() && b.queuedBytes() == 0) {
                b.markUnderrun(t * MS);
            }
            if (t >= 25_000 && b.playing()) {
                low = Math.min(low, b.queuedMillis());
            }
        }
        return low;
    }
}
