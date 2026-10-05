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
package com.katmoda.jterm.ui.grid;

import com.katmoda.jterm.terminal.TerminalSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingOpensTest {

    private final AtomicInteger abandoned = new AtomicInteger();
    private final List<TerminalSession> placed = new ArrayList<>();
    private boolean hasContent;
    private final PendingOpens opens = new PendingOpens(() -> hasContent, abandoned::incrementAndGet);

    /** A session that records only whether it was closed. */
    private static final class Probe {
        final AtomicInteger closes = new AtomicInteger();
        final TerminalSession session = (TerminalSession) Proxy.newProxyInstance(
                TerminalSession.class.getClassLoader(), new Class<?>[]{TerminalSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closes.incrementAndGet();
                    }
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                });
    }

    private void place(TerminalSession session) {
        placed.add(session);
        hasContent = true;
    }

    @Test
    void aLoneFailureOnAnEmptyGridAbandonsIt() {
        opens.begin().onError().run();
        assertEquals(1, abandoned.get());
    }

    @Test
    void aFailureWaitsForTheOtherOpensStillInFlight() {
        PendingOpens.Ticket first = opens.begin();
        PendingOpens.Ticket second = opens.begin();
        first.onError().run();
        assertEquals(0, abandoned.get());
        second.onError().run();
        assertEquals(1, abandoned.get());
    }

    @Test
    void aFailureAfterAnotherOpenLandedKeepsTheGrid() {
        PendingOpens.Ticket first = opens.begin();
        PendingOpens.Ticket second = opens.begin();
        Probe probe = new Probe();
        first.onReady(this::place).accept(probe.session);
        second.onError().run();
        assertEquals(List.of(probe.session), placed);
        assertEquals(0, probe.closes.get());
        assertEquals(0, abandoned.get());
    }

    @Test
    void aFailureKeepsAGridTheUserFilledMeanwhile() {
        PendingOpens.Ticket ticket = opens.begin();
        hasContent = true; // e.g. a local shell double-clicked into the empty cell
        ticket.onError().run();
        assertEquals(0, abandoned.get());
    }

    @Test
    void aFailureAfterDisposeDoesNotCloseAgain() {
        PendingOpens.Ticket ticket = opens.begin();
        opens.dispose();
        ticket.onError().run();
        assertEquals(0, abandoned.get());
    }

    @Test
    void aSessionArrivingAfterDisposeIsClosedNotPlaced() {
        PendingOpens.Ticket ticket = opens.begin();
        opens.dispose();
        Probe probe = new Probe();
        ticket.onReady(this::place).accept(probe.session);
        assertTrue(placed.isEmpty());
        assertEquals(1, probe.closes.get());
    }

    @Test
    void aTicketSettlesOnlyOnce() {
        PendingOpens.Ticket first = opens.begin();
        PendingOpens.Ticket second = opens.begin();
        Runnable failed = first.onError();
        failed.run();
        failed.run(); // must not count for the second open as well
        assertEquals(0, abandoned.get());
        second.onError().run();
        assertEquals(1, abandoned.get());
    }
}
