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
package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * A {@link JediTerminal} that tells the panel when {@code CSI L} / {@code CSI M} move lines around.
 *
 * <p>Every other way of scrolling goes through {@code JediTerminal.scrollArea}, which notifies the
 * display before touching the buffer, so {@link JtermTerminalPanel#scrollArea} can keep a text
 * selection on its own characters. Insert-line and delete-line are the exception: JediTerm 3.70's
 * {@code insertLines}/{@code deleteLines} call {@code myTerminalTextBuffer} directly and the
 * display never hears about it. The selection then stays on rows whose content has moved out from
 * under it, and the auto-clear correctly — but unhelpfully — drops it.</p>
 *
 * <p>This is not a corner case: it is how vim scrolls backwards. {@code CTRL-E} sets a scroll
 * region and sends a newline at the bottom of it (a real scroll, already handled), while
 * {@code CTRL-Y} sets the same region and sends {@code CSI L} at the top — which is exactly why
 * scrolling down in vim kept a selection and scrolling up lost it.</p>
 */
final class JtermJediTerminal extends JediTerminal {

    private static final Logger LOG = LoggerFactory.getLogger(JtermJediTerminal.class);

    /**
     * Cached private {@code JediTerminal.myScrollRegionBottom}. There is a public
     * {@code getScrollRegionTop()} but no accessor for the bottom, and the region's real bottom is
     * what decides whether a selection sits inside the moving rows — guessing the screen height
     * would shift selections on rows that never moved.
     */
    private static final Field SCROLL_REGION_BOTTOM = resolveScrollRegionBottom();

    private final JtermTerminalPanel panel;

    JtermJediTerminal(TerminalDisplay display, TerminalTextBuffer textBuffer, StyleState styleState) {
        super(display, textBuffer, styleState);
        this.panel = display instanceof JtermTerminalPanel p ? p : null;
    }

    @Override
    public void insertLines(int count) {
        // Notify before mutating, matching the display-then-buffer order JediTerminal.scrollArea
        // uses. The shift is pure row arithmetic, so it doesn't depend on the buffer's new state.
        shiftSelection(count);
        super.insertLines(count);
    }

    @Override
    public void deleteLines(int count) {
        shiftSelection(-count);
        super.deleteLines(count);
    }

    /**
     * Both operations act on the rows from the cursor to the bottom of the scroll region, which is
     * the region {@code TerminalTextBuffer} is handed. {@code getCursorY()} and the region bottom
     * are both 1-based, as {@link JtermTerminalPanel#shiftSelectionForLineChange} expects.
     */
    private void shiftSelection(int dy) {
        if (panel == null || SCROLL_REGION_BOTTOM == null || dy == 0) {
            return;
        }
        int top = getCursorY();
        int bottom;
        try {
            bottom = SCROLL_REGION_BOTTOM.getInt(this);
        } catch (ReflectiveOperationException e) {
            // Best-effort: without it the selection is dropped on this kind of scroll, as before.
            LOG.debug("could not read the scroll region bottom reflectively", e);
            return;
        }
        if (bottom < top) {
            return;
        }
        panel.shiftSelectionForLineChange(top, bottom - top + 1, dy);
    }

    private static Field resolveScrollRegionBottom() {
        try {
            Field f = JediTerminal.class.getDeclaredField("myScrollRegionBottom");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
