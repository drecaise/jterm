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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HelperResolverTest {

    private static InetAddress ip(String host, int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(host, new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    @Test
    void acceptsPlainHostNamesOnly() {
        assertTrue(HelperResolver.isHostName("rdsh01"));
        assertTrue(HelperResolver.isHostName("rdsh01.corp.example.com"));
        assertTrue(HelperResolver.isHostName("rdsh-01.example.com."));
        assertFalse(HelperResolver.isHostName(""));
        assertFalse(HelperResolver.isHostName(null));
        assertFalse(HelperResolver.isHostName("."));
        assertFalse(HelperResolver.isHostName("/run/user/1000/bus"));
        assertFalse(HelperResolver.isHostName("|5"));
        assertFalse(HelperResolver.isHostName("vsock://3"));
        assertFalse(HelperResolver.isHostName("a..b"));
        assertFalse(HelperResolver.isHostName("-a.example"));
        assertFalse(HelperResolver.isHostName("a-.example"));
        assertFalse(HelperResolver.isHostName("host name"));
        assertFalse(HelperResolver.isHostName("host%00"));
        assertFalse(HelperResolver.isHostName("a".repeat(64) + ".example"));
        assertFalse(HelperResolver.isHostName("abc.".repeat(64) + "x"));
    }

    @Test
    void neverLooksUpAnInvalidName() {
        List<String> got = HelperResolver.resolve("/etc/passwd", name -> fail("looked up " + name));
        assertTrue(got.isEmpty());
    }

    @Test
    void returnsNumericAddressesInOrderWithoutDuplicatesOrScope() throws Exception {
        InetAddress v4 = ip("h", 10, 0, 0, 5);
        byte[] ll = new byte[16];
        ll[0] = (byte) 0xfe;
        ll[1] = (byte) 0x80;
        ll[15] = 1;
        InetAddress scoped = Inet6Address.getByAddress("h", ll, 2);
        List<String> got = HelperResolver.resolve("h.example", name -> new InetAddress[]{v4, scoped, v4});
        assertEquals(List.of("10.0.0.5", "fe80:0:0:0:0:0:0:1"), got);
    }

    @Test
    void notFoundIsEmpty() {
        assertTrue(HelperResolver.resolve("nowhere.example", name -> {
            throw new UnknownHostException(name);
        }).isEmpty());
    }

    @Test
    void capsTheAnswer() throws Exception {
        List<InetAddress> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(ip("h", 10, 0, 0, i));
        }
        List<String> got = HelperResolver.resolve("h.example", name -> many.toArray(new InetAddress[0]));
        assertEquals(RdpProtocol.RESOLVE_MAX_ADDRESSES, got.size());
        assertEquals("10.0.0.0", got.get(0));
    }
}
