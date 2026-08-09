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

/**
 * A parsed quick-connect target: {@code [user@]host[:port]}.
 *
 * <p>Parsing is deliberately strict — the string becomes a live connection, so anything
 * ambiguous is rejected with a message the UI can show verbatim rather than guessed at. In
 * particular {@code user:password@host} is refused outright: a password must never be typed
 * into a plain, shoulder-surfable text field, and jterm has an interactive prompt for it.</p>
 *
 * <p>A blank {@link #user()} means "inherit", matching {@link SshSessionConfig#getUser()} — it is
 * resolved at connect time by {@link SessionStore#effectiveUser(SshSessionConfig)}, which falls
 * through to the global default username for a config that isn't in the saved tree.</p>
 */
public record SshTarget(String user, String host, int port) {

    /** The port assumed when the target doesn't name one. */
    public static final int DEFAULT_PORT = 22;

    private static final String SCHEME = "ssh://";

    /**
     * Parses {@code [user@]host[:port]} (optionally with an {@code ssh://} prefix, since targets
     * are often pasted from a URL). IPv6 literals need brackets to carry a port ({@code [::1]:22});
     * an unbracketed literal is accepted as a host on the default port.
     *
     * @throws IllegalArgumentException with a user-facing message if the target is unusable
     */
    public static SshTarget parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            text = text.substring(SCHEME.length()).trim();
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Enter a target like user@host:port.");
        }

        String user = "";
        // Last '@', not first: a stray '@' left of the separator is a malformed user name, and
        // catching it here beats silently connecting as someone else.
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            user = text.substring(0, at);
            text = text.substring(at + 1);
            if (user.isEmpty()) {
                throw new IllegalArgumentException("Missing user name before \"@\".");
            }
            if (user.indexOf(':') >= 0) {
                throw new IllegalArgumentException("Passwords in the target are not supported — "
                        + "connect first, then enter the password when prompted.");
            }
            if (hasForbiddenChar(user) || user.indexOf('@') >= 0) {
                throw new IllegalArgumentException("User name contains invalid characters.");
            }
        }

        String host;
        int port = DEFAULT_PORT;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed \"[\" in the host address.");
            }
            host = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.charAt(0) != ':') {
                    throw new IllegalArgumentException("Expected \":port\" after \"]\".");
                }
                port = parsePort(rest.substring(1));
            }
        } else {
            int colon = text.indexOf(':');
            if (colon >= 0 && colon == text.lastIndexOf(':')) {
                host = text.substring(0, colon);
                port = parsePort(text.substring(colon + 1));
            } else {
                // No colon at all, or several — the latter is an unbracketed IPv6 literal, which
                // can't also carry a port (that is what the bracket form is for).
                host = text;
            }
        }

        if (host.isEmpty()) {
            throw new IllegalArgumentException("Missing host name.");
        }
        if (hasForbiddenChar(host) || host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
            throw new IllegalArgumentException("Host name contains invalid characters.");
        }
        return new SshTarget(user, host, port);
    }

    /**
     * A display label for this target, e.g. {@code root@example.com} or {@code me@host:2222}.
     * The default port is left off, and an IPv6 host is bracketed so the result stays parseable.
     *
     * @param effectiveUser the resolved user name (see {@link SessionStore#effectiveUser}), or
     *                      blank to label by host alone
     */
    public String label(String effectiveUser) {
        String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        if (effectiveUser != null && !effectiveUser.isBlank()) {
            shown = effectiveUser + "@" + shown;
        }
        return port == DEFAULT_PORT ? shown : shown + ":" + port;
    }

    private static int parsePort(String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Missing port number after \":\".");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Port must be a number between 1 and 65535.");
            }
        }
        // Digits only, so the only remaining failure is an absurdly long run of them (overflow).
        int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number between 1 and 65535.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be a number between 1 and 65535.");
        }
        return port;
    }

    /** Whitespace, control characters and path separators never belong in a user name or host. */
    private static boolean hasForbiddenChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '/' || c == '\\') {
                return true;
            }
        }
        return false;
    }
}
