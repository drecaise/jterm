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

import com.fasterxml.jackson.core.type.TypeReference;
import com.katmoda.jterm.config.AppPaths;
import com.katmoda.jterm.config.JsonStore;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * jterm's trust store for RDP server certificates: {@code host:port} → pinned SHA-256 fingerprint,
 * in {@code rdp_known_hosts.json}. The RDP counterpart of {@code JtermKnownHostsVerifier}.
 *
 * <p>How it is used: every pin for the target goes to the helper in CONNECT, and FreeRDP accepts a
 * matching certificate before any other check. A certificate that is neither pinned nor valid under
 * normal CA + hostname validation comes back as a {@code CertRequest}; {@link #pinFor} then tells
 * the prompt whether this is a <em>first</em> sighting or a <em>changed</em> certificate (a host we
 * already pinned presenting something else — the man-in-the-middle case, warned about loudly).</p>
 *
 * <p>If the file exists but can't be read, saving is refused so the unreadable original (and its
 * pins) isn't silently replaced by an empty store.</p>
 */
public final class RdpKnownHosts {

    private static final TypeReference<TreeMap<String, String>> TYPE = new TypeReference<>() { };

    private final Path file;
    private final Map<String, String> pins;
    private final boolean loadFailed;

    /** The store in the application config directory. */
    public static RdpKnownHosts load() {
        return new RdpKnownHosts(AppPaths.file("rdp_known_hosts.json"));
    }

    RdpKnownHosts(Path file) {
        this.file = file;
        JsonStore.LoadResult<TreeMap<String, String>> result = JsonStore.loadResult(file, TYPE);
        this.loadFailed = result.isCorrupt();
        this.pins = result.value() != null ? result.value() : new TreeMap<>();
    }

    static String key(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** The pinned fingerprint ({@code aa:bb:...}, lower case) for the target, or {@code null}. */
    public synchronized String pinFor(String host, int port) {
        return pins.get(key(host, port));
    }

    /** Pins for CONNECT: {@code sha256:<fingerprint>}, or empty when none is stored. */
    public synchronized String pinsForConnect(String host, int port) {
        String fp = pinFor(host, port);
        return fp != null ? "sha256:" + fp : "";
    }

    /** Pins (or re-pins) the target. Returns whether it was persisted. */
    public synchronized boolean pin(String host, int port, String sha256Fingerprint) {
        if (loadFailed) {
            return false;
        }
        pins.put(key(host, port), sha256Fingerprint.trim().toLowerCase(Locale.ROOT));
        return JsonStore.save(file, pins);
    }
}
