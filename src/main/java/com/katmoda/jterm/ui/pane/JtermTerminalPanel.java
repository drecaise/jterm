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

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.katmoda.jterm.config.AppSettings;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;

/**
 * A {@link TerminalPanel} that adds "paste on right click" behaviour.
 *
 * <p>JediTerm opens its context menu from its own internal mouse listener on a right-click, so
 * a listener added from outside can't suppress it. Intercepting {@link #processMouseEvent} —
 * which dispatches to those listeners — lets us paste and swallow the click before the popup is
 * built. When the preference is on, a plain right-click pastes; holding Ctrl still opens the
 * context menu. When it's off, the default context-menu behaviour is untouched.</p>
 */
final class JtermTerminalPanel extends TerminalPanel {

    private final String pasteActionName;

    JtermTerminalPanel(SettingsProvider settingsProvider, TerminalTextBuffer textBuffer, StyleState styleState) {
        super(settingsProvider, textBuffer, styleState);
        this.pasteActionName = settingsProvider.getPasteActionPresentation().getName();
    }

    /**
     * Restricts drag-to-select to the left mouse button. JediTerm extends the selection on any
     * {@code MOUSE_DRAGGED}, so a right- (or middle-) button drag would select text; swallowing
     * those drags before they reach JediTerm's motion listener prevents it. Mouse-aware remote
     * programs still receive the event so their own drag handling keeps working.
     */
    @Override
    protected void processMouseMotionEvent(MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_DRAGGED
                && !SwingUtilities.isLeftMouseButton(e)
                && !isRemoteMouseAction(e)) {
            return;
        }
        super.processMouseMotionEvent(e);
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
        if (isPlainPasteClick(e)) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                requestFocusInWindow();
                paste();
            } else if (e.getID() == MouseEvent.MOUSE_CLICKED) {
                return; // swallow so JediTerm's default context menu doesn't open
            }
        }
        super.processMouseEvent(e);
    }

    /**
     * A plain (no-Ctrl) right-click while the preference is on and the terminal app isn't
     * itself capturing the mouse (so mouse-aware programs still receive the event).
     */
    private boolean isPlainPasteClick(MouseEvent e) {
        return SwingUtilities.isRightMouseButton(e)
                && AppSettings.get().isPasteOnRightClick()
                && !e.isControlDown()
                && !isRemoteMouseAction(e);
    }

    /** Defers to JediTerm's Paste action, which honours bracketed-paste mode. */
    private void paste() {
        for (TerminalAction action : getActions()) {
            if (pasteActionName.equals(action.getName())) {
                action.actionPerformed(null);
                return;
            }
        }
    }
}
