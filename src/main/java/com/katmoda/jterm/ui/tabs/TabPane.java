package com.katmoda.jterm.ui.tabs;

import com.katmoda.jterm.app.TerminalServices;
import com.katmoda.jterm.app.TerminalWindow;
import com.katmoda.jterm.app.WindowManager;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.dnd.PaneMoveCoordinator;
import com.katmoda.jterm.dnd.PaneTransferable;
import com.katmoda.jterm.dnd.TabTransferable;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.keymap.TermAction;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.local.LocalSession;
import com.katmoda.jterm.terminal.ssh.SshSession;
import com.katmoda.jterm.ui.SessionIcon;
import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.grid.PaneGrid;
import com.katmoda.jterm.ui.grid.TabActivityIcon;
import com.katmoda.jterm.ui.pane.PaneActivity;
import com.katmoda.jterm.ui.pane.TerminalPane;
import com.katmoda.jterm.ui.theme.ThemeColors;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Insets;
import java.awt.Point;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DragSourceListener;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A tab strip of {@link PaneGrid}s (one grid per tab), with a trailing "+" new-tab button.
 *
 * <p>Reusable across windows: the single {@link com.katmoda.jterm.app.MainWindow} and every
 * {@link com.katmoda.jterm.app.DetachedWindow} each embed one. Tabs (live grids + sessions) can be
 * dragged between windows, detached into a new window when dropped outside, and re-attached. SSH
 * connection, icon and tab-color lookup come from {@link TerminalServices}; cross-window moves are
 * coordinated through the {@link WindowManager}.</p>
 */
public final class TabPane extends JPanel {

    /** Detach/adopt a pane from whatever window owns it (searched across all windows). */
    private static final PaneMoveCoordinator MOVE_COORDINATOR = pane -> {
        TabPane host = WindowManager.get().hostContaining(pane);
        return host == null ? null : host.detachPaneFromOwnGrid(pane);
    };

    private final TerminalWindow owner;
    private final TerminalServices services;
    private final JTabbedPane tabs = new JTabbedPane();

    /** Permanent trailing tab hosting the "+" button, keeping it right after the last real tab. */
    private final JPanel plusPlaceholder = new JPanel();
    private JButton plusButton;
    /** Tab selected immediately before the current press, so a tab drag can keep the previously
     *  active grid visible as a drop target instead of the just-pressed tab. */
    private int selectedBeforePress = -1;
    private int tabCounter = 0;

    public TabPane(TerminalWindow owner, TerminalServices services) {
        super(new BorderLayout());
        this.owner = owner;
        this.services = services;
        configureTabs();
        add(tabs, BorderLayout.CENTER);
        tabs.addChangeListener(e -> {
            guardPlusSelection();
            updateForegroundStates();
        });
    }

    public TerminalWindow owner() {
        return owner;
    }

    // ---- setup ----

    private void configureTabs() {
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        BiConsumer<JTabbedPane, Integer> closeCallback = (pane, index) -> closeTabAt(index);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", closeCallback);
        installPlusTab();
        installTabDnd();
        installTabContextMenu();
    }

    private void installPlusTab() {
        plusPlaceholder.putClientProperty("JTabbedPane.tabClosable", false);
        tabs.addTab(null, plusPlaceholder);
        plusButton = buildNewTabButton();
        tabs.setTabComponentAt(tabs.indexOfComponent(plusPlaceholder), plusButton);
        installNewTabDropTarget(plusButton);
    }

    /** Dropping a dragged pane on "+" pulls it out of its split into a brand-new tab here. */
    private void installNewTabDropTarget(JButton button) {
        new DropTarget(button, DnDConstants.ACTION_MOVE, new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(PaneTransferable.PANE_FLAVOR)) {
                    dtde.acceptDrag(DnDConstants.ACTION_MOVE);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (!dtde.isDataFlavorSupported(PaneTransferable.PANE_FLAVOR)) {
                        dtde.rejectDrop();
                        return;
                    }
                    dtde.acceptDrop(DnDConstants.ACTION_MOVE);
                    TerminalPane pane = (TerminalPane) dtde.getTransferable()
                            .getTransferData(PaneTransferable.PANE_FLAVOR);
                    movePaneToNewTab(pane);
                    dtde.dropComplete(true);
                } catch (Exception e) {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private JButton buildNewTabButton() {
        JButton button = new JButton("+");
        button.setToolTipText(newTabTooltip());
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(false);
        button.setMargin(new Insets(0, 5, 0, 5));
        button.addActionListener(e -> addTab());
        return button;
    }

    private String newTabTooltip() {
        KeyStroke ks = services.keymap().strokeFor(TermAction.NEW_TAB);
        if (ks == null) {
            return "New tab";
        }
        String mods = KeyEvent.getModifiersExText(ks.getModifiers());
        String key = KeyEvent.getKeyText(ks.getKeyCode());
        String accel = mods.isBlank() ? key : mods + "+" + key;
        return "New tab (" + accel + ")";
    }

    /** After the keymap changes, refresh the "+" tooltip's accelerator hint. */
    public void refreshNewTabTooltip() {
        if (plusButton != null) {
            plusButton.setToolTipText(newTabTooltip());
        }
    }

    // ---- creating / wiring grids ----

    public void addTab() {
        PaneGrid grid = newGrid();
        insertGrid(grid);
        grid.openInitialLocal();
    }

    /** Opens an SSH session in a fresh tab: shows the session's name/icon, then connects async. */
    public void addSshTab(SshSessionConfig cfg) {
        PaneGrid grid = newGrid();
        int at = insertGrid(grid);
        tabs.setTitleAt(at, cfg.getName());
        tabs.setIconAt(at, services.iconFor(cfg.getIconId()));
        setTabColor(at, services.effectiveTabColorHex(cfg));
        grid.initEmpty();
        services.connectAsync(cfg, session -> grid.placeSessionInActive(session, sshFactory(cfg)));
    }

    public PaneGrid newGrid() {
        PaneGrid grid = new PaneGrid();
        wireGrid(grid);
        return grid;
    }

    /** Bind a grid's callbacks (drop/decoration/empty/move) to this tab pane. Idempotent, so it
     *  safely re-homes a grid moved in from another window. */
    private void wireGrid(PaneGrid grid) {
        grid.setDropHandler((cfg, placer) ->
                services.connectAsync(cfg, session -> placer.accept(session, sshFactory(cfg))));
        grid.setOnActiveChanged(() -> decorateTab(grid));
        grid.setOnActivity(() -> decorateTab(grid));
        grid.setOnEmpty(() -> closeTabForGrid(grid));
        grid.setMoveCoordinator(MOVE_COORDINATOR);
    }

    /** A factory that reconnects this SSH session (async, with re-auth) for restart/duplicate. */
    public SessionFactory sshFactory(SshSessionConfig cfg) {
        return onReady -> services.connectAsync(cfg, onReady::accept);
    }

    /** Inserts a grid as a new tab right before the "+" placeholder and selects it. */
    public int insertGrid(PaneGrid grid) {
        String base = "Terminal " + (++tabCounter);
        grid.putClientProperty("baseTitle", base);
        int at = plusIndex();
        tabs.insertTab(base, null, grid, null, at);
        tabs.setSelectedIndex(at);
        return at;
    }

    // ---- closing ----

    public void closeCurrentTab() {
        int index = tabs.getSelectedIndex();
        if (index >= 0) {
            closeTabAt(index);
        }
    }

    private void closeTabAt(int index) {
        if (index < 0 || index >= tabs.getTabCount()
                || tabs.getComponentAt(index) == plusPlaceholder) {
            return;
        }
        if (tabs.getComponentAt(index) instanceof PaneGrid grid) {
            grid.disposeAll();
        }
        tabs.removeTabAt(index);
    }

    /** Closes the tab hosting {@code grid}, terminating its sessions, if it's still present. */
    public void closeTabForGrid(PaneGrid grid) {
        int index = tabs.indexOfComponent(grid);
        if (index >= 0) {
            closeTabAt(index);
        }
    }

    /** Terminates every session in this strip (called when a detached window is closed). */
    public void disposeAllGrids() {
        for (PaneGrid grid : realGrids()) {
            grid.disposeAll();
        }
    }

    // ---- pane moves between tabs / windows ----

    /** Pull a pane out of its split into a new tab here. No-op for a sole pane within this window. */
    private void movePaneToNewTab(TerminalPane pane) {
        TabPane sourceHost = WindowManager.get().hostContaining(pane);
        if (sourceHost == null) {
            return;
        }
        PaneGrid sourceGrid = sourceHost.gridContaining(pane);
        if (sourceGrid == null) {
            return;
        }
        // Moving the only pane of a tab into a new tab in the same window is pointless; allow it
        // across windows (it's a meaningful relocation that empties/closes the source tab).
        if (sourceHost == this && sourceGrid.paneCount() <= 1) {
            return;
        }
        SessionFactory factory = sourceHost.detachPaneFromOwnGrid(pane);
        if (factory == null) {
            return;
        }
        PaneGrid grid = newGrid();
        insertGrid(grid);
        grid.adopt(pane, factory);
        decorateTab(grid);
        toFront();
    }

    /** Detach a pane from this strip's grid (without closing it), closing the tab if it empties. */
    public SessionFactory detachPaneFromOwnGrid(TerminalPane pane) {
        PaneGrid grid = gridContaining(pane);
        if (grid == null) {
            return null;
        }
        SessionFactory factory = grid.detachForMove(pane);
        if (grid.paneCount() == 0) {
            closeTabForGrid(grid);
        }
        return factory;
    }

    /** The grid in this strip that currently holds {@code pane}, or {@code null}. */
    public PaneGrid gridContaining(TerminalPane pane) {
        for (int i = 0; i <= lastRealTabIndex(); i++) {
            if (tabs.getComponentAt(i) instanceof PaneGrid grid && grid.contains(pane)) {
                return grid;
            }
        }
        return null;
    }

    public boolean containsGrid(PaneGrid grid) {
        return tabs.indexOfComponent(grid) >= 0;
    }

    /** Remove {@code grid} from this strip <em>without</em> closing its sessions (it's being moved). */
    public void detachGridForMove(PaneGrid grid) {
        int idx = tabs.indexOfComponent(grid);
        if (idx >= 0) {
            tabs.removeTabAt(idx);
        }
    }

    /** Adopt a grid moved in from another window: re-wire its callbacks here, then show it as a tab. */
    public void adoptGrid(PaneGrid grid) {
        wireGrid(grid);
        Object base = grid.getClientProperty("baseTitle");
        int at = plusIndex();
        tabs.insertTab(base != null ? base.toString() : "Terminal", null, grid, null, at);
        tabs.setSelectedIndex(at);
        decorateTab(grid);
    }

    // ---- detach / attach / duplicate ----

    /** Detach the selected tab into a brand-new detached window. */
    public void detachSelectedTab() {
        PaneGrid grid = currentGrid();
        if (grid != null) {
            WindowManager.get().detachToNewWindow(grid, null);
        }
    }

    /** Move the selected tab (this window must be detached) back to the main window. */
    public void attachSelectedToMain() {
        if (owner.isMain()) {
            return;
        }
        PaneGrid grid = currentGrid();
        if (grid == null) {
            return;
        }
        TabPane main = WindowManager.get().mainWindow().tabPane();
        detachGridForMove(grid);
        main.adoptGrid(grid);
        main.toFront();
    }

    /** Move every tab in this (detached) window back to the main window; the empty window closes. */
    public void attachAllToMain() {
        if (owner.isMain()) {
            return;
        }
        TabPane main = WindowManager.get().mainWindow().tabPane();
        for (PaneGrid grid : realGrids()) {
            detachGridForMove(grid);
            main.adoptGrid(grid);
        }
        main.toFront();
        // Removing the last tab triggers guardPlusSelection, which disposes this empty window.
    }

    /**
     * Duplicate the selected tab: clone its grid layout and open a fresh session in each cell via the
     * cell's restart factory (local shells start fresh; SSH reconnects through the vault). Cells with
     * no factory (e.g. the SFTP browser) are skipped.
     */
    public void duplicateSelectedTab() {
        PaneGrid source = currentGrid();
        if (source == null) {
            return;
        }
        List<PaneGrid.CellSpec> specs = source.cellSpecs();
        if (specs.isEmpty()) {
            return;
        }
        PaneGrid dup = newGrid();
        insertGrid(dup);
        dup.prepareEmptyGrid(source.rows(), source.cols(), source.activeRow(), source.activeCol());
        for (PaneGrid.CellSpec spec : specs) {
            spec.factory().create(session ->
                    dup.placeSessionInCell(spec.row(), spec.col(), session, spec.factory()));
        }
        decorateTab(dup);
    }

    // ---- decoration ----

    public PaneGrid currentGrid() {
        return (tabs.getSelectedComponent() instanceof PaneGrid grid) ? grid : null;
    }

    /** Sets a tab's icon + title from its active pane's session (SSH icon/name, or themed local). */
    public void decorateTab(PaneGrid grid) {
        int idx = tabs.indexOfComponent(grid);
        if (idx < 0) {
            return;
        }
        GridContent content = grid.activeContent();
        if (content == null) {
            return; // leave the current label (e.g. an SSH tab that's still connecting)
        }
        if (!(content instanceof TerminalPane pane)) {
            tabs.setIconAt(idx, IconLibrary.get().icon("builtin/folder", 16));
            tabs.setTitleAt(idx, content.displayTitle());
            setTabColor(idx, null);
            applyActivityIndicator(idx, grid, content);
            return;
        }
        TerminalSession session = pane.session();
        tabs.setIconAt(idx, SessionIcon.forSession(session, 16));
        if (session instanceof SshSession ssh) {
            tabs.setTitleAt(idx, ssh.title());
            setTabColor(idx, ssh.tabColorHex());
        } else {
            LocalSession local = (session instanceof LocalSession ls) ? ls : null;
            boolean customLocal = local != null && local.iconId() != null;
            Object base = grid.getClientProperty("baseTitle");
            String title = customLocal ? session.title()
                    : (base != null ? base.toString() : session.title());
            tabs.setTitleAt(idx, title);
            setTabColor(idx, null);
        }
        applyActivityIndicator(idx, grid, content);
    }

    private void applyActivityIndicator(int idx, PaneGrid grid, GridContent active) {
        if (grid.paneCount() > 1) {
            tabs.setIconAt(idx, new TabActivityIcon(grid));
            tabs.setForegroundAt(idx, null);
        } else {
            PaneActivity activity = (active instanceof TerminalPane pane)
                    ? pane.activity() : PaneActivity.NONE;
            tabs.setForegroundAt(idx, titleColorFor(activity));
        }
    }

    private static Color titleColorFor(PaneActivity activity) {
        return switch (activity) {
            case NEW_OUTPUT -> TabActivityIcon.NEW_OUTPUT_COLOR;
            case DISCONNECTED -> TabActivityIcon.DISCONNECTED_COLOR;
            case NONE -> null;
        };
    }

    private void updateForegroundStates() {
        Component selected = tabs.getSelectedComponent();
        for (int i = 0; i <= lastRealTabIndex(); i++) {
            if (tabs.getComponentAt(i) instanceof PaneGrid grid) {
                grid.setForeground(grid == selected);
            }
        }
    }

    private void setTabColor(int idx, String hex) {
        if (idx < 0 || idx >= tabs.getTabCount()) {
            return;
        }
        Color color = null;
        if (hex != null && !hex.isBlank()) {
            try {
                color = Color.decode(hex);
            } catch (NumberFormatException ignored) {
                // Malformed color → fall back to the default.
            }
        }
        tabs.setBackgroundAt(idx, color);
    }

    /** Re-apply theme to every grid in this strip (terminal recolor + active-pane accent borders). */
    public void refreshThemeAllTabs(ThemeColors theme) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof PaneGrid grid) {
                grid.refreshTheme();
                grid.applyTheme(theme);
            }
        }
    }

    // ---- tab indexing / ordering ----

    private int plusIndex() {
        return tabs.indexOfComponent(plusPlaceholder);
    }

    public int realTabCount() {
        return tabs.getTabCount() - (plusIndex() >= 0 ? 1 : 0);
    }

    private int lastRealTabIndex() {
        int plus = plusIndex();
        return (plus >= 0 ? plus : tabs.getTabCount()) - 1;
    }

    private List<PaneGrid> realGrids() {
        List<PaneGrid> grids = new ArrayList<>();
        for (int i = 0; i <= lastRealTabIndex(); i++) {
            if (tabs.getComponentAt(i) instanceof PaneGrid grid) {
                grids.add(grid);
            }
        }
        return grids;
    }

    /** Keep the "+" placeholder from ever being the active tab. */
    private void guardPlusSelection() {
        int plus = plusIndex();
        if (tabs.getSelectedIndex() != plus) {
            return;
        }
        if (realTabCount() == 0) {
            if (owner.isMain()) {
                // Honor the startup preference: closing the last tab springs a fresh one only when
                // the user opted into a startup terminal; otherwise leave the window empty.
                if (AppSettings.get().isOpenTerminalOnStartup()) {
                    addTab();
                }
            } else {
                // A detached window with nothing left to show closes itself.
                WindowManager.get().closeDetached(owner);
            }
        } else {
            tabs.setSelectedIndex(plus - 1);
        }
    }

    public void moveSelectedTab(int delta) {
        int from = tabs.getSelectedIndex();
        if (from < 0 || tabs.getComponentAt(from) == plusPlaceholder) {
            return;
        }
        int to = from + delta;
        if (to < 0 || to > lastRealTabIndex()) {
            return;
        }
        moveTab(from, to);
    }

    private void moveTab(int from, int to) {
        to = Math.max(0, Math.min(to, lastRealTabIndex()));
        if (from == to || from < 0 || tabs.getComponentAt(from) == plusPlaceholder) {
            return;
        }
        Component comp = tabs.getComponentAt(from);
        String title = tabs.getTitleAt(from);
        javax.swing.Icon icon = tabs.getIconAt(from);
        String tip = tabs.getToolTipTextAt(from);
        boolean wasSelected = tabs.getSelectedIndex() == from;
        tabs.removeTabAt(from);
        tabs.insertTab(title, icon, comp, tip, to);
        if (comp instanceof PaneGrid grid) {
            decorateTab(grid);
        }
        if (wasSelected) {
            tabs.setSelectedIndex(to);
        }
    }

    private void toFront() {
        if (owner.frame() != null) {
            owner.frame().toFront();
        }
    }

    // ---- drag & drop (reorder, move across windows, detach by dropping outside) ----

    private void installTabDnd() {
        captureSelectionBeforeUi();

        DragGestureListener onDrag = dge -> {
            Point origin = dge.getDragOrigin();
            int idx = tabs.indexAtLocation(origin.x, origin.y);
            if (idx < 0 || tabs.getComponentAt(idx) == plusPlaceholder
                    || !(tabs.getComponentAt(idx) instanceof PaneGrid grid)) {
                return;
            }
            int restore = (selectedBeforePress >= 0 && selectedBeforePress <= lastRealTabIndex())
                    ? selectedBeforePress : idx;
            if (tabs.getSelectedIndex() != restore) {
                tabs.setSelectedIndex(restore);
            }
            DragSourceListener dsl = new DragSourceAdapter() {
                @Override
                public void dragDropEnd(DragSourceDropEvent dsde) {
                    // A successful drop was handled by a target (reorder, cross-window move, or a
                    // pane drop into a grid). Only an unhandled drop *outside* every window detaches.
                    if (dsde.getDropSuccess() || !containsGrid(grid)) {
                        return;
                    }
                    if (WindowManager.get().isInsideAnyWindow(dsde.getLocation())) {
                        return;
                    }
                    WindowManager.get().detachToNewWindow(grid, dsde.getLocation());
                }
            };
            try {
                dge.startDrag(null, new TabTransferable(grid), dsl);
            } catch (InvalidDnDOperationException ignored) {
                // A drag is already in flight.
            }
        };
        DragSource.getDefaultDragSource()
                .createDefaultDragGestureRecognizer(tabs, DnDConstants.ACTION_MOVE, onDrag);

        new DropTarget(tabs, DnDConstants.ACTION_MOVE, new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(TabTransferable.TAB_FLAVOR)) {
                    dtde.acceptDrag(DnDConstants.ACTION_MOVE);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (!dtde.isDataFlavorSupported(TabTransferable.TAB_FLAVOR)) {
                        dtde.rejectDrop();
                        return;
                    }
                    dtde.acceptDrop(DnDConstants.ACTION_MOVE);
                    PaneGrid grid = (PaneGrid) dtde.getTransferable()
                            .getTransferData(TabTransferable.TAB_FLAVOR);
                    if (containsGrid(grid)) {
                        int from = tabs.indexOfComponent(grid);
                        int to = tabs.indexAtLocation(dtde.getLocation().x, dtde.getLocation().y);
                        if (to < 0 || to > lastRealTabIndex()) {
                            to = lastRealTabIndex();
                        }
                        if (from >= 0) {
                            moveTab(from, to);
                        }
                    } else {
                        // A tab dragged in from another window: take ownership of its live grid.
                        TabPane source = WindowManager.get().hostContaining(grid);
                        if (source != null) {
                            source.detachGridForMove(grid);
                            adoptGrid(grid);
                            toFront();
                        }
                    }
                    dtde.dropComplete(true);
                } catch (Exception e) {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private void captureSelectionBeforeUi() {
        MouseListener capture = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectedBeforePress = tabs.getSelectedIndex();
            }
        };
        MouseListener[] existing = tabs.getMouseListeners();
        for (MouseListener ml : existing) {
            tabs.removeMouseListener(ml);
        }
        tabs.addMouseListener(capture);
        for (MouseListener ml : existing) {
            tabs.addMouseListener(ml);
        }
    }

    // ---- tab context menu ----

    private void installTabContextMenu() {
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int idx = tabs.indexAtLocation(e.getX(), e.getY());
        if (idx < 0 || tabs.getComponentAt(idx) == plusPlaceholder
                || !(tabs.getComponentAt(idx) instanceof PaneGrid grid)) {
            return;
        }
        tabs.setSelectedIndex(idx);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem duplicate = new JMenuItem("Duplicate Tab");
        duplicate.addActionListener(a -> duplicateSelectedTab());
        menu.add(duplicate);
        JMenuItem detach = new JMenuItem("Detach Tab to New Window");
        detach.addActionListener(a -> WindowManager.get().detachToNewWindow(grid, null));
        menu.add(detach);
        if (!owner.isMain()) {
            JMenuItem attach = new JMenuItem("Attach to Main Window");
            attach.addActionListener(a -> attachSelectedToMain());
            menu.add(attach);
        }
        menu.addSeparator();
        JMenuItem close = new JMenuItem("Close Tab");
        close.addActionListener(a -> closeTabForGrid(grid));
        menu.add(close);
        menu.show(tabs, e.getX(), e.getY());
    }
}
