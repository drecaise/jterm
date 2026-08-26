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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the copy behind the macro manager's <i>Duplicate</i>. Swing-free so the identity rules
 * below are unit-testable; the dialog contributes only the vault prompt and the save.
 *
 * <p>The copy differs from its source in exactly three ways, each load-bearing:</p>
 * <ul>
 *   <li><b>A fresh id.</b> Two macros sharing an id would collide in {@link MacroLibrary#byId}
 *       and in {@code replace}/{@code remove}, which both match by id.</li>
 *   <li><b>No hotkey.</b> {@link MacroLibrary#byHotkey} returns the <em>first</em> match, so a
 *       carried-over hotkey would silently shadow: one of the two macros could never fire.</li>
 *   <li><b>A unique name</b>, suffixed with the next free {@code (n)} — the same convention the
 *       session sidebar uses for a duplicated SSH session.</li>
 * </ul>
 */
public final class MacroDuplicator {

    private MacroDuplicator() {
    }

    /**
     * A copy of {@code plain} with a fresh id, no hotkey, and a name unique among {@code existing}.
     *
     * <p><b>{@code plain} must not be sealed.</b> {@link Macro#sealAad()} binds a sealed blob to the
     * macro's id, so re-identifying a copy that still carries the ciphertext yields something that
     * can never be opened again — a failure that would surface much later as a {@code VaultException}
     * on replay. Unseal through {@link MacroCrypto#resolved} first; the library re-seals the copy
     * under its new id on the next {@link MacroLibrary#save()}.</p>
     *
     * @throws IllegalArgumentException if {@code plain} is still sealed
     */
    public static Macro duplicate(Macro plain, List<Macro> existing) {
        if (plain.isSealed()) {
            throw new IllegalArgumentException(
                    "Cannot duplicate a sealed macro: its id is bound into the blob's AAD. "
                            + "Unseal it with MacroCrypto.resolved first.");
        }
        Set<String> taken = new HashSet<>();
        for (Macro m : existing) {
            taken.add(m.getName());
        }
        Macro copy = plain.copy(); // copies the step list; steps themselves are immutable records
        copy.setId(UUID.randomUUID().toString());
        copy.setHotkey(null);
        copy.setName(uniqueName(plain.getName(), taken));
        return copy;
    }

    /**
     * {@code original} (minus any {@code (n)} counter it already carries) plus the smallest
     * {@code (n)}, n &ge; 1, that is not in {@code taken} — so duplicating "Deploy (1)" gives
     * "Deploy (2)" rather than "Deploy (1) (1)".
     */
    static String uniqueName(String original, Set<String> taken) {
        String base = original.replaceFirst("\\s*\\(\\d+\\)$", "");
        for (int n = 1; ; n++) {
            String candidate = base + " (" + n + ")";
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }
}
