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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The environment {@code jterm-rdp-helper} is started with: an allow-list, never jterm's own
 * environment (sandbox design, layer 1).
 *
 * <p>Inheriting would hand the helper, which parses a possibly hostile server's bytes, the way to
 * the user's other secrets ({@code SSH_AUTH_SOCK}, {@code DBUS_SESSION_BUS_ADDRESS},
 * {@code XDG_RUNTIME_DIR}), and variables that change what it loads or where it connects:
 * {@code LD_PRELOAD}/{@code DYLD_*}, {@code OPENSSL_CONF}/{@code OPENSSL_MODULES}, WinPR's
 * {@code WTSAPI_LIBRARY} (a library path it loads), WLog's file and UDP appenders, and
 * {@code https_proxy}/{@code no_proxy}, which FreeRDP reads by itself to route the connection
 * through a proxy nobody configured in jterm.</p>
 *
 * <p>What is kept, and why each is needed:</p>
 * <ul>
 *   <li>{@code HOME} (and {@code USERPROFILE} on Windows): FreeRDP refuses to create its settings
 *       without a home path ({@code freerdp_settings_new} fails on a null {@code HomePath}), so the
 *       helper could not start at all.</li>
 *   <li>{@code XDG_CONFIG_HOME} (Unix): FreeRDP's own folder is {@code <it>/jterm/jterm-rdp-helper},
 *       else {@code $HOME/.config/...}, i.e. inside jterm's config directory, which {@code AppPaths}
 *       resolves from the same variable. The Flatpak sets it to the app's private folder; without it
 *       the Flatpak's helper looked in {@code ~/.config/jterm}, the folder of a jterm installed from
 *       a package. The helper writes nothing there (certificates are accepted for the session only)
 *       and the Linux lockdown would refuse a write.</li>
 *   <li>{@code WLOG_LEVEL}: inherited for debugging, else {@code WARN}.</li>
 *   <li>{@code TZ}, {@code LANG}, {@code LANGUAGE}, {@code LC_*}: time stamps in the log and the
 *       keyboard locale FreeRDP derives from {@code LANG}.</li>
 *   <li>{@code TMPDIR} (Unix); {@code SystemRoot}, {@code SystemDrive}, {@code windir},
 *       {@code TEMP}, {@code TMP} (Windows): what system libraries expect to find.</li>
 *   <li>{@code JTERM_OPENH264}: only ever from the target, never inherited.</li>
 * </ul>
 *
 * <p>Swing-free and pure, so it is unit-tested.</p>
 */
final class HelperEnvironment {

    static final String OPENH264 = "JTERM_OPENH264";
    static final String WLOG_LEVEL = "WLOG_LEVEL";
    /**
     * Windows: the helper runs without the restricted token and signs in through Windows' own
     * authentication (Kerberos). It has to be decided before the helper reads anything from jterm
     * (the relaunch comes first), hence the environment rather than CONNECT.
     */
    static final String KERBEROS = "JTERM_RDP_KERBEROS";

    private static final List<String> COMMON = List.of("HOME", "TZ", "LANG", "LANGUAGE");
    private static final List<String> UNIX = List.of("TMPDIR", "XDG_CONFIG_HOME");
    private static final List<String> WINDOWS =
            List.of("USERPROFILE", "SystemRoot", "SystemDrive", "windir", "TEMP", "TMP");

    private HelperEnvironment() {
    }

    /**
     * The helper's whole environment.
     *
     * @param parent       jterm's environment (normally {@link System#getenv()})
     * @param codecLibrary the downloaded OpenH264 to preload, or {@code null}
     * @param windows      whether variable names compare without case, as Windows does
     */
    static Map<String, String> build(Map<String, String> parent, Path codecLibrary, boolean windows) {
        return build(parent, codecLibrary, windows, false);
    }

    /** As above; {@code kerberos} (Windows only) asks for Windows' own authentication, see {@link #KERBEROS}. */
    static Map<String, String> build(Map<String, String> parent, Path codecLibrary, boolean windows,
                                     boolean kerberos) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : COMMON) {
            copy(parent, name, windows, env);
        }
        for (String name : windows ? WINDOWS : UNIX) {
            copy(parent, name, windows, env);
        }
        for (Map.Entry<String, String> e : parent.entrySet()) {
            String upper = e.getKey().toUpperCase(Locale.ROOT);
            boolean locale = windows ? upper.startsWith("LC_") : e.getKey().startsWith("LC_");
            if (locale && e.getValue() != null && !e.getKey().contains("=")) {
                env.put(e.getKey(), e.getValue());
            }
        }
        String level = lookup(parent, WLOG_LEVEL, windows);
        env.put(WLOG_LEVEL, level == null || level.isBlank() ? "WARN" : level);
        if (codecLibrary != null) {
            env.put(OPENH264, codecLibrary.toString());
        }
        if (windows && kerberos) {
            env.put(KERBEROS, "1");
        }
        return env;
    }

    private static void copy(Map<String, String> parent, String name, boolean windows, Map<String, String> into) {
        String value = lookup(parent, name, windows);
        if (value != null) {
            into.put(name, value);
        }
    }

    /** A variable by name; on Windows ignoring case, whatever kind of map {@code parent} is. */
    private static String lookup(Map<String, String> parent, String name, boolean windows) {
        String value = parent.get(name);
        if (value != null || !windows) {
            return value;
        }
        for (Map.Entry<String, String> e : parent.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
