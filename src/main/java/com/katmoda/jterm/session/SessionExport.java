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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON envelope for exporting/importing a folder subtree.
 *
 * <p>{@link #folder} is the exported folder with its full recursive contents. {@link #credentials}
 * maps a session's {@code id} to its plaintext SSH password and is populated only when the user
 * opts in (gated behind the master password); it is empty otherwise.</p>
 *
 * <p>{@link #root} marks an export of the top-level "Sessions" folder. On import its children are
 * merged into the destination rather than nesting another "Sessions" folder inside it.</p>
 */
public final class SessionExport {

    public FolderNode folder;
    public Map<String, String> credentials = new LinkedHashMap<>();
    public boolean root;
}
