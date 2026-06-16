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
 * A WSL2 distribution surfaced as a launchable session. These are <em>discovered at runtime</em>
 * (on Windows) and live only in memory under the sidebar's pinned "WSL" folder — they are never
 * added to the persisted session tree, so they're never serialized and never editable. Mutators
 * are intentionally no-ops; the icon is always the WSL glyph (decorated by the renderer).
 */
public final class WslDistroNode implements SessionNode {

    private final String distro;

    public WslDistroNode(String distro) {
        this.distro = distro;
    }

    /** The distribution name passed to {@code wsl.exe -d <distro>}. */
    public String distro() {
        return distro;
    }

    @Override
    public String getName() {
        return distro;
    }

    @Override
    public void setName(String name) {
        // Non-editable: detected distros can't be renamed.
    }

    @Override
    public String getIconId() {
        return "builtin/wsl";
    }

    @Override
    public void setIconId(String iconId) {
        // Non-editable: the icon is fixed.
    }
}
