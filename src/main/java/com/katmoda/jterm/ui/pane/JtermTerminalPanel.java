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
import com.jediterm.terminal.model.SelectionUtil;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link TerminalPanel} that adds "paste on right click" behaviour and keeps a text selection
 * on the characters it was made on while the terminal scrolls.
 *
 * <p>JediTerm opens its context menu from its own internal mouse listener on a right-click, so
 * a listener added from outside can't suppress it. Intercepting {@link #processMouseEvent} —
 * which dispatches to those listeners — lets us paste and swallow the click before the popup is
 * built. When the preference is on, a plain right-click always pastes — even while a mouse-aware
 * program (e.g. an editor, or a CLI that enabled xterm mouse reporting) is running, matching the
 * PuTTY/Windows-Terminal convention. Holding Ctrl opens the context menu; holding Shift bypasses
 * the paste and falls through to JediTerm's default handling. When the preference is off, the
 * default context-menu behaviour is untouched.</p>
 */
final class JtermTerminalPanel extends TerminalPanel {

    private static final Logger LOG = LoggerFactory.getLogger(JtermTerminalPanel.class);

    /** Cached private {@code TerminalPanel.updateSelection(TerminalSelection)} — see {@link #clearSelectionOnEdt()}. */
    private static final Method UPDATE_SELECTION = resolveUpdateSelection();

    /** Cached private {@code TerminalPanel.mySelection} — see {@link #restoreSelection(TerminalSelection)}. */
    private static final Field SELECTION_FIELD = resolveSelectionField();

    private final String pasteActionName;
    private final IntConsumer onCtrlWheelZoom;
    private final TerminalTextBuffer textBuffer;
    /** Last-seen alternate-screen state, to detect editor enter/leave transitions. Accessed off the EDT. */
    private volatile boolean usingAlternateBuffer;
    /**
     * The text the selection covered when it was made — {@code null} when there is no complete
     * selection. Compared against the live text to decide whether a change really disturbed the
     * selection. Written from the EDT (mouse) and the reader thread (scroll), hence volatile.
     */
    private volatile String selectedSnapshot;
    /** Guards against queuing more than one pending verification during a burst of output. */
    private final AtomicBoolean verifyQueued = new AtomicBoolean();

    JtermTerminalPanel(SettingsProvider settingsProvider, TerminalTextBuffer textBuffer, StyleState styleState,
                       IntConsumer onCtrlWheelZoom) {
        super(settingsProvider, textBuffer, styleState);
        this.pasteActionName = settingsProvider.getPasteActionPresentation().getName();
        this.onCtrlWheelZoom = onCtrlWheelZoom;
        this.textBuffer = textBuffer;
        this.usingAlternateBuffer = textBuffer.isUsingAlternateBuffer();
        addSelectionListener(selection -> selectedSnapshot = snapshotOf(selection));
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
     * Scrolling is deliberately <em>not</em> one of these cases. Panning the viewport over the
     * scrollback leaves the selection alone — rows are absolute and JediTerm paints them against
     * the scroll origin — while a scroll of the buffer itself renumbers those rows and is
     * compensated by {@link #scrollArea}.
     *
     * <p>A {@code linesChanged} only reports where a change <em>starts</em>, so it can't say on its
     * own whether the selection was hit; it is used as a cheap filter, and the verdict comes from
     * comparing the selected text against {@link #selectedSnapshot} — see
     * {@link #verifySelectionSoon()}.</p>
     */
    private void installSelectionAutoClear() {
        textBuffer.addChangesListener(new TextBufferChangesListener() {
            @Override
            public void linesChanged(int fromIndex) {
                if (changeAffectsSelection(fromIndex)) {
                    verifySelectionSoon();
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
                // Scrollback rows are numbered backwards from the newest, so evicting the oldest
                // lines doesn't renumber the survivors and nothing needs shifting. But a selection
                // that reached into the evicted lines is no longer addressable — this is how a
                // scroll that overflows a full scrollback ends up clearing it, since scrollArea
                // runs before the buffer scrolls and can't see the eviction coming.
                TerminalSelection selection = getSelection();
                if (selection == null) {
                    return;
                }
                Point start = selection.getStart();
                Point end = selection.getEnd();
                if (start != null && end != null
                        && Math.min(start.y, end.y) < -textBuffer.getHistoryLinesCount()) {
                    SwingUtilities.invokeLater(JtermTerminalPanel.this::clearSelectionOnEdt);
                }
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
     * Whether a {@code linesChanged(fromIndex)} event <em>could</em> have disturbed the current
     * selection — a cheap filter in front of {@link #verifySelectionSoon()}, not the verdict.
     * The change spans {@code fromIndex} downward — JediTerm's contract is "the line at fromIndex
     * and probably some lines after it" — and an insert/delete-line scroll (CSI L/M, which vim uses
     * for one scroll direction) reports its scroll-region top here while shifting every line below
     * it. So anything at or above the selection's bottom row is worth a look; content streaming in
     * strictly <em>below</em> the selection — the common case, a command printing under a
     * highlight — is dismissed here and costs nothing further.
     *
     * <p>Deliberately generous, because being wrong in this direction costs one text comparison.
     * Being wrong the other way would leave a highlight on rewritten text.</p>
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

    /**
     * Queues a check that the selected text is still the text that was selected, and clears the
     * selection if it isn't.
     *
     * <p>This is the only thing that decides an auto-clear, because it asks the actual question.
     * Row arithmetic cannot: {@code linesChanged} reports only where a change starts, and the two
     * directions of a pager look completely different from there. Scrolling forward in {@code less}
     * writes the newly exposed line at the <em>bottom</em> of the screen, below the selection;
     * scrolling back emits a reverse index and writes it at the <em>top</em>, above the selection —
     * which a "did anything change at or above my last row?" test reads as a hit. That asymmetry is
     * exactly why scrolling up used to drop the highlight while scrolling down kept it. Comparing
     * the text is indifferent to where the change landed.</p>
     *
     * <p>Coalesced: a burst of output queues one pass, not one per line. It runs on the EDT so it
     * can clear through the same path a user action would.</p>
     */
    private void verifySelectionSoon() {
        if (verifyQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::verifySelectionOnEdt);
        }
    }

    private void verifySelectionOnEdt() {
        verifyQueued.set(false);
        boolean stale;
        // The buffer lock is what makes reading the selection and its snapshot atomic. JediTerminal
        // holds that lock across a whole scroll — display first, buffer second — so without it this
        // can land in the middle of scrollArea and see the shifted selection next to a snapshot that
        // super's clear has just nulled, then throw away a perfectly good highlight. That race is
        // timing-dependent and showed up as a scroll that survived or didn't depending on load.
        textBuffer.lock();
        try {
            TerminalSelection selection = getSelection();
            if (selection == null) {
                return;
            }
            // A null reading means the selection has left the buffer; either way, anything other
            // than the text it was made on means it is no longer the user's selection.
            String now = snapshotOf(selection);
            stale = now == null || !now.equals(selectedSnapshot);
        } finally {
            textBuffer.unlock();
        }
        if (stale) {
            clearSelectionOnEdt();
        }
    }

    /**
     * The text a selection covers, or {@code null} if it is absent, still mid-drag (no end point
     * yet), or reaching outside the buffer. Takes the buffer lock, which is reentrant — the reader
     * thread already holds it when a scroll drives this.
     */
    private String snapshotOf(TerminalSelection selection) {
        if (selection == null) {
            return null;
        }
        Point start = selection.getStart();
        Point end = selection.getEnd();
        if (start == null || end == null) {
            return null;
        }
        textBuffer.lock();
        try {
            if (Math.min(start.y, end.y) < -textBuffer.getHistoryLinesCount()
                    || Math.max(start.y, end.y) >= textBuffer.getHeight()) {
                return null;
            }
            // pointsForRun sorts the ends and makes the last column inclusive, as JediTerm's own
            // copy does; it copies the points rather than mutating the selection.
            var run = selection.pointsForRun(textBuffer.getWidth());
            return SelectionUtil.getSelectionText(run.getFirst(), run.getSecond(), textBuffer);
        } finally {
            textBuffer.unlock();
        }
    }

    /**
     * Moves the selection with the content instead of dropping it. JediTerm's
     * {@link TerminalPanel#scrollArea} clears the selection unconditionally, so every line of
     * output that pushed the screen up — a command printing, Enter at the bottom of the screen, a
     * pager scrolling its region — used to lose the highlight.
     *
     * <p>{@code super} still has to run: it owns the private {@code scrollDy} counter that
     * {@code updateScrolling} drains to keep the scrollbar in step, and nothing else can reach it.
     * So the selection is captured first, {@code super} clears it, and the shifted copy goes back.</p>
     *
     * <p>Called on the terminal reader thread with the text buffer locked, and — this is what
     * decides the arithmetic — <em>before</em> the buffer itself scrolls: {@code JediTerminal}
     * calls {@code myDisplay.scrollArea(...)} and only then
     * {@code myTerminalTextBuffer.scrollArea(...)}. The history count read here is thus the
     * pre-scroll one, so the rows this scroll is about to append are added by hand. When the
     * scrollback is already at its cap those rows evict the oldest history rather than growing it;
     * that case is caught a moment later by {@code linesDiscardedFromHistory}, which sees the real
     * count.</p>
     */
    @Override
    public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        TerminalSelection selection = getSelection();
        // Read the points before super clears the selection; mid-drag (no end yet) there is
        // nothing to track. super's clear notifies listeners, which nulls the snapshot, so it is
        // held here and put back with the selection — a shift moves rows, not text.
        Point start = selection != null ? selection.getStart() : null;
        Point end = selection != null ? selection.getEnd() : null;
        String snapshot = selectedSnapshot;

        super.scrollArea(scrollRegionTop, scrollRegionSize, dy);

        // TerminalTextBuffer.scrollArea appends the rows it removes to the scrollback whenever the
        // region starts at row 1, which renumbers everything already in there.
        shiftSelection(start, end, snapshot, scrollRegionTop, scrollRegionSize, dy,
                scrollRegionTop == 1 && dy < 0);
    }

    /**
     * Compensates a {@code CSI L} / {@code CSI M} line insert/delete, which moves content exactly
     * as a scroll does but never reaches a {@link TerminalPanel}.
     *
     * <p>{@code JediTerminal.insertLines}/{@code deleteLines} call the text buffer straight out,
     * with no {@code myDisplay.scrollArea(...)} alongside — so nothing here would otherwise hear
     * about it. That is the whole reason scrolling <em>up</em> in vim lost the selection while
     * scrolling down kept it: {@code CTRL-E} sets a scroll region and sends a newline at its
     * bottom (a real scroll), whereas {@code CTRL-Y} sends {@code CSI L} at the top.</p>
     *
     * <p>Unlike {@link #scrollArea}, this must <em>not</em> touch JediTerm's {@code scrollDy}:
     * JediTerm never counted this movement, so adding to it would put the scrollbar out of step.
     * These rows are also discarded rather than appended to the scrollback, even at row 1.</p>
     *
     * @param regionTop 1-based first row of the affected region, as JediTerm counts it
     */
    void shiftSelectionForLineChange(int regionTop, int regionSize, int dy) {
        TerminalSelection selection = getSelection();
        Point start = selection != null ? selection.getStart() : null;
        Point end = selection != null ? selection.getEnd() : null;
        shiftSelection(start, end, selectedSnapshot, regionTop, regionSize, dy, false);
    }

    /**
     * Puts the selection back on its own characters after content moved by {@code dy}. Does nothing
     * when the move can't be tracked exactly — the caller's clear stands, or, where there was none,
     * {@link #verifySelectionSoon()} notices the text no longer matches and clears it then.
     */
    private void shiftSelection(Point start, Point end, String snapshot,
                                int regionTop, int regionSize, int dy, boolean toHistory) {
        if (start == null || end == null) {
            return;
        }
        int regionTop0 = regionTop - 1;
        int historyLines = textBuffer.getHistoryLinesCount() + (toHistory ? -dy : 0);
        SelectionScroll.Rows rows = SelectionScroll.shiftForScroll(start.y, end.y, regionTop0,
                regionTop0 + regionSize - 1, dy, toHistory, textBuffer.getHeight(), historyLines);
        if (rows == null) {
            return;
        }
        restoreSelection(new TerminalSelection(new Point(start.x, rows.startY()),
                new Point(end.x, rows.endY())), snapshot);
    }

    /**
     * Installs {@code selection} <em>without</em> notifying JediTerm's selection listeners, and
     * repaints.
     *
     * <p>A scroll renumbers rows; it does not change what is selected. That distinction matters
     * because {@code TerminalPane.installCopyOnSelect} copies on every selection event — routing a
     * shift through {@code updateSelection} would re-push the same text to the clipboard once per
     * scrolled line, overwriting whatever the user had copied somewhere else. Hence the direct
     * field write instead of the setter {@link #clearSelectionOnEdt()} uses (a genuine clear
     * <em>should</em> notify).</p>
     *
     * <p>The caller passes a freshly built {@link TerminalSelection} rather than mutating one in
     * place with {@code shiftY}: this runs on the reader thread while the EDT paints, and
     * publishing a new object as a single reference write means a paint can never catch the
     * selection half-shifted.</p>
     */
    private void restoreSelection(TerminalSelection selection, String snapshot) {
        if (SELECTION_FIELD == null) {
            return;
        }
        try {
            // Snapshot first: a reader that catches the gap then sees no selection yet and bows
            // out, rather than a selection with no snapshot to compare against.
            selectedSnapshot = snapshot;
            SELECTION_FIELD.set(this, selection);
        } catch (ReflectiveOperationException e) {
            // Best-effort: without it the selection is dropped on scroll, as it was before.
            LOG.debug("could not restore terminal selection reflectively", e);
            return;
        }
        repaint();
    }

    /** Clears the selection (notifying JediTerm's selection listeners) and repaints. EDT only. */
    private void clearSelectionOnEdt() {
        if (getSelection() == null || UPDATE_SELECTION == null) {
            return;
        }
        try {
            UPDATE_SELECTION.invoke(this, (TerminalSelection) null);
        } catch (ReflectiveOperationException e) {
            // Best-effort: without it the stale selection lingers until the next mouse-press.
            LOG.debug("could not clear terminal selection reflectively", e);
        }
        repaint();
    }

    private static Field resolveSelectionField() {
        try {
            Field f = TerminalPanel.class.getDeclaredField("mySelection");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
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
     *
     * <p>Otherwise the wheel is left to JediTerm, with one repair: before forwarding a wheel event
     * to a mouse-aware program it drops the selection outright, from a listener registered inside
     * {@code addTerminalMouseListener} that can't be unregistered. Scrolling on its own shouldn't
     * destroy a selection, so it goes back afterwards — whether it then <em>survives</em> is left
     * to the content. A program that scrolls its region is tracked by {@link #scrollArea}; one
     * that repaints the rows underneath trips {@code installSelectionAutoClear}, which is the right
     * answer, since the highlight would otherwise sit on text the user never chose.</p>
     */
    @Override
    protected void processMouseWheelEvent(MouseWheelEvent e) {
        if (e.isControlDown() && !isRemoteMouseAction(e)) {
            onCtrlWheelZoom.accept(-e.getWheelRotation());
            e.consume();
            return;
        }
        TerminalSelection before = getSelection();
        String snapshot = selectedSnapshot;
        super.processMouseWheelEvent(e);
        if (before != null && getSelection() == null) {
            restoreSelection(before, snapshot);
        }
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
            }
            // Swallow every right-button event (PRESSED/RELEASED/CLICKED): stops JediTerm's
            // default context menu from opening, and stops a stray button-3 report from
            // reaching a mouse-aware program that had enabled mouse reporting.
            return;
        }
        super.processMouseEvent(e);
    }

    /**
     * A plain (no-Ctrl, no-Shift) right-click while the preference is on. This intentionally
     * ignores mouse reporting: the paste fires even while a mouse-aware program is capturing the
     * mouse. Holding Ctrl opens the context menu instead; holding Shift falls through to
     * JediTerm's default handling.
     */
    private boolean isPlainPasteClick(MouseEvent e) {
        return SwingUtilities.isRightMouseButton(e)
                && AppSettings.get().isPasteOnRightClick()
                && !e.isControlDown()
                && !e.isShiftDown();
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
