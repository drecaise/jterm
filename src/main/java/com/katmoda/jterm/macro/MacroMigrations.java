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

import com.fasterxml.jackson.databind.JsonNode;
import com.katmoda.jterm.config.JsonStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema handling for {@code macros.json}, mirroring {@code session.SessionMigrations}.
 *
 * <p><b>v0 → v1.</b> The file used to be a bare JSON <em>array</em> of macros, which left nowhere to
 * put a version stamp or any file-level metadata. v1 wraps it in an object:</p>
 *
 * <pre>{@code { "schemaVersion": 1, "macros": [ ... ] }}</pre>
 *
 * <p>The two shapes are told apart by the root JSON token, so the upgrade needs no stamp to detect
 * (an array is v0 by definition). {@link MacroLibrary} then stamps and saves the version even when
 * nothing else changed — that stamp is what makes the migration one-shot.</p>
 *
 * <p>Note this is a one-way door: an older jterm reading a v1 file sees an object where it expects
 * an array, treats it as corrupt, and preserves it as {@code macros.json.unreadable-N} rather than
 * losing it.</p>
 */
final class MacroMigrations {

    static final int CURRENT_VERSION = 1;

    private MacroMigrations() {
    }

    /**
     * Reads {@code file} in whichever schema it is written in.
     *
     * @return the parsed macros plus the version they were read at, or {@code null} when the file is
     *         missing or unreadable (the caller must distinguish those — see {@link MacroLibrary}).
     */
    static Loaded read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = JsonStore.mapper().readTree(file.toFile());
            if (root == null || root.isNull()) {
                return null;
            }
            if (root.isArray()) {
                // v0: the whole document is the macro list.
                return new Loaded(readMacros(root), 0);
            }
            int version = root.path("schemaVersion").asInt(0);
            return new Loaded(readMacros(root.path("macros")), version);
        } catch (Exception e) {
            return null; // corrupt — the caller preserves the file aside and reports it
        }
    }

    private static List<Macro> readMacros(JsonNode array) throws Exception {
        List<Macro> macros = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return macros;
        }
        for (JsonNode node : array) {
            macros.add(JsonStore.mapper().treeToValue(node, Macro.class));
        }
        return macros;
    }

    /** Parsed macros plus the schema version the file was written at. */
    record Loaded(List<Macro> macros, int version) {
    }

    /** The on-disk root object for v1 and later. */
    record Document(int schemaVersion, List<Macro> macros) {
    }
}
