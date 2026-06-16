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
package com.katmoda.jterm.dnd;

import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.ui.pane.TerminalPane;

/**
 * Detaches a pane from whichever tab's grid currently owns it, so another grid can adopt it. Only
 * the main window knows about all tabs, so it implements this; a grid receiving a cross-tab pane
 * drop uses it to pull the pane out of its source tab (closing that tab if it becomes empty).
 */
@FunctionalInterface
public interface PaneMoveCoordinator {

    /**
     * Remove {@code pane} from its owning grid <em>without</em> closing the session, closing the
     * owning tab if it is left empty.
     *
     * @return the pane's restart factory, so the adopting grid can keep restart working, or
     *         {@code null} if no grid owns the pane.
     */
    SessionFactory detachFromOwner(TerminalPane pane);
}
