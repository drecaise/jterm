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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.katmoda.jterm.security.CredentialVault;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named, replayable sequence of {@link MacroStep}s. Optionally bound to a global
 * {@code hotkey} (stored as a {@link javax.swing.KeyStroke#toString()} value, e.g.
 * {@code "shift ctrl F1"}; {@code null} when unbound). Persisted as part of
 * {@code macros.json} (see {@link MacroLibrary}).
 *
 * <p><b>Sealed vs. plaintext.</b> When {@code AppSettings.isEncryptMacros()} is on, the steps are
 * stored as one AES-GCM {@link #getSealedSteps() sealedSteps} blob instead of a readable list.
 * {@code id}, {@code name} and {@code hotkey} stay plaintext on purpose, so the Macros menu and the
 * global hotkey dispatcher work with the vault locked — see {@link MacroCrypto}.</p>
 *
 * <p><b>Invariant:</b> exactly one of {@code steps} / {@code sealedSteps} is populated, in memory as
 * well as on disk. {@link MacroCrypto#unseal} moves a macro from the second form to the first and
 * {@link MacroCrypto#seal} moves it back; neither keeps a copy in the other form. That means
 * {@link #getSteps()} on a still-sealed macro returns an <em>empty</em> list rather than the real
 * steps, so anything that replays or edits a macro must resolve it through {@link MacroCrypto}
 * first. {@link #isSealed()} says which form this instance is in.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Macro {

    private String id = UUID.randomUUID().toString();
    private String name = "Macro";
    private String hotkey;

    // Omitted from the JSON while sealed, so an encrypted macros.json carries no readable steps at
    // all (not even an empty "steps": []) and a half-migrated file stays unambiguous.
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MacroStep> steps = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private CredentialVault.Blob sealedSteps;

    public Macro() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The bound hotkey as a {@code KeyStroke} string, or {@code null} if unbound. */
    public String getHotkey() {
        return hotkey;
    }

    public void setHotkey(String hotkey) {
        this.hotkey = (hotkey != null && !hotkey.isBlank()) ? hotkey : null;
    }

    /**
     * The plaintext steps — <b>empty while {@link #isSealed()}</b>. Resolve a macro through
     * {@link MacroCrypto#unseal} before replaying or editing it.
     */
    public List<MacroStep> getSteps() {
        return steps;
    }

    public void setSteps(List<MacroStep> steps) {
        this.steps = (steps != null) ? steps : new ArrayList<>();
    }

    /** The AES-GCM blob holding the steps while encrypted at rest, or {@code null} when plaintext. */
    public CredentialVault.Blob getSealedSteps() {
        return sealedSteps;
    }

    public void setSealedSteps(CredentialVault.Blob sealedSteps) {
        this.sealedSteps = sealedSteps;
    }

    /** Whether the steps are encrypted and must be unsealed before use. */
    @JsonIgnore
    public boolean isSealed() {
        return sealedSteps != null;
    }

    /**
     * The additional authenticated data binding a sealed blob to <em>this</em> macro. Without it,
     * every blob in {@code macros.json} decrypts under the same vault key, so an attacker with write
     * access could move one macro's steps under another's name and hotkey and have it run.
     */
    @JsonIgnore
    public byte[] sealAad() {
        return MacroCrypto.aad(id);
    }

    /**
     * A deep-ish copy for editing (steps are immutable records, so the list copy suffices). The
     * sealed blob is carried over as-is: it is immutable ciphertext, and keeping it means copying a
     * macro that was never unsealed does not need the vault.
     */
    public Macro copy() {
        Macro c = new Macro();
        c.id = id;
        c.name = name;
        c.hotkey = hotkey;
        c.steps = new ArrayList<>(steps);
        c.sealedSteps = sealedSteps;
        return c;
    }

    @Override
    public String toString() {
        return name;
    }
}
