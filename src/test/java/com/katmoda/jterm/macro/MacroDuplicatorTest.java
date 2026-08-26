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

import com.katmoda.jterm.security.CredentialVault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroDuplicatorTest {

    private static Macro macro(String name, String hotkey, String... lines) {
        Macro m = new Macro();
        m.setName(name);
        m.setHotkey(hotkey);
        List<MacroStep> steps = new ArrayList<>();
        for (String line : lines) {
            steps.add(new MacroStep.TextStep(line, 0));
        }
        m.setSteps(steps);
        return m;
    }

    @Test
    void theCopyGetsAFreshId() {
        Macro original = macro("Deploy", null, "make");
        Macro copy = MacroDuplicator.duplicate(original, List.of(original));
        assertNotEquals(original.getId(), copy.getId());
        assertTrue(!copy.getId().isBlank());
    }

    @Test
    void theHotkeyIsNotCarriedOver() {
        Macro original = macro("Deploy", "shift ctrl pressed F1", "make");
        Macro copy = MacroDuplicator.duplicate(original, List.of(original));
        assertNull(copy.getHotkey());
        assertEquals("shift ctrl pressed F1", original.getHotkey(), "the source must be untouched");
    }

    @Test
    void theNameGetsTheNextFreeCounter() {
        Macro original = macro("Deploy", null, "make");
        assertEquals("Deploy (1)", MacroDuplicator.duplicate(original, List.of(original)).getName());
    }

    @Test
    void aTakenCounterIsSkipped() {
        Macro original = macro("Deploy", null, "make");
        List<Macro> existing = List.of(original, macro("Deploy (1)", null, "make"));
        assertEquals("Deploy (2)", MacroDuplicator.duplicate(original, existing).getName());
    }

    @Test
    void duplicatingACopyDoesNotStackCounters() {
        Macro copy = macro("Deploy (1)", null, "make");
        List<Macro> existing = List.of(macro("Deploy", null, "make"), copy);
        assertEquals("Deploy (2)", MacroDuplicator.duplicate(copy, existing).getName());
    }

    @Test
    void theStepsAreCopiedIntoAnIndependentList() {
        Macro original = macro("Deploy", null, "make", "make install");
        Macro copy = MacroDuplicator.duplicate(original, List.of(original));
        assertEquals(original.getSteps(), copy.getSteps());

        copy.getSteps().clear();
        assertEquals(2, original.getSteps().size(), "the source's steps must not be shared");
    }

    /**
     * The id is bound into a sealed blob's AAD, so a sealed macro must never be re-identified —
     * the result would be ciphertext nothing can open. Callers unseal through
     * {@link MacroCrypto#resolved} first.
     */
    @Test
    void aSealedMacroIsRefused() {
        Macro sealed = macro("Deploy", null);
        sealed.setSealedSteps(new CredentialVault.Blob());
        assertThrows(IllegalArgumentException.class,
                () -> MacroDuplicator.duplicate(sealed, List.of(sealed)));
    }
}
