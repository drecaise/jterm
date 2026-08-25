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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroMigrationsTest {

    @TempDir
    Path dir;

    /** The v0 shape: the whole document was a bare array, with nowhere to put a version. */
    private static final String V0 = """
            [ {
              "id" : "abc",
              "name" : "One",
              "hotkey" : "shift ctrl pressed F1",
              "steps" : [ { "type" : "text", "text" : "echo hi", "keystrokeDelayMs" : 0 },
                          { "type" : "key", "key" : "RETURN" } ]
            } ]""";

    @Test
    void readsTheLegacyBareArrayAsVersionZero() throws Exception {
        Path file = dir.resolve("macros.json");
        Files.writeString(file, V0);

        MacroMigrations.Loaded loaded = MacroMigrations.read(file);
        assertEquals(0, loaded.version());
        assertEquals(1, loaded.macros().size());
        assertEquals("One", loaded.macros().get(0).getName());
        assertEquals("shift ctrl pressed F1", loaded.macros().get(0).getHotkey());
        assertEquals(2, loaded.macros().get(0).getSteps().size());
        assertEquals(new MacroStep.TextStep("echo hi", 0), loaded.macros().get(0).getSteps().get(0));
    }

    @Test
    void readsTheVersionedObjectShape() throws Exception {
        Path file = dir.resolve("macros.json");
        Files.writeString(file, """
                { "schemaVersion" : 1, "macros" : [ { "id" : "abc", "name" : "One" } ] }""");

        MacroMigrations.Loaded loaded = MacroMigrations.read(file);
        assertEquals(1, loaded.version());
        assertEquals("One", loaded.macros().get(0).getName());
    }

    @Test
    void aSealedMacroSurvivesAReadWithItsBlobIntact() throws Exception {
        Path file = dir.resolve("macros.json");
        Files.writeString(file, """
                { "schemaVersion" : 1, "macros" : [ {
                    "id" : "abc", "name" : "One",
                    "sealedSteps" : { "nonce" : "AAAA", "ciphertext" : "BBBB" } } ] }""");

        Macro macro = MacroMigrations.read(file).macros().get(0);
        assertTrue(macro.isSealed());
        assertEquals("AAAA", macro.getSealedSteps().nonce);
        assertTrue(macro.getSteps().isEmpty());
    }

    @Test
    void aMissingFileReadsAsNull() {
        assertNull(MacroMigrations.read(dir.resolve("nope.json")));
    }

    @Test
    void anUnparsableFileReadsAsNullRatherThanAnEmptyLibrary() throws Exception {
        Path file = dir.resolve("macros.json");
        Files.writeString(file, "{ not json");
        // Null, not an empty Loaded: MacroLibrary must be able to tell corrupt from missing, since
        // one of those may be written over and the other must not.
        assertNull(MacroMigrations.read(file));
    }

    @Test
    void migratedMacrosRoundTripThroughTheCurrentShape() throws Exception {
        Path v0File = dir.resolve("v0.json");
        Files.writeString(v0File, V0);
        MacroMigrations.Loaded loaded = MacroMigrations.read(v0File);

        Path v1File = dir.resolve("v1.json");
        com.katmoda.jterm.config.JsonStore.save(v1File,
                new MacroMigrations.Document(MacroMigrations.CURRENT_VERSION, loaded.macros()));

        MacroMigrations.Loaded reread = MacroMigrations.read(v1File);
        assertEquals(MacroMigrations.CURRENT_VERSION, reread.version());
        assertEquals(loaded.macros().get(0).getSteps(), reread.macros().get(0).getSteps());
        assertTrue(Files.readString(v1File).contains("schemaVersion"));
    }
}
