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

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalModelListener;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.model.TextBufferChangesListener;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.katmoda.jterm.config.AppSettings;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.IntConsumer;

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

    /** Cached private {@code TerminalPanel.updateSelection(TerminalSelection)} — see {@link #clearSelectionOnEdt()}. */
    private static final Method UPDATE_SELECTION = resolveUpdateSelection();

    private final String pasteActionName;
    private final IntConsumer onCtrlWheelZoom;
    private final TerminalTextBuffer textBuffer;
    /** Last-seen alternate-screen state, to detect editor enter/leave transitions. Accessed off the EDT. */
    private volatile boolean usingAlternateBuffer;

    JtermTerminalPanel(SettingsProvider settingsProvider, TerminalTextBuffer textBuffer, StyleState styleState,
                       IntConsumer onCtrlWheelZoom) {
        super(settingsProvider, textBuffer, styleState);
        this.pasteActionName = settingsProvider.getPasteActionPresentation().getName();
        this.onCtrlWheelZoom = onCtrlWheelZoom;
        this.textBuffer = textBuffer;
        this.usingAlternateBuffer = textBuffer.isUsingAlternateBuffer();
        installSelectionAutoClear();
    }

    // ---- auto-clear a stale selection when its content changes ----

    /**
     * Drops the current selection when the text under it stops being what was selected. JediTerm
     * only clears a selection on a new mouse-press or a scroll-region escape, so a selection
     * survives an in-place repaint (e.g. mouse-wheel scrolling inside vim, which rewrites the
     * viewport cell-by-cell) and an alternate-screen switch (entering/leaving an editor) — leaving
     * the highlight glued to the same screen rows while the content beneath it changes.
     *
     * <p>Two signals cover the cases JediTerm misses:
     * <ul>
     *   <li>{@code linesChanged} — a line that is part of the selection was rewritten in place;
     *   {@code historyCleared} — the buffer was cleared/reset.</li>
     *   <li>{@code modelChanged} + the alternate-buffer flag — the alt screen was switched, which
     *   emits no line-change event.</li>
     * </ul>
     * True scrollback scrolling is left untouched: the selection is in absolute buffer coordinates,
     * so JediTerm already pans it with the content.
     */
    private void installSelectionAutoClear() {
        textBuffer.addChangesListener(new TextBufferChangesListener() {
            @Override
            public void linesChanged(int fromIndex) {
                if (changeAffectsSelection(fromIndex)) {
                    SwingUtilities.invokeLater(JtermTerminalPanel.this::clearSelectionOnEdt);
                }
            }

            @Override
            public void historyCleared() {
                if (getSelection() != null) {
                    SwingUtilities.invokeLater(JtermTerminalPanel.this::clearSelectionOnEdt);
                }
            }

            @Override
            public void linesDiscardedFromHistory(List<com.jediterm.terminal.model.TerminalLine> lines) {
                // No-op: the selection is in absolute coordinates that JediTerm shifts to match.
            }

            @Override
            public void widthResized() {
                // No-op: JediTerm preserves the selection across a width change itself.
            }
        });

        textBuffer.addModelListener(new TerminalModelListener() {
            @Override
            public void modelChanged() {
                boolean nowAlternate = textBuffer.isUsingAlternateBuffer();
                if (nowAlternate != usingAlternateBuffer) {
                    usingAlternateBuffer = nowAlternate;
                    if (getSelection() != null) {
                        SwingUtilities.invokeLater(JtermTerminalPanel.this::clearSelectionOnEdt);
                    }
                }
            }
        });
    }

    /**
     * Whether a {@code linesChanged(fromIndex)} event can have disturbed the current selection.
     * The change spans {@code fromIndex} downward — JediTerm's contract is "the line at fromIndex
     * and probably some lines after it" — and an insert/delete-line scroll (CSI L/M, which vim uses
     * for one scroll direction) reports its scroll-region top here while shifting every line below
     * it. So the selection is affected whenever {@code fromIndex} is at or above its bottom row;
     * content streaming in strictly <em>below</em> the selection (the common case while
     * copy-on-select is on) leaves it intact.
     *
     * <p>Called on the terminal reader thread, so it stays a cheap field read in the common
     * (no-selection) case; reading the selection's points cross-thread is the same benign race
     * JediTerm itself relies on. Returns false mid-drag (end not yet set).
     */
    private boolean changeAffectsSelection(int fromIndex) {
        TerminalSelection selection = getSelection();
        if (selection == null) {
            return false;
        }
        Point start = selection.getStart();
        Point end = selection.getEnd();
        if (start == null || end == null) {
            return false;
        }
        return fromIndex <= Math.max(start.y, end.y);
    }

    /** Clears the selection (notifying JediTerm's selection listeners) and repaints. EDT only. */
    private void clearSelectionOnEdt() {
        if (getSelection() == null || UPDATE_SELECTION == null) {
            return;
        }
        try {
            UPDATE_SELECTION.invoke(this, (TerminalSelection) null);
        } catch (ReflectiveOperationException ignored) {
            // Best-effort: without it the stale selection lingers until the next mouse-press.
        }
        repaint();
    }

    private static Method resolveUpdateSelection() {
        try {
            Method m = TerminalPanel.class.getDeclaredMethod("updateSelection", TerminalSelection.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Ctrl + scroll-wheel zooms this pane's font instead of scrolling the buffer. JediTerm scrolls
     * via a {@code MouseWheelListener} it registers in {@code init(JScrollBar)}, so consuming the
     * event here — before {@code super} dispatches to that listener — cleanly suppresses the scroll.
     * Wheel-up ({@code rotation < 0}) increases the size. We bow out when a mouse-aware remote
     * program is capturing the wheel so its own handling keeps working.
     */
    @Override
    protected void processMouseWheelEvent(MouseWheelEvent e) {
        if (e.isControlDown() && !isRemoteMouseAction(e)) {
            onCtrlWheelZoom.accept(-e.getWheelRotation());
            e.consume();
            return;
        }
        super.processMouseWheelEvent(e);
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
