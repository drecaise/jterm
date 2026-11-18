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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HelperEnvironmentTest {

    private static Map<String, String> linuxDesktop() {
        Map<String, String> env = new HashMap<>();
        env.put("HOME", "/home/u");
        env.put("TMPDIR", "/tmp/u");
        env.put("XDG_CONFIG_HOME", "/home/u/.var/app/com.katmoda.jterm/config");
        env.put("LANG", "de_DE.UTF-8");
        env.put("LC_TIME", "en_GB.UTF-8");
        env.put("TZ", "Europe/Berlin");
        env.put("PATH", "/usr/bin");
        env.put("SSH_AUTH_SOCK", "/run/user/1000/ssh-agent");
        env.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus");
        env.put("XDG_RUNTIME_DIR", "/run/user/1000");
        env.put("LD_PRELOAD", "/tmp/evil.so");
        env.put("LD_LIBRARY_PATH", "/tmp");
        env.put("OPENSSL_CONF", "/tmp/openssl.cnf");
        env.put("OPENSSL_MODULES", "/tmp");
        env.put("https_proxy", "http://proxy:3128");
        env.put("WTSAPI_LIBRARY", "/tmp/wts.so");
        env.put("WLOG_APPENDER", "FILE");
        env.put("JTERM_OPENH264", "/tmp/inherited.so");
        return env;
    }

    @Test
    void keepsOnlyTheAllowList() {
        Map<String, String> env = HelperEnvironment.build(linuxDesktop(), null, false);
        assertEquals(Map.of("HOME", "/home/u", "TMPDIR", "/tmp/u",
                "XDG_CONFIG_HOME", "/home/u/.var/app/com.katmoda.jterm/config", "LANG", "de_DE.UTF-8",
                "LC_TIME", "en_GB.UTF-8", "TZ", "Europe/Berlin", "WLOG_LEVEL", "WARN"), env);
    }

    @Test
    void theCodecComesFromTheTargetNeverFromJtermsEnvironment() {
        Map<String, String> without = HelperEnvironment.build(linuxDesktop(), null, false);
        assertFalse(without.containsKey(HelperEnvironment.OPENH264));
        Map<String, String> with = HelperEnvironment.build(linuxDesktop(), Path.of("/c/libopenh264.so.8"), false);
        assertEquals("/c/libopenh264.so.8", with.get(HelperEnvironment.OPENH264));
    }

    @Test
    void anInheritedLogLevelIsKeptForDebugging() {
        Map<String, String> parent = linuxDesktop();
        parent.put("WLOG_LEVEL", "DEBUG");
        assertEquals("DEBUG", HelperEnvironment.build(parent, null, false).get("WLOG_LEVEL"));
        parent.put("WLOG_LEVEL", " ");
        assertEquals("WARN", HelperEnvironment.build(parent, null, false).get("WLOG_LEVEL"));
    }

    @Test
    void windowsNamesMatchWithoutCaseAndKeepTheSystemVariables() {
        // A plain HashMap: the lookup must not rely on System.getenv()'s case-insensitive map.
        Map<String, String> parent = new HashMap<>();
        parent.put("SYSTEMROOT", "C:\\Windows");
        parent.put("SystemDrive", "C:");
        parent.put("windir", "C:\\Windows");
        parent.put("Temp", "C:\\Users\\u\\AppData\\Local\\Temp");
        parent.put("TMP", "C:\\Users\\u\\AppData\\Local\\Temp");
        parent.put("UserProfile", "C:\\Users\\u");
        parent.put("Path", "C:\\Windows\\system32");
        parent.put("SSH_AUTH_SOCK", "\\\\.\\pipe\\openssh-ssh-agent");
        parent.put("HTTPS_PROXY", "http://proxy:3128");
        parent.put("TMPDIR", "C:\\elsewhere");
        Map<String, String> env = HelperEnvironment.build(parent, null, true);
        assertEquals(Map.of("SystemRoot", "C:\\Windows", "SystemDrive", "C:", "windir", "C:\\Windows",
                "TEMP", "C:\\Users\\u\\AppData\\Local\\Temp", "TMP", "C:\\Users\\u\\AppData\\Local\\Temp",
                "USERPROFILE", "C:\\Users\\u", "WLOG_LEVEL", "WARN"), env);
    }

    @Test
    void localeVariablesAreCaseSensitiveOffWindows() {
        Map<String, String> parent = new HashMap<>();
        parent.put("lc_all", "C");
        parent.put("LC_ALL", "C.UTF-8");
        Map<String, String> env = HelperEnvironment.build(parent, null, false);
        assertEquals(Map.of("LC_ALL", "C.UTF-8", "WLOG_LEVEL", "WARN"), env);
    }

    @Test
    void kerberosIsAskedForOnlyOnWindows() {
        Map<String, String> parent = Map.of("SystemRoot", "C:\\Windows", "USERPROFILE", "C:\\Users\\me");
        assertEquals("1", HelperEnvironment.build(parent, null, true, true).get(HelperEnvironment.KERBEROS));
        assertFalse(HelperEnvironment.build(parent, null, true, false).containsKey(HelperEnvironment.KERBEROS));
        assertFalse(HelperEnvironment.build(linuxDesktop(), null, false, true).containsKey(HelperEnvironment.KERBEROS));
        // Never taken from jterm's own environment.
        Map<String, String> leaking = Map.of("SystemRoot", "C:\\Windows", HelperEnvironment.KERBEROS, "1");
        assertFalse(HelperEnvironment.build(leaking, null, true, false).containsKey(HelperEnvironment.KERBEROS));
    }
}
