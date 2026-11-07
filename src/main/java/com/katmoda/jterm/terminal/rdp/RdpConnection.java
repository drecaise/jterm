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
package com.katmoda.jterm.terminal.rdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * One RDP session: a {@code jterm-rdp-helper} process and the protocol spoken with it.
 *
 * <p>Threads: a reader thread decodes helper messages and hands them to the {@link Listener} (on
 * that thread — the listener marshals to the EDT as it needs); a second thread drains the helper's
 * stderr into the log and keeps a short tail for error reports. The helper's stderr must be piped
 * and drained, never inherited: a GUI-subsystem helper on Windows can't use an inherited console
 * handle, and an undrained pipe would eventually block it.</p>
 *
 * <p>Sending is thread-safe. A send to a helper that has gone away is silently dropped — the reader
 * thread notices the exit and reports it through {@link Listener#onClosed}.</p>
 */
public final class RdpConnection {

    private static final Logger LOG = LoggerFactory.getLogger(RdpConnection.class);
    private static final int STDERR_TAIL_LINES = 40;

    /** Callbacks, all on the connection's reader thread. */
    public interface Listener {

        /** Any message other than a certificate request. A {@code Frame}'s pixels are only valid during the call. */
        void onMessage(RdpProtocol.Inbound message);

        /**
         * The server certificate needs a decision; block until the user decides. Returning
         * {@code true} accepts it for this session (pinning is the listener's business).
         */
        boolean verifyCertificate(RdpProtocol.CertRequest request);

        /**
         * The helper is gone. {@code last} is its final {@code Disconnected} message, or {@code null}
         * if it died without one (crash, killed); {@code stderrTail} is its recent log output.
         */
        void onClosed(RdpProtocol.Disconnected last, String stderrTail);
    }

    /** What to connect to. {@code password} is copied into the CONNECT message and then zeroed. */
    public record Target(String host, int port, String user, String domain, char[] password,
                         String pins, int width, int height, int scalePercent) {
    }

    private final Process process;
    private final OutputStream out;
    private final Listener listener;
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private volatile boolean closing;

    private RdpConnection(Process process, Listener listener) {
        this.process = process;
        this.out = new BufferedOutputStream(process.getOutputStream());
        this.listener = listener;
    }

    /**
     * Starts the helper and sends HELLO + CONNECT. Returns at once; progress arrives through
     * {@code listener}.
     */
    public static RdpConnection start(Path helper, Target target, Listener listener) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(helper.toAbsolutePath().toString());
        pb.environment().putIfAbsent("WLOG_LEVEL", "WARN");
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process process = pb.start();
        RdpConnection conn = new RdpConnection(process, listener);
        conn.startStderrDrain();
        try {
            conn.send(RdpProtocol.hello());
            conn.send(RdpProtocol.connect(target.host(), target.port(), target.user(), target.domain(),
                    target.password(), target.pins(), target.width(), target.height(),
                    target.scalePercent()));
        } finally {
            if (target.password() != null) {
                Arrays.fill(target.password(), '\0');
            }
        }
        Thread reader = new Thread(conn::readLoop, "rdp-reader-" + process.pid());
        reader.setDaemon(true);
        reader.start();
        return conn;
    }

    // ------------------------------------------------------------------------------- sending

    public void sendKey(boolean down, int windowsVk, int flags) {
        send(RdpProtocol.key(down, windowsVk, flags));
    }

    public void sendUnicode(boolean down, char unit) {
        send(RdpProtocol.unicode(down, unit));
    }

    public void sendMouse(int flags, int x, int y) {
        send(RdpProtocol.mouse(flags, x, y));
    }

    /** Asks the server to re-lay out its desktop (Display Control); ignored by servers without it. */
    public void requestResize(int width, int height, int scalePercent) {
        send(RdpProtocol.resize(width, height, scalePercent));
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Ends the session without blocking the caller: says BYE, closes the helper's stdin (it exits on
     * EOF), and kills it if it hasn't gone after a few seconds.
     */
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        send(RdpProtocol.bye());
        try {
            out.close();
        } catch (IOException ignored) {
            // already gone
        }
        Thread reaper = new Thread(() -> {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    LOG.warn("RDP helper {} did not exit after BYE; killing it", process.pid());
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }, "rdp-reaper-" + process.pid());
        reaper.setDaemon(true);
        reaper.start();
    }

    private synchronized void send(byte[] message) {
        try {
            out.write(message);
            out.flush();
        } catch (IOException e) {
            LOG.debug("RDP helper input closed; dropping message", e);
        } finally {
            Arrays.fill(message, (byte) 0);
        }
    }

    // ------------------------------------------------------------------------------- receiving

    private void readLoop() {
        RdpProtocol.Disconnected last = null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(process.getInputStream(), 1 << 16))) {
            byte[] buf = new byte[1 << 20];
            while (true) {
                int len = Integer.reverseBytes(in.readInt());
                if (len < 1 || len > RdpProtocol.MAX_INBOUND) {
                    throw new IOException("RDP helper sent a malformed message (length " + len + ")");
                }
                int type = in.readUnsignedByte();
                int n = len - 1;
                if (n > buf.length) {
                    buf = new byte[Math.max(n, buf.length * 2)];
                }
                in.readFully(buf, 0, n);
                ByteBuffer body = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                RdpProtocol.Inbound message = RdpProtocol.decode(type, body);
                switch (message) {
                    case RdpProtocol.CertRequest req -> send(RdpProtocol.certReply(listener.verifyCertificate(req)));
                    case RdpProtocol.Disconnected d -> {
                        last = d;
                        listener.onMessage(d);
                    }
                    case RdpProtocol.HelloReply h -> {
                        LOG.info("RDP helper protocol v{}, FreeRDP {}", h.version(), h.freerdpVersion());
                        listener.onMessage(h);
                    }
                    default -> listener.onMessage(message);
                }
            }
        } catch (EOFException e) {
            // normal end: the helper exited after DISCONNECTED (or was killed)
        } catch (Exception e) {
            if (!closing) {
                LOG.warn("RDP helper connection failed", e);
            }
        }
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listener.onClosed(last, stderrTail());
    }

    private void startStderrDrain() {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    LOG.debug("[rdp-helper] {}", line);
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        while (stderrTail.size() > STDERR_TAIL_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // helper gone
            }
        }, "rdp-stderr-" + process.pid());
        t.setDaemon(true);
        t.start();
    }

    private String stderrTail() {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail);
        }
    }
}
