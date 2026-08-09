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

import com.jediterm.terminal.TtyConnector;
import com.katmoda.jterm.broadcast.BroadcastBus;
import com.katmoda.jterm.broadcast.BroadcastingTtyConnector;
import com.katmoda.jterm.broadcast.PaneBroadcastBus;
import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.dnd.DetachedPane;
import com.katmoda.jterm.dnd.LocalTransferable;
import com.katmoda.jterm.dnd.PaneMoveCoordinator;
import com.katmoda.jterm.dnd.PaneTransferable;
import com.katmoda.jterm.dnd.SessionDropHandler;
import com.katmoda.jterm.dnd.SessionTransferable;
import com.katmoda.jterm.dnd.WslTransferable;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.pane.PaneActivity;
import com.katmoda.jterm.ui.pane.TerminalPane;
import com.katmoda.jterm.ui.theme.ThemeColors;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One tab's pane layout: a uniform grid of up to {@value #MAX}×{@value #MAX} cells, sized by
 * per-row/per-column weights that the user adjusts by dragging the gutters between cells
 * (see {@link WeightedGridLayout}). Each in-bounds cell either holds a {@link GridContent} (a terminal
 * {@link TerminalPane} or the on-demand SFTP browser) or is empty (re-openable). Splitting
 * grows a dimension; closing empties a cell and collapses a fully-empty trailing row/column
 * so the grid stays rectangular.
 *
 * <p>Also hosts broadcast fan-out ({@link BroadcastBus}) and session drag-and-drop drops.
 * Broadcast and the session-stopped/restart screen are terminal-only, so those paths reach
 * for {@link TerminalPane} via {@code instanceof}; everything structural works on
 * {@link GridContent}.</p>
 */
public final class PaneGrid extends JPanel implements BroadcastBus {

    private static final Logger LOG = LoggerFactory.getLogger(PaneGrid.class);

    public static final int MAX = 3;

    private static final int CONTENT_BORDER = 2;
    /** Width of the draggable gap between cells (also the visible seam). */
    private static final int GUTTER = 6;
    /** A row/column can never be dragged below this many pixels. */
    private static final int MIN_CELL_PX = 80;

    private final GridContent[][] panes = new GridContent[MAX][MAX];
    /** How to recreate the session in each cell (for restart), parallel to {@link #panes}. */
    private final SessionFactory[][] factories = new SessionFactory[MAX][MAX];
    private int rows = 1;
    private int cols = 1;
    private int activeRow = 0;
    private int activeCol = 0;
    /** Per-axis size weights (only the first {@link #rows}/{@link #cols} entries are live). */
    private final double[] rowWeights = equalWeights();
    private final double[] colWeights = equalWeights();
    private final WeightedGridLayout gridLayout =
            new WeightedGridLayout(rowWeights, colWeights, GUTTER);
    /** Divider currently being dragged, or {@code null}. */
    private WeightedGridLayout.Divider dragging;
    /** Divider under the cursor (accent-tinted), or {@code null}. */
    private WeightedGridLayout.Divider hovered;
    /** Owns broadcast on/off state and the keystroke fan-out over this grid's registered panes. */
    private final PaneBroadcastBus broadcastBus = new PaneBroadcastBus();
    /** True while this grid's tab is the selected (front) one; suppresses activity flagging. */
    private boolean foreground = false;
    private SessionDropHandler dropHandler;
    private PaneMoveCoordinator moveCoordinator;
    private Runnable onActiveChanged;
    private Runnable onActivity;
    private Runnable onEmpty;
    private BiConsumer<TerminalSession, SessionFactory> onOpenSessionInNewTab;

    public PaneGrid() {
        setLayout(gridLayout);
        setOpaque(true); // the gutters show the themed panel background
        installResizeHandler();
    }

    public void setDropHandler(SessionDropHandler dropHandler) {
        this.dropHandler = dropHandler;
    }

    /** Provides cross-tab pane detachment so this grid can adopt a pane dropped from another tab. */
    public void setMoveCoordinator(PaneMoveCoordinator moveCoordinator) {
        this.moveCoordinator = moveCoordinator;
    }

    /** Fired whenever the active pane or its content changes, so the owning tab can re-decorate. */
    public void setOnActiveChanged(Runnable onActiveChanged) {
        this.onActiveChanged = onActiveChanged;
    }

    /** Fired when the last pane is removed (e.g. "exit" on the stopped screen), so the owning
     *  tab can close itself instead of leaving an empty grid. */
    public void setOnEmpty(Runnable onEmpty) {
        this.onEmpty = onEmpty;
    }

    /** Fired when a pane's background-activity state changes, so the owning tab can re-decorate. */
    public void setOnActivity(Runnable onActivity) {
        this.onActivity = onActivity;
    }

    /** Fired to open an already-built session in a fresh tab (used when duplicating a pane). */
    public void setOnOpenSessionInNewTab(BiConsumer<TerminalSession, SessionFactory> handler) {
        this.onOpenSessionInNewTab = handler;
    }

    /**
     * Marks whether this grid's tab is in front. Going to the front means everything is "seen", so
     * every pane's activity is cleared and the tab re-decorated. Activity only accrues while in the
     * background (see {@link #markOutput} and {@link #handleSessionEnd}).
     */
    public void setForeground(boolean fg) {
        this.foreground = fg;
        if (fg) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (panes[r][c] instanceof TerminalPane pane) {
                        pane.setActivity(PaneActivity.NONE);
                    }
                }
            }
            fireActivity();
        }
    }

    /** This grid's live row/column counts and per-cell activity, for the tab indicator. */
    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public int activeRow() {
        return activeRow;
    }

    public int activeCol() {
        return activeCol;
    }

    /** A cell's coordinates and its restart factory, used to clone a grid's layout (duplicate tab). */
    public record CellSpec(int row, int col, SessionFactory factory) {
    }

    /**
     * Snapshot of every occupied cell that can be recreated: its position plus the
     * {@link SessionFactory} that opens a fresh equivalent session. Cells without a factory
     * (e.g. the SFTP browser) are omitted — they can't be duplicated.
     */
    public java.util.List<CellSpec> cellSpecs() {
        java.util.List<CellSpec> specs = new java.util.ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null && factories[r][c] != null) {
                    specs.add(new CellSpec(r, c, factories[r][c]));
                }
            }
        }
        return specs;
    }

    /**
     * Resize this (freshly created) grid to {@code rows}×{@code cols} with every cell empty and the
     * given active cell, so {@link #placeSessionInCell} can fill specific cells as duplicated
     * sessions connect (mirrors the empty-then-place flow of an async SSH tab).
     */
    public void prepareEmptyGrid(int rows, int cols, int activeRow, int activeCol) {
        this.rows = Math.max(1, Math.min(MAX, rows));
        this.cols = Math.max(1, Math.min(MAX, cols));
        equalizeWeights(rowWeights, this.rows);
        equalizeWeights(colWeights, this.cols);
        this.activeRow = Math.max(0, Math.min(this.rows - 1, activeRow));
        this.activeCol = Math.max(0, Math.min(this.cols - 1, activeCol));
        relayout();
    }

    /** Whether the cell at (r,c) holds content (a terminal or the SFTP browser). */
    public boolean isCellOccupied(int r, int c) {
        return r >= 0 && r < rows && c >= 0 && c < cols && panes[r][c] != null;
    }

    /** Activity of the cell at (r,c); {@link PaneActivity#NONE} for empty or non-terminal cells. */
    public PaneActivity activityAt(int r, int c) {
        if (r >= 0 && r < rows && c >= 0 && c < cols && panes[r][c] instanceof TerminalPane pane) {
            return pane.activity();
        }
        return PaneActivity.NONE;
    }

    private void fireActivity() {
        if (onActivity != null) {
            onActivity.run();
        }
    }

    /**
     * A background pane produced output (EDT, coalesced via the connector). Flag it as having
     * unread output unless the tab is in front (visible → already seen) or the pane has already
     * disconnected (the more urgent state wins).
     */
    private void markOutput(TerminalPane pane) {
        if (foreground || locate(pane) == null || pane.activity() == PaneActivity.DISCONNECTED) {
            return;
        }
        if (pane.activity() != PaneActivity.NEW_OUTPUT) {
            pane.setActivity(PaneActivity.NEW_OUTPUT);
            fireActivity();
        }
    }

    /** The currently focused cell if it's a terminal, or {@code null} (empty or non-terminal). */
    public TerminalPane activePane() {
        GridContent content = panes[activeRow][activeCol];
        return (content instanceof TerminalPane tp) ? tp : null;
    }

    /** The currently focused cell's content, or {@code null} if the active cell is empty. */
    public GridContent activeContent() {
        return panes[activeRow][activeCol];
    }

    /** Populate the initial single cell with a local shell. */
    public void openInitialLocal() {
        openLocalAt(0, 0);
        relayout();
        focusActive();
    }

    /** Lay out an empty single cell (used by tabs that will receive a session asynchronously). */
    public void initEmpty() {
        relayout();
    }

    // ---- structural operations (invoked by the global shortcut dispatcher) ----

    /** ctrl+RIGHT: add a column (if room) and open a local shell in it. */
    public void splitColumn() {
        openLocal(this::splitColumnAndOpen);
    }

    /** ctrl+DOWN: add a row (if room) and open a local shell in it. */
    public void splitRow() {
        openLocal(this::splitRowAndOpen);
    }

    /** Add a column (if room) and open the given session in it; else replace the active cell. */
    public void splitColumnAndOpen(TerminalSession session, SessionFactory factory) {
        if (cols < MAX) {
            growWeight(colWeights, cols);
            int newCol = cols;
            cols++;
            placeAt(activeRow, newCol, session, factory);
        } else {
            replaceActiveContent(session, factory);
        }
        relayout();
        focusActive();
    }

    /** Add a row (if room) and open the given session in it; else replace the active cell. */
    public void splitRowAndOpen(TerminalSession session, SessionFactory factory) {
        if (rows < MAX) {
            growWeight(rowWeights, rows);
            int newRow = rows;
            rows++;
            placeAt(newRow, activeCol, session, factory);
        } else {
            replaceActiveContent(session, factory);
        }
        relayout();
        focusActive();
    }

    /** Drop/context-menu entry: split relative to a specific cell, then open the session. */
    public void splitFromPaneAndOpen(GridContent target, DropRegion region,
                                     TerminalSession session, SessionFactory factory) {
        int[] pos = locate(target);
        if (pos != null) {
            activeRow = pos[0];
            activeCol = pos[1];
        }
        if (region == DropRegion.COLUMN) {
            splitColumnAndOpen(session, factory);
        } else {
            splitRowAndOpen(session, factory);
        }
    }

    /**
     * Place pre-built content (e.g. the SFTP browser) in the best available split: the empty
     * in-bounds cell nearest the active pane if there is one, else a new column, else a new row.
     * Reusing an empty cell is preferred over enlarging the grid so a visible blank pane gets filled
     * instead of pushing the layout wider/taller. Returns {@code false} when the grid is full (3×3
     * with no empties) so the caller can open a new tab instead.
     */
    public boolean openContentInBestSplit(GridContent content) {
        if (content == null) {
            return true;
        }
        int[] empty = nearestEmptyCell();
        if (empty != null) {
            placeExistingPaneAt(empty[0], empty[1], content, null);
        } else if (cols < MAX) {
            growWeight(colWeights, cols);
            int newCol = cols;
            cols++;
            placeExistingPaneAt(activeRow, newCol, content, null);
        } else if (rows < MAX) {
            growWeight(rowWeights, rows);
            int newRow = rows;
            rows++;
            placeExistingPaneAt(newRow, activeCol, content, null);
        } else {
            return false;
        }
        relayout();
        focusActive();
        return true;
    }

    /** Place pre-built content in the active cell (replacing any existing content). */
    public void placeContentInActive(GridContent content) {
        if (content == null) {
            return;
        }
        GridContent existing = panes[activeRow][activeCol];
        if (existing != null) {
            unbind(existing);
            existing.closeContent();
        }
        placeExistingPaneAt(activeRow, activeCol, content, null);
        relayout();
        focusActive();
    }

    /** ctrl+UP: close the focused cell; shrink the grid around any row/column it empties. */
    public void closeActivePane() {
        GridContent content = panes[activeRow][activeCol];
        if (content == null) {
            return;
        }
        unbind(content);
        content.closeContent();
        panes[activeRow][activeCol] = null;
        factories[activeRow][activeCol] = null;
        compactGrid();
        relayout();
        moveActiveToExistingPane();
        focusActive();
    }

    /** Open a fresh local shell in the active cell (replacing any existing pane). */
    public void openLocalInActive() {
        openLocal(this::placeSessionInActive);
    }

    /** Place an already-connected session in the active cell (replacing any existing pane). */
    public void placeSessionInActive(TerminalSession session, SessionFactory factory) {
        replaceActiveContent(session, factory);
        relayout();
        focusActive();
    }

    /**
     * Fill a specific empty cell from a drop, focusing it. If the cell is no longer an empty,
     * in-bounds cell (it was filled or the grid collapsed while an SSH drop connected), the session
     * opens in the active cell instead, so a dropped session is never silently lost.
     */
    public void placeSessionInCell(int row, int col, TerminalSession session, SessionFactory factory) {
        if (session == null) {
            return;
        }
        if (row >= 0 && row < rows && col >= 0 && col < cols && panes[row][col] == null) {
            placeAt(row, col, session, factory);
            relayout();
            focusActive();
        } else {
            placeSessionInActive(session, factory);
        }
    }

    /**
     * Open the given session in the best available split: the empty in-bounds cell nearest the
     * active pane if there is one, else a new column, else a new row. Mirrors
     * {@link #openContentInBestSplit} but builds a fresh pane from {@code session}/{@code factory}.
     * Returns {@code false} when the grid is full (3×3 with no empties) so the caller can open a
     * new tab instead.
     */
    public boolean placeSessionInBestSplit(TerminalSession session, SessionFactory factory) {
        if (session == null) {
            return true;
        }
        int[] empty = nearestEmptyCell();
        if (empty != null) {
            placeAt(empty[0], empty[1], session, factory);
        } else if (cols < MAX) {
            growWeight(colWeights, cols);
            int newCol = cols;
            cols++;
            placeAt(activeRow, newCol, session, factory);
        } else if (rows < MAX) {
            growWeight(rowWeights, rows);
            int newRow = rows;
            rows++;
            placeAt(newRow, activeCol, session, factory);
        } else {
            return false;
        }
        relayout();
        focusActive();
        return true;
    }

    /**
     * Re-open the session held by {@code pane} (a fresh, independent instance via its restart
     * factory) either in a new split within this grid or in a new tab. No-op if the pane is gone
     * or has no factory (e.g. an SFTP browser). When duplicating into a split and the grid is
     * full, falls back to a new tab so the duplicate is never lost.
     */
    public void duplicatePane(GridContent pane, boolean toNewTab) {
        int[] pos = locate(pane);
        if (pos == null) {
            return;
        }
        duplicateCell(pos[0], pos[1], toNewTab);
    }

    /** Duplicate the active cell's session (keyboard entry point); see {@link #duplicatePane}. */
    public void duplicateActivePane(boolean toNewTab) {
        duplicateCell(activeRow, activeCol, toNewTab);
    }

    private void duplicateCell(int r, int c, boolean toNewTab) {
        SessionFactory factory = factories[r][c];
        if (factory == null) {
            return;
        }
        factory.create(session -> {
            if (toNewTab) {
                if (onOpenSessionInNewTab != null) {
                    onOpenSessionInNewTab.accept(session, factory);
                }
            } else if (!placeSessionInBestSplit(session, factory) && onOpenSessionInNewTab != null) {
                onOpenSessionInNewTab.accept(session, factory);
            }
        });
    }

    // ---- pane move (drag a pane/tab into this grid) ----

    /** Whether this grid currently holds {@code content}. */
    public boolean contains(GridContent content) {
        return locate(content) != null;
    }

    /** Number of live cells (terminals and other content) in this grid. */
    public int paneCount() {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** This grid's only cell if it holds exactly one pane (any kind), else {@code null}. Used to let
     *  a single-pane tab — terminal or SFTP — be dragged by its header into another grid. */
    public GridContent solePane() {
        return paneCount() == 1 ? firstContent() : null;
    }

    private GridContent firstContent() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    return panes[r][c];
                }
            }
        }
        return null;
    }

    /** Swap two cells' positions within this grid; the dragged content {@code a} becomes active. */
    public void swapPanes(GridContent a, GridContent b) {
        int[] pa = locate(a);
        int[] pb = locate(b);
        if (pa == null || pb == null || a == b) {
            return;
        }
        panes[pa[0]][pa[1]] = b;
        panes[pb[0]][pb[1]] = a;
        SessionFactory fa = factories[pa[0]][pa[1]];
        factories[pa[0]][pa[1]] = factories[pb[0]][pb[1]];
        factories[pb[0]][pb[1]] = fa;
        activeRow = pb[0];
        activeCol = pb[1];
        relayout();
        focusActive();
    }

    /** Move a pane already in this grid into an empty in-bounds cell, collapsing what it vacates. */
    public void movePaneToEmptyCell(GridContent content, int r, int c) {
        int[] pos = locate(content);
        if (pos == null || r < 0 || r >= rows || c < 0 || c >= cols || panes[r][c] != null) {
            return;
        }
        panes[r][c] = content;
        factories[r][c] = factories[pos[0]][pos[1]];
        panes[pos[0]][pos[1]] = null;
        factories[pos[0]][pos[1]] = null;
        activeRow = r;
        activeCol = c;
        compactGrid();
        relayout();
        focusActive();
    }

    /**
     * Remove a pane from this grid <em>without</em> closing its session, returning its restart
     * factory so an adopting grid can keep restart working. The grid collapses around the gap; the
     * caller (e.g. the move coordinator) decides whether an emptied tab should close.
     */
    public DetachedPane detachForMove(GridContent content) {
        int[] pos = locate(content);
        if (pos == null) {
            return null;
        }
        SessionFactory factory = factories[pos[0]][pos[1]];
        unbind(content);
        panes[pos[0]][pos[1]] = null;
        factories[pos[0]][pos[1]] = null;
        compactGrid();
        relayout();
        moveActiveToExistingPane();
        focusActive();
        return new DetachedPane(content, factory);
    }

    /** Adopt an existing pane into this (fresh, single-cell) grid at (0,0). */
    public void adopt(GridContent pane, SessionFactory factory) {
        placeExistingPaneAt(0, 0, pane, factory);
        relayout();
        focusActive();
    }

    /** Adopt an existing pane as a split relative to {@code target} (column/row by drop region). */
    public void adoptAsSplit(GridContent target, DropRegion region,
                             GridContent pane, SessionFactory factory) {
        int[] pos = locate(target);
        if (pos != null) {
            activeRow = pos[0];
            activeCol = pos[1];
        }
        if (region == DropRegion.COLUMN && cols < MAX) {
            growWeight(colWeights, cols);
            int newCol = cols;
            cols++;
            placeExistingPaneAt(activeRow, newCol, pane, factory);
        } else if (region == DropRegion.ROW && rows < MAX) {
            growWeight(rowWeights, rows);
            int newRow = rows;
            rows++;
            placeExistingPaneAt(newRow, activeCol, pane, factory);
        } else {
            replaceActiveWithPane(pane, factory);
        }
        relayout();
        focusActive();
    }

    /** Re-apply theme-derived chrome (accent border, gutter hover tint) after a theme switch. */
    public void refreshTheme() {
        updateBorders();
        repaint();
    }

    /** Recolor every live cell in this grid for the new theme (no restart). */
    public void applyTheme(ThemeColors theme) {
        for (int r = 0; r < MAX; r++) {
            for (int c = 0; c < MAX; c++) {
                if (panes[r][c] != null) {
                    panes[r][c].applyTheme(theme);
                }
            }
        }
    }

    /** Terminate every cell's session (called when the owning tab closes). */
    public void disposeAll() {
        for (int r = 0; r < MAX; r++) {
            for (int c = 0; c < MAX; c++) {
                if (panes[r][c] != null) {
                    unbind(panes[r][c]);
                    panes[r][c].closeContent();
                    panes[r][c] = null;
                    factories[r][c] = null;
                }
            }
        }
    }

    // ---- broadcast ----

    /** ctrl+shift+B: toggle input broadcast and show/hide per-pane title bars. */
    public void toggleBroadcast() {
        broadcastBus.setActive(!broadcastBus.isActive());
        boolean active = broadcastBus.isActive();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] instanceof TerminalPane pane) {
                    pane.setBroadcastMode(active);
                }
            }
        }
        updateBorders();
        revalidate();
        repaint();
    }

    @Override
    public void broadcast(TtyConnector source, byte[] data) {
        broadcastBus.broadcast(source, data);
    }

    // ---- divider resize (drag the gutters between cells) ----

    private static double[] equalWeights() {
        double[] w = new double[MAX];
        Arrays.fill(w, 1.0);
        return w;
    }

    /** A new trailing row/column gets the mean weight — an equal 1/(n+1) share of the space
     *  while the existing rows/columns keep their ratio to each other. Call before growing
     *  {@link #rows}/{@link #cols}, so {@code oldCount} is the index of the new line. */
    private static void growWeight(double[] w, int oldCount) {
        double sum = 0;
        for (int i = 0; i < oldCount; i++) {
            sum += w[i];
        }
        w[oldCount] = oldCount == 0 ? 1.0 : sum / oldCount;
    }

    /** Reset an axis to equal shares. */
    private static void equalizeWeights(double[] w, int count) {
        for (int i = 0; i < count; i++) {
            w[i] = 1.0;
        }
    }

    /**
     * Divider dragging. The listeners live on the grid panel itself: every child fully covers
     * its cell, so the only pixels that deliver mouse events here are the gutters between
     * cells — no conflict with the title-bar drag sources, terminal text selection or the
     * per-cell drop targets, which all live on children.
     */
    private void installResizeHandler() {
        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(e.getX(), e.getY());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHover(null);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                WeightedGridLayout.Divider divider =
                        gridLayout.hitTest(e.getX(), e.getY(), PaneGrid.this);
                if (divider == null) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    equalizeAxis(divider.axis());
                    dragging = null;
                    return;
                }
                dragging = divider;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                dragDivider(e.getX(), e.getY());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = null;
                updateHover(e.getX(), e.getY());
            }
        };
        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    private void updateHover(int x, int y) {
        WeightedGridLayout.Divider divider =
                dragging != null ? dragging : gridLayout.hitTest(x, y, this);
        if (divider == null) {
            setCursor(Cursor.getDefaultCursor());
        } else {
            setCursor(Cursor.getPredefinedCursor(divider.axis() == WeightedGridLayout.Axis.COLUMN
                    ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR));
        }
        setHover(divider);
    }

    private void setHover(WeightedGridLayout.Divider divider) {
        if (!Objects.equals(divider, hovered)) {
            hovered = divider;
            repaint();
        }
    }

    /** Whether a divider still exists in the current shape (the grid may have collapsed while
     *  it was hovered/dragged, e.g. an async close during a drag). */
    private boolean dividerValid(WeightedGridLayout.Divider d) {
        return d != null
                && d.index() < (d.axis() == WeightedGridLayout.Axis.COLUMN ? cols : rows);
    }

    /**
     * Move the dragged divider: only the two adjacent rows/columns change and their summed
     * weight is conserved, so every other band keeps its exact size (JSplitPane-like, local
     * feel). Pixels convert back into weights so the layout stays purely proportional — an OS
     * window resize keeps the chosen ratios for free.
     */
    private void dragDivider(int x, int y) {
        if (!dividerValid(dragging)) {
            dragging = null;
            return;
        }
        int i = dragging.index();
        if (dragging.axis() == WeightedGridLayout.Axis.COLUMN) {
            resizePair(colWeights, i, gridLayout.colX(this), gridLayout.colW(this), x);
        } else {
            resizePair(rowWeights, i, gridLayout.rowY(this), gridLayout.rowH(this), y);
        }
        revalidate();
        repaint();
    }

    /** Re-split the combined span of bands {@code i-1} and {@code i} so the gutter is centered
     *  under {@code mouse}, clamped so neither band shrinks below {@link #MIN_CELL_PX} (pinned
     *  to the middle when the pair is too small to honour both minimums). */
    private void resizePair(double[] weights, int i, int[] pos, int[] size, int mouse) {
        int combinedPx = size[i - 1] + size[i];
        if (combinedPx <= 0) {
            return;
        }
        int first = mouse - pos[i - 1] - GUTTER / 2;
        first = combinedPx < 2 * MIN_CELL_PX
                ? combinedPx / 2
                : Math.max(MIN_CELL_PX, Math.min(combinedPx - MIN_CELL_PX, first));
        double combinedW = weights[i - 1] + weights[i];
        weights[i - 1] = combinedW * first / combinedPx;
        weights[i] = combinedW - weights[i - 1];
    }

    /** Double-click on a divider: reset that whole axis to equal shares. */
    private void equalizeAxis(WeightedGridLayout.Axis axis) {
        if (axis == WeightedGridLayout.Axis.COLUMN) {
            equalizeWeights(colWeights, cols);
        } else {
            equalizeWeights(rowWeights, rows);
        }
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g); // themed panel background = the resting gutter color
        if (dividerValid(hovered)) {
            Rectangle r = gridLayout.gutterRect(hovered, this);
            g.setColor(ThemeManager.accentColor());
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    // ---- internals ----

    private void replaceActiveContent(TerminalSession session, SessionFactory factory) {
        if (session == null) {
            return;
        }
        GridContent existing = panes[activeRow][activeCol];
        if (existing != null) {
            unbind(existing);
            existing.closeContent();
        }
        placeAt(activeRow, activeCol, session, factory);
    }

    private void placeAt(int r, int c, TerminalSession session, SessionFactory factory) {
        placeExistingPaneAt(r, c, createPane(session), factory);
    }

    /** Store already-built content (new or adopted from another grid) at a cell and bind it here. */
    private void placeExistingPaneAt(int r, int c, GridContent content, SessionFactory factory) {
        panes[r][c] = content;
        factories[r][c] = factory;
        activeRow = r;
        activeCol = c;
        registerPane(content);
    }

    /** Replace the active cell's content with existing content (used when a split is full). */
    private void replaceActiveWithPane(GridContent content, SessionFactory factory) {
        GridContent existing = panes[activeRow][activeCol];
        if (existing != null) {
            unbind(existing);
            existing.closeContent();
        }
        placeExistingPaneAt(activeRow, activeCol, content, factory);
    }

    private void openLocalAt(int r, int c) {
        openLocal((session, factory) -> placeAt(r, c, session, factory));
    }

    /**
     * Synchronously start a fresh local shell and, on success, hand the session plus its restart
     * factory to {@code placer}. On failure the error is shown and {@code placer} is not called.
     */
    private void openLocal(BiConsumer<TerminalSession, SessionFactory> placer) {
        SessionFactory factory = SessionFactory.local(reporter());
        factory.create(session -> placer.accept(session, factory));
    }

    /** As {@link #openLocal}, but starts a shell inside the given WSL2 distribution. */
    private void openWsl(String distro, BiConsumer<TerminalSession, SessionFactory> placer) {
        SessionFactory factory = SessionFactory.wsl(distro, reporter());
        factory.create(session -> placer.accept(session, factory));
    }

    /** Reports a session-creation failure through the richer {@link ErrorDialog}, parented on this grid. */
    private BiConsumer<String, Throwable> reporter() {
        return (header, error) -> ErrorDialog.show(this, "jterm", header, error);
    }

    /** Build a pane wrapping a fresh session; not yet bound to this grid (see {@link #registerPane}). */
    private TerminalPane createPane(TerminalSession session) {
        TtyConnector wrapped = new BroadcastingTtyConnector(session.connector(), this);
        return new TerminalPane(session, ThemeManager.get().current(), wrapped);
    }

    /**
     * Bind content — new or adopted from another grid — to this grid: focus/end/broadcast
     * callbacks, drop target, broadcast bus, and current broadcast mode. Terminal-only wiring is
     * applied only to {@link TerminalPane}; other content (the SFTP browser) just removes its cell
     * when its connection ends. Re-binding is idempotent, so this safely re-homes a moved pane.
     */
    private void registerPane(GridContent content) {
        content.setOnFocus(() -> setActiveByContent(content));
        if (content instanceof TerminalPane pane) {
            pane.setOnContentEnded(() -> handleSessionEnd(pane));
            pane.setOnBroadcastToggle(this::updateBorders);
            pane.setDuplicateHandler(toNewTab -> duplicatePane(pane, toNewTab));
            // A freshly placed/adopted pane has no unseen output yet.
            pane.setActivity(PaneActivity.NONE);
            broadcastBus.register(pane);
            if (pane.inputConnector() instanceof BroadcastingTtyConnector b) {
                b.setBus(this);
                // Output read off-EDT → flag background-tab activity on the EDT (coalesced).
                b.setOnOutput(() -> SwingUtilities.invokeLater(() -> {
                    b.outputHandled();
                    markOutput(pane);
                }));
            }
            pane.setBroadcastMode(broadcastBus.isActive());
        } else {
            content.setOnContentEnded(() -> removePane(content));
        }
        installDnd(content);
    }

    /**
     * The parts of a grid drop that differ between the two targets — dropping onto an occupied pane
     * (splits relative to it, shows an in-pane split hint) and dropping onto an empty cell (fills the
     * cell, shows a border hint). The shared {@link #installDropTarget} drives the flavor dispatch.
     */
    private interface DropBehavior {
        /** Visual feedback while a pane (move) is dragged over. */
        void hintMove();

        /** Visual feedback while a session (copy) is dragged over, given the split it would perform. */
        void hintDrop(DropRegion region);

        /** Remove any drag feedback (on exit or before a drop). */
        void clearHint();

        /** A pane was dropped here; {@code region} matters only for the pane-target (split) case. */
        void onPaneDrop(GridContent dragged, DropRegion region);

        /** Where a freshly opened session (SSH/local/WSL) should land for this target. */
        BiConsumer<TerminalSession, SessionFactory> placer(DropRegion region);
    }

    private void installDnd(GridContent content) {
        JComponent comp = content.ui();
        installDropTarget(comp, new DropBehavior() {
            @Override
            public void hintMove() {
                content.showMoveHint();
            }

            @Override
            public void hintDrop(DropRegion region) {
                content.showDropHint(region);
            }

            @Override
            public void clearHint() {
                content.clearDropHint();
            }

            @Override
            public void onPaneDrop(GridContent dragged, DropRegion region) {
                dropPaneOnPane(dragged, content, region);
            }

            @Override
            public BiConsumer<TerminalSession, SessionFactory> placer(DropRegion region) {
                return (session, factory) -> splitFromPaneAndOpen(content, region, session, factory);
            }
        });
    }

    /**
     * Wire {@code comp} as a grid drop target handling all four flavors — PANE (move), SESSION,
     * LOCAL and WSL (copy) — with the accept/reject bookkeeping and error tail in one place. What a
     * drop actually does is supplied by {@code behavior}.
     */
    private void installDropTarget(JComponent comp, DropBehavior behavior) {
        new DropTarget(comp, DnDConstants.ACTION_COPY_OR_MOVE, new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(PaneTransferable.PANE_FLAVOR)) {
                    dtde.acceptDrag(DnDConstants.ACTION_MOVE);
                    behavior.hintMove();
                } else if (isSessionDrag(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    behavior.hintDrop(DropRegion.forPosition(dtde.getLocation().y, comp.getHeight()));
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragExit(DropTargetEvent dte) {
                behavior.clearHint();
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                behavior.clearHint();
                DropRegion region = DropRegion.forPosition(dtde.getLocation().y, comp.getHeight());
                try {
                    if (dtde.isDataFlavorSupported(PaneTransferable.PANE_FLAVOR)) {
                        dtde.acceptDrop(DnDConstants.ACTION_MOVE);
                        GridContent dragged = (GridContent) dtde.getTransferable()
                                .getTransferData(PaneTransferable.PANE_FLAVOR);
                        behavior.onPaneDrop(dragged, region);
                        dtde.dropComplete(true);
                    } else if (dtde.isDataFlavorSupported(SessionTransferable.SESSION_FLAVOR)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        SshSessionConfig cfg = (SshSessionConfig) dtde.getTransferable()
                                .getTransferData(SessionTransferable.SESSION_FLAVOR);
                        if (dropHandler != null) {
                            dropHandler.connect(cfg, behavior.placer(region));
                        }
                        dtde.dropComplete(true);
                    } else if (dtde.isDataFlavorSupported(LocalTransferable.LOCAL_FLAVOR)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        openLocal(behavior.placer(region));
                        dtde.dropComplete(true);
                    } else if (dtde.isDataFlavorSupported(WslTransferable.WSL_FLAVOR)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        String distro = (String) dtde.getTransferable()
                                .getTransferData(WslTransferable.WSL_FLAVOR);
                        openWsl(distro, behavior.placer(region));
                        dtde.dropComplete(true);
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception e) {
                    LOG.warn("drop onto pane grid failed", e);
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private static boolean isSessionDrag(DropTargetDragEvent dtde) {
        return dtde.isDataFlavorSupported(SessionTransferable.SESSION_FLAVOR)
                || dtde.isDataFlavorSupported(LocalTransferable.LOCAL_FLAVOR)
                || dtde.isDataFlavorSupported(WslTransferable.WSL_FLAVOR);
    }

    /**
     * A pane was dropped on {@code target}. If it already lives in this grid, rearrange (swap);
     * otherwise it came from another tab — detach it from its source grid and bring it in as a split.
     */
    private void dropPaneOnPane(GridContent dragged, GridContent target, DropRegion region) {
        if (dragged == null) {
            return;
        }
        if (contains(dragged)) {
            if (dragged != target) {
                swapPanes(dragged, target);
            }
        } else if (moveCoordinator != null) {
            DetachedPane detached = moveCoordinator.detachFromOwner(dragged);
            if (detached != null) {
                adoptAsSplit(target, region, detached.content(), detached.factory());
            }
        }
    }

    /**
     * A pane was dropped on an empty cell. Same-grid → move it there; from another tab → detach and
     * fill the cell (falling back to the active cell if the cell is no longer available).
     */
    private void dropPaneOnEmptyCell(GridContent dragged, int r, int c) {
        if (dragged == null) {
            return;
        }
        if (contains(dragged)) {
            movePaneToEmptyCell(dragged, r, c);
            return;
        }
        if (moveCoordinator == null) {
            return;
        }
        DetachedPane detached = moveCoordinator.detachFromOwner(dragged);
        if (detached == null) {
            return;
        }
        if (r >= 0 && r < rows && c >= 0 && c < cols && panes[r][c] == null) {
            placeExistingPaneAt(r, c, detached.content(), detached.factory());
        } else {
            replaceActiveWithPane(detached.content(), detached.factory());
        }
        relayout();
        focusActive();
    }

    /**
     * The session backing a pane ended: keep the pane and show its "Session stopped" screen,
     * wiring Return → remove the pane and R → restart a fresh session in the same cell.
     */
    private void handleSessionEnd(TerminalPane pane) {
        if (locate(pane) == null) {
            return;
        }
        pane.showSessionStopped(() -> removePane(pane), () -> restartPane(pane));
        // Flag the disconnect on a background tab (a visible tab is "seen"; selection clears it).
        if (!foreground) {
            pane.setActivity(PaneActivity.DISCONNECTED);
            fireActivity();
        }
    }

    /** Return/exit on the stopped screen (or a non-terminal cell ending): drop the cell and collapse. */
    private void removePane(GridContent content) {
        int[] pos = locate(content);
        if (pos == null) {
            return;
        }
        unbind(content);
        content.closeContent();
        panes[pos[0]][pos[1]] = null;
        factories[pos[0]][pos[1]] = null;
        if (!hasAnyPane() && onEmpty != null) {
            // Last cell gone (single-session tab): let the owner close the whole tab.
            onEmpty.run();
            return;
        }
        compactGrid();
        relayout();
        moveActiveToExistingPane();
        focusActive();
    }

    private boolean hasAnyPane() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * R/restart on the stopped screen: reopen the same kind of session in the same cell, reusing the
     * pane's widget so its scrollback is preserved (see {@link TerminalPane#reconnect}). The dead
     * session acts as a one-shot token: if a prior R already reconnected (or the pane moved/was
     * removed) while this create was in flight, the late session is discarded. A failed create never
     * invokes the callback, so the stopped overlay stays in place and R remains available to retry.
     */
    private void restartPane(TerminalPane pane) {
        int[] pos = locate(pane);
        if (pos == null) {
            return;
        }
        SessionFactory factory = factories[pos[0]][pos[1]];
        if (factory == null) {
            return;
        }
        int r = pos[0];
        int c = pos[1];
        TerminalSession dead = pane.session();
        factory.create(session -> {
            if (session == null) {
                return;
            }
            if (panes[r][c] != pane || pane.session() != dead) {
                // Pane moved/replaced, or already reconnected by an earlier R; drop the late session.
                session.close();
                return;
            }
            pane.reconnect(session);
            focusActive();
        }, () -> pane.restoreStoppedScreen());
    }

    private void setActiveByContent(GridContent content) {
        int[] pos = locate(content);
        if (pos != null && (pos[0] != activeRow || pos[1] != activeCol)) {
            activeRow = pos[0];
            activeCol = pos[1];
            updateBorders();
        }
    }

    /** Detach content from broadcast fan-out when it leaves a cell (closed, replaced, or moved out). */
    private void unbind(GridContent content) {
        if (content instanceof TerminalPane pane) {
            broadcastBus.unregister(pane);
        }
    }

    private int[] locate(GridContent content) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] == content) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    private int[] firstEmptyCell() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] == null) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    /**
     * The empty in-bounds cell closest to the active pane by Manhattan distance, or {@code null} if
     * none. Ties favour the same row, then the same column, then top-left — so an empty pane beside
     * or above the active one wins over a more distant hole.
     */
    private int[] nearestEmptyCell() {
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    continue;
                }
                int dist = Math.abs(r - activeRow) + Math.abs(c - activeCol);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new int[]{r, c};
                }
            }
        }
        return best;
    }

    /**
     * Shrink the grid only when a whole row or column can be <em>freed</em> by rearranging — i.e.
     * the surviving panes still fit after dropping a row (or column) while keeping the other
     * dimension. Freeing rows is preferred over columns. When at least one dimension shrinks, the
     * survivors are re-packed in row-major (reading) order into the smaller grid and the previously
     * focused pane keeps focus at its new position. When nothing can be freed, panes are left
     * exactly where they are (so closing one pane in, say, a 2x3 just leaves a re-openable hole
     * rather than reshuffling the others).
     *
     * <p>Only relocates references — never closes a session — so it is safe on both the close path
     * and the move/detach path. A grid with no panes left collapses to a single empty 1x1 cell.
     */
    private void compactGrid() {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    n++;
                }
            }
        }

        // Greedily drop a row (preferred) or column as long as the panes still fit in what remains.
        int newRows = rows;
        int newCols = cols;
        boolean changed = true;
        while (changed) {
            changed = false;
            if (newRows > 1 && n <= (newRows - 1) * newCols) {
                newRows--;
                changed = true;
            } else if (newCols > 1 && n <= newRows * (newCols - 1)) {
                newCols--;
                changed = true;
            }
        }
        if (newRows == rows && newCols == cols) {
            return; // no whole row or column can be freed — leave panes in place
        }

        // Gather survivors in reading order (remembering the focused pane), then re-pack them into
        // the smaller grid.
        GridContent activeContent = panes[activeRow][activeCol];
        GridContent[] keptPanes = new GridContent[MAX * MAX];
        SessionFactory[] keptFactories = new SessionFactory[MAX * MAX];
        int k = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    keptPanes[k] = panes[r][c];
                    keptFactories[k] = factories[r][c];
                    k++;
                }
            }
        }

        // Clear every cell (not just in-bounds) so no stale reference survives a reshape.
        for (int r = 0; r < MAX; r++) {
            for (int c = 0; c < MAX; c++) {
                panes[r][c] = null;
                factories[r][c] = null;
            }
        }
        for (int i = 0; i < k; i++) {
            panes[i / newCols][i % newCols] = keptPanes[i];
            factories[i / newCols][i % newCols] = keptFactories[i];
        }
        // Re-packing moves survivors into different slots, so per-line weights on a shrunk axis
        // no longer correspond to the panes now in them — reset that axis to equal shares.
        if (newRows != rows) {
            equalizeWeights(rowWeights, newRows);
        }
        if (newCols != cols) {
            equalizeWeights(colWeights, newCols);
        }
        rows = newRows;
        cols = newCols;

        // Restore focus to the same pane; if it was the cell just closed, moveActiveToExistingPane()
        // (run by every caller after this) lands focus on a survivor.
        activeRow = 0;
        activeCol = 0;
        if (activeContent != null) {
            int[] pos = locate(activeContent);
            if (pos != null) {
                activeRow = pos[0];
                activeCol = pos[1];
            }
        }
    }

    private void moveActiveToExistingPane() {
        if (panes[activeRow][activeCol] != null) {
            return;
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    activeRow = r;
                    activeCol = c;
                    return;
                }
            }
        }
    }

    private void relayout() {
        removeAll();
        // WeightedGridLayout divides the area purely by the row/column weights, ignoring each
        // cell's preferred size. (GridBagLayout with equal weights only splits the *slack*
        // evenly, so a cell with a large preferred width — e.g. the SFTP browser's wide
        // toolbar/table — would hog its row.)
        gridLayout.setGrid(rows, cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                GridContent content = panes[r][c];
                add(content != null ? content.ui() : emptyCell(r, c));
            }
        }
        updateBorders();
        revalidate();
        repaint();
    }

    private void updateBorders() {
        Border activeBorder = BorderFactory.createLineBorder(ThemeManager.accentColor(), CONTENT_BORDER);
        Border broadcastBorder = BorderFactory.createLineBorder(broadcastEnabledColor(), CONTENT_BORDER);
        Border plain = BorderFactory.createEmptyBorder(
                CONTENT_BORDER, CONTENT_BORDER, CONTENT_BORDER, CONTENT_BORDER);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                GridContent content = panes[r][c];
                if (content == null) {
                    continue;
                }
                Border border;
                if (broadcastBus.isActive()) {
                    // Every participating terminal is highlighted; excluded/non-terminal cells plain.
                    border = (content instanceof TerminalPane pane && pane.isBroadcastChecked())
                            ? broadcastBorder : plain;
                } else {
                    boolean isActive = (r == activeRow && c == activeCol);
                    border = isActive ? activeBorder : plain;
                }
                content.ui().setBorder(border);
            }
        }
        if (onActiveChanged != null) {
            onActiveChanged.run();
        }
    }

    /** Put keyboard focus in the active cell (used to claim the window's initial focus). */
    public void focusActivePane() {
        focusActive();
    }

    private void focusActive() {
        GridContent content = panes[activeRow][activeCol];
        if (content != null) {
            content.focusContent();
        }
        updateBorders();
    }

    private JPanel emptyCell(int r, int c) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel hint = new JLabel("double-click for local shell", SwingConstants.CENTER);
        hint.setEnabled(false);
        panel.add(hint, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(
                CONTENT_BORDER, CONTENT_BORDER, CONTENT_BORDER, CONTENT_BORDER));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                activeRow = r;
                activeCol = c;
                if (e.getClickCount() >= 2) {
                    openLocalInActive();
                } else {
                    updateBorders();
                }
            }
        });
        installEmptyCellDnd(panel, r, c);
        return panel;
    }

    /** Make an empty cell a drop target that fills itself (no split) with the dropped session/pane. */
    private void installEmptyCellDnd(JPanel cell, int r, int c) {
        Border idle = cell.getBorder();
        Border hover = BorderFactory.createLineBorder(ThemeManager.accentColor(), CONTENT_BORDER);
        installDropTarget(cell, new DropBehavior() {
            @Override
            public void hintMove() {
                cell.setBorder(hover);
            }

            @Override
            public void hintDrop(DropRegion region) {
                cell.setBorder(hover);
            }

            @Override
            public void clearHint() {
                cell.setBorder(idle);
            }

            @Override
            public void onPaneDrop(GridContent dragged, DropRegion region) {
                dropPaneOnEmptyCell(dragged, r, c);
            }

            @Override
            public BiConsumer<TerminalSession, SessionFactory> placer(DropRegion region) {
                return (session, factory) -> placeSessionInCell(r, c, session, factory);
            }
        });
    }

    /**
     * Border color for broadcast-enabled panes. In dark theme the focus accent is lifted toward
     * white so the highlight reads clearly across every participating pane; in light theme the
     * plain accent already stands out, so it's used as-is.
     */
    private static Color broadcastEnabledColor() {
        Color base = ThemeManager.accentColor();
        return ThemeManager.get().isDark() ? brighten(base, 0.35) : base;
    }

    /** Lighten {@code c} toward white by {@code amount} (0 = unchanged, 1 = white). */
    private static Color brighten(Color c, double amount) {
        return new Color(
                (int) Math.round(c.getRed() + (255 - c.getRed()) * amount),
                (int) Math.round(c.getGreen() + (255 - c.getGreen()) * amount),
                (int) Math.round(c.getBlue() + (255 - c.getBlue()) * amount));
    }
}
