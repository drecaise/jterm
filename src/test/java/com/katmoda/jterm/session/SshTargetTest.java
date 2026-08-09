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
package com.katmoda.jterm.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Quick-connect target parsing: {@code [user@]host[:port]}. */
class SshTargetTest {

    @Test
    void hostOnlyInheritsUserAndDefaultsPort() {
        SshTarget t = SshTarget.parse("example.com");
        assertEquals("", t.user(), "a blank user means inherit");
        assertEquals("example.com", t.host());
        assertEquals(22, t.port());
    }

    @Test
    void parsesUserAndPort() {
        assertEquals(new SshTarget("root", "example.com", 22), SshTarget.parse("root@example.com"));
        assertEquals(new SshTarget("", "example.com", 2222), SshTarget.parse("example.com:2222"));
        assertEquals(new SshTarget("root", "example.com", 2222),
                SshTarget.parse("root@example.com:2222"));
    }

    @Test
    void trimsAndStripsScheme() {
        assertEquals(new SshTarget("me", "host", 2222), SshTarget.parse("  ssh://me@host:2222 "));
        assertEquals(new SshTarget("", "host", 22), SshTarget.parse("SSH://host"));
    }

    @Test
    void bracketedIpv6CarriesAPort() {
        assertEquals(new SshTarget("me", "::1", 2222), SshTarget.parse("me@[::1]:2222"));
        assertEquals(new SshTarget("", "fe80::1", 22), SshTarget.parse("[fe80::1]"));
    }

    @Test
    void bareIpv6IsAHostOnTheDefaultPort() {
        // Several colons and no brackets: the trailing group is part of the address, not a port.
        assertEquals(new SshTarget("", "::1", 22), SshTarget.parse("::1"));
        assertEquals(new SshTarget("me", "fe80::1", 22), SshTarget.parse("me@fe80::1"));
    }

    @Test
    void lastAtSeparatesTheUser() {
        // A '@' inside the user name is a typo, not a valid name — better rejected than connected.
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("a@b@host"));
    }

    @Test
    void rejectsPasswordsInTheTarget() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SshTarget.parse("root:hunter2@example.com"));
        assertTrue(e.getMessage().contains("Passwords"), e.getMessage());
    }

    @Test
    void rejectsMalformedTargets() {
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse(null));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("@host"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("me@"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("host:"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("me@ host"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("host/path"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("[::1:22"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("[::1]22"));
    }

    @Test
    void rejectsOutOfRangePorts() {
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("host:0"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("host:70000"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("host:22x"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("host:-1"));
        assertThrows(IllegalArgumentException.class,
                () -> SshTarget.parse("host:99999999999999999999"));
    }

    @Test
    void labelUsesTheResolvedUserAndHidesTheDefaultPort() {
        assertEquals("root@example.com", SshTarget.parse("example.com").label("root"));
        assertEquals("root@example.com:2222", SshTarget.parse("example.com:2222").label("root"));
        assertEquals("example.com", SshTarget.parse("example.com").label(""));
        // Re-bracketed so the label stays a parseable target.
        assertEquals("me@[::1]:2222", SshTarget.parse("[::1]:2222").label("me"));
    }
}
