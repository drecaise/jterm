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

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * macOS Local Network privacy, before an RDP connect.
 *
 * <p>Since macOS 15 the first connection an app makes to an address on the local network shows
 * "allow jterm to find devices on your local network?", and the connection fails with
 * {@code EHOSTUNREACH} while that prompt is open, and for as long as access is denied. The helper
 * counts as jterm (macOS attributes a child process to the app that started it), so its first
 * connect failed before the user could answer, and they had to press Reconnect.</p>
 *
 * <p>So on macOS jterm makes a plain TCP connection to a local destination first, from the JVM,
 * and waits while it keeps getting "no route to host": that brings the prompt up from jterm itself,
 * and the helper starts only once the user has answered. Any other result (connected, refused,
 * timed out) means the network is not what blocks the connection, and the helper takes over and
 * reports whatever is really wrong. One success is enough for the life of the process: access is
 * granted per app, not per host.</p>
 *
 * <p>Swing-free and blocking: run it off the EDT.</p>
 */
public final class LocalNetworkAccess {

    /** How the check ended. */
    public enum Outcome {
        /** Go ahead and connect: access is granted, or not needed, or something else is wrong. */
        PROCEED,
        /** Still "no route to host" at the deadline: access is denied (or the prompt unanswered). */
        BLOCKED,
        /** The caller cancelled (pane closed, newer attempt). */
        CANCELLED
    }

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final long PAUSE_MS = 1000;

    /** After one denial, later checks wait only this long: there is no prompt left to answer. */
    static final Duration AFTER_DENIAL = Duration.ofSeconds(5);

    private static volatile boolean granted;
    private static volatile boolean deniedBefore;

    private LocalNetworkAccess() {
    }

    /** Whether a check is worth making in this process at all: macOS, and not yet granted. */
    public static boolean needed(boolean macOS) {
        return macOS && !granted;
    }

    /**
     * Blocks until local network access is known not to stand in the way of {@code host}:{@code port}.
     * {@code onWaiting} runs (on this thread) the first time a connection is refused by the
     * privacy check, so the caller can explain the prompt; {@code cancelled} is polled between tries.
     * Waits up to {@code max} the first time; after a denial in this process, only
     * {@link #AFTER_DENIAL}, since no prompt is coming.
     */
    public static Outcome await(String host, int port, Duration max, Runnable onWaiting,
                                BooleanSupplier cancelled) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Outcome.PROCEED; // the helper reports the name error
        }
        InetAddress local = null;
        for (InetAddress a : addresses) {
            if (isLocal(a)) {
                local = a;
                break;
            }
        }
        if (local == null) {
            return Outcome.PROCEED; // the privacy check covers only the local network
        }
        Duration wait = deniedBefore && AFTER_DENIAL.compareTo(max) < 0 ? AFTER_DENIAL : max;
        long deadline = System.nanoTime() + wait.toNanos();
        boolean announced = false;
        while (true) {
            if (cancelled.getAsBoolean()) {
                return Outcome.CANCELLED;
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(local, port), CONNECT_TIMEOUT_MS);
                granted = true;
                return Outcome.PROCEED;
            } catch (NoRouteToHostException e) {
                if (!announced) {
                    announced = true;
                    onWaiting.run();
                }
                if (System.nanoTime() - deadline >= 0) {
                    deniedBefore = true;
                    return Outcome.BLOCKED;
                }
            } catch (java.net.ConnectException e) {
                granted = true; // refused: the packet reached the host, so the network is open
                return Outcome.PROCEED;
            } catch (IOException e) {
                return Outcome.PROCEED; // timed out or similar: not the privacy check's doing
            }
            try {
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.CANCELLED;
            }
        }
    }

    /**
     * Whether {@code a} is on the local network as far as the privacy check is concerned: private
     * IPv4 ranges, link-local, and IPv6 unique-local addresses. Loopback is exempt.
     */
    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress()) {
            return false;
        }
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
            return true;
        }
        // fc00::/7, which Java's isSiteLocalAddress (the deprecated fec0::/10) doesn't cover.
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
    }
}
