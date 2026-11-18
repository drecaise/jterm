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

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Plays one RDP connection's sound: takes the helper's AUDIO_* messages and feeds a
 * {@link AudioSink} through a {@link JitterBuffer}. Swing-free.
 *
 * <p>Threads: the {@code on*} methods run on the connection's reader thread and only validate and
 * enqueue, never block and never throw; everything that can block (opening the device, writing to
 * it) runs on this player's own thread, {@code rdp-audio-out}, started with the first stream.</p>
 *
 * <p>Everything here is hostile input: the format comes from the helper, which got it from the
 * server. A format outside {@link PcmFormat#RATES}, a chunk that isn't whole frames or is larger
 * than the protocol allows, or data with no stream open, is dropped and counted.</p>
 */
public final class RdpAudioPlayer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RdpAudioPlayer.class);

    /** Largest chunk the helper may send (protocol.h {@code JRDP_AUDIO_MAX_CHUNK}). */
    public static final int MAX_CHUNK = 65536;
    /** The output is closed after this long without data, so the stream leaves the system mixer. */
    static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(3);
    /** A device that failed to open is retried at most this often; data meanwhile is dropped. */
    static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(2);
    /** How much is handed to the output per write. */
    static final int WRITE_MS = 20;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    /** A measured delay is reported again only when it moved by at least this much. */
    static final int DELAY_STEP_MS = 10;

    /**
     * What the pane shows: in its tooltip and in "Show performance". {@code lowestMs} is the low point
     * of everything waiting to be heard over the last second, and {@code marginMs} what steering aims
     * that at (both -1 when unknown or steering is off); {@code trimmedMs} was cut to keep the delay
     * down, {@code droppedMs} lost otherwise.
     */
    public record Status(PcmFormat format, int codec, boolean muted, boolean deviceFailed, long queuedMs,
                         long underruns, long droppedMs, long badMessages, long lowestMs, int marginMs,
                         long trimmedMs) {
    }

    private final Supplier<AudioSink> sinks;
    private final LongSupplier clock;
    private final Runnable onStatusChanged;
    private final IntConsumer onDelay;
    private final boolean startThread;
    private final int startDelayMs;
    private final boolean steer;
    private final Object lock = new Object();

    // Guarded by lock.
    private PcmFormat stream;          // the announced format; null = no stream open
    private int codec;
    private JitterBuffer buffer;       // the current format's queue (kept across a close, to drain)
    private int left = Gain.UNITY;
    private int right = Gain.UNITY;
    private boolean muted;
    private boolean flushRequested;
    private boolean closed;
    private long lastDataNanos;
    private long badMessages;
    private long pastUnderruns;        // counters of queues replaced on a format change
    private long pastDroppedMs;
    private long pastTrimmedMs;
    private Thread thread;

    // The play thread's own.
    private AudioSink sink;
    private PcmFormat sinkFormat;
    private long retryAt;
    private long stepFailures;
    private long reportedDelayMs = -1;
    private final byte[] chunk = new byte[64 * 1024];
    private volatile boolean deviceFailed;
    /** Set while the play thread opens the output, which can take long (logged). */
    private volatile boolean opening;
    private int overflowsLogged;              // guarded by lock
    private static final int MAX_OVERFLOW_LOGS = 20;

    /**
     * @param onDelay called on the play thread, about once a second at most, with how long sound now
     *                waits before it is heard (ms) whenever that has moved by {@link #DELAY_STEP_MS}:
     *                what the server should be told instead of a constant. May block briefly; must
     *                not throw.
     */
    public RdpAudioPlayer(Runnable onStatusChanged, IntConsumer onDelay) {
        this(JavaSoundSink::new, System::nanoTime, onStatusChanged, onDelay, true, JitterBuffer.START_DELAY_MS,
                true);
    }

    /**
     * Tests: no start delay, so playback starts on the byte threshold alone (see {@link JitterBuffer}),
     * and no steering.
     */
    RdpAudioPlayer(Supplier<AudioSink> sinks, LongSupplier clock, Runnable onStatusChanged, boolean startThread) {
        this(sinks, clock, onStatusChanged, null, startThread, 0, false);
    }

    /** {@code startThread == false} leaves the play loop to the caller's {@link #step} (tests). */
    RdpAudioPlayer(Supplier<AudioSink> sinks, LongSupplier clock, Runnable onStatusChanged, IntConsumer onDelay,
                   boolean startThread, int startDelayMs, boolean steer) {
        this.startDelayMs = startDelayMs;
        this.steer = steer;
        this.sinks = Objects.requireNonNull(sinks);
        this.clock = Objects.requireNonNull(clock);
        this.onStatusChanged = onStatusChanged != null ? onStatusChanged : () -> { };
        this.onDelay = onDelay != null ? onDelay : ms -> { };
        this.startThread = startThread;
    }

    // --------------------------------------------------------------------- reader thread side

    /** AUDIO_OPEN: the format of what follows. An unlisted format leaves no stream open. */
    public void onOpen(int sampleRate, int channels, int codec) {
        PcmFormat f = PcmFormat.of(sampleRate, channels).orElse(null);
        synchronized (lock) {
            if (closed) {
                return;
            }
            stream = f;
            if (f == null) {
                badMessages++;
                LOG.debug("RDP sound: refused format {} Hz, {} channels", sampleRate, channels);
            } else {
                this.codec = codec;
                if (buffer == null || !buffer.format().equals(f)) {
                    retire(buffer);
                    buffer = new JitterBuffer(f, JitterBuffer.START_MS, JitterBuffer.MAX_MS,
                            JitterBuffer.TRIM_TO_MS, startDelayMs, steer);
                }
                ensureThread();
                lock.notifyAll();
            }
        }
        onStatusChanged.run();
    }

    /** AUDIO_DATA. {@code samples} may alias the reader's buffer: it is copied before returning. */
    public void onData(ByteBuffer samples) {
        int len = samples.remaining();
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (stream == null || len > MAX_CHUNK || !stream.isWholeFrames(len)) {
                badMessages++;
                return;
            }
            lastDataNanos = clock.getAsLong();
            if (muted) {
                return;
            }
            long lostBefore = buffer.droppedMillis() + buffer.trimmedMillis();
            if (samples.hasArray()) {
                buffer.offer(samples.array(), samples.arrayOffset() + samples.position(), len, lastDataNanos);
            } else {
                byte[] copy = new byte[len];
                samples.duplicate().get(copy);
                buffer.offer(copy, 0, len, lastDataNanos);
            }
            long lost = buffer.droppedMillis() + buffer.trimmedMillis() - lostBefore;
            if (lost > 0 && overflowsLogged < MAX_OVERFLOW_LOGS) {
                overflowsLogged++;
                // Only an overflow takes anything away on arrival. Info: rare, and it says where a
                // "dropped" figure comes from.
                LOG.info("RDP sound: more than {} ms piled up, {} ms skipped ({})", JitterBuffer.MAX_MS, lost,
                        opening ? "while the sound output was opening" : buffer.playing() ? "while playing"
                                : "before playback started");
            }
            lock.notifyAll();
        }
    }

    /** AUDIO_CLOSE: the stream ended. What is queued still plays out. */
    public void onClose() {
        synchronized (lock) {
            stream = null;
        }
        onStatusChanged.run();
    }

    /** AUDIO_VOLUME: the server's level per channel, applied in software, never above unity. */
    public void onVolume(int left, int right) {
        synchronized (lock) {
            this.left = Math.max(0, Math.min(Gain.UNITY, left));
            this.right = Math.max(0, Math.min(Gain.UNITY, right));
        }
    }

    // ------------------------------------------------------------------------------ the pane

    /** Mute discards the sound instead of queueing it and closes the output; the channel stays open. */
    public void setMuted(boolean muted) {
        synchronized (lock) {
            if (this.muted == muted) {
                return;
            }
            this.muted = muted;
            if (muted && buffer != null) {
                buffer.clear();
                flushRequested = true;
                lock.notifyAll();
            }
        }
        onStatusChanged.run();
    }

    public boolean isMuted() {
        synchronized (lock) {
            return muted;
        }
    }

    public Status status() {
        synchronized (lock) {
            JitterBuffer b = buffer;
            return new Status(stream, codec, muted, deviceFailed,
                    b != null ? b.queuedMillis() : 0,
                    pastUnderruns + (b != null ? b.underruns() : 0),
                    pastDroppedMs + (b != null ? b.droppedMillis() : 0),
                    badMessages,
                    b != null ? b.lowestWaitingMillis() : -1,
                    b != null ? b.marginMillis() : -1,
                    pastTrimmedMs + (b != null ? b.trimmedMillis() : 0));
        }
    }

    /** Stops the play thread, which closes the output. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            stream = null;
            lock.notifyAll();
            if (thread == null) {
                closeSink();
            }
        }
    }

    // -------------------------------------------------------------------------- play thread

    private void retire(JitterBuffer old) {
        if (old != null) {
            pastUnderruns += old.underruns();
            pastDroppedMs += old.droppedMillis();
            pastTrimmedMs += old.trimmedMillis();
        }
    }

    private void ensureThread() {
        if (thread != null || !startThread) {
            return;
        }
        thread = new Thread(this::run, "rdp-audio-out");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            while (true) {
                long wait = stepOrRecover();
                if (wait < 0) {
                    break;
                }
                if (wait > 0) {
                    synchronized (lock) {
                        if (closed) {
                            break;
                        }
                        lock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(wait)));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSink();
        }
    }

    /**
     * {@link #step}, surviving an unexpected failure in it (a Java Sound call that throws where none
     * is declared, say). The thread must not end on one: nothing would start another, and the sound
     * would be gone until a reconnect. So the output is closed and treated as a device that failed,
     * which drops what arrives and tries again after {@link #RETRY_NANOS}. Closing the player still
     * ends the loop the usual way, through {@code closed}.
     */
    long stepOrRecover() {
        try {
            return step();
        } catch (RuntimeException e) {
            if (stepFailures++ == 0) {
                LOG.warn("RDP sound: the play loop failed; trying again", e);
            } else {
                LOG.debug("RDP sound: the play loop failed again", e);
            }
            try {
                closeSink();
            } catch (RuntimeException closing) {
                LOG.debug("RDP sound: closing the output failed", closing);
            }
            failed(clock.getAsLong());
            return POLL_NANOS * 20;
        }
    }

    /**
     * One round of the play loop: open, write or close the output as needed. Returns how long to
     * wait before the next round (0 = at once, negative = the player is closed). Never holds the
     * lock while touching the device, since a write blocks.
     */
    long step() {
        long now = clock.getAsLong();
        JitterBuffer buf;
        int l;
        int r;
        boolean idle;
        boolean flush;
        boolean mute;
        synchronized (lock) {
            if (closed) {
                return -1;
            }
            mute = muted;
            buf = buffer;
            l = left;
            r = right;
            idle = now - lastDataNanos > IDLE_NANOS;
            flush = flushRequested;
            flushRequested = false;
        }
        if (flush && sink != null) {
            sink.flush();
        }
        if (mute) {
            // Closed, not just left silent: data keeps arriving while muted, so the idle rule below
            // would never close it and a muted pane would stay in the system mixer. Nothing is
            // queued while muted; the first data after an unmute wakes this thread and reopens it.
            closeSink();
            return IDLE_NANOS;
        }
        if (buf == null) {
            return IDLE_NANOS;
        }
        PcmFormat fmt = buf.format();
        if (sink != null && !fmt.equals(sinkFormat)) {
            closeSink();
        }
        if (sink == null) {
            if (buf.queuedBytes() == 0) {
                return idle ? IDLE_NANOS : POLL_NANOS;
            }
            if (deviceFailed && now - retryAt < 0) {
                buf.clear(); // no device: dropped, not saved up to play late
                return Math.min(retryAt - now, POLL_NANOS * 20);
            }
            if (!openSink(fmt, now, buf)) {
                return POLL_NANOS * 20;
            }
        }
        buf.steer(sink.queuedMillis(), now);
        reportDelay(buf);
        int n = buf.poll(chunk, 0, (int) Math.min(chunk.length, fmt.bytesForMillis(WRITE_MS)), now);
        if (n == 0) {
            // An empty queue is only an underrun once the output has nothing left either; until
            // then the next wave may still arrive in time.
            if (buf.playing() && buf.queuedBytes() == 0 && sink.queuedMillis() == 0) {
                buf.markUnderrun(now);
            }
            if (idle && buf.queuedBytes() == 0) {
                closeSink();
                return IDLE_NANOS;
            }
            return POLL_NANOS;
        }
        Gain.apply(chunk, 0, n, fmt.channels(), l, r);
        try {
            sink.write(chunk, 0, n);
        } catch (Exception e) {
            LOG.debug("RDP sound output failed", e);
            closeSink();
            failed(now);
        }
        return 0;
    }

    /**
     * Tells the server's side how late the sound really is. Steering's low point is the right figure:
     * it is what waits ahead of a wave when it arrives, i.e. how long until that wave starts to
     * play, which is what the wave confirmation's render delay means. Nothing is reported before
     * the first full window (the helper keeps CONNECT's value until then).
     */
    private void reportDelay(JitterBuffer buf) {
        long low = buf.lowestWaitingMillis();
        if (low < 0 || (reportedDelayMs >= 0 && Math.abs(low - reportedDelayMs) < DELAY_STEP_MS)) {
            return;
        }
        reportedDelayMs = low;
        onDelay.accept((int) Math.min(low, Integer.MAX_VALUE));
    }

    private boolean openSink(PcmFormat fmt, long now, JitterBuffer buf) {
        AudioSink s = sinks.get();
        long started = System.nanoTime();
        opening = true;
        try {
            s.open(fmt, JitterBuffer.LINE_BUFFER_MS);
        } catch (Exception | LinkageError e) {
            opening = false;
            s.close();
            if (!deviceFailed) {
                LOG.info("RDP sound: no sound device for {} ({})", fmt.describe(), e.toString());
            }
            buf.clear();
            failed(now);
            return false;
        }
        opening = false;
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (tookMs >= 100) {
            LOG.info("RDP sound: opening the sound output for {} took {} ms", fmt.describe(), tookMs);
        }
        sink = s;
        sinkFormat = fmt;
        if (deviceFailed) {
            deviceFailed = false;
            onStatusChanged.run();
        }
        return true;
    }

    private void failed(long now) {
        retryAt = now + RETRY_NANOS;
        if (!deviceFailed) {
            deviceFailed = true;
            onStatusChanged.run();
        }
    }

    private void closeSink() {
        AudioSink s = sink;
        sink = null;
        sinkFormat = null;
        if (s != null) {
            s.close();
        }
    }
}
