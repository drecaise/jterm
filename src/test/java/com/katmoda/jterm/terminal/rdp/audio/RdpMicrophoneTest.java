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

/** Drives the capture loop by hand ({@code step()}) with a manual clock and a fake microphone. */
class RdpMicrophoneTest {

    /** A microphone that "captures" a byte pattern instantly, advancing the clock by what it read. */
    final class FakeSource implements AudioSource {
        PcmFormat format;
        int opens;
        int closes;
        int flushes;
        boolean open;
        boolean failOpen;
        /** What Java Sound throws on a machine with no device at all (a macOS VM, measured). */
        boolean noLineAtAll;
        int available;
        /** Throws from {@link #available()} once: a failure no method declares. */
        boolean failAvailableOnce;
        Runnable duringRead;
        byte next = 1;

        @Override
        public void open(PcmFormat format, int bufferMs) throws Exception {
            opens++;
            if (noLineAtAll) {
                throw new IllegalArgumentException("No line matching interface TargetDataLine is supported.");
            }
            if (failOpen) {
                throw new Exception("no microphone");
            }
            this.format = format;
            open = true;
        }

        @Override
        public int read(byte[] data, int off, int len) {
            if (duringRead != null) {
                duringRead.run();
            }
            for (int i = 0; i < len; i++) {
                data[off + i] = next;
            }
            next++;
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(format.millisForBytes(len)));
            return len;
        }

        @Override
        public int available() {
            if (failAvailableOnce) {
                failAvailableOnce = false;
                throw new IllegalStateException("line gone");
            }
            return available;
        }

        @Override
        public void flush() {
            flushes++;
            available = 0;
        }

        @Override
        public void close() {
            closes++;
            open = false;
        }
    }

    record Sent(int streamId, byte[] data) {
        boolean silent() {
            for (byte b : data) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    private AtomicLong clock;
    private FakeSource source;
    private List<Sent> sent;
    private AtomicInteger asks;
    private RdpMicrophone mic;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        source = new FakeSource();
        sent = new ArrayList<>();
        asks = new AtomicInteger();
        mic = newMic(false);
    }

    private RdpMicrophone newMic(boolean muted) {
        return new RdpMicrophone(() -> source, clock::get,
                (id, data, off, len) -> {
                    byte[] copy = new byte[len];
                    System.arraycopy(data, off, copy, 0, len);
                    sent.add(new Sent(id, copy));
                }, asks::incrementAndGet, null, muted, false);
    }

    /** Runs the loop for {@code ms} of clock time, honouring the waits it asks for. */
    private void run(int ms) {
        long end = clock.get() + ms * MS;
        for (int i = 0; i < 10_000 && clock.get() < end; i++) {
            long wait = mic.step();
            if (wait < 0) {
                return;
            }
            if (wait > 0) {
                clock.addAndGet(Math.min(wait, end - clock.get()));
            }
        }
    }

    @Test
    void anUnlistedFormatIsIgnoredEntirely() {
        mic.onOpen(1, 96000, 2, 960);
        mic.onOpen(2, 44100, 6, 441);
        run(200);
        assertTrue(sent.isEmpty(), "not even silence");
        assertEquals(0, asks.get());
        assertEquals(0, source.opens);
        assertNull(mic.status().format());
    }

    @Test
    void theFirstStreamAsksOnceAndSilenceIsPacedMeanwhile() {
        mic.onOpen(7, 16000, 1, 320); // 20 ms packets of 640 bytes
        assertEquals(1, asks.get());
        run(100);
        assertEquals(0, source.opens, "never opened before an answer");
        assertEquals(5, sent.size(), "one packet per 20 ms");
        for (Sent s : sent) {
            assertEquals(7, s.streamId());
            assertEquals(640, s.data().length);
            assertTrue(s.silent());
        }
        mic.onClose(7);
        mic.onOpen(8, 16000, 1, 320);
        assertEquals(1, asks.get(), "a second stream in the same connection does not ask");
    }

    @Test
    void allowedCapturesAndSendsTheMicrophone() {
        mic.onOpen(3, 48000, 2, 960);
        mic.answer(true);
        run(60);
        assertEquals(1, source.opens);
        assertEquals(new PcmFormat(48000, 2), source.format);
        assertTrue(mic.status().capturing());
        assertFalse(sent.isEmpty());
        assertEquals(960 * 4, sent.get(0).data().length);
        assertFalse(sent.get(0).silent());
        assertEquals(3, sent.get(0).streamId());
    }

    @Test
    void refusedSendsSilenceAndNeverOpensTheLine() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(false);
        run(200);
        assertEquals(0, source.opens);
        assertFalse(sent.isEmpty());
        assertTrue(sent.stream().allMatch(Sent::silent));
        assertFalse(mic.status().capturing());
    }

    @Test
    void muteClosesTheLineAndUnmuteReopensIt() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        run(40);
        assertTrue(source.open);
        mic.setMuted(true);
        sent.clear();
        run(100);
        assertFalse(source.open, "closed, not merely ignored: the OS indicator goes off");
        assertFalse(mic.status().capturing());
        assertFalse(sent.isEmpty());
        assertTrue(sent.stream().allMatch(Sent::silent));
        mic.setMuted(false);
        sent.clear();
        run(40);
        assertTrue(source.open);
        assertEquals(2, source.opens);
        assertFalse(sent.get(sent.size() - 1).silent());
    }

    @Test
    void blockFromTheButtonClosesTheLineAndAllowReopensIt() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(false);
        mic.setAllowed(true);
        run(40);
        assertTrue(source.open);
        mic.setAllowed(false);
        run(40);
        assertFalse(source.open);
    }

    @Test
    void aPacketCapturedWhileTheUserMutedIsNotSent() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        source.duringRead = () -> mic.setMuted(true);
        mic.step();
        assertTrue(sent.isEmpty(), "captured before the mute took effect, still never sent");
        source.duringRead = null;
        mic.step();
        assertFalse(source.open);
    }

    @Test
    void streamCloseClosesTheLineAndStopsSending() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        run(40);
        mic.onClose(99); // not the open stream
        assertTrue(mic.status().streamOpen());
        mic.onClose(1);
        sent.clear();
        run(200);
        assertFalse(source.open);
        assertTrue(sent.isEmpty());
        assertFalse(mic.status().streamOpen());
    }

    @Test
    void aBacklogAfterASlowSendIsFlushedNotSentLate() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        source.available = (int) new PcmFormat(16000, 1).bytesForMillis(150);
        mic.step();
        assertEquals(0, source.flushes, "150 ms is not late yet");
        source.available = (int) new PcmFormat(16000, 1).bytesForMillis(RdpMicrophone.LATE_MS + 50);
        mic.step();
        assertEquals(1, source.flushes);
        assertEquals(1, mic.lateFlushes());
    }

    @Test
    void noMicrophoneSendsSilenceAndRetriesEveryTwoSeconds() {
        source.failOpen = true;
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        run(1000);
        assertEquals(1, source.opens);
        assertTrue(mic.status().deviceFailed());
        assertFalse(sent.isEmpty());
        assertTrue(sent.stream().allMatch(Sent::silent), "the remote program keeps getting data");
        run(1100);
        assertEquals(2, source.opens);
        source.failOpen = false;
        run(2100);
        assertTrue(source.open);
        assertFalse(mic.status().deviceFailed());
    }

    @Test
    void aMachineWithNoAudioDeviceAtAllSendsSilence() {
        source.noLineAtAll = true;
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        run(200);
        assertTrue(mic.status().deviceFailed());
        assertFalse(sent.isEmpty());
        assertTrue(sent.stream().allMatch(Sent::silent));
    }

    @Test
    void anOutOfRangePacketSizeBecomesTwentyMilliseconds() {
        assertEquals(320, RdpMicrophone.packetFrames(new PcmFormat(16000, 1), 0));
        assertEquals(320, RdpMicrophone.packetFrames(new PcmFormat(16000, 1), 3201));
        assertEquals(3200, RdpMicrophone.packetFrames(new PcmFormat(16000, 1), 3200));
        assertEquals(1, RdpMicrophone.packetFrames(new PcmFormat(16000, 1), 1));
        assertEquals(882, RdpMicrophone.packetFrames(new PcmFormat(44100, 2), 0xFFFFFFFFL));
    }

    @Test
    void aNewFormatReopensTheLine() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        run(40);
        mic.onClose(1);
        mic.onOpen(2, 44100, 2, 441);
        run(40);
        assertEquals(2, source.opens);
        assertEquals(new PcmFormat(44100, 2), source.format);
        assertEquals(2, sent.get(sent.size() - 1).streamId());
    }

    @Test
    void mutedFromTheStartAsksButNeverCaptures() {
        mic = newMic(true);
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        run(100);
        assertEquals(0, source.opens);
        assertTrue(sent.stream().allMatch(Sent::silent));
    }

    @Test
    void anUnexpectedFailureInTheCaptureLoopClosesTheLineAndRecovers() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        mic.stepOrRecover();
        assertTrue(source.open);

        source.failAvailableOnce = true;
        long wait = mic.stepOrRecover();
        assertTrue(wait > 0, "the loop goes on");
        assertFalse(source.open, "never left open behind a loop that failed");
        assertTrue(mic.status().deviceFailed());
        assertFalse(mic.status().capturing());

        // Silence meanwhile, and the microphone again after the retry time.
        sent.clear();
        run(100);
        assertFalse(sent.isEmpty());
        assertTrue(sent.stream().allMatch(Sent::silent));
        assertEquals(1, source.opens);
        clock.addAndGet(RdpMicrophone.RETRY_NANOS);
        sent.clear();
        mic.stepOrRecover();
        assertEquals(2, source.opens);
        assertFalse(mic.status().deviceFailed());
        assertFalse(sent.get(sent.size() - 1).silent());
    }

    @Test
    void closeStopsTheLoopAndClosesTheLine() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(true);
        run(40);
        mic.close();
        assertEquals(-1, mic.step());
        mic.onOpen(2, 16000, 1, 320);
        assertEquals(-1, mic.step(), "nothing reopens after close");
    }

    @Test
    void silenceDoesNotBurstAfterAStall() {
        mic.onOpen(1, 16000, 1, 320);
        mic.answer(false);
        mic.step();
        clock.addAndGet(1000 * MS); // the send blocked for a second
        sent.clear();
        mic.step();
        mic.step();
        assertEquals(1, sent.size(), "one packet, then back on schedule");
    }

    @Test
    void capturedBytesArriveUnchanged() {
        mic.onOpen(1, 8000, 1, 160);
        mic.answer(true);
        mic.step();
        mic.step();
        byte[] one = new byte[320];
        java.util.Arrays.fill(one, (byte) 1);
        byte[] two = new byte[320];
        java.util.Arrays.fill(two, (byte) 2);
        assertArrayEquals(one, sent.get(0).data());
        assertArrayEquals(two, sent.get(1).data());
    }
}
