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
package com.katmoda.jterm.highlight;

import com.katmoda.jterm.config.AppSettings;

/**
 * Resolves which {@link HighlightList} applies to a terminal, given that terminal's per-session
 * override id. The three override states are encoded compactly:
 *
 * <ul>
 *   <li>{@code null} — <b>inherit</b>: fall back to the global default
 *       ({@link AppSettings#getGlobalHighlightListId()}).</li>
 *   <li>{@link #NONE} — explicit "(None)": no highlighting for this session.</li>
 *   <li>any other value — a {@link HighlightList} id.</li>
 * </ul>
 *
 * <p>A stale id (the list was deleted) resolves to {@code null} (no highlighting), never an error.</p>
 */
public final class HighlightListResolver {

    /** Sentinel meaning "(None)" — highlighting explicitly turned off for a session/globally. */
    public static final String NONE = "__none__";

    private HighlightListResolver() {
    }

    /** @param sessionOverrideId the session's stored override id (see class doc); may be {@code null}. */
    public static HighlightList resolve(String sessionOverrideId) {
        String id = sessionOverrideId;
        if (id == null) {
            // Inherit the global default.
            id = AppSettings.get().getGlobalHighlightListId();
        }
        if (id == null || NONE.equals(id)) {
            return null;
        }
        return HighlightLibrary.get().byId(id);
    }
}
