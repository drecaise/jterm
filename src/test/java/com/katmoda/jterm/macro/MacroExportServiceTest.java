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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroExportServiceTest {

    @TempDir
    Path dir;

    private static Macro macro(String id, String name, String text) {
        Macro m = new Macro();
        m.setId(id);
        m.setName(name);
        m.setSteps(new ArrayList<>(List.of(
                new MacroStep.TextStep(text, 0),
                new MacroStep.KeyStep(MacroKey.RETURN))));
        return m;
    }

    @Test
    void plainExportRoundTrips() throws Exception {
        Path file = dir.resolve("macros.json");
        MacroExportService.writePlain(file, MacroExportService.build(List.of(macro("a", "One", "echo hi"))));

        assertFalse(MacroExportService.isEncrypted(file.toFile()));
        MacroExport read = MacroExportService.readPlain(file.toFile());
        assertEquals(1, read.macros.size());
        assertEquals("One", read.macros.get(0).getName());
        assertEquals(new MacroStep.TextStep("echo hi", 0), read.macros.get(0).getSteps().get(0));
        assertEquals(MacroMigrations.CURRENT_VERSION, read.schemaVersion);
    }

    @Test
    void encryptedExportRoundTripsAndHidesItsContents() throws Exception {
        Path file = dir.resolve("macros.json");
        MacroExportService.writeEncrypted(file,
                MacroExportService.build(List.of(macro("a", "One", "hunter2"))),
                "pass phrase".toCharArray());

        // The point of the feature: the secret must not survive anywhere in the file.
        assertFalse(Files.readString(file).contains("hunter2"));
        assertTrue(MacroExportService.isEncrypted(file.toFile()));

        MacroExport opened = MacroExportService.openEnvelope(
                MacroExportService.readEnvelope(file.toFile()), "pass phrase".toCharArray());
        assertEquals(new MacroStep.TextStep("hunter2", 0), opened.macros.get(0).getSteps().get(0));
    }

    @Test
    void wrongPassphraseReturnsNullRatherThanThrowing() throws Exception {
        Path file = dir.resolve("macros.json");
        MacroExportService.writeEncrypted(file,
                MacroExportService.build(List.of(macro("a", "One", "x"))), "right".toCharArray());

        // Null, not an exception, so the caller can re-prompt in a loop.
        assertNull(MacroExportService.openEnvelope(
                MacroExportService.readEnvelope(file.toFile()), "wrong".toCharArray()));
    }

    @Test
    void writeEncryptedClearsThePassphrase() throws Exception {
        char[] passphrase = "secret".toCharArray();
        MacroExportService.writeEncrypted(dir.resolve("m.json"),
                MacroExportService.build(List.of(macro("a", "One", "x"))), passphrase);
        assertEquals("\0\0\0\0\0\0", new String(passphrase));
    }

    @Test
    void mergeAddsNonCollidingMacros() {
        MacroExportService.MergeResult r = MacroExportService.merge(
                List.of(macro("b", "Two", "y")), List.of(macro("a", "One", "x")),
                m -> { throw new AssertionError("policy must not be consulted without a collision"); },
                null);
        assertEquals(2, r.macros().size());
        assertEquals(1, r.added());
        assertEquals(0, r.replaced());
    }

    @Test
    void mergeReplaceOverwritesInPlace() {
        List<Macro> existing = List.of(macro("a", "One", "old"), macro("b", "Two", "y"));
        MacroExportService.MergeResult r = MacroExportService.merge(
                List.of(macro("a", "One updated", "new")), existing,
                m -> MacroExportService.Conflict.REPLACE, null);

        assertEquals(2, r.macros().size());
        assertEquals(1, r.replaced());
        assertEquals(0, r.added());
        assertEquals("One updated", r.macros().get(0).getName()); // kept its position
        assertEquals("a", r.macros().get(0).getId());
    }

    @Test
    void mergeKeepBothAssignsAFreshId() {
        MacroExportService.MergeResult r = MacroExportService.merge(
                List.of(macro("a", "One", "new")), List.of(macro("a", "One", "old")),
                m -> MacroExportService.Conflict.KEEP_BOTH, null);

        assertEquals(2, r.macros().size());
        assertEquals(1, r.added());
        assertEquals("a", r.macros().get(0).getId());
        assertNotEquals("a", r.macros().get(1).getId());
    }

    @Test
    void mergeSkipDiscardsTheImportedMacro() {
        MacroExportService.MergeResult r = MacroExportService.merge(
                List.of(macro("a", "One", "new")), List.of(macro("a", "One", "old")),
                m -> MacroExportService.Conflict.SKIP, null);

        assertEquals(1, r.macros().size());
        assertEquals(1, r.skipped());
        assertEquals(new MacroStep.TextStep("old", 0), r.macros().get(0).getSteps().get(0));
    }

    @Test
    void mergeClearsAHotkeyAlreadyOwnedByAMacroThatStays() {
        Macro existing = macro("a", "One", "x");
        existing.setHotkey("shift ctrl pressed F1");
        Macro incoming = macro("b", "Two", "y");
        incoming.setHotkey("shift ctrl pressed F1");

        MacroExportService.MergeResult r =
                MacroExportService.merge(List.of(incoming), List.of(existing), m -> null, null);

        // Left bound, the imported macro would simply never fire — the first match wins at dispatch.
        assertNull(r.macros().get(1).getHotkey());
        assertEquals(1, r.clearedHotkeys().size());
        assertTrue(r.clearedHotkeys().get(0).contains("Two"));
        assertEquals("shift ctrl pressed F1", r.macros().get(0).getHotkey()); // the existing one is untouched
    }

    @Test
    void mergeKeepsAHotkeyWhenReplacingItsOwnMacro() {
        Macro existing = macro("a", "One", "old");
        existing.setHotkey("shift ctrl pressed F1");
        Macro incoming = macro("a", "One", "new");
        incoming.setHotkey("shift ctrl pressed F1");

        MacroExportService.MergeResult r = MacroExportService.merge(List.of(incoming),
                List.of(existing), m -> MacroExportService.Conflict.REPLACE, null);

        // The only claimant is the macro being replaced, so this is not a conflict.
        assertEquals("shift ctrl pressed F1", r.macros().get(0).getHotkey());
        assertTrue(r.clearedHotkeys().isEmpty());
    }

    @Test
    void openEnvelopeWithoutAPayloadIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MacroExportService.openEnvelope(new EncryptedMacroExport(), "x".toCharArray()));
    }

    @Test
    void aTamperedEnvelopeIsRejectedTheSameWayAWrongPassphraseIs() throws Exception {
        Path file = dir.resolve("m.json");
        MacroExportService.writeEncrypted(file,
                MacroExportService.build(List.of(macro("a", "One", "x"))), "pw".toCharArray());
        EncryptedMacroExport envelope = MacroExportService.readEnvelope(file.toFile());

        // Flip a byte of the ciphertext: GCM must reject it rather than return garbage steps.
        String ct = envelope.box.ciphertext();
        char[] chars = ct.toCharArray();
        chars[0] = (chars[0] == 'A') ? 'B' : 'A';
        envelope.box = new com.katmoda.jterm.security.PassphraseBox.SealedBox(
                envelope.box.salt(), envelope.box.iterations(), envelope.box.nonce(),
                new String(chars));

        // GCM cannot tell "tampered" from "wrong key", so this takes the wrong-passphrase path:
        // null rather than an exception. What matters is that it never yields the original steps.
        assertNull(MacroExportService.openEnvelope(envelope, "pw".toCharArray()));
    }
}
