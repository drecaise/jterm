package com.katmoda.jterm.app;

import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.keymap.TermAction;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.macro.MacroRunner;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.dnd.PaneTransferable;
import com.katmoda.jterm.dnd.TabTransferable;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.terminal.local.LocalSession;
import com.katmoda.jterm.terminal.TerminalProfile;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.ssh.SshSession;
import com.katmoda.jterm.terminal.ssh.agent.AgentSupport;
import com.katmoda.jterm.ui.AgentKeysDialog;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.grid.PaneGrid;
import com.katmoda.jterm.ui.macro.MacroManagerDialog;
import com.katmoda.jterm.ui.pane.TerminalPane;
import com.katmoda.jterm.ui.preferences.PreferencesDialog;
import com.katmoda.jterm.ui.preferences.ShortcutsDialog;
import com.katmoda.jterm.ui.security.MasterPasswordDialog;
import com.katmoda.jterm.ui.sidebar.OpenMode;
import com.katmoda.jterm.ui.sidebar.SessionSidebar;
import com.katmoda.jterm.ui.SessionIcon;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Taskbar;
import java.awt.KeyboardFocusManager;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Top-level window: sidebar on the left, a tab strip of {@link PaneGrid}s on the right.
 *
 * <p>Terminal shortcuts (split/close/new-tab/theme/…) are handled by a single
 * {@link KeyboardFocusManager} dispatcher that runs before normal key dispatch, so they
 * work even while a terminal has keyboard focus (JediTerm would otherwise consume them).
 * The dispatcher consumes matching events, preventing duplicate firing via menu
 * accelerators (which are shown only for discoverability).</p>
 */
public final class MainWindow {

    private final JFrame frame = new JFrame("jterm");
    private final JTabbedPane tabs = new JTabbedPane();
    private final SessionStore sessionStore = new SessionStore();
    private final Keymap keymap = Keymap.loadOrDefaults();

    /** Permanent trailing tab that hosts the "+" button, keeping it right after the last tab. */
    private final JPanel plusPlaceholder = new JPanel();
    private JButton plusButton;
    /** Tab selected immediately before the current mouse press, captured so a tab drag can keep the
     *  previously-active grid visible as the drop target instead of switching to the dragged tab. */
    private int selectedBeforePress = -1;

    private int tabCounter = 0;
    /** While true, the global terminal-shortcut dispatcher stands down so the editor can capture keys. */
    private boolean shortcutCaptureActive = false;
    private SessionSidebar sidebar;

    public void show() {
        sidebar = new SessionSidebar(sessionStore, this::openSshSession,
                this::openLocalInCurrent, this::openWslSession);

        configureTabs();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, tabs);
        split.setDividerLocation(240);
        split.setResizeWeight(0.0);

        frame.setJMenuBar(buildMenuBar());
        frame.setLayout(new BorderLayout());
        frame.add(split, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(new Dimension(1100, 720));
        frame.setLocationRelativeTo(null);
        applyAppIcon();

        installShortcutDispatcher();

        // Refresh active-pane accent borders and re-decorate tabs (so the themed local-terminal
        // icon swaps) across all tabs when the theme switches.
        ThemeManager.get().addListener(theme -> {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (tabs.getComponentAt(i) instanceof PaneGrid grid) {
                    grid.refreshTheme();
                    grid.applyTheme(theme);
                }
            }
        });

        addTab();
        // Attached only after the first real tab exists so the placeholder's transient
        // auto-selection during setup doesn't trigger a spurious extra tab.
        tabs.addChangeListener(e -> guardPlusSelection());

        frame.setVisible(true);
    }

    private void applyAppIcon() {
        frame.setIconImages(AppIcon.images());
        // Taskbar/dock icon where the platform supports it (macOS dock, some Linux WMs).
        if (Taskbar.isTaskbarSupported()) {
            try {
                Taskbar.getTaskbar().setIconImage(AppIcon.render(256));
            } catch (UnsupportedOperationException | SecurityException ignored) {
                // Not all platforms allow setting the taskbar image; the frame icon still applies.
            }
        }
    }

    private void configureTabs() {
        // Card-style tabs: each tab is drawn as a bordered "card" so they read as real tabs.
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        BiConsumer<JTabbedPane, Integer> closeCallback = (pane, index) -> closeTabAt(index);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", closeCallback);
        installPlusTab();
        installTabDnd();
    }

    /**
     * Adds a permanent, non-closable trailing tab whose tab-component is a compact "+" button,
     * so "new tab" sits immediately right of the last real tab (the browser pattern) rather than
     * pinned to the far edge. The placeholder never holds content; {@link #guardPlusSelection()}
     * keeps it from becoming the active tab.
     */
    private void installPlusTab() {
        plusPlaceholder.putClientProperty("JTabbedPane.tabClosable", false);
        tabs.addTab(null, plusPlaceholder);
        plusButton = buildNewTabButton();
        tabs.setTabComponentAt(tabs.indexOfComponent(plusPlaceholder), plusButton);
        installNewTabDropTarget(plusButton);
    }

    /** Dropping a dragged pane on "+" pulls it out of its split into a brand-new tab. */
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
        KeyStroke ks = keymap.strokeFor(TermAction.NEW_TAB);
        if (ks == null) {
            return "New tab";
        }
        String mods = KeyEvent.getModifiersExText(ks.getModifiers());
        String key = KeyEvent.getKeyText(ks.getKeyCode());
        String accel = mods.isBlank() ? key : mods + "+" + key;
        return "New tab (" + accel + ")";
    }

    // ---- tabs ----

    private void addTab() {
        PaneGrid grid = newGrid();
        insertGrid(grid);
        grid.openInitialLocal();
    }

    /** Opens an SSH session in a fresh tab: shows the session's name/icon, then connects async. */
    private void addSshTab(SshSessionConfig cfg) {
        PaneGrid grid = newGrid();
        int at = insertGrid(grid);
        // Immediate feedback before the (off-EDT) connection completes.
        tabs.setTitleAt(at, cfg.getName());
        tabs.setIconAt(at, iconFor(cfg.getIconId()));
        setTabColor(at, cfg.getTabColorHex());
        grid.initEmpty();
        connectAsync(cfg, session -> grid.placeSessionInActive(session, sshFactory(cfg)));
    }

    private PaneGrid newGrid() {
        PaneGrid grid = new PaneGrid();
        // A dropped SSH session connects off-EDT; the grid then places it (split a pane or fill an
        // empty cell). The factory is paired with the session so a restart can reconnect it.
        grid.setDropHandler((cfg, placer) ->
                connectAsync(cfg, session -> placer.accept(session, sshFactory(cfg))));
        grid.setOnActiveChanged(() -> decorateTab(grid));
        // When the last pane is closed from the stopped screen, close the tab too.
        grid.setOnEmpty(() -> closeTabForGrid(grid));
        // Lets this grid adopt a pane dragged in from another tab (detach it from its source first).
        grid.setMoveCoordinator(this::detachFromOwner);
        return grid;
    }

    // ---- pane moves between tabs (drag a pane out / a tab into a grid) ----

    /** Pull a pane out of its split into a new tab. No-op if it's the only pane in its tab. */
    private void movePaneToNewTab(TerminalPane pane) {
        PaneGrid owner = gridContaining(pane);
        if (owner == null || owner.paneCount() <= 1) {
            return;
        }
        SessionFactory factory = owner.detachForMove(pane);
        if (factory == null) {
            return;
        }
        PaneGrid grid = newGrid();
        insertGrid(grid);
        grid.adopt(pane, factory);
        decorateTab(grid);
    }

    /**
     * Detach a pane from whichever tab's grid owns it (without closing it), closing that tab if it
     * empties. Used by a destination grid to take ownership of a pane dragged in from another tab.
     */
    private SessionFactory detachFromOwner(TerminalPane pane) {
        PaneGrid owner = gridContaining(pane);
        if (owner == null) {
            return null;
        }
        SessionFactory factory = owner.detachForMove(pane);
        if (owner.paneCount() == 0) {
            closeTabForGrid(owner);
        }
        return factory;
    }

    /** The tab grid that currently holds {@code pane}, or {@code null}. */
    private PaneGrid gridContaining(TerminalPane pane) {
        for (int i = 0; i <= lastRealTabIndex(); i++) {
            if (tabs.getComponentAt(i) instanceof PaneGrid grid && grid.contains(pane)) {
                return grid;
            }
        }
        return null;
    }

    /** Closes the tab hosting {@code grid}, if it's still present. */
    private void closeTabForGrid(PaneGrid grid) {
        int index = tabs.indexOfComponent(grid);
        if (index >= 0) {
            closeTabAt(index);
        }
    }

    /** A factory that reconnects this SSH session (async, with re-auth) for restart. */
    private SessionFactory sshFactory(SshSessionConfig cfg) {
        return onReady -> connectAsync(cfg, onReady::accept);
    }

    /** Inserts a grid as a new tab right before the "+" placeholder and selects it. */
    private int insertGrid(PaneGrid grid) {
        String base = "Terminal " + (++tabCounter);
        grid.putClientProperty("baseTitle", base);
        int at = plusIndex();
        tabs.insertTab(base, null, grid, null, at);
        tabs.setSelectedIndex(at);
        return at;
    }

    private void closeCurrentTab() {
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
        // When the last real tab goes, removing it selects the placeholder; the selection
        // guard then recreates a fresh tab, so no explicit re-add is needed here.
    }

    /** Index of the "+" placeholder, which always trails the real tabs. */
    private int plusIndex() {
        return tabs.indexOfComponent(plusPlaceholder);
    }

    private int realTabCount() {
        return tabs.getTabCount() - (plusIndex() >= 0 ? 1 : 0);
    }

    /** Keep the "+" placeholder from ever being the active tab. */
    private void guardPlusSelection() {
        int plus = plusIndex();
        if (tabs.getSelectedIndex() != plus) {
            return;
        }
        if (realTabCount() == 0) {
            addTab();
        } else {
            tabs.setSelectedIndex(plus - 1);
        }
    }

    private PaneGrid currentGrid() {
        return (tabs.getSelectedComponent() instanceof PaneGrid grid) ? grid : null;
    }

    /** Sets a tab's icon + title from its active pane's session (SSH icon/name, or themed local). */
    private void decorateTab(PaneGrid grid) {
        int idx = tabs.indexOfComponent(grid);
        if (idx < 0) {
            return;
        }
        TerminalPane pane = grid.activePane();
        if (pane == null) {
            return; // leave the current label (e.g. an SSH tab that's still connecting)
        }
        TerminalSession session = pane.session();
        tabs.setIconAt(idx, SessionIcon.forSession(session, 16));
        if (session instanceof SshSession ssh) {
            tabs.setTitleAt(idx, ssh.title());
            setTabColor(idx, ssh.tabColorHex());
        } else {
            LocalSession local = (session instanceof LocalSession ls) ? ls : null;
            boolean customLocal = local != null && local.iconId() != null;
            // A WSL distro (a custom-icon local session) carries its own name; a plain shell uses
            // the tab's generic base title ("Terminal N"). Deriving both from the active pane keeps
            // the title tracking the focused split the same way the icon already does.
            Object base = grid.getClientProperty("baseTitle");
            String title = customLocal ? session.title()
                    : (base != null ? base.toString() : session.title());
            tabs.setTitleAt(idx, title);
            setTabColor(idx, null);
        }
    }

    /**
     * Applies a custom tab background (or clears it to the theme default when {@code hex} is null).
     * A custom color is a plain {@code Color}, so it intentionally persists across theme toggles;
     * the default is restored by passing {@code null}, which lets the tab follow the theme again.
     */
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

    private Icon iconFor(String iconId) {
        String id = (iconId != null && !iconId.isBlank()) ? iconId : "builtin/server";
        return IconLibrary.get().icon(id, 16);
    }

    // ---- session opening ----

    private void openLocalInCurrent() {
        PaneGrid grid = currentGrid();
        if (grid != null) {
            grid.openLocalInActive();
        }
    }

    private void openSshSession(SshSessionConfig cfg, OpenMode mode) {
        if (mode == OpenMode.NEW_TAB) {
            addSshTab(cfg);
            return;
        }
        PaneGrid grid = currentGrid();
        if (grid == null) {
            return;
        }
        connectAsync(cfg, session -> {
            SessionFactory factory = sshFactory(cfg);
            switch (mode) {
                case ACTIVE -> grid.placeSessionInActive(session, factory);
                case SPLIT_COLUMN -> grid.splitColumnAndOpen(session, factory);
                case SPLIT_ROW -> grid.splitRowAndOpen(session, factory);
                default -> { }
            }
        });
    }

    /** Opens a detected WSL2 distribution (synchronously — it's a local pty, no network connect). */
    private void openWslSession(String distro, OpenMode mode) {
        if (mode == OpenMode.NEW_TAB) {
            addWslTab(distro);
            return;
        }
        PaneGrid grid = currentGrid();
        if (grid == null) {
            return;
        }
        LocalSession session = safeWslSession(distro);
        if (session == null) {
            return;
        }
        SessionFactory factory = wslFactory(distro);
        switch (mode) {
            case ACTIVE -> grid.placeSessionInActive(session, factory);
            case SPLIT_COLUMN -> grid.splitColumnAndOpen(session, factory);
            case SPLIT_ROW -> grid.splitRowAndOpen(session, factory);
            default -> { }
        }
    }

    /** Opens a WSL2 distribution in a fresh tab titled with the distro name. */
    private void addWslTab(String distro) {
        LocalSession session = safeWslSession(distro);
        if (session == null) {
            return;
        }
        PaneGrid grid = newGrid();
        insertGrid(grid);
        // The tab keeps its generic "Terminal N" base title (for any plain shell split into it);
        // decorateTab names the WSL pane itself from the session (the distro).
        grid.initEmpty();
        grid.placeSessionInActive(session, wslFactory(distro));
    }

    /** Starts a WSL session, surfacing any failure as a dialog (mirrors the local-shell path). */
    private LocalSession safeWslSession(String distro) {
        try {
            return LocalSession.startWsl(distro);
        } catch (Exception e) {
            ErrorDialog.show(frame, "jterm", "Failed to start WSL distribution \"" + distro + "\":", e);
            return null;
        }
    }

    /** A factory that restarts this WSL session (e.g. from the session-stopped screen). */
    private SessionFactory wslFactory(String distro) {
        return onReady -> {
            LocalSession session = safeWslSession(distro);
            if (session != null) {
                onReady.accept(session);
            }
        };
    }

    /** Connect an SSH session off the EDT, then hand the live session to {@code onConnected} on the EDT. */
    private void connectAsync(SshSessionConfig cfg, Consumer<SshSession> onConnected) {
        // Resolve any password on the EDT first — it may unlock the vault or prompt.
        String password = resolvePassword(cfg);
        new SwingWorker<SshSession, Void>() {
            @Override
            protected SshSession doInBackground() throws Exception {
                TerminalProfile profile = AppSettings.get().resolve(cfg.getTerminalType(),
                        cfg.getTerminalCharset(), cfg.getFontFamily(), cfg.getFontSize());
                return SshSession.connect(cfg.getHost(), cfg.getPort(), cfg.getUser(),
                        cfg.isAgentForwarding(), password, cfg.getName(), cfg.getIconId(), profile,
                        cfg.getHighlightListId(), cfg.getTabColorHex());
            }

            @Override
            protected void done() {
                try {
                    SshSession session = get();
                    onConnected.accept(session);
                    runConnectMacro(cfg, session);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    ErrorDialog.show(frame, "jterm", "SSH connection failed:", cause);
                }
            }
        }.execute();
    }

    /** If the session has a configured run-on-connect macro, replay it into the new channel. */
    private void runConnectMacro(SshSessionConfig cfg, SshSession session) {
        Macro macro = MacroLibrary.get().byId(cfg.getMacroId());
        if (macro != null) {
            MacroRunner.run(macro, session.connector());
        }
    }

    /**
     * Resolves the password to try (EDT): {@code null} if password auth is off; a saved
     * password unlocked from the vault (via keyring or prompt); otherwise a one-time prompt.
     */
    private String resolvePassword(SshSessionConfig cfg) {
        if (!cfg.isPasswordAuth()) {
            return null;
        }
        VaultManager vaults = VaultManager.get();
        if (cfg.isSavePassword() && vaults.vault().hasPassword(cfg.getId())) {
            if (!vaults.ensureUnlocked(frame)) {
                return null;
            }
            try {
                return vaults.vault().getPassword(cfg.getId());
            } catch (VaultException e) {
                return null;
            }
        }
        char[] entered = MasterPasswordDialog.promptSessionPassword(frame, cfg.getName());
        if (entered == null) {
            return null;
        }
        String password = new String(entered);
        java.util.Arrays.fill(entered, '\0');
        return password;
    }

    // ---- shortcuts ----

    private void installShortcutDispatcher() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || shortcutCaptureActive) {
                return false; // let the shortcut editor capture keys while it's recording
            }
            KeyStroke stroke = KeyStroke.getKeyStrokeForEvent(e);
            TermAction action = keymap.actionFor(stroke);
            if (action == null) {
                // No keymap action: a macro may claim this stroke (conflicts are prevented at
                // assignment time, so a stroke never maps to both an action and a macro).
                Macro macro = MacroLibrary.get().byHotkey(stroke.toString());
                if (macro != null) {
                    runMacroOnActivePane(macro);
                    return true;
                }
                return false;
            }
            handle(action);
            return true; // consume so JediTerm / menu accelerators don't also fire
        });
    }

    private void handle(TermAction action) {
        PaneGrid grid = currentGrid();
        switch (action) {
            case NEW_TAB -> addTab();
            case CLOSE_TAB -> closeCurrentTab();
            case SPLIT_COLUMN -> {
                if (grid != null) {
                    grid.splitColumn();
                }
            }
            case SPLIT_ROW -> {
                if (grid != null) {
                    grid.splitRow();
                }
            }
            case CLOSE_PANE -> {
                if (grid != null) {
                    grid.closeActivePane();
                }
            }
            case OPEN_LOCAL -> openLocalInCurrent();
            case TOGGLE_THEME -> ThemeManager.get().toggle();
            case TOGGLE_BROADCAST -> {
                if (grid != null) {
                    grid.toggleBroadcast();
                }
            }
            case MOVE_SESSION_UP -> {
                if (sidebar != null) {
                    sidebar.moveSelectedUp();
                }
            }
            case MOVE_SESSION_DOWN -> {
                if (sidebar != null) {
                    sidebar.moveSelectedDown();
                }
            }
            case MOVE_TAB_LEFT -> moveSelectedTab(-1);
            case MOVE_TAB_RIGHT -> moveSelectedTab(1);
        }
    }

    // ---- tab reordering (keyboard + drag) ----

    /** Moves the active tab one slot left ({@code -1}) or right ({@code +1}), clamped to real tabs. */
    private void moveSelectedTab(int delta) {
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

    /** Highest index that holds a real (movable) tab — everything before the "+" placeholder. */
    private int lastRealTabIndex() {
        int plus = plusIndex();
        return (plus >= 0 ? plus : tabs.getTabCount()) - 1;
    }

    /**
     * Reorders a tab from {@code from} to {@code to}, preserving its title, icon, tooltip, custom
     * tab color and selection. The "+" placeholder is never moved past. JTabbedPane has no native
     * move, so the tab is removed and re-inserted.
     */
    private void moveTab(int from, int to) {
        to = Math.max(0, Math.min(to, lastRealTabIndex()));
        if (from == to || from < 0 || tabs.getComponentAt(from) == plusPlaceholder) {
            return;
        }
        Component comp = tabs.getComponentAt(from);
        String title = tabs.getTitleAt(from);
        Icon icon = tabs.getIconAt(from);
        String tip = tabs.getToolTipTextAt(from);
        boolean wasSelected = tabs.getSelectedIndex() == from;
        tabs.removeTabAt(from);
        tabs.insertTab(title, icon, comp, tip, to);
        // Re-derive icon/title/custom color from the live session so a moved default-colored tab
        // keeps following the theme (rather than pinning whatever color it had at this index).
        if (comp instanceof PaneGrid grid) {
            decorateTab(grid);
        }
        if (wasSelected) {
            tabs.setSelectedIndex(to);
        }
    }

    /**
     * Wires tab drag-and-drop: a tab can be dragged across the strip to reorder, or (single-pane
     * tabs only) dropped into another tab's pane/empty cell to move that terminal in. Reorder and
     * drag-into-grid both start from a tab drag, so this replaces the old {@code MouseAdapter}
     * reorder — a {@code MouseAdapter} drag and a Swing DnD drag can't share the tab strip.
     */
    private void installTabDnd() {
        // Capture the selection *before* the press selects the pressed tab, so a drag can keep the
        // previously-active grid on screen as the drop target. Runs before the UI's own listener.
        captureSelectionBeforeUi();

        DragGestureListener onDrag = dge -> {
            Point origin = dge.getDragOrigin();
            int idx = tabs.indexAtLocation(origin.x, origin.y);
            if (idx < 0 || tabs.getComponentAt(idx) == plusPlaceholder
                    || !(tabs.getComponentAt(idx) instanceof PaneGrid grid)) {
                return;
            }
            // Keep the previously-viewed grid visible (the press already switched to the dragged tab).
            int restore = (selectedBeforePress >= 0 && selectedBeforePress <= lastRealTabIndex())
                    ? selectedBeforePress : idx;
            if (tabs.getSelectedIndex() != restore) {
                tabs.setSelectedIndex(restore);
            }
            try {
                dge.startDrag(null, new TabTransferable(grid));
            } catch (InvalidDnDOperationException ignored) {
                // A drag is already in flight.
            }
        };
        DragSource.getDefaultDragSource()
                .createDefaultDragGestureRecognizer(tabs, DnDConstants.ACTION_MOVE, onDrag);

        // Reorder: a tab dropped on the strip (drops over panes/cells are handled by their own,
        // deeper drop targets, so they never reach here).
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
                    int from = tabs.indexOfComponent(grid);
                    int to = tabs.indexAtLocation(dtde.getLocation().x, dtde.getLocation().y);
                    if (to < 0 || to > lastRealTabIndex()) {
                        to = lastRealTabIndex();
                    }
                    if (from >= 0) {
                        moveTab(from, to);
                    }
                    dtde.dropComplete(true);
                } catch (Exception e) {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    /** Records the selected tab on each press, ahead of the UI's selection, into
     *  {@link #selectedBeforePress}. */
    private void captureSelectionBeforeUi() {
        MouseListener capture = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectedBeforePress = tabs.getSelectedIndex();
            }
        };
        // Re-order listeners so ours fires before the UI's selection handler (added at UI install).
        MouseListener[] existing = tabs.getMouseListeners();
        for (MouseListener ml : existing) {
            tabs.removeMouseListener(ml);
        }
        tabs.addMouseListener(capture);
        for (MouseListener ml : existing) {
            tabs.addMouseListener(ml);
        }
    }

    // ---- menu ----

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("New Tab", TermAction.NEW_TAB));
        file.add(menuItem("Close Tab", TermAction.CLOSE_TAB));
        file.add(menuItem("Move Tab Left", TermAction.MOVE_TAB_LEFT));
        file.add(menuItem("Move Tab Right", TermAction.MOVE_TAB_RIGHT));
        file.addSeparator();
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> frame.dispose());
        file.add(quit);

        JMenu terminal = new JMenu("Terminal");
        terminal.add(menuItem("Open Local Shell", TermAction.OPEN_LOCAL));
        terminal.add(menuItem("Split Column", TermAction.SPLIT_COLUMN));
        terminal.add(menuItem("Split Row", TermAction.SPLIT_ROW));
        terminal.add(menuItem("Close Pane", TermAction.CLOSE_PANE));

        JMenu ssh = new JMenu("SSH");
        JMenuItem agentKeys = new JMenuItem("Show Agent Keys…");
        agentKeys.addActionListener(e -> showAgentKeys());
        ssh.add(agentKeys);

        JMenu preferences = new JMenu("Preferences");
        preferences.add(menuItem("Toggle Light/Dark", TermAction.TOGGLE_THEME));
        preferences.addSeparator();
        JMenuItem shortcuts = new JMenuItem("Keyboard Shortcuts…");
        shortcuts.addActionListener(e -> openShortcutsEditor());
        preferences.add(shortcuts);
        JMenuItem prefsDialog = new JMenuItem("Preferences…");
        prefsDialog.addActionListener(e -> PreferencesDialog.show(frame));
        preferences.add(prefsDialog);

        bar.add(file);
        bar.add(terminal);
        bar.add(ssh);
        bar.add(buildMacrosMenu());
        bar.add(preferences);
        return bar;
    }

    /**
     * The Macros menu: one item per saved macro (runs it on the active pane), then
     * "Manage Macros…" to edit the collection. Rebuilt with the menu bar so it reflects the
     * current {@link MacroLibrary}.
     */
    private JMenu buildMacrosMenu() {
        JMenu macros = new JMenu("Macros");
        List<Macro> all = MacroLibrary.get().macros();
        for (Macro macro : all) {
            JMenuItem item = new JMenuItem(macro.getName());
            item.addActionListener(e -> runMacroOnActivePane(macro));
            macros.add(item);
        }
        if (!all.isEmpty()) {
            macros.addSeparator();
        }
        JMenuItem manage = new JMenuItem("Manage Macros…");
        manage.addActionListener(e -> openMacroManager());
        macros.add(manage);
        return macros;
    }

    /** Runs a macro on the active pane's (broadcasting) connector, so broadcast is respected. */
    private void runMacroOnActivePane(Macro macro) {
        PaneGrid grid = currentGrid();
        if (grid == null) {
            return;
        }
        TerminalPane pane = grid.activePane();
        if (pane != null) {
            MacroRunner.run(macro, pane.inputConnector());
        }
    }

    /**
     * Opens the macro manager with the global shortcut dispatcher suppressed, so the hotkey
     * recorder inside captures combinations instead of firing their actions (same mechanism as
     * the keyboard-shortcuts editor). Rebuilds the menu afterwards to reflect any changes.
     */
    private void openMacroManager() {
        shortcutCaptureActive = true;
        try {
            MacroManagerDialog.show(frame, keymap);
        } finally {
            shortcutCaptureActive = false;
        }
        frame.setJMenuBar(buildMenuBar());
        frame.revalidate();
    }

    /**
     * Opens the (modal) shortcut editor with the global terminal-shortcut dispatcher suppressed,
     * so pressing a bound combination is captured as the new binding instead of firing its action.
     */
    private void openShortcutsEditor() {
        shortcutCaptureActive = true;
        try {
            ShortcutsDialog.show(frame, keymap, this::onKeymapChanged);
        } finally {
            shortcutCaptureActive = false;
        }
    }

    /** After the keymap is edited: rebuild the menu (accelerators) and refresh the "+" tooltip. */
    private void onKeymapChanged() {
        frame.setJMenuBar(buildMenuBar());
        frame.revalidate();
        if (plusButton != null) {
            plusButton.setToolTipText(newTabTooltip());
        }
    }

    /** Lists the keys the app can use from the ssh-agent (read off the EDT, shown on it). */
    private void showAgentKeys() {
        new SwingWorker<List<AgentSupport.AgentKey>, Void>() {
            private Exception failure;

            @Override
            protected List<AgentSupport.AgentKey> doInBackground() {
                try {
                    return AgentSupport.listIdentities();
                } catch (Exception e) {
                    failure = e;
                    return null;
                }
            }

            @Override
            protected void done() {
                if (failure != null) {
                    ErrorDialog.show(frame, "SSH Agent",
                            "Could not read keys from the ssh-agent. Is it running, and have you "
                                    + "added a key (ssh-add)?", failure);
                    return;
                }
                try {
                    AgentKeysDialog.show(frame, get());
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    /**
     * Menu item showing the bound accelerator for discoverability. The actual handling
     * is done by the global dispatcher (which consumes the event first), so no listener
     * is attached here to avoid double-firing.
     */
    private JMenuItem menuItem(String label, TermAction action) {
        JMenuItem item = new JMenuItem(label);
        item.setAccelerator(keymap.strokeFor(action));
        item.setEnabled(true);
        item.addActionListener(e -> handle(action));
        return item;
    }
}
