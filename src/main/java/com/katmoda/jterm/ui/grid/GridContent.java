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
package com.katmoda.jterm.ui.grid;

import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.ui.theme.ThemeColors;

import javax.swing.JComponent;

/**
 * A cell hosted by {@link PaneGrid}. Implemented by {@link com.katmoda.jterm.ui.pane.TerminalPane}
 * (a terminal) and by the on-demand SFTP browser, so the grid can lay out, focus, theme, close and
 * drag-decorate either kind of content without knowing which it is.
 *
 * <p>Terminal-only behavior (input broadcast, the session-stopped/restart screen) is not part of
 * this contract — {@code PaneGrid} reaches for it via {@code instanceof TerminalPane} in the few
 * places it applies. Every implementor is a {@link JComponent}; {@link #ui()} returns {@code this}
 * so the grid can add it, border it and make it a drop target.</p>
 */
public interface GridContent {

    /** This content as a Swing component (always {@code this}). */
    JComponent ui();

    /** Tear down the content and its backing resources (session/connection, timers, channels). */
    void closeContent();

    /** Recolor for a light/dark theme switch, in place (no recreate). */
    void applyTheme(ThemeColors theme);

    /** Move keyboard focus into the content. */
    void focusContent();

    /** Set the callback fired when this content gains focus (so the grid marks it active). */
    void setOnFocus(Runnable onFocus);

    /** Set the callback fired when the content's backing session/connection ends. */
    void setOnContentEnded(Runnable onEnded);

    /** Full-border highlight while a dragged pane hovers this cell. */
    void showMoveHint();

    /** Edge highlight showing where a dropped session would split (column/row). */
    void showDropHint(DropRegion region);

    /** Clear any drag hint, restoring the resting border. */
    void clearDropHint();

    /** Short label for tab decoration when this content is the active cell. */
    String displayTitle();
}
