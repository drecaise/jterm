package com.katmoda.jterm.ui.grid;

import com.jediterm.terminal.TtyConnector;
import com.katmoda.jterm.broadcast.BroadcastBus;
import com.katmoda.jterm.broadcast.BroadcastingTtyConnector;
import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.dnd.LocalTransferable;
import com.katmoda.jterm.dnd.SessionDropHandler;
import com.katmoda.jterm.dnd.SessionTransferable;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.local.LocalSession;
import com.katmoda.jterm.ui.pane.TerminalPane;
import com.katmoda.jterm.ui.theme.ThemeColors;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * One tab's pane layout: a uniform grid of up to {@value #MAX}×{@value #MAX} cells,
 * all equally sized. Each in-bounds cell either holds a {@link TerminalPane} or is empty
 * (re-openable). Splitting grows a dimension; closing empties a cell and collapses a
 * fully-empty trailing row/column so the grid stays rectangular.
 *
 * <p>Also hosts broadcast fan-out ({@link BroadcastBus}) and session drag-and-drop drops.</p>
 */
public final class PaneGrid extends JPanel implements BroadcastBus {

    public static final int MAX = 3;

    private static final int CONTENT_BORDER = 2;

    private final TerminalPane[][] panes = new TerminalPane[MAX][MAX];
    /** How to recreate the session in each cell (for restart), parallel to {@link #panes}. */
    private final SessionFactory[][] factories = new SessionFactory[MAX][MAX];
    private int rows = 1;
    private int cols = 1;
    private int activeRow = 0;
    private int activeCol = 0;
    private boolean broadcastActive = false;
    private SessionDropHandler dropHandler;
    private Runnable onActiveChanged;
    private Runnable onEmpty;

    public PaneGrid() {
        super(new GridBagLayout());
    }

    public void setDropHandler(SessionDropHandler dropHandler) {
        this.dropHandler = dropHandler;
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

    /** The currently focused pane, or {@code null} if the active cell is empty. */
    public TerminalPane activePane() {
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
        TerminalSession session = safeLocalSession();
        if (session != null) {
            splitColumnAndOpen(session, localFactory());
        }
    }

    /** ctrl+DOWN: add a row (if room) and open a local shell in it. */
    public void splitRow() {
        TerminalSession session = safeLocalSession();
        if (session != null) {
            splitRowAndOpen(session, localFactory());
        }
    }

    /** Add a column (if room) and open the given session in it; else replace the active cell. */
    public void splitColumnAndOpen(TerminalSession session, SessionFactory factory) {
        if (cols < MAX) {
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
            int newRow = rows;
            rows++;
            placeAt(newRow, activeCol, session, factory);
        } else {
            replaceActiveContent(session, factory);
        }
        relayout();
        focusActive();
    }

    /** Drop/context-menu entry: split relative to a specific pane, then open the session. */
    public void splitFromPaneAndOpen(TerminalPane target, DropRegion region,
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

    /** ctrl+UP: close the focused pane; collapse cleared trailing rows/cols. */
    public void closeActivePane() {
        TerminalPane pane = panes[activeRow][activeCol];
        if (pane == null) {
            return;
        }
        pane.close();
        panes[activeRow][activeCol] = null;
        collapseTrailingEmpty();
        relayout();
        moveActiveToExistingPane();
        focusActive();
    }

    /** Open a fresh local shell in the active cell (replacing any existing pane). */
    public void openLocalInActive() {
        TerminalSession session = safeLocalSession();
        if (session != null) {
            replaceActiveContent(session, localFactory());
            relayout();
            focusActive();
        }
    }

    /** Place an already-connected session in the active cell (replacing any existing pane). */
    public void placeSessionInActive(TerminalSession session, SessionFactory factory) {
        replaceActiveContent(session, factory);
        relayout();
        focusActive();
    }

    /** Re-apply theme-derived chrome (active-pane accent border) after a theme switch. */
    public void refreshTheme() {
        updateBorders();
    }

    /** Recolor every live terminal in this grid for the new theme (no restart). */
    public void applyTheme(ThemeColors theme) {
        for (int r = 0; r < MAX; r++) {
            for (int c = 0; c < MAX; c++) {
                if (panes[r][c] != null) {
                    panes[r][c].applyTheme(theme);
                }
            }
        }
    }

    /** Terminate every pane's session (called when the owning tab closes). */
    public void disposeAll() {
        for (int r = 0; r < MAX; r++) {
            for (int c = 0; c < MAX; c++) {
                if (panes[r][c] != null) {
                    panes[r][c].close();
                    panes[r][c] = null;
                    factories[r][c] = null;
                }
            }
        }
    }

    // ---- broadcast ----

    /** ctrl+shift+B: toggle input broadcast and show/hide per-pane title bars. */
    public void toggleBroadcast() {
        broadcastActive = !broadcastActive;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] != null) {
                    panes[r][c].setBroadcastBarVisible(broadcastActive);
                }
            }
        }
        revalidate();
        repaint();
    }

    @Override
    public void broadcast(TtyConnector source, byte[] data) {
        if (!broadcastActive) {
            return;
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                TerminalPane pane = panes[r][c];
                if (pane != null && pane.realConnector() != source && pane.isBroadcastChecked()) {
                    try {
                        pane.realConnector().write(data);
                    } catch (Exception ignored) {
                        // A dead pane shouldn't break the fan-out to the others.
                    }
                }
            }
        }
    }

    // ---- internals ----

    private void replaceActiveContent(TerminalSession session, SessionFactory factory) {
        if (session == null) {
            return;
        }
        TerminalPane existing = panes[activeRow][activeCol];
        if (existing != null) {
            existing.close();
        }
        placeAt(activeRow, activeCol, session, factory);
    }

    private void placeAt(int r, int c, TerminalSession session, SessionFactory factory) {
        TerminalPane pane = makePane(session);
        panes[r][c] = pane;
        factories[r][c] = factory;
        activeRow = r;
        activeCol = c;
        if (broadcastActive) {
            pane.setBroadcastBarVisible(true);
        }
    }

    private void openLocalAt(int r, int c) {
        TerminalSession session = safeLocalSession();
        if (session != null) {
            placeAt(r, c, session, localFactory());
        }
    }

    /** A factory that synchronously opens a fresh local shell (used for restart). */
    private SessionFactory localFactory() {
        return onReady -> {
            TerminalSession session = safeLocalSession();
            if (session != null) {
                onReady.accept(session);
            }
        };
    }

    private TerminalSession safeLocalSession() {
        try {
            return LocalSession.start(null);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Failed to start local shell:\n" + e.getMessage(),
                    "jterm", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private TerminalPane makePane(TerminalSession session) {
        TtyConnector wrapped = new BroadcastingTtyConnector(session.connector(), this);
        TerminalPane pane = new TerminalPane(session, ThemeManager.get().current(), wrapped);
        pane.setOnFocus(() -> setActiveByPane(pane));
        pane.setOnSessionEnd(() -> handleSessionEnd(pane));
        installDnd(pane);
        return pane;
    }

    private void installDnd(TerminalPane pane) {
        new DropTarget(pane, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (isAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    pane.showDropHint(DropRegion.forPosition(dtde.getLocation().y, pane.getHeight()));
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragExit(DropTargetEvent dte) {
                pane.clearDropHint();
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                pane.clearDropHint();
                DropRegion region = DropRegion.forPosition(dtde.getLocation().y, pane.getHeight());
                try {
                    if (dtde.isDataFlavorSupported(SessionTransferable.SESSION_FLAVOR)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        SshSessionConfig cfg = (SshSessionConfig) dtde.getTransferable()
                                .getTransferData(SessionTransferable.SESSION_FLAVOR);
                        if (dropHandler != null) {
                            dropHandler.onDrop(pane, region, cfg);
                        }
                        dtde.dropComplete(true);
                    } else if (dtde.isDataFlavorSupported(LocalTransferable.LOCAL_FLAVOR)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        TerminalSession session = safeLocalSession();
                        if (session != null) {
                            splitFromPaneAndOpen(pane, region, session, localFactory());
                        }
                        dtde.dropComplete(true);
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception e) {
                    dtde.dropComplete(false);
                }
            }

            private boolean isAcceptable(DropTargetDragEvent dtde) {
                return dtde.isDataFlavorSupported(SessionTransferable.SESSION_FLAVOR)
                        || dtde.isDataFlavorSupported(LocalTransferable.LOCAL_FLAVOR);
            }
        });
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
    }

    /** Return/exit on the stopped screen: drop the pane and collapse the grid (old behavior). */
    private void removePane(TerminalPane pane) {
        int[] pos = locate(pane);
        if (pos == null) {
            return;
        }
        pane.close();
        panes[pos[0]][pos[1]] = null;
        factories[pos[0]][pos[1]] = null;
        if (!hasAnyPane() && onEmpty != null) {
            // Last pane gone (single-session tab): let the owner close the whole tab.
            onEmpty.run();
            return;
        }
        collapseTrailingEmpty();
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

    /** R/restart on the stopped screen: reopen the same kind of session in the same cell. */
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
        factory.create(session -> {
            if (session == null || panes[r][c] != pane) {
                // Pane was removed/replaced while connecting (async SSH); drop the late session.
                if (session != null && panes[r][c] != pane) {
                    session.close();
                }
                return;
            }
            pane.close();
            placeAt(r, c, session, factory);
            relayout();
            focusActive();
        });
    }

    private void setActiveByPane(TerminalPane pane) {
        int[] pos = locate(pane);
        if (pos != null && (pos[0] != activeRow || pos[1] != activeCol)) {
            activeRow = pos[0];
            activeCol = pos[1];
            updateBorders();
        }
    }

    private int[] locate(TerminalPane pane) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (panes[r][c] == pane) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    private void collapseTrailingEmpty() {
        while (rows > 1 && rowEmpty(rows - 1)) {
            rows--;
        }
        while (cols > 1 && colEmpty(cols - 1)) {
            cols--;
        }
        if (activeRow >= rows) {
            activeRow = rows - 1;
        }
        if (activeCol >= cols) {
            activeCol = cols - 1;
        }
    }

    private boolean rowEmpty(int r) {
        for (int c = 0; c < cols; c++) {
            if (panes[r][c] != null) {
                return false;
            }
        }
        return true;
    }

    private boolean colEmpty(int c) {
        for (int r = 0; r < rows; r++) {
            if (panes[r][c] != null) {
                return false;
            }
        }
        return true;
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
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                gbc.gridx = c;
                gbc.gridy = r;
                TerminalPane pane = panes[r][c];
                add(pane != null ? pane : emptyCell(r, c), gbc);
            }
        }
        updateBorders();
        revalidate();
        repaint();
    }

    private void updateBorders() {
        Color accent = accentColor();
        Border active = BorderFactory.createLineBorder(accent, CONTENT_BORDER);
        Border inactive = BorderFactory.createEmptyBorder(
                CONTENT_BORDER, CONTENT_BORDER, CONTENT_BORDER, CONTENT_BORDER);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                TerminalPane pane = panes[r][c];
                if (pane != null) {
                    boolean isActive = (r == activeRow && c == activeCol);
                    pane.setBorder(isActive ? active : inactive);
                }
            }
        }
        if (onActiveChanged != null) {
            onActiveChanged.run();
        }
    }

    private void focusActive() {
        TerminalPane pane = panes[activeRow][activeCol];
        if (pane != null) {
            pane.focusTerminal();
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
        return panel;
    }

    private static Color accentColor() {
        Color c = UIManager.getColor("Component.focusColor");
        return c != null ? c : new Color(0x4A90D9);
    }
}
