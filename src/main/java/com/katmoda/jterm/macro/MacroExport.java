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
package com.katmoda.jterm.macro;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON envelope for exporting/importing macros.
 *
 * <p>The macros in an export always carry <b>plaintext</b> steps, even when this machine stores them
 * encrypted: an export is protected by its own passphrase (see {@link EncryptedMacroExport}), not by
 * this machine's credential vault, because the vault key exists nowhere else and an export that only
 * opened on the machine that wrote it would be useless.</p>
 *
 * <p>{@link #schemaVersion} tracks {@code MacroMigrations.CURRENT_VERSION} so a future step format
 * change can be migrated on import the same way {@code macros.json} is.</p>
 */
public final class MacroExport {

    public int schemaVersion;
    public List<Macro> macros = new ArrayList<>();
}
