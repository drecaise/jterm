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
 * Bounded byte queue between the thread that receives audio and the one that plays it. Methods are
 * synchronized. It starts buffering, plays once {@code startMs} is queued <em>and</em>
 * {@code startDelayMs} has passed since the first byte arrived, goes back to buffering on an
 * underrun, and drops the oldest audio when more than {@code maxMs} is queued.
 *
 * <p>The delay is what gives a margin against late audio, whatever the server's wave size. A byte
 * threshold alone gives none when waves are large: Windows 11 sends one every ~186 ms (32 KiB at
 * 44.1 kHz stereo, measured), so the first wave alone passes a 60 ms threshold, playback starts at
 * once, and the next wave is due exactly when the output runs dry. Waiting 60 ms after the first byte
 * keeps 60 ms in hand; with small waves the two conditions coincide.</p>
 *
 * <p>An empty queue while playing is not an underrun by itself: the output still holds up to its
 * own buffer. Only the player knows when the output ran dry, and says so with
 * {@link #markUnderrun()}.</p>
 *
 * <p>Built with steering on, it also keeps the delay down ({@link LatencySteering} decides how much
 * is surplus, from what {@link #steer} reports). The surplus is cut from the front of the queue (as
 * it arrives, when the surplus is in the output rather than the queue), in a
 * quiet stretch if one comes along within {@link #FORCE_AFTER_MS}; otherwise in cuts of at most
 * {@link #SPLICE_MAX_MS} with a {@link #FADE_MS} crossfade, so the cut neither clicks nor skips a
 * whole word. Cuts are counted apart from drops ({@link #trimmedMillis()}), and so is silence an
 * overflow skips: a drop is sound that was meant to be heard.</p>
 */
public final class JitterBuffer {

    public static final int START_MS = 60;
    public static final int MAX_MS = 500;
    public static final int TRIM_TO_MS = 60;
    public static final int START_DELAY_MS = 60;
    public static final int LINE_BUFFER_MS = 100;
    /** Sound whose loudest sample stays below this (about -42 dBFS) is quiet enough to cut silently. */
    static final int QUIET_PEAK = 256;
    /** How much of the front is looked at, and at most cut, per quiet cut. */
    static final int QUIET_LOOK_MS = 20;
    /** How long a surplus waits for a quiet stretch before it is cut anyway. */
    static final int FORCE_AFTER_MS = 1000;
    /** Largest cut made in sound that isn't quiet, and the least time between two of them. */
    static final int SPLICE_MAX_MS = 20;
    static final int SPLICE_GAP_MS = 250;
    /** Length of the crossfade over such a cut. */
    static final int FADE_MS = 5;
    /**
     * Sound resuming within this long of the output running dry means a wave came late, and steering
     * keeps more margin; a longer gap is just a pause in the sound (a clip ended), which says nothing
     * about the network. Windows' waves are ~186 ms apart.
     */
    static final int LATE_LIMIT_MS = 250;

    private final PcmFormat format;
    private final int startBytes;
    private final int maxBytes;
    private final int trimBytes;
    private final long startDelayNanos;
    private long firstDataNanos = -1; // while buffering: when the first byte of this round arrived
    private byte[] ring;
    private int head;
    private int size;
    private boolean playing;
    private long underruns;
    private long droppedBytes;
    private final LatencySteering steering; // null: steering off
    private int pendingCutBytes;
    private long pendingSinceNanos;
    private long lastSpliceNanos;
    private boolean spliced;
    private long trimmedBytes;
    private long dryNanos = -1; // when the output last ran dry, until sound resumes

    public JitterBuffer(PcmFormat format) {
        this(format, START_MS, MAX_MS, TRIM_TO_MS, START_DELAY_MS);
    }

    /** No start delay: playback starts on the byte threshold alone. */
    public JitterBuffer(PcmFormat format, int startMs, int maxMs, int trimToMs) {
        this(format, startMs, maxMs, trimToMs, 0);
    }

    public JitterBuffer(PcmFormat format, int startMs, int maxMs, int trimToMs, int startDelayMs) {
        this(format, startMs, maxMs, trimToMs, startDelayMs, false);
    }

    /** {@code steer}: keep the delay down as well (the class comment). */
    public JitterBuffer(PcmFormat format, int startMs, int maxMs, int trimToMs, int startDelayMs, boolean steer) {
        this.format = format;
        this.steering = steer ? new LatencySteering() : null;
        this.startDelayNanos = TimeUnit.MILLISECONDS.toNanos(startDelayMs);
        this.startBytes = (int) format.bytesForMillis(startMs);
        this.maxBytes = (int) format.bytesForMillis(maxMs);
        this.trimBytes = (int) format.bytesForMillis(trimToMs);
        this.ring = new byte[Math.max(format.frameBytes(), maxBytes + 65536 + format.frameBytes())];
    }

    public void offer(byte[] data, int off, int len) {
        offer(data, off, len, System.nanoTime());
    }

    /** As {@link #offer(byte[], int, int)}, {@code nowNanos} being the arrival time. */
    public synchronized void offer(byte[] data, int off, int len, long nowNanos) {
        int whole = format.wholeFrames(len);
        if (!playing && firstDataNanos < 0 && whole > 0) {
            firstDataNanos = nowNanos;
        }
        if (dryNanos >= 0 && whole > 0) {
            if (steering != null && nowNanos - dryNanos <= TimeUnit.MILLISECONDS.toNanos(LATE_LIMIT_MS)) {
                steering.underrun(nowNanos);
            }
            dryNanos = -1;
        }
        droppedBytes += len - whole;
        if (whole > ring.length) {
            // Bounded by protocol (65536); defensive only: keep the newest bytes that fit.
            droppedBytes += whole - ring.length;
            off += whole - ring.length;
            whole = ring.length;
        }
        if (whole > ring.length - size) {
            int over = whole - (ring.length - size);
            over = format.wholeFrames(over + format.frameBytes() - 1);
            discard(over);
        }
        int tail = (head + size) % ring.length;
        int first = Math.min(whole, ring.length - tail);
        System.arraycopy(data, off, ring, tail, first);
        System.arraycopy(data, off + first, ring, 0, whole - first);
        size += whole;
        if (size > maxBytes) {
            // Silence skipped this way is a cut, not a loss: Windows starts every new sound with a
            // batch of more than 500 ms that nobody heard missing (measured: 454 ms per sound).
            int over = format.wholeFrames(size - Math.min(trimBytes, size));
            int quiet = quietPrefix(over);
            skip(quiet);
            discard(over - quiet);
        }
    }

    private void discard(int bytes) {
        int n = Math.min(format.wholeFrames(bytes), size);
        head = (head + n) % ring.length;
        size -= n;
        droppedBytes += n;
    }

    public int poll(byte[] dst, int off, int maxLen) {
        return poll(dst, off, maxLen, System.nanoTime());
    }

    /**
     * Takes up to {@code maxLen} bytes (whole frames) once playing; 0 while buffering or empty.
     * {@code nowNanos} decides whether the start delay has passed.
     */
    public synchronized int poll(byte[] dst, int off, int maxLen, long nowNanos) {
        if (!playing) {
            if (size < startBytes || size == 0 || nowNanos - firstDataNanos < startDelayNanos) {
                return 0;
            }
            playing = true;
        }
        if (size == 0) {
            return 0; // not an underrun yet: the output may still be playing (markUnderrun)
        }
        if (pendingCutBytes > 0) {
            cut(nowNanos);
            if (size == 0) {
                return 0;
            }
        }
        int n = Math.min(format.wholeFrames(maxLen), size);
        if (n <= 0) {
            return 0;
        }
        int first = Math.min(n, ring.length - head);
        System.arraycopy(ring, head, dst, off, first);
        System.arraycopy(ring, 0, dst, off + first, n - first);
        head = (head + n) % ring.length;
        size -= n;
        return n;
    }

    /**
     * Reports how much the output still holds, unheard, at {@code nowNanos}; with the queue, that is
     * everything waiting to be heard. Does nothing unless steering is on and playback running. The
     * player calls this on every round, so the low point just before a wave arrives is seen.
     */
    public synchronized void steer(int outputMs, long nowNanos) {
        if (steering == null || !playing || pendingCutBytes > 0) {
            return;
        }
        long surplusMs = steering.observe(format.millisForBytes(size) + Math.max(0, outputMs), nowNanos);
        if (surplusMs > 0) {
            // Not capped by what is queued: with small, steady frames (GNOME, Windows' AAC) the queue
            // is empty at the low point and the surplus sits in the output, which is never touched.
            // Cutting it from the frames still to come lets the output drain down to the margin.
            pendingCutBytes = format.wholeFrames((int) Math.min(maxBytes, format.bytesForMillis(surplusMs)));
            pendingSinceNanos = nowNanos;
            spliced = false;
        }
    }

    /** Cuts some of the pending surplus from the front: quiet sound at once, other sound only when overdue. */
    private void cut(long nowNanos) {
        int look = format.wholeFrames((int) Math.min(size, format.bytesForMillis(QUIET_LOOK_MS)));
        if (look > 0 && peak(0, look) < QUIET_PEAK) {
            int n = Math.min(pendingCutBytes, look);
            skip(n);
            pendingCutBytes -= n;
        } else if (nowNanos - pendingSinceNanos >= TimeUnit.MILLISECONDS.toNanos(FORCE_AFTER_MS)
                && (!spliced || nowNanos - lastSpliceNanos >= TimeUnit.MILLISECONDS.toNanos(SPLICE_GAP_MS))) {
            int fade = format.wholeFrames((int) format.bytesForMillis(FADE_MS));
            int n = format.wholeFrames(Math.min(Math.min(pendingCutBytes, (int) format.bytesForMillis(SPLICE_MAX_MS)),
                    size - fade));
            if (n > 0 && fade > 0) {
                crossfade(n, fade);
                skip(n);
                pendingCutBytes -= n;
                spliced = true;
                lastSpliceNanos = nowNanos;
            }
        }
        if (pendingCutBytes <= 0) {
            pendingCutBytes = 0;
            steering.restartWindow(nowNanos);
        }
    }

    /**
     * Blends the {@code fade} bytes at the front into the ones {@code cut} bytes later, so that
     * dropping the first {@code cut} bytes leaves no step: the result starts as what would have played
     * next and ends as what follows the cut.
     */
    private void crossfade(int cut, int fade) {
        int frameBytes = format.frameBytes();
        int frames = fade / frameBytes;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < frameBytes; c += 2) {
                int from = sample(f * frameBytes + c);
                int to = sample(cut + f * frameBytes + c);
                putSample(cut + f * frameBytes + c, (from * (frames - f) + to * f) / frames);
            }
        }
    }

    /** Loudest absolute sample in {@code len} bytes from {@code off} bytes past the front. */
    private int peak(int off, int len) {
        int max = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            max = Math.max(max, Math.abs(sample(off + i)));
        }
        return max;
    }

    /** How many of the first {@code len} bytes (whole frames) are quiet, from the front. */
    private int quietPrefix(int len) {
        int i = 0;
        while (i + 1 < len && Math.abs(sample(i)) < QUIET_PEAK) {
            i += 2;
        }
        return format.wholeFrames(i);
    }

    /** The 16-bit little-endian sample {@code off} bytes past the front. */
    private int sample(int off) {
        int p = (head + off) % ring.length;
        return (short) ((ring[p] & 0xFF) | (ring[(p + 1) % ring.length] << 8));
    }

    private void putSample(int off, int v) {
        int p = (head + off) % ring.length;
        ring[p] = (byte) v;
        ring[(p + 1) % ring.length] = (byte) (v >> 8);
    }

    /** Takes bytes off the front as a cut, not a drop. */
    private void skip(int bytes) {
        int n = Math.min(format.wholeFrames(bytes), size);
        head = (head + n) % ring.length;
        size -= n;
        trimmedBytes += n;
    }

    /**
     * The output ran dry while playing and nothing is queued: counts an underrun and goes back to
     * buffering. Does nothing otherwise.
     */
    public void markUnderrun() {
        markUnderrun(System.nanoTime());
    }

    /**
     * As {@link #markUnderrun()}. With steering on, the margin grows if sound resumes within
     * {@link #LATE_LIMIT_MS}.
     */
    public synchronized void markUnderrun(long nowNanos) {
        if (playing && size == 0) {
            underruns++;
            playing = false;
            firstDataNanos = -1;
            pendingCutBytes = 0;
            dryNanos = nowNanos;
            if (steering != null) {
                steering.pause();
            }
        }
    }

    public synchronized void clear() {
        head = 0;
        size = 0;
        playing = false;
        firstDataNanos = -1;
        pendingCutBytes = 0;
        dryNanos = -1;
        if (steering != null) {
            steering.pause();
        }
    }

    public synchronized int queuedBytes() {
        return size;
    }

    public synchronized long queuedMillis() {
        return format.millisForBytes(size);
    }

    public synchronized long underruns() {
        return underruns;
    }

    public synchronized long droppedBytes() {
        return droppedBytes;
    }

    public synchronized long droppedMillis() {
        return format.millisForBytes(droppedBytes);
    }

    /** Sound cut to keep the delay down (never counted in {@link #droppedMillis()}). */
    public synchronized long trimmedMillis() {
        return format.millisForBytes(trimmedBytes);
    }

    /** Steering's low point of everything waiting over its last window, ms; -1 unknown or steering off. */
    public synchronized long lowestWaitingMillis() {
        return steering != null ? steering.lastLowMs() : -1;
    }

    /** Steering's current margin, ms; -1 with steering off. */
    public synchronized int marginMillis() {
        return steering != null ? steering.marginMs() : -1;
    }

    public synchronized boolean playing() {
        return playing;
    }

    public PcmFormat format() {
        return format;
    }
}
