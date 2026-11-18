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
package com.katmoda.jterm.terminal.ssh;

import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A local port forward that owns its SSH connection: {@code 127.0.0.1:<ephemeral>} on this machine
 * to {@code destHost:destPort} as seen from the SSH server. Used to reach an RDP host through a
 * saved SSH session (and so through that session's own jump hosts).
 *
 * <p>The listener binds to loopback only, on a port the OS picks, so nothing off this machine can
 * use it. It lives as long as the RDP connection does, because FreeRDP opens more than one TCP
 * connection during security negotiation and on reconnect. {@link #close()} stops the forward and
 * closes the connection; it is idempotent and safe from any thread.</p>
 */
public final class LocalForward implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalForward.class);
    private static final String LOOPBACK = "127.0.0.1";

    private final SshConnect.Connected connection;
    private final SshdSocketAddress bound;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LocalForward(SshConnect.Connected connection, SshdSocketAddress bound) {
        this.connection = connection;
        this.bound = bound;
    }

    /**
     * Opens the forward on an authenticated {@code connection}, which the returned object then
     * owns. On failure the connection is closed and the exception rethrown, so the caller never
     * holds a half-open connection.
     */
    public static LocalForward open(SshConnect.Connected connection, String destHost, int destPort)
            throws IOException {
        try {
            SshdSocketAddress bound = connection.session().startLocalPortForwarding(
                    new SshdSocketAddress(LOOPBACK, 0), new SshdSocketAddress(destHost, destPort));
            return new LocalForward(connection, bound);
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** The loopback address to connect to: always {@code 127.0.0.1}. */
    public String localHost() {
        return LOOPBACK;
    }

    /** The port the forward listens on. */
    public int localPort() {
        return bound.getPort();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            connection.session().stopLocalPortForwarding(bound);
        } catch (Exception e) {
            // Closing the connection below tears the forward down regardless.
            LOG.debug("could not stop the local forward on port {}", bound.getPort(), e);
        }
        connection.close();
    }
}
