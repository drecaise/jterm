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

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sends this computer's microphone to one RDP connection attempt, or silence when it may not be
 * heard. Swing-free; the decisions are a {@link MicGate}'s, which this class guards.
 *
 * <p>Threads: {@link #onOpen}/{@link #onClose} run on the connection's reader thread and only
 * update state; the pane's calls ({@link #answer}, {@link #setAllowed}, {@link #setMuted}) on the
 * EDT. Everything that blocks (opening the capture line, reading it, sending) runs on this object's
 * own thread, {@code rdp-audio-in}, started with the first stream.</p>
 *
 * <p>Sharp edges:</p>
 * <ul>
 *   <li><b>The capture line is closed, not merely ignored,</b> whenever the gate says the microphone
 *   may not be heard (A1), so the OS's indicator goes off. A packet whose capture ended after the
 *   user muted or blocked is dropped, never sent.</li>
 *   <li><b>Silence, not nothing.</b> A remote program that opened a capture stream may stall
 *   without data, so while a stream is open and the microphone may not be heard, zero-filled
 *   packets go out paced by the clock.</li>
 *   <li><b>Late audio is dropped.</b> Sending blocks while the helper's pipe is full; when it
 *   returns with more than {@link #LATE_MS} captured meanwhile, that backlog is flushed rather than
 *   sent late.</li>
 * </ul>
 */
public final class RdpMicrophone implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RdpMicrophone.class);

    /** Where packets go: {@code RdpConnection.sendMicData}. May block; must not throw. */
    public interface Sender {
        void send(int streamId, byte[] data, int off, int len);
    }

    /** What the pane shows on the microphone button and the tab. */
    public record Status(MicGate.Consent consent, boolean streamOpen, boolean capturing, boolean muted,
                         boolean deviceFailed, PcmFormat format) {
    }

    /** Captured audio older than this when a send returns is flushed instead of sent. */
    static final int LATE_MS = 200;
    /** The capture line's buffer: comfortably more than {@link #LATE_MS}, so a backlog can be seen. */
    static final int LINE_BUFFER_MS = 500;
    /** Packet length when the server's frames-per-packet is outside 1..rate/5. */
    static final int DEFAULT_PACKET_MS = 20;
    /** A microphone that failed to open is retried at most this often; silence goes out meanwhile. */
    static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long IDLE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private record Stream(int id, PcmFormat format, int packetBytes, long packetNanos) {
    }

    private final Supplier<AudioSource> sources;
    private final LongSupplier clock;
    private final Sender sender;
    private final Runnable onAsk;
    private final Runnable onStatusChanged;
    private final boolean startThread;
    private final Object lock = new Object();

    // Guarded by lock.
    private final MicGate gate;
    private Stream stream;
    private boolean closed;
    private long refusedOpens;
    private Thread thread;

    // The capture thread's own.
    private AudioSource source;
    private PcmFormat sourceFormat;
    private long retryAt;
    private long stepFailures;
    private Stream pacedStream;   // the stream the silence clock below belongs to
    private long nextSilenceAt;
    private byte[] buf = new byte[0];
    private byte[] zeros = new byte[0];
    private volatile boolean capturing;
    private volatile boolean deviceFailed;
    private volatile long lateFlushes;

    /**
     * @param onAsk           called (on the reader thread) when the user must be asked, once per
     *                        connection at most; the answer comes back through {@link #answer}
     * @param onStatusChanged called from any thread when {@link #status()} changed
     * @param muted           whether the pane's microphone is muted at the start
     */
    public RdpMicrophone(Sender sender, Runnable onAsk, Runnable onStatusChanged, boolean muted) {
        this(JavaSoundSource::new, System::nanoTime, sender, onAsk, onStatusChanged, muted, true);
    }

    /** {@code startThread == false} leaves the capture loop to the caller's {@link #step} (tests). */
    RdpMicrophone(Supplier<AudioSource> sources, LongSupplier clock, Sender sender, Runnable onAsk,
                  Runnable onStatusChanged, boolean muted, boolean startThread) {
        this.sources = Objects.requireNonNull(sources);
        this.clock = Objects.requireNonNull(clock);
        this.sender = Objects.requireNonNull(sender);
        this.onAsk = onAsk != null ? onAsk : () -> { };
        this.onStatusChanged = onStatusChanged != null ? onStatusChanged : () -> { };
        this.startThread = startThread;
        this.gate = new MicGate(muted);
    }

    /** Frames per packet the way the design bounds it: 1..rate/5 (200 ms), else 20 ms. */
    static int packetFrames(PcmFormat f, long framesPerPacket) {
        if (framesPerPacket >= 1 && framesPerPacket <= f.sampleRate() / 5) {
            return (int) framesPerPacket;
        }
        return f.sampleRate() * DEFAULT_PACKET_MS / 1000;
    }

    // --------------------------------------------------------------------- reader thread side

    /**
     * MIC_OPEN. A format outside the list is ignored: no stream, nothing sent, not even silence (and
     * a stream that was open is over, since the helper has replaced it).
     */
    public void onOpen(int streamId, int sampleRate, int channels, long framesPerPacket) {
        PcmFormat f = PcmFormat.of(sampleRate, channels).orElse(null);
        boolean ask = false;
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (f == null) {
                refusedOpens++;
                stream = null;
                gate.streamClosed();
                LOG.debug("RDP microphone: refused format {} Hz, {} channels", sampleRate, channels);
            } else {
                int frames = packetFrames(f, framesPerPacket);
                stream = new Stream(streamId, f, frames * f.frameBytes(),
                        TimeUnit.SECONDS.toNanos(frames) / f.sampleRate());
                ask = gate.streamOpened();
                ensureThread();
            }
            lock.notifyAll();
        }
        onStatusChanged.run();
        if (ask) {
            onAsk.run();
        }
    }

    /** MIC_CLOSE. Only the open stream's id counts. */
    public void onClose(int streamId) {
        synchronized (lock) {
            if (stream == null || stream.id() != streamId) {
                return;
            }
            stream = null;
            gate.streamClosed();
            lock.notifyAll();
        }
        onStatusChanged.run();
    }

    // ------------------------------------------------------------------------------ the pane

    /** The user's answer to the question {@code onAsk} asked for. */
    public void answer(boolean allow) {
        synchronized (lock) {
            gate.answer(allow);
            lock.notifyAll();
        }
        onStatusChanged.run();
    }

    /** The microphone button's Allow / Block: a user action, no question. */
    public void setAllowed(boolean allow) {
        synchronized (lock) {
            gate.setAllowed(allow);
            lock.notifyAll();
        }
        onStatusChanged.run();
    }

    public void setMuted(boolean muted) {
        synchronized (lock) {
            gate.setMuted(muted);
            lock.notifyAll();
        }
        onStatusChanged.run();
    }

    public Status status() {
        synchronized (lock) {
            return new Status(gate.consent(), gate.isStreamOpen(), capturing, gate.isMuted(), deviceFailed,
                    stream != null ? stream.format() : null);
        }
    }

    /** Captured backlogs flushed because sending fell behind (for tests and diagnostics). */
    long lateFlushes() {
        return lateFlushes;
    }

    /** Stops the capture thread, which closes the line. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            stream = null;
            gate.streamClosed();
            lock.notifyAll();
            if (thread == null) {
                closeSource();
            }
        }
    }

    // ------------------------------------------------------------------------ capture thread

    private void ensureThread() {
        if (thread != null || !startThread) {
            return;
        }
        thread = new Thread(this::run, "rdp-audio-in");
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
                        // Nanosecond precision matters for pacing: a 20 ms packet waited as 19 or
                        // 21 ms drifts, and the clock-based schedule in step() corrects the rest.
                        TimeUnit.NANOSECONDS.timedWait(lock, wait);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSource();
        }
    }

    /**
     * {@link #step}, surviving an unexpected failure in it. The thread must not end on one: nothing
     * would start another before the server's next stream, and until then not even silence would go
     * out. So the capture line is closed (it must never stay open behind a loop that is not sending,
     * A1) and treated as a microphone that failed: silence, and another try after
     * {@link #RETRY_NANOS}. Closing this object still ends the loop the usual way.
     */
    long stepOrRecover() {
        try {
            return step();
        } catch (RuntimeException e) {
            if (stepFailures++ == 0) {
                LOG.warn("RDP microphone: the capture loop failed; trying again", e);
            } else {
                LOG.debug("RDP microphone: the capture loop failed again", e);
            }
            try {
                closeSource();
            } catch (RuntimeException closing) {
                LOG.debug("RDP microphone: closing the line failed", closing);
            }
            failed(clock.getAsLong());
            return TimeUnit.MILLISECONDS.toNanos(DEFAULT_PACKET_MS);
        }
    }

    /**
     * One round of the capture loop: open or close the line as the gate says, then send one packet
     * of the microphone or of silence. Returns how long to wait before the next round (0 = at once,
     * negative = closed). Never holds the lock while touching the line or sending.
     */
    long step() {
        long now = clock.getAsLong();
        Stream s;
        boolean capture;
        synchronized (lock) {
            if (closed) {
                return -1;
            }
            s = stream;
            capture = gate.mayCapture();
        }
        if (s == null) {
            closeSource();
            pacedStream = null;
            return IDLE_WAIT_NANOS;
        }
        if (!capture || (source != null && !s.format().equals(sourceFormat))) {
            closeSource();
        }
        if (capture && source == null && (!deviceFailed || now - retryAt >= 0)) {
            openSource(s.format(), now);
        }
        if (buf.length < s.packetBytes()) {
            buf = new byte[s.packetBytes()];
            zeros = new byte[s.packetBytes()];
        }
        if (source != null) {
            return sendCaptured(s, now);
        }
        return sendSilence(s, now);
    }

    private long sendCaptured(Stream s, long now) {
        int n;
        try {
            n = source.read(buf, 0, s.packetBytes());
        } catch (Exception e) {
            LOG.debug("RDP microphone capture failed", e);
            closeSource();
            failed(now);
            return 0;
        }
        n = s.format().wholeFrames(n);
        boolean still;
        synchronized (lock) {
            still = !closed && stream == s && gate.mayCapture();
        }
        if (!still) {
            // Muted, blocked or replaced while the read was blocked: what it captured is not sent.
            // The next round closes the line.
            return 0;
        }
        if (n > 0) {
            sender.send(s.id(), buf, 0, n);
        }
        // A send that blocked (a stalled helper) left a backlog in the line: drop it, never send late.
        AudioSource src = source;
        if (src != null && src.available() > s.format().bytesForMillis(LATE_MS)) {
            src.flush();
            lateFlushes++;
        }
        // Should the line close now, silence continues from here without a burst or a gap.
        pacedStream = s;
        nextSilenceAt = clock.getAsLong();
        return 0;
    }

    private long sendSilence(Stream s, long now) {
        if (pacedStream != s) {
            pacedStream = s;
            nextSilenceAt = now;
        }
        long wait = nextSilenceAt - now;
        if (wait > 0) {
            return wait;
        }
        sender.send(s.id(), zeros, 0, s.packetBytes());
        nextSilenceAt += s.packetNanos();
        long after = clock.getAsLong();
        if (after - nextSilenceAt > TimeUnit.MILLISECONDS.toNanos(LATE_MS)) {
            // Far behind (sending blocked): start again from now rather than send a burst.
            nextSilenceAt = after + s.packetNanos();
        }
        return Math.max(0, nextSilenceAt - after);
    }

    private void openSource(PcmFormat fmt, long now) {
        AudioSource src = sources.get();
        try {
            src.open(fmt, LINE_BUFFER_MS);
        } catch (Exception | LinkageError e) {
            src.close();
            if (!deviceFailed) {
                LOG.info("RDP microphone: could not open the microphone for {} ({})", fmt.describe(), e.toString());
            }
            failed(now);
            return;
        }
        source = src;
        sourceFormat = fmt;
        boolean changed = !capturing || deviceFailed;
        capturing = true;
        deviceFailed = false;
        if (changed) {
            onStatusChanged.run();
        }
    }

    private void failed(long now) {
        retryAt = now + RETRY_NANOS;
        if (!deviceFailed) {
            deviceFailed = true;
            onStatusChanged.run();
        }
    }

    private void closeSource() {
        AudioSource src = source;
        source = null;
        sourceFormat = null;
        if (src != null) {
            src.close();
        }
        if (capturing) {
            capturing = false;
            onStatusChanged.run();
        }
    }
}
