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
package com.katmoda.jterm.ui.component;

/**
 * Turns a user-chosen name into a safe default file name for a save dialog.
 *
 * <p>Shared by the sessions and macros exports so both offer the same kind of default. Deliberately
 * strict — anything outside {@code [A-Za-z0-9-_]} collapses to an underscore — which incidentally
 * rules out path separators and {@code ..}, so a folder or macro named after a path fragment cannot
 * steer the chooser somewhere unexpected.</p>
 */
public final class FileNames {

    private FileNames() {
    }

    /** Sanitises {@code name}, falling back to {@code fallback} when nothing usable remains. */
    public static String safe(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String cleaned = name.replaceAll("[^a-zA-Z0-9-_]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
