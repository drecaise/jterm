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

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalNetworkAccessTest {

    private static boolean local(String literal) throws Exception {
        return LocalNetworkAccess.isLocal(InetAddress.getByName(literal));
    }

    @Test
    void privateAndLinkLocalAddressesAreLocal() throws Exception {
        assertTrue(local("10.1.2.3"));
        assertTrue(local("172.16.1.77"));
        assertTrue(local("192.168.0.10"));
        assertTrue(local("169.254.10.10"));
        assertTrue(local("fe80::1"));
        assertTrue(local("fd12:3456::1"), "IPv6 unique-local (fc00::/7)");
    }

    @Test
    void loopbackAndPublicAddressesAreNot() throws Exception {
        assertFalse(local("127.0.0.1"), "an SSH forward's loopback end needs no permission");
        assertFalse(local("::1"));
        assertFalse(local("8.8.8.8"));
        assertFalse(local("172.32.0.1"), "just outside 172.16.0.0/12");
        assertFalse(local("2001:db8::1"));
    }

    @Test
    void onlyMacOsChecks() {
        assertFalse(LocalNetworkAccess.needed(false));
    }

    @Test
    void publicDestinationsProceedWithoutConnecting() {
        Runnable fail = () -> {
            throw new AssertionError("must not report waiting");
        };
        assertEquals(LocalNetworkAccess.Outcome.PROCEED,
                LocalNetworkAccess.await("8.8.8.8", 1, Duration.ZERO,
                        fail, () -> {
                            throw new AssertionError("must not even try");
                        }));
    }
}
