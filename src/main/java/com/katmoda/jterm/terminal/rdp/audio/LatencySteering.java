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

import java.util.concurrent.TimeUnit;

/**
 * Decides how much queued sound to drop so it stays close to the picture. Pure arithmetic over the
 * values it is given (no clock of its own, not thread-safe: {@link JitterBuffer} calls it under its
 * lock).
 *
 * <p>What it watches is the <em>lowest</em> amount of sound waiting to be heard (the queue plus
 * what the output still holds) over a window. With waves as large as Windows 11's (~186 ms), the
 * amount waiting swings by a whole wave every time one arrives, so its average or current value says
 * little; the low point just before the next wave says how much margin there really is. Anything
 * above {@link #marginMs()} at that point only adds delay. A burst (the network held two waves, then
 * delivered both) leaves exactly that kind of surplus behind, and nothing else ever plays it away:
 * measured, the queue ratcheted up from 70 to 170 ms and stayed there until it overflowed.</p>
 *
 * <p>The margin adapts: an underrun (the output ran dry) raises it, and two minutes without one
 * lowers it again, never below where it started.</p>
 */
final class LatencySteering {

    static final int WINDOW_MS = 1000;
    static final int START_MARGIN_MS = 40;
    static final int MAX_MARGIN_MS = 200;
    static final int UNDERRUN_STEP_MS = 20;
    static final int RELAX_STEP_MS = 10;
    static final long RELAX_AFTER_MS = 120_000;
    /** A surplus smaller than this is left alone: not worth a cut. */
    static final int HYSTERESIS_MS = 15;

    private static final long WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(WINDOW_MS);
    private static final long RELAX_AFTER_NANOS = TimeUnit.MILLISECONDS.toNanos(RELAX_AFTER_MS);

    private int marginMs = START_MARGIN_MS;
    private boolean windowOpen;
    private long windowStart;
    private long windowMin;
    private long lastLowMs = -1;
    private boolean marginChanged;
    private long marginChangedAt;

    /**
     * One observation of how much sound is waiting, in milliseconds, at {@code nowNanos}. Returns how
     * many milliseconds to drop now; 0 for nothing. A window closes only on a call, so the first one
     * after a pause just starts a new window.
     */
    long observe(long waitingMs, long nowNanos) {
        relax(nowNanos);
        if (!windowOpen) {
            restartWindow(nowNanos);
        }
        windowMin = Math.min(windowMin, waitingMs);
        if (nowNanos - windowStart < WINDOW_NANOS) {
            return 0;
        }
        lastLowMs = windowMin;
        long surplus = windowMin - marginMs;
        restartWindow(nowNanos);
        return surplus > HYSTERESIS_MS ? surplus : 0;
    }

    /** The output ran dry: more margin from now on, and the current measurement no longer counts. */
    void underrun(long nowNanos) {
        marginMs = Math.min(MAX_MARGIN_MS, marginMs + UNDERRUN_STEP_MS);
        marginChanged = true;
        marginChangedAt = nowNanos;
        windowOpen = false;
    }

    /** Starts a fresh measurement, e.g. after a cut changed what is waiting. */
    void restartWindow(long nowNanos) {
        windowOpen = true;
        windowStart = nowNanos;
        windowMin = Long.MAX_VALUE;
    }

    /** Forgets the running measurement (playback stopped); the margin stays. */
    void pause() {
        windowOpen = false;
    }

    private void relax(long nowNanos) {
        if (marginChanged && marginMs > START_MARGIN_MS && nowNanos - marginChangedAt >= RELAX_AFTER_NANOS) {
            marginMs = Math.max(START_MARGIN_MS, marginMs - RELAX_STEP_MS);
            marginChangedAt = nowNanos;
        }
    }

    int marginMs() {
        return marginMs;
    }

    /** The low point of the last complete window, or -1 before the first. */
    long lastLowMs() {
        return lastLowMs;
    }
}
