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
package com.katmoda.jterm.terminal.ssh;

import java.io.IOException;

/**
 * Authentication for one hop failed (every method the client and server share was exhausted, or
 * the user cancelled an interactive prompt).
 *
 * <p>Exists so the error dialog can lead with {@code Authentication failed for user@host} instead
 * of MINA's raw {@code No more authentication methods available}; the original exception is kept
 * as the cause, so {@code ErrorDialog}'s cause chain still shows it.</p>
 */
public final class SshAuthException extends IOException {

    private final String user;
    private final String host;

    public SshAuthException(String user, String host, Throwable cause) {
        super("Authentication failed for " + user + "@" + host, cause);
        this.user = user;
        this.host = host;
    }

    public String user() {
        return user;
    }

    public String host() {
        return host;
    }
}
