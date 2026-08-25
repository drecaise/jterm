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

import com.fasterxml.jackson.core.type.TypeReference;
import com.katmoda.jterm.config.JsonStore;
import com.katmoda.jterm.security.CredentialVault;
import com.katmoda.jterm.security.VaultException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Moves a {@link Macro}'s steps between their plaintext and encrypted-at-rest forms.
 *
 * <p>Swing-free on purpose: it takes an already-unlocked {@link CredentialVault} and throws rather
 * than prompting, so the unlock stays a UI decision (and so this is unit-testable headlessly). The
 * callers are responsible for {@code VaultManager.ensureUnlocked(parent)} first — see
 * {@code ui.macro.MacroManagerDialog} and {@code app.MainWindow}.</p>
 *
 * <p>The steps are serialized to JSON and sealed as one blob under the vault key, with the macro's
 * id as additional authenticated data ({@link Macro#sealAad()}). Nothing here ever sees the master
 * password: the vault key is already in memory once unlocked, which is what keeps macro encryption
 * on the same single secret as saved SSH passwords rather than introducing a second one.</p>
 *
 * <p><b>What this does and does not protect.</b> It defends {@code macros.json} at rest — in
 * backups, sync folders, support bundles, on a lost disk, against other local accounts. It does
 * <em>not</em> defend against anything running as the user: the master password normally comes back
 * from the OS keyring without a prompt, so a process with the user's privileges can unlock the vault
 * exactly as jterm does. It also does nothing about where a secret ends up <em>after</em> a macro
 * runs (shell history, scrollback, the remote host's logs).</p>
 */
public final class MacroCrypto {

    private static final TypeReference<List<MacroStep>> STEP_LIST = new TypeReference<>() { };

    private MacroCrypto() {
    }

    /**
     * Encrypts {@code macro}'s steps under the vault key. A macro that is already sealed is left
     * alone, so this is safe to call over the whole library on every save.
     */
    public static void seal(Macro macro, CredentialVault vault) throws VaultException {
        if (macro.isSealed()) {
            return;
        }
        byte[] json;
        try {
            // writerFor(STEP_LIST), not writeValueAsBytes(list): a bare List erases its element type,
            // and MacroStep is polymorphic, so Jackson would omit the "type" discriminator it needs
            // to read the steps back — the blob would seal fine and refuse to open.
            json = JsonStore.mapper().writerFor(STEP_LIST).writeValueAsBytes(macro.getSteps());
        } catch (Exception e) {
            throw new VaultException("Failed to serialize macro steps", e);
        }
        try {
            macro.setSealedSteps(vault.seal(json, macro.sealAad()));
            // Hold the invariant: exactly one form is populated at a time.
            macro.getSteps().clear();
        } finally {
            Arrays.fill(json, (byte) 0);
        }
    }

    /**
     * Decrypts {@code macro}'s steps back into {@link Macro#getSteps()} and drops the sealed blob.
     * A macro that is already plaintext is left alone.
     *
     * @throws VaultException if the vault is locked, or the blob does not authenticate — which is
     *         what a macro's steps being moved under another macro's name and hotkey looks like.
     */
    public static void unseal(Macro macro, CredentialVault vault) throws VaultException {
        if (!macro.isSealed()) {
            return;
        }
        byte[] json = null;
        try {
            json = vault.open(macro.getSealedSteps(), macro.sealAad());
            List<MacroStep> steps = JsonStore.mapper().readValue(json, STEP_LIST);
            macro.setSteps(steps != null ? steps : new ArrayList<>());
            macro.setSealedSteps(null);
        } catch (VaultException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultException("Failed to decrypt macro \"" + macro.getName() + "\"", e);
        } finally {
            if (json != null) {
                Arrays.fill(json, (byte) 0);
            }
        }
    }

    /** {@link #seal}s every macro in {@code all}. */
    public static void sealAll(List<Macro> all, CredentialVault vault) throws VaultException {
        for (Macro macro : all) {
            seal(macro, vault);
        }
    }

    /** {@link #unseal}s every macro in {@code all} — used when the setting is turned back off. */
    public static void unsealAll(List<Macro> all, CredentialVault vault) throws VaultException {
        for (Macro macro : all) {
            unseal(macro, vault);
        }
    }

    /**
     * A plaintext copy of {@code macro} with its steps resolved, leaving the original untouched.
     * Used by export and by the run/edit paths, which must not flip the library's in-memory macros
     * to plaintext as a side effect of being read.
     */
    public static Macro resolved(Macro macro, CredentialVault vault) throws VaultException {
        Macro copy = macro.copy();
        unseal(copy, vault);
        return copy;
    }

    /** Whether any macro in {@code all} is still sealed (i.e. whether a vault unlock is needed). */
    public static boolean anySealed(List<Macro> all) {
        for (Macro macro : all) {
            if (macro.isSealed()) {
                return true;
            }
        }
        return false;
    }

    /** Marker used by {@link Macro#sealAad()}; kept here so the format is documented in one place. */
    static byte[] aad(String macroId) {
        return ("jterm-macro:" + macroId).getBytes(StandardCharsets.UTF_8);
    }
}
