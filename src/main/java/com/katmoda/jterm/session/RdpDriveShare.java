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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A local folder an RDP session shares with the remote desktop as a drive. Read-only unless the
 * user deliberately unticks it. Machine-specific, so never part of a session export.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RdpDriveShare {

    private String path = "";
    private String name = "";
    private boolean readOnly = true;

    public RdpDriveShare() {
    }

    public RdpDriveShare(String path, String name, boolean readOnly) {
        setPath(path);
        setName(name);
        this.readOnly = readOnly;
    }

    /** The local folder, as the user chose it. */
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path != null ? path : "";
    }

    /** What the remote desktop shows: {@code \\tsclient\<name>}. Sanitised when connecting. */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim() : "";
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public RdpDriveShare copy() {
        return new RdpDriveShare(path, name, readOnly);
    }
}
