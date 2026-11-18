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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves a host name for the sandboxed helper ({@link RdpProtocol.ResolveRequest}, sandbox design
 * decision D3). The helper resolves the session's own host before its lockdown; it asks for a name
 * only when FreeRDP wants one it hasn't seen, which in practice is the host a server redirected it
 * to (an RDS connection broker naming a session host).
 *
 * <p>The name comes from the server, so it is checked to be a plain DNS name before anything looks
 * it up, and only numeric addresses go back. Resolving it reveals nothing the server doesn't know
 * already; where the helper may then connect is the sandbox's business (its port rule). Swing-free,
 * unit-tested with a fake lookup.</p>
 */
final class HelperResolver {

    /** Lookup function, {@link InetAddress#getAllByName} in production. */
    @FunctionalInterface
    interface Lookup {
        InetAddress[] all(String name) throws UnknownHostException;
    }

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private HelperResolver() {
    }

    /** A DNS host name: dot-separated labels of letters, digits and inner hyphens, 253 characters at most. */
    static boolean isHostName(String name) {
        if (name == null || name.isEmpty() || name.length() > 253) {
            return false;
        }
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (n.isEmpty()) {
            return false;
        }
        for (String label : n.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The numeric addresses of {@code name}, in the resolver's order, at most
     * {@link RdpProtocol#RESOLVE_MAX_ADDRESSES}; empty when the name is not a plain host name or
     * doesn't resolve. IPv6 scope ids are dropped (the helper accepts plain numeric forms only).
     */
    static List<String> resolve(String name, Lookup lookup) {
        if (!isHostName(name)) {
            return List.of();
        }
        InetAddress[] found;
        try {
            found = lookup.all(name);
        } catch (UnknownHostException | SecurityException e) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (InetAddress a : found) {
            if (out.size() >= RdpProtocol.RESOLVE_MAX_ADDRESSES) {
                break;
            }
            String text = a.getHostAddress();
            int scope = text.indexOf('%');
            if (scope >= 0) {
                text = text.substring(0, scope);
            }
            if (!out.contains(text)) {
                out.add(text);
            }
        }
        return out;
    }
}
