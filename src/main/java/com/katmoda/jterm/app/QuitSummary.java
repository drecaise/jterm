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
package com.katmoda.jterm.app;

import java.util.ArrayList;
import java.util.List;

/**
 * What quitting jterm would end, across every window, and whether the macOS quit handler should
 * ask first. Swing-free so the policy and the wording are unit-tested; {@code MainWindow} does the
 * counting.
 *
 * <p><b>Only something remote makes it ask</b> (a remote desktop, an SSH session, an SFTP browser
 * or a tunnel): every jterm window starts with a local shell, so counting those would make Cmd+Q
 * ask every time, and a confirmation that always appears stops being read. Local terminals are
 * still named in the question when it is asked, since they close too.</p>
 *
 * @param remoteDesktops RDP panes connected or connecting
 * @param sshSessions    SSH terminal panes whose session is still alive
 * @param sftpBrowsers   SFTP browser panes
 * @param tunnels        running tunnels (Tunnels…, including auto-started ones)
 * @param localTerminals local shells (WSL included) whose process is still alive
 */
public record QuitSummary(int remoteDesktops, int sshSessions, int sftpBrowsers, int tunnels,
                          int localTerminals) {

    /** Nothing open at all. */
    public static final QuitSummary NOTHING = new QuitSummary(0, 0, 0, 0, 0);

    /** Whether the quit handler asks before quitting: anything remote would be disconnected. */
    public boolean shouldConfirm() {
        return remoteDesktops + sshSessions + sftpBrowsers + tunnels > 0;
    }

    /**
     * The confirmation's text, for example "Quit jterm? 2 remote desktops and 5 SSH sessions will be
     * disconnected." Local terminals follow as their own clause, since "disconnected" is the wrong
     * word for them.
     */
    public String question() {
        List<String> remote = new ArrayList<>();
        add(remote, remoteDesktops, "remote desktop", "remote desktops");
        add(remote, sshSessions, "SSH session", "SSH sessions");
        add(remote, sftpBrowsers, "SFTP browser", "SFTP browsers");
        add(remote, tunnels, "tunnel", "tunnels");
        StringBuilder sb = new StringBuilder("Quit jterm?");
        String local = localTerminals == 0 ? null
                : count(localTerminals, "local terminal", "local terminals") + " will be closed";
        if (!remote.isEmpty()) {
            sb.append(' ').append(join(remote)).append(" will be disconnected");
            if (local != null) {
                sb.append(", and ").append(local);
            }
            sb.append('.');
        } else if (local != null) {
            sb.append(' ').append(local).append('.');
        }
        return sb.toString();
    }

    private static void add(List<String> parts, int n, String one, String many) {
        if (n > 0) {
            parts.add(count(n, one, many));
        }
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** "a", "a and b", "a, b and c". */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
