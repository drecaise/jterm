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
package com.katmoda.jterm.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuitSummaryTest {

    @Test
    void asksWhenAnythingRemoteIsOpen() {
        assertTrue(new QuitSummary(1, 0, 0, 0, 0).shouldConfirm());
        assertTrue(new QuitSummary(0, 1, 0, 0, 0).shouldConfirm());
        assertTrue(new QuitSummary(0, 0, 1, 0, 0).shouldConfirm());
        assertTrue(new QuitSummary(0, 0, 0, 1, 0).shouldConfirm(), "a tunnel alone is worth asking about");
    }

    @Test
    void localTerminalsAloneDoNotAsk() {
        assertFalse(QuitSummary.NOTHING.shouldConfirm());
        assertFalse(new QuitSummary(0, 0, 0, 0, 3).shouldConfirm(),
                "every window starts with a local shell; asking for it would make Cmd+Q always ask");
    }

    @Test
    void theDesignsExampleReadsAsWritten() {
        assertEquals("Quit jterm? 2 remote desktops and 5 SSH sessions will be disconnected.",
                new QuitSummary(2, 5, 0, 0, 0).question());
    }

    @Test
    void singularsListsAndLocalTerminals() {
        assertEquals("Quit jterm? 1 remote desktop will be disconnected.",
                new QuitSummary(1, 0, 0, 0, 0).question());
        assertEquals("Quit jterm? 1 SSH session, 2 SFTP browsers and 1 tunnel will be disconnected.",
                new QuitSummary(0, 1, 2, 1, 0).question());
        assertEquals("Quit jterm? 1 remote desktop, 3 SSH sessions, 1 SFTP browser and 2 tunnels will be "
                + "disconnected, and 1 local terminal will be closed.", new QuitSummary(1, 3, 1, 2, 1).question());
        assertEquals("Quit jterm? 4 local terminals will be closed.", new QuitSummary(0, 0, 0, 0, 4).question());
        assertEquals("Quit jterm?", QuitSummary.NOTHING.question());
    }
}
