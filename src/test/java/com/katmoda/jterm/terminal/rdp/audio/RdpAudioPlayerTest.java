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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the play loop by hand ({@code step()}) with a manual clock and a recording sink. */
class RdpAudioPlayerTest {

    static final class FakeSink implements AudioSink {
        /** What {@link #queuedMillis()} reports; 0 = dry, the default. */
        volatile int queued;
        /** Throws from {@link #queuedMillis()} once: a failure no method declares. */
        boolean failQueuedOnce;

        @Override
        public int queuedMillis() {
            if (failQueuedOnce) {
                failQueuedOnce = false;
                throw new IllegalStateException("line gone");
            }
            return queued;
        }

        final List<PcmFormat> openFormats = new ArrayList<>();
        final List<Integer> openBufferMs = new ArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        int opens;
        int flushes;
        int closes;
        boolean failOpen;
        /** What Java Sound throws on a machine with no device at all (a macOS VM, measured). */
        boolean noLineAtAll;

        @Override
        public void open(PcmFormat format, int bufferMs) throws Exception {
            opens++;
            if (noLineAtAll) {
                throw new IllegalArgumentException("No line matching interface SourceDataLine is supported.");
            }
            if (failOpen) {
                throw new Exception("no device");
            }
            openFormats.add(format);
            openBufferMs.add(bufferMs);
        }

        @Override
        public void write(byte[] data, int off, int len) {
            written.write(data, off, len);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final long SEC = TimeUnit.SECONDS.toNanos(1);

    private FakeSink sink;
    private AtomicLong clock;
    private AtomicInteger statusCalls;
    private RdpAudioPlayer player;

    @BeforeEach
    void setUp() {
        sink = new FakeSink();
        clock = new AtomicLong(10 * SEC);
        statusCalls = new AtomicInteger();
        player = new RdpAudioPlayer(() -> sink, clock::get, statusCalls::incrementAndGet, false);
    }

    private static byte[] pattern(int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (i * 7 + 3);
        }
        return b;
    }

    /** 48 kHz stereo: 192 bytes per ms. */
    private static int stereoBytes(int ms) {
        return ms * 48 * 4;
    }

    private void drain() {
        for (int i = 0; i < 200 && player.status().queuedMs() > 0; i++) {
            player.step();
        }
        player.step();
    }

    @Test
    void badFormatLeavesNoStreamAndLaterDataIsDropped() {
        player.onOpen(44100, 6, 0);
        RdpAudioPlayer.Status s = player.status();
        assertNull(s.format());
        assertEquals(1, s.badMessages());
        player.onOpen(96000, 2, 0);
        assertEquals(2, player.status().badMessages());

        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        assertEquals(3, player.status().badMessages());
        drain();
        assertEquals(0, sink.written.size());
        assertEquals(0, sink.opens);
    }

    @Test
    void dataWithNoStreamIsDropped() {
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        assertEquals(1, player.status().badMessages());
        drain();
        assertEquals(0, sink.written.size());
    }

    @Test
    void dataNotWholeFramesIsDropped() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100) + 2]));
        assertEquals(1, player.status().badMessages());
        assertEquals(0, player.status().queuedMs());
    }

    @Test
    void dataLargerThanMaxChunkIsDropped() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[RdpAudioPlayer.MAX_CHUNK + 4]));
        assertEquals(1, player.status().badMessages());
        assertEquals(0, player.status().queuedMs());
        // exactly MAX_CHUNK is accepted
        player.onData(ByteBuffer.wrap(new byte[RdpAudioPlayer.MAX_CHUNK]));
        assertEquals(1, player.status().badMessages());
        assertTrue(player.status().queuedMs() > 0);
    }

    @Test
    void normalFlowWritesExactlyWhatWasOffered() {
        player.onOpen(48000, 2, 1);
        assertEquals(new PcmFormat(48000, 2), player.status().format());
        assertEquals(1, player.status().codec());
        byte[] data = pattern(stereoBytes(100));
        player.onData(ByteBuffer.wrap(data));
        assertEquals(100, player.status().queuedMs());

        player.step();
        assertEquals(1, sink.opens);
        assertEquals(new PcmFormat(48000, 2), sink.openFormats.get(0));
        assertEquals(JitterBuffer.LINE_BUFFER_MS, sink.openBufferMs.get(0));
        assertEquals(stereoBytes(RdpAudioPlayer.WRITE_MS), sink.written.size(), "one write chunk per step");

        byte[] more = pattern(stereoBytes(40));
        player.onData(ByteBuffer.wrap(more));
        drain();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(data);
        expected.writeBytes(more);
        assertArrayEquals(expected.toByteArray(), sink.written.toByteArray());
        assertEquals(1, sink.opens, "one open for the whole stream");
    }

    /** The real start policy: a large wave waits out the delay, giving margin before the next. */
    @Test
    void withTheStartDelayALargeWaveWaitsBeforePlaying() {
        player = new RdpAudioPlayer(() -> sink, clock::get, statusCalls::incrementAndGet, null, false,
                JitterBuffer.START_DELAY_MS, false);
        player.onOpen(44100, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[32768])); // one Windows 11 wave, ~186 ms
        player.step();
        assertEquals(0, sink.written.size(), "just arrived");
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(JitterBuffer.START_DELAY_MS));
        player.step();
        assertTrue(sink.written.size() > 0);
    }

    /** The output still holding audio means an empty queue is no underrun. */
    @Test
    void anEmptyQueueIsNoUnderrunWhileTheOutputStillPlays() {
        sink.queued = 50;
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(60)]));
        drain();
        player.step();
        assertEquals(0, player.status().underruns());
        sink.queued = 0;
        player.step();
        assertEquals(1, player.status().underruns());
    }

    @Test
    void belowStartThresholdNothingIsWrittenYet() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(30)]));
        player.step();
        assertEquals(0, sink.written.size());
    }

    @Test
    void slicedAndOffsetBuffersAreReadFromTheirPosition() {
        player.onOpen(48000, 2, 0);
        byte[] backing = pattern(stereoBytes(80) + 64);
        // slice: arrayOffset 16, position 0
        ByteBuffer slice = ByteBuffer.wrap(backing, 16, stereoBytes(80)).slice();
        byte[] expected = new byte[stereoBytes(80)];
        System.arraycopy(backing, 16, expected, 0, expected.length);
        player.onData(slice);
        drain();
        assertArrayEquals(expected, sink.written.toByteArray());

        // slice with a nonzero position as well
        sink.written.reset();
        ByteBuffer s2 = ByteBuffer.wrap(backing, 8, stereoBytes(80) + 40).slice();
        s2.position(32);
        byte[] expected2 = new byte[s2.remaining()];
        System.arraycopy(backing, 8 + 32, expected2, 0, expected2.length);
        player.onData(s2);
        assertEquals(32, s2.position(), "caller's buffer is not consumed");
        drain();
        assertArrayEquals(expected2, sink.written.toByteArray());
    }

    @Test
    void directBufferIsRead() {
        player.onOpen(48000, 2, 0);
        byte[] data = pattern(stereoBytes(80));
        ByteBuffer direct = ByteBuffer.allocateDirect(data.length + 8);
        direct.position(8);
        direct.put(data);
        direct.position(8);
        player.onData(direct);
        assertEquals(8, direct.position(), "caller's buffer is not consumed");
        drain();
        assertArrayEquals(data, sink.written.toByteArray());
    }

    @Test
    void volumeScalesLeftAndSilencesRight() {
        player.onOpen(48000, 2, 0);
        player.onVolume(0x8000, 0);
        int frames = 48 * 80;
        ByteBuffer data = ByteBuffer.allocate(frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            data.putShort((short) 10000).putShort((short) -10000);
        }
        player.onData(ByteBuffer.wrap(data.array()));
        drain();
        ByteBuffer out = ByteBuffer.wrap(sink.written.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(frames * 4, out.remaining());
        for (int i = 0; i < frames; i++) {
            int l = out.getShort();
            int r = out.getShort();
            assertTrue(Math.abs(l - 5000) <= 1, "left about half, was " + l);
            assertEquals(0, r);
        }
    }

    @Test
    void volumeIsClampedAndNeverAmplifies() {
        player.onOpen(48000, 2, 0);
        player.onVolume(0x1FFFF, -5);
        byte[] data = pattern(stereoBytes(80));
        player.onData(ByteBuffer.wrap(data));
        drain();
        byte[] out = sink.written.toByteArray();
        assertEquals(data.length, out.length);
        for (int i = 0; i < out.length; i += 4) {
            assertEquals(data[i], out[i]);
            assertEquals(data[i + 1], out[i + 1]);
            assertEquals(0, out[i + 2]);
            assertEquals(0, out[i + 3]);
        }
    }

    @Test
    void muteClearsQueueFlushesAndDiscardsUntilUnmuted() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.step(); // opens the sink, writes 20 ms
        assertEquals(1, sink.opens);
        int before = sink.written.size();
        int callsBefore = statusCalls.get();

        player.setMuted(true);
        assertTrue(player.isMuted());
        assertTrue(statusCalls.get() > callsBefore);
        assertEquals(0, player.status().queuedMs());
        assertEquals(0, sink.flushes, "flush happens on the play thread");
        player.step();
        assertEquals(1, sink.flushes);
        assertEquals(1, sink.closes, "a muted pane leaves the system mixer");
        player.step();
        assertEquals(1, sink.flushes, "one request, one flush");
        assertEquals(1, sink.closes);

        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        assertEquals(0, player.status().queuedMs());
        assertEquals(0, player.status().badMessages(), "muted data is silently discarded, not bad");
        drain();
        assertEquals(before, sink.written.size());

        player.setMuted(false);
        assertFalse(player.isMuted());
        byte[] data = pattern(stereoBytes(80));
        player.onData(ByteBuffer.wrap(data));
        drain();
        byte[] all = sink.written.toByteArray();
        byte[] tail = java.util.Arrays.copyOfRange(all, before, all.length);
        assertArrayEquals(data, tail);
    }

    @Test
    void dataArrivingWhileMutedDoesNotReopenTheOutput() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.step();
        player.setMuted(true);
        for (int i = 0; i < 10; i++) {
            player.onData(ByteBuffer.wrap(new byte[stereoBytes(20)]));
            clock.addAndGet(20 * SEC / 1000);
            player.step();
        }
        assertEquals(1, sink.opens);
        assertEquals(1, sink.closes);
    }

    @Test
    void anUnexpectedFailureInThePlayLoopClosesTheOutputAndRecovers() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.stepOrRecover(); // opens, writes
        assertEquals(1, sink.opens);

        sink.failQueuedOnce = true;
        long wait = player.stepOrRecover();
        assertTrue(wait > 0, "the loop goes on");
        assertEquals(1, sink.closes);
        assertTrue(player.status().deviceFailed());

        // Dropped, not saved up, until the retry time; then it plays again.
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.stepOrRecover();
        assertEquals(1, sink.opens);
        clock.addAndGet(RdpAudioPlayer.RETRY_NANOS);
        int before = sink.written.size();
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.stepOrRecover();
        assertEquals(2, sink.opens);
        assertFalse(player.status().deviceFailed());
        assertTrue(sink.written.size() > before);
    }

    @Test
    void theMeasuredDelayIsReportedWhenItMovesAndNotOtherwise() {
        List<Integer> delays = new ArrayList<>();
        player = new RdpAudioPlayer(() -> sink, clock::get, statusCalls::incrementAndGet, delays::add, false,
                0, true);
        player.onOpen(48000, 2, 0);
        sink.queued = 70; // what the output holds, unheard
        long tick = 20 * SEC / 1000;
        for (int i = 0; i < 400; i++) { // 8 s of steady 20 ms pieces
            player.onData(ByteBuffer.wrap(pattern(stereoBytes(20))));
            player.step();
            clock.addAndGet(tick);
        }
        assertFalse(delays.isEmpty(), "reported once the first window closed");
        assertEquals(player.status().lowestMs(), (long) delays.get(delays.size() - 1), RdpAudioPlayer.DELAY_STEP_MS - 1);
        assertTrue(delays.get(0) >= 70, "the output's share counts: " + delays);
        assertTrue(delays.size() <= 4, "a steady stream is not reported every second: " + delays);
        for (int i = 1; i < delays.size(); i++) {
            assertTrue(Math.abs(delays.get(i) - delays.get(i - 1)) >= RdpAudioPlayer.DELAY_STEP_MS, delays.toString());
        }
    }

    @Test
    void aMachineWithNoAudioDeviceAtAllIsNoSoundDevice() {
        sink.noLineAtAll = true;
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.step();
        assertTrue(player.status().deviceFailed());
        assertEquals(0, player.status().queuedMs());
    }

    @Test
    void deviceFailureDropsDataRetriesAfterTwoSecondsAndRecovers() {
        sink.failOpen = true;
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        int calls = statusCalls.get();
        player.step();
        assertEquals(1, sink.opens);
        assertTrue(player.status().deviceFailed());
        assertEquals(0, player.status().queuedMs(), "queued data dropped");
        assertTrue(statusCalls.get() > calls, "status change reported");
        assertEquals(1, sink.closes, "failed sink released");

        // within the retry window: no new open, data dropped
        clock.addAndGet(RdpAudioPlayer.RETRY_NANOS - 1);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.step();
        assertEquals(1, sink.opens);
        assertEquals(0, player.status().queuedMs());

        // past it: retried, still failing
        clock.addAndGet(2);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(100)]));
        player.step();
        assertEquals(2, sink.opens);
        assertTrue(player.status().deviceFailed());

        // device comes back
        clock.addAndGet(RdpAudioPlayer.RETRY_NANOS + 1);
        sink.failOpen = false;
        calls = statusCalls.get();
        byte[] data = pattern(stereoBytes(80));
        player.onData(ByteBuffer.wrap(data));
        player.step();
        assertEquals(3, sink.opens);
        assertFalse(player.status().deviceFailed());
        assertTrue(statusCalls.get() > calls, "recovery reported");
        drain();
        assertArrayEquals(data, sink.written.toByteArray());
    }

    @Test
    void sinkClosesWhenIdleAndReopensOnNewData() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(pattern(stereoBytes(80))));
        drain();
        assertEquals(1, sink.opens);
        assertEquals(0, sink.closes);

        clock.addAndGet(RdpAudioPlayer.IDLE_NANOS - SEC / 2);
        player.step();
        assertEquals(0, sink.closes, "not idle yet");

        clock.addAndGet(SEC);
        player.step();
        assertEquals(1, sink.closes);

        player.onData(ByteBuffer.wrap(pattern(stereoBytes(80))));
        drain();
        assertEquals(2, sink.opens);
    }

    @Test
    void formatChangeClosesAndReopensTheSink() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(80)]));
        drain();
        assertEquals(1, sink.opens);

        player.onOpen(16000, 1, 0);
        assertEquals(new PcmFormat(16000, 1), player.status().format());
        byte[] data = pattern(16 * 2 * 100);
        player.onData(ByteBuffer.wrap(data));
        int before = sink.written.size();
        player.step();
        assertEquals(1, sink.closes);
        assertEquals(2, sink.opens);
        assertEquals(new PcmFormat(16000, 1), sink.openFormats.get(1));
        drain();
        byte[] all = sink.written.toByteArray();
        assertArrayEquals(data, java.util.Arrays.copyOfRange(all, before, all.length));
    }

    @Test
    void sameFormatReopenKeepsTheSinkAndQueue() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(80)]));
        player.step();
        player.onOpen(48000, 2, 0);
        drain();
        assertEquals(1, sink.opens);
        assertEquals(0, sink.closes);
        assertEquals(stereoBytes(80), sink.written.size());
    }

    @Test
    void closeEndsTheLoopAndReleasesTheSink() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(80)]));
        player.step();
        assertEquals(0, sink.closes);
        player.close();
        assertEquals(1, sink.closes);
        assertTrue(player.step() < 0);
        player.close(); // idempotent
        int bad = player.status().badMessages() == 0 ? 0 : -1;
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(80)]));
        player.onOpen(48000, 2, 0);
        assertEquals(bad == 0 ? 0 : -1, player.status().badMessages());
        assertTrue(player.step() < 0);
        assertEquals(1, sink.opens);
    }

    @Test
    void dataAfterAudioCloseIsDropped() {
        player.onOpen(48000, 2, 0);
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(80)]));
        player.onClose();
        assertNull(player.status().format());
        player.onData(ByteBuffer.wrap(new byte[stereoBytes(10)]));
        assertEquals(1, player.status().badMessages());
        assertEquals(80, player.status().queuedMs(), "what was queued still plays out");
        drain();
        assertEquals(stereoBytes(80), sink.written.size());
    }
}
