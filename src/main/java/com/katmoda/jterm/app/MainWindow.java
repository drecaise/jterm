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
package com.katmoda.jterm.app;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.keymap.TermAction;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.macro.MacroRunner;
import com.katmoda.jterm.security.CredentialResolver;
import com.katmoda.jterm.security.CredentialVault;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.TunnelConfig;
import com.katmoda.jterm.session.TunnelStore;
import com.katmoda.jterm.terminal.ConnectionService;
import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.ssh.SshConnect;
import com.katmoda.jterm.terminal.ssh.SshSession;
import com.katmoda.jterm.terminal.ssh.TunnelManager;
import com.katmoda.jterm.terminal.ssh.agent.AgentSupport;
import com.katmoda.jterm.ui.AgentKeysDialog;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.grid.PaneGrid;
import com.katmoda.jterm.ui.macro.MacroManagerDialog;
import com.katmoda.jterm.ui.pane.TerminalPane;
import com.katmoda.jterm.ui.sftp.SftpLauncher;
import com.katmoda.jterm.ui.tunnel.TunnelManagerDialog;
import com.katmoda.jterm.ui.preferences.PreferencesDialog;
import com.katmoda.jterm.ui.preferences.ShortcutsDialog;
import com.katmoda.jterm.ui.security.MasterPasswordDialog;
import com.katmoda.jterm.ui.sidebar.FolderOpenMode;
import com.katmoda.jterm.ui.sidebar.OpenMode;
import com.katmoda.jterm.ui.sidebar.SessionSidebar;
import com.katmoda.jterm.ui.sidebar.SidebarSplit;
import com.katmoda.jterm.ui.tabs.TabPane;
import com.katmoda.jterm.ui.theme.ThemeManager;
import com.katmoda.jterm.ui.windowing.TerminalServices;
import com.katmoda.jterm.ui.windowing.TerminalWindow;

import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Taskbar;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level window: sidebar on the left, a tab strip of {@link PaneGrid}s on the right.
 *
 * <p>Terminal shortcuts (split/close/new-tab/theme/…) are handled by a single
 * {@link KeyboardFocusManager} dispatcher that runs before normal key dispatch, so they
 * work even while a terminal has keyboard focus (JediTerm would otherwise consume them).
 * The dispatcher consumes matching events, preventing duplicate firing via menu
 * accelerators (which are shown only for discoverability).</p>
 */
public final class MainWindow implements TerminalWindow, TerminalServices {

    private static final Logger LOG = LoggerFactory.getLogger(MainWindow.class);

    private final JFrame frame = new JFrame("jterm");
    private final SessionStore sessionStore = new SessionStore();
    private final Keymap keymap = Keymap.loadOrDefaults();
    private final CredentialResolver credentialResolver;
    private final ConnectionService connectionService;
    private final TabPane tabPane;

    /** While true, the global terminal-shortcut dispatcher stands down so the editor can capture keys. */
    private boolean shortcutCaptureActive = false;
    private SessionSidebar sidebar;
    private SidebarSplit split;
    /** The View menu's sidebar checkbox, kept so a shortcut/drag toggle can re-check it. */
    private JCheckBoxMenuItem sidebarMenuItem;
    /** The window's most recent restored-down (non-maximized) bounds, tracked so a maximized exit
     *  still persists the monitor + size to reopen at when un-maximized. */
    private Rectangle lastNormalBounds;

    public MainWindow() {
        // Register before building the tab pane: the WindowManager is the shared registry every
        // window (and the global shortcut dispatcher) consults.
        WindowManager.get().registerMain(this);
        // Wire the credential/connection subsystem: the vault + prompt adapters here are the only
        // Swing-aware pieces; CredentialResolver and ConnectionService themselves are headless.
        CredentialResolver.VaultAccess vaultAccess = new CredentialResolver.VaultAccess() {
            @Override
            public boolean ensureUnlocked() {
                return VaultManager.get().ensureUnlocked(frame);
            }

            @Override
            public CredentialVault vault() {
                return VaultManager.get().vault();
            }
        };
        CredentialResolver.Prompts prompts = new CredentialResolver.Prompts() {
            @Override
            public CredentialResolver.SessionPassword promptSessionPassword(String sessionName,
                    String hostLabel, String error, boolean allowRemember) {
                MasterPasswordDialog.SessionPasswordResult result =
                        MasterPasswordDialog.promptSessionPassword(frame, sessionName, hostLabel,
                                error, allowRemember);
                return result == null ? null
                        : new CredentialResolver.SessionPassword(result.password(), result.remember());
            }

            @Override
            public CredentialResolver.KeyPassphrase promptKeyPassphrase(String keyPath, String error,
                    boolean allowRemember) {
                MasterPasswordDialog.KeyPassphraseResult result =
                        MasterPasswordDialog.promptKeyPassphrase(frame, keyPath, error, allowRemember);
                return result == null ? null
                        : new CredentialResolver.KeyPassphrase(result.passphrase(), result.remember());
            }

            @Override
            public String[] promptChallenge(String hostLabel, String instruction, String[] prompts,
                    boolean[] echo) {
                return MasterPasswordDialog.promptChallenge(frame, hostLabel, instruction, prompts, echo);
            }
        };
        this.credentialResolver = new CredentialResolver(sessionStore, vaultAccess, prompts);
        this.connectionService = new ConnectionService(sessionStore, credentialResolver,
                (msg, cause) -> ErrorDialog.show(frame, "jterm", msg, cause));
        this.tabPane = new TabPane(this, this, WindowManager.get());
    }

    public void show() {
        sidebar = new SessionSidebar(sessionStore, this::openSshSession,
                this::openLocalInCurrent, this::openWslSession, this::openSftpForConfig,
                this::openFolderSessions);

        split = new SidebarSplit(sidebar, tabPane, AppSettings.get().getSidebarWidth(),
                AppSettings.get().isSidebarVisible());
        // Keep the View menu's checkmark honest when the sidebar is closed by a shortcut or by
        // dragging the divider to the edge, not by the menu item itself.
        split.onVisibilityChanged(visible -> {
            if (sidebarMenuItem != null) {
                sidebarMenuItem.setSelected(visible);
            }
        });

        frame.setJMenuBar(buildMenuBar());
        frame.setLayout(new BorderLayout());
        frame.add(split, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // Tear down any running tunnels cleanly before the process exits.
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveWindowState();
                shutdownSessions();
                TunnelManager.get().stopAll();
            }
        });
        restoreWindowBounds();
        // Track the restored-down bounds so a maximized exit still records which monitor (and what
        // size) to reopen at; updated only while not maximized.
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                rememberNormalBounds();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                rememberNormalBounds();
            }
        });
        // Restore the maximized state from the previous session (the bounds above become the
        // restored-down geometry once the user un-maximizes).
        if (AppSettings.get().isWindowMaximized()) {
            frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);
        }
        applyAppIcon();

        installShortcutDispatcher();

        // Refresh active-pane accent borders and re-decorate tabs (so the themed local-terminal
        // icon swaps) across every window's tabs when the theme switches.
        ThemeManager.get().addListener(theme -> {
            for (TerminalWindow window : WindowManager.get().windows()) {
                window.tabPane().refreshThemeAllTabs(theme);
            }
        });

        // Open the initial local terminal unless the user opted out (then the window starts with
        // only the "+" placeholder; the selection guard recreates a tab once one is closed to zero).
        if (AppSettings.get().isOpenTerminalOnStartup()) {
            tabPane.addTab();
        }

        frame.setVisible(true);

        // Claim the initial keyboard focus for the terminal. The focus request inside addTab() above
        // ran while the frame was still invisible and so had no effect, leaving Swing to hand focus
        // to the first component in the sidebar's traversal order — which is now the Quick Connect
        // text field, where startup keystrokes would silently land.
        tabPane.focusCurrentPane();

        // Bring up any tunnels the user marked auto-start (resolves credentials as needed).
        startAutoStartTunnels();
    }

    /**
     * Restores the window to its previous bounds (size + monitor) when those still fall on a
     * connected screen; otherwise centers a default-sized window on the primary screen. The saved
     * location can become off-screen if a monitor was disconnected or rearranged since last run.
     */
    private void restoreWindowBounds() {
        AppSettings s = AppSettings.get();
        Rectangle bounds = new Rectangle(s.getWindowX(), s.getWindowY(),
                s.getWindowWidth(), s.getWindowHeight());
        if (s.hasWindowLocation() && isOnScreen(bounds)) {
            frame.setBounds(bounds);
        } else {
            // No usable saved geometry. With no saved location at all (a fresh install) the size is
            // the built-in default, so scale it — a first launch at a large UI scale shouldn't be
            // cramped. A saved window that has merely gone off-screen keeps its own size, which is
            // already in scaled pixels.
            int width = s.getWindowWidth();
            int height = s.getWindowHeight();
            if (!s.hasWindowLocation()) {
                width = UIScale.scale(width);
                height = UIScale.scale(height);
            }
            frame.setSize(width, height);
            frame.setLocationRelativeTo(null);
        }
        lastNormalBounds = frame.getBounds();
    }

    /** True if a meaningful portion of {@code bounds} lands on some connected screen device. */
    private static boolean isOnScreen(Rectangle bounds) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getScreenDevices()) {
            Rectangle visible = device.getDefaultConfiguration().getBounds().intersection(bounds);
            if (visible.width >= 100 && visible.height >= 100) {
                return true;
            }
        }
        return false;
    }

    /** Captures the current bounds as the restored-down geometry, unless the window is maximized. */
    private void rememberNormalBounds() {
        if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != JFrame.MAXIMIZED_BOTH) {
            lastNormalBounds = frame.getBounds();
        }
    }

    /**
     * Persists the window's maximized state, restored-down bounds (so it reopens on the same
     * monitor at the same size), and the sidebar's open/closed state plus its width. Called on
     * close from both the window's X and the Quit menu item. The divider location is the sidebar's pixel width
     * regardless of window size (a left-anchored horizontal split), so it's captured the same way
     * whether or not the window is maximized; the bounds come from the tracked restored-down
     * geometry so a maximized exit doesn't persist the maximized size.
     */
    private void saveWindowState() {
        AppSettings settings = AppSettings.get();
        boolean maximized = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        settings.setWindowMaximized(maximized);
        if (split != null) {
            // expandedWidth() rather than the divider location, so quitting with the sidebar
            // closed still persists the width to reopen at.
            settings.setSidebarWidth(split.expandedWidth());
            settings.setSidebarVisible(split.isSidebarVisible());
        }
        Rectangle b = (lastNormalBounds != null) ? lastNormalBounds : frame.getBounds();
        settings.setWindowBounds(b.x, b.y, b.width, b.height);
        settings.save();
    }

    /**
     * On quit, close every open session across all windows so SSH channels get a clean teardown and
     * keep-alive futures are cancelled (otherwise only tunnels were stopped and sessions leaked).
     * Runs on the EDT: {@code session.close()} may do IO, but the SSH close is async/non-blocking
     * ({@code channel.close(false)}), matching the existing per-pane close behavior. Detached windows
     * dispose their own grids on close, but a Quit from the main window must reach all of them.
     */
    private void shutdownSessions() {
        for (TerminalWindow window : WindowManager.get().windows()) {
            LOG.debug("closing sessions for window {}", window);
            window.tabPane().disposeAllGrids();
        }
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

    // ---- TerminalWindow / TerminalServices ----

    @Override
    public JFrame frame() {
        return frame;
    }

    @Override
    public TabPane tabPane() {
        return tabPane;
    }

    @Override
    public boolean isMain() {
        return true;
    }

    @Override
    public Keymap keymap() {
        return keymap;
    }

    @Override
    public String effectiveTabColorHex(SshSessionConfig cfg) {
        return sessionStore.effectiveTabColorHex(cfg);
    }

    @Override
    public Icon iconFor(String iconId) {
        String id = (iconId != null && !iconId.isBlank()) ? iconId : "builtin/server";
        return IconLibrary.get().icon(id, 16);
    }

    // ---- session opening ----

    /** OPEN_LOCAL shortcut: open a local shell in the focused window. */
    private void openLocalInFocused() {
        TabPane active = WindowManager.get().focusedTabPane();
        if (active != null) {
            openLocalPreferringNewTab(active);
        }
    }

    /**
     * Open a local shell in a new tab — unless the focused cell is empty (a hole left by a closed
     * pane, or a tab whose async SSH connect never landed), in which case fill that hole rather
     * than stranding it. Never replaces a live session; that is what {@link OpenMode#ACTIVE} is for.
     */
    private static void openLocalPreferringNewTab(TabPane host) {
        PaneGrid grid = host.currentGrid();
        if (grid != null && grid.activeContent() == null) {
            grid.openLocalInActive();
        } else {
            // No open tab (e.g. the startup terminal was suppressed), or the focused cell is busy.
            host.addTab();
        }
    }

    private void openLocalInCurrent(OpenMode mode) {
        PaneGrid grid = tabPane.currentGrid();
        if (grid == null) {
            tabPane.addTab();
            return;
        }
        switch (mode) {
            case SPLIT_COLUMN -> grid.splitColumn();
            case SPLIT_ROW -> grid.splitRow();
            case ACTIVE -> grid.openLocalInActive();
            default -> openLocalPreferringNewTab(tabPane);
        }
    }

    /**
     * QUICK_CONNECT shortcut: put the caret in the sidebar's Quick Connect field. The sidebar lives
     * only on the main window, so a shortcut pressed in a detached window raises this one first,
     * and a closed sidebar is reopened — focusing a hidden field would silently swallow typing.
     */
    private void focusQuickConnect() {
        if (sidebar == null) {
            return;
        }
        if (split != null) {
            split.setSidebarVisible(true);
        }
        frame.toFront();
        sidebar.focusQuickConnect();
    }

    /** TOGGLE_SIDEBAR shortcut / View menu: open or close the sessions sidebar. */
    private void toggleSidebar() {
        if (split != null) {
            split.toggleSidebar();
        }
    }

    /** True when the sidebar is on screen, so its selection-driven actions have something to act on. */
    private boolean sidebarActive() {
        return sidebar != null && split != null && split.isSidebarVisible();
    }

    private void openSshSession(SshSessionConfig cfg, OpenMode mode) {
        if (mode == OpenMode.NEW_TAB) {
            tabPane.addSshTab(cfg);
            return;
        }
        PaneGrid grid = tabPane.currentGrid();
        if (grid == null) {
            return;
        }
        connectAsync(cfg, session -> {
            SessionFactory factory = SessionFactory.ssh(cfg, connectionService);
            switch (mode) {
                case ACTIVE -> grid.placeSessionInActive(session, factory);
                case SPLIT_COLUMN -> grid.splitColumnAndOpen(session, factory);
                case SPLIT_ROW -> grid.splitRowAndOpen(session, factory);
                default -> { }
            }
        });
    }

    /**
     * Open every SSH session under {@code folder} (recursively). In {@code SEPARATE_TABS} each
     * session gets its own tab; in {@code SPLIT_TABS} they are packed into split-pane grids of up
     * to {@link PaneGrid#MAX}² panes, spilling into additional tabs. Connecting more than 9 hosts
     * at once is confirmed first, since each may prompt for credentials.
     */
    private void openFolderSessions(FolderNode folder, FolderOpenMode mode) {
        List<SshSessionConfig> sessions = SessionStore.collectSshSessions(folder);
        if (sessions.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "This folder has no SSH connections.",
                    "Open Folder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (sessions.size() > 9) {
            int ok = JOptionPane.showConfirmDialog(frame,
                    "Open " + sessions.size() + " connections from \"" + folder.getName() + "\"?",
                    "Open Folder", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return;
            }
        }
        if (mode == FolderOpenMode.SEPARATE_TABS) {
            for (SshSessionConfig cfg : sessions) {
                tabPane.addSshTab(cfg);
            }
            return;
        }
        int perTab = PaneGrid.MAX * PaneGrid.MAX;
        int tabCount = (sessions.size() + perTab - 1) / perTab;
        List<PaneGrid> grids = new ArrayList<>();
        for (int t = 0; t < tabCount; t++) {
            String title = tabCount > 1 ? folder.getName() + " (" + (t + 1) + ")" : folder.getName();
            grids.add(tabPane.addSplitTab(title));
        }
        for (int i = 0; i < sessions.size(); i++) {
            SshSessionConfig cfg = sessions.get(i);
            PaneGrid grid = grids.get(i / perTab);
            connectAsync(cfg, session ->
                    grid.placeSessionInBestSplit(session, SessionFactory.ssh(cfg, connectionService)));
        }
    }

    // ---- SFTP browser ----

    /**
     * Ctrl+F / SSH menu: open an SFTP browser on the active pane's live SSH connection (reusing its
     * authenticated session — no re-auth). No-op unless the active pane is an SSH terminal.
     */
    private void openSftpForActivePane() {
        TabPane host = WindowManager.get().focusedTabPane();
        PaneGrid grid = host != null ? host.currentGrid() : null;
        if (grid == null || !(grid.activePane() instanceof TerminalPane pane)
                || !(pane.session() instanceof SshSession ssh)) {
            return;
        }
        SftpLauncher.openOnLiveSession(ssh, this::placeSftp,
                cause -> ErrorDialog.show(frame, "SFTP", "Could not open SFTP:", cause));
    }

    /**
     * Sidebar context menu: open an SFTP browser for a saved SSH session over a fresh, dedicated
     * connection (the session may not be open), reusing the normal password/vault resolution.
     */
    private void openSftpForConfig(SshSessionConfig cfg) {
        String password = credentialResolver.resolvePassword(cfg);
        String effectiveUser = sessionStore.effectiveUser(cfg);
        String effectiveKeyPath = sessionStore.effectiveKeyPath(cfg);
        String label = (!effectiveUser.isBlank() ? effectiveUser + "@" : "") + cfg.getHost();
        SftpLauncher.openFresh(cfg.getHost(), cfg.getPort(), effectiveUser, password,
                effectiveKeyPath, credentialResolver.keyPassphraseProvider(cfg, effectiveKeyPath),
                connectionService.interactiveAuth(cfg), cfg.getId(), label, cfg.getIconId(),
                this::placeSftp,
                cause -> ErrorDialog.show(frame, "SFTP", "SFTP connection failed:", cause));
    }

    /**
     * Places a freshly built SFTP browser: a split of the current grid (new column, else row, else
     * an empty cell), or a new tab when the grid is full.
     */
    private void placeSftp(GridContent content) {
        TabPane host = WindowManager.get().focusedTabPane();
        if (host == null) {
            host = tabPane;
        }
        PaneGrid grid = host.currentGrid();
        if (grid != null && grid.openContentInBestSplit(content)) {
            host.decorateTab(grid);
            return;
        }
        PaneGrid fresh = host.newGrid();
        host.insertGrid(fresh);
        fresh.initEmpty();
        fresh.placeContentInActive(content);
        host.decorateTab(fresh);
    }

    /** Opens a detected WSL2 distribution (synchronously — it's a local pty, no network connect). */
    private void openWslSession(String distro, OpenMode mode) {
        if (mode == OpenMode.NEW_TAB) {
            addWslTab(distro);
            return;
        }
        PaneGrid grid = tabPane.currentGrid();
        if (grid == null) {
            return;
        }
        SessionFactory factory = SessionFactory.wsl(distro, wslErrorReporter());
        factory.create(session -> {
            switch (mode) {
                case ACTIVE -> grid.placeSessionInActive(session, factory);
                case SPLIT_COLUMN -> grid.splitColumnAndOpen(session, factory);
                case SPLIT_ROW -> grid.splitRowAndOpen(session, factory);
                default -> { }
            }
        });
    }

    /** Opens a WSL2 distribution in a fresh tab titled with the distro name. */
    private void addWslTab(String distro) {
        SessionFactory factory = SessionFactory.wsl(distro, wslErrorReporter());
        factory.create(session -> {
            PaneGrid grid = tabPane.newGrid();
            tabPane.insertGrid(grid);
            // The tab keeps its generic "Terminal N" base title (for any plain shell split into it);
            // decorateTab names the WSL pane itself from the session (the distro).
            grid.initEmpty();
            grid.placeSessionInActive(session, factory);
        });
    }

    /** Reports a WSL-launch failure through the richer {@link ErrorDialog}, parented on the frame. */
    private BiConsumer<String, Throwable> wslErrorReporter() {
        return (header, error) -> ErrorDialog.show(frame, "jterm", header, error);
    }

    /** Connect an SSH session off the EDT, then hand the live session to {@code onConnected} on the EDT. */
    @Override
    public void connectAsync(SshSessionConfig cfg, Consumer<SshSession> onConnected, Runnable onError) {
        connectionService.connectAsync(cfg, onConnected, onError);
    }

    // ---- tunneling ----

    /** Opens the SSH tunnel manager, wiring start/stop back through this window's SSH connect path. */
    private void openTunnelManager() {
        TunnelManagerDialog.show(frame, sessionStore,
                this::startTunnel,
                id -> TunnelManager.get().stop(id));
    }

    /**
     * Starts {@code tunnel} by opening a dedicated SSH connection (no shell) to its referenced
     * session and attaching the forward. Credentials are resolved on the EDT (may prompt/unlock the
     * vault), then the blocking connect runs off it; {@code onDone} (may be {@code null}) runs on
     * the EDT once the attempt finishes, succeed or fail.
     */
    private void startTunnel(TunnelConfig tunnel, Runnable onDone) {
        SshSessionConfig cfg = findSshSession(tunnel.getSshSessionId());
        if (cfg == null) {
            JOptionPane.showMessageDialog(frame,
                    "The SSH session for tunnel \"" + tunnel.getName() + "\" no longer exists.",
                    "Tunneling", JOptionPane.WARNING_MESSAGE);
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        String password = credentialResolver.resolvePassword(cfg);
        List<SshConnect.HostHop> jumpHosts = credentialResolver.resolveJumpHosts(cfg);
        String effectiveUser = sessionStore.effectiveUser(cfg);
        String effectiveKeyPath = sessionStore.effectiveKeyPath(cfg);
        SshConnect.PassphraseProvider passphrases = credentialResolver.keyPassphraseProvider(cfg, effectiveKeyPath);
        SshConnect.InteractiveAuth interactive = connectionService.interactiveAuth(cfg);
        new SwingWorker<SshConnect.Connected, Void>() {
            @Override
            protected SshConnect.Connected doInBackground() throws Exception {
                return SshConnect.open(jumpHosts,
                        new SshConnect.HostHop(cfg.getHost(), cfg.getPort(), effectiveUser,
                                password, effectiveKeyPath, cfg.getId()),
                        passphrases, interactive);
            }

            @Override
            protected void done() {
                try {
                    TunnelManager.get().start(tunnel, get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    ErrorDialog.show(frame, "Tunneling",
                            "Failed to start tunnel \"" + tunnel.getName() + "\":", cause);
                }
                if (onDone != null) {
                    onDone.run();
                }
            }
        }.execute();
    }

    /** Starts every tunnel flagged auto-start (best-effort, on launch). */
    private void startAutoStartTunnels() {
        for (TunnelConfig t : TunnelStore.get().tunnels()) {
            if (t.isAutoStart()) {
                startTunnel(t, null);
            }
        }
    }

    /** Finds a saved SSH session by id anywhere in the sidebar tree, or {@code null}. */
    private SshSessionConfig findSshSession(String id) {
        if (id == null) {
            return null;
        }
        return findSshSession(sessionStore.root(), id);
    }

    private static SshSessionConfig findSshSession(FolderNode folder, String id) {
        for (SessionNode node : folder.getChildren()) {
            if (node instanceof SshSessionConfig ssh && id.equals(ssh.getId())) {
                return ssh;
            }
            if (node instanceof FolderNode child) {
                SshSessionConfig found = findSshSession(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ---- shortcuts ----

    private void installShortcutDispatcher() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || shortcutCaptureActive) {
                return false; // let the shortcut editor capture keys while it's recording
            }
            // A stopped pane claims bare Return / R / S so those actions work wherever focus sits in
            // the pane (e.g. on the dead terminal), not only on the small "Session stopped" strip.
            // Restricted to no Ctrl/Alt/Meta so real shortcuts (Ctrl+R, …) are untouched; only fires
            // while the active pane is genuinely stopped, so live typing is unaffected. Text fields
            // are exempt: this dispatcher sees every window, and swallowing bare letters would eat
            // them out of the Quick Connect field (or any dialog's inputs) whenever a pane is dead.
            if ((e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK | KeyEvent.META_DOWN_MASK)) == 0
                    && !(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                            instanceof JTextComponent)) {
                int kc = e.getKeyCode();
                if (kc == KeyEvent.VK_R || kc == KeyEvent.VK_S || kc == KeyEvent.VK_ENTER) {
                    TabPane stoppedHost = WindowManager.get().focusedTabPane();
                    PaneGrid stoppedGrid = stoppedHost != null ? stoppedHost.currentGrid() : null;
                    if (stoppedGrid != null && stoppedGrid.activePane() instanceof TerminalPane p
                            && p.isStopped() && p.handleStoppedKey(kc)) {
                        return true; // consume so JediTerm / menu accelerators don't also fire
                    }
                }
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
                // Built-in main-row aliases for the numpad font shortcuts (Ctrl+= / Ctrl+- / Ctrl+0).
                // The numpad strokes above are the configurable defaults; these convenience aliases
                // are recognised in addition and aren't shown in the keymap editor.
                TermAction alias = mainRowFontAlias(e);
                if (alias != null) {
                    handle(alias);
                    return true;
                }
                return false;
            }
            handle(action);
            return true; // consume so JediTerm / menu accelerators don't also fire
        });
    }

    /**
     * Maps the main-row font shortcuts (Ctrl+= / Ctrl++ to increase, Ctrl+- to decrease, Ctrl+0 to
     * reset) to their font actions, or {@code null} if {@code e} isn't one. These are fixed aliases
     * for the configurable numpad bindings; we match on key code so a US-layout Ctrl+= works without
     * Shift. Only fires with Ctrl held and no Alt/Meta, so it won't shadow other shortcuts.
     */
    private static TermAction mainRowFontAlias(KeyEvent e) {
        if (!e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return null;
        }
        return switch (e.getKeyCode()) {
            case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS -> TermAction.FONT_INCREASE;
            case KeyEvent.VK_MINUS -> TermAction.FONT_DECREASE;
            case KeyEvent.VK_0 -> TermAction.FONT_RESET;
            default -> null;
        };
    }

    /**
     * Routes a bound action. Tab/pane/grid actions target whichever window currently has focus (the
     * main window or a detached one); the sidebar-only actions act on the main window's sidebar.
     */
    private void handle(TermAction action) {
        TabPane active = WindowManager.get().focusedTabPane();
        PaneGrid grid = active != null ? active.currentGrid() : null;
        switch (action) {
            case NEW_TAB -> {
                if (active != null) {
                    active.addTab();
                }
            }
            case CLOSE_TAB -> {
                if (active != null) {
                    active.closeCurrentTab();
                }
            }
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
            case OPEN_LOCAL -> openLocalInFocused();
            case QUICK_CONNECT -> focusQuickConnect();
            case OPEN_SFTP -> openSftpForActivePane();
            case OPEN_TUNNELS -> openTunnelManager();
            case TOGGLE_THEME -> ThemeManager.get().toggle();
            case TOGGLE_SIDEBAR -> toggleSidebar();
            case TOGGLE_BROADCAST -> {
                if (grid != null) {
                    grid.toggleBroadcast();
                }
            }
            // Selection-driven sidebar actions: no-ops while the sidebar is closed, so they can't
            // reorder or duplicate against a selection the user can't see.
            case MOVE_SESSION_UP -> {
                if (sidebarActive()) {
                    sidebar.moveSelectedUp();
                }
            }
            case MOVE_SESSION_DOWN -> {
                if (sidebarActive()) {
                    sidebar.moveSelectedDown();
                }
            }
            case DUPLICATE_SESSION -> {
                if (sidebarActive()) {
                    sidebar.duplicateSelected();
                }
            }
            case DUPLICATE_PANE_SPLIT -> {
                if (grid != null) {
                    grid.duplicateActivePane(false);
                }
            }
            case DUPLICATE_PANE_TAB -> {
                if (grid != null) {
                    grid.duplicateActivePane(true);
                }
            }
            case RENAME_PANE -> {
                if (grid != null && grid.activeContent() instanceof TerminalPane pane) {
                    pane.promptRename();
                }
            }
            case MOVE_TAB_LEFT -> {
                if (active != null) {
                    active.moveSelectedTab(-1);
                }
            }
            case MOVE_TAB_RIGHT -> {
                if (active != null) {
                    active.moveSelectedTab(1);
                }
            }
            case DUPLICATE_TAB -> {
                if (active != null) {
                    active.duplicateSelectedTab();
                }
            }
            case DETACH_TAB -> {
                if (active != null) {
                    active.detachSelectedTab();
                }
            }
            case ATTACH_TAB -> {
                if (active != null) {
                    active.attachSelectedToMain();
                }
            }
            case FONT_INCREASE -> {
                if (grid != null && grid.activePane() instanceof TerminalPane p) {
                    p.increaseFontSize();
                }
            }
            case FONT_DECREASE -> {
                if (grid != null && grid.activePane() instanceof TerminalPane p) {
                    p.decreaseFontSize();
                }
            }
            case FONT_RESET -> {
                if (grid != null && grid.activePane() instanceof TerminalPane p) {
                    p.resetFontSize();
                }
            }
        }
    }

    // ---- menu ----

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("New Tab", TermAction.NEW_TAB));
        file.add(menuItem("Close Tab", TermAction.CLOSE_TAB));
        file.add(menuItem("Duplicate Tab", TermAction.DUPLICATE_TAB));
        file.add(menuItem("Detach Tab to New Window", TermAction.DETACH_TAB));
        file.add(menuItem("Move Tab Left", TermAction.MOVE_TAB_LEFT));
        file.add(menuItem("Move Tab Right", TermAction.MOVE_TAB_RIGHT));
        file.addSeparator();
        JMenuItem exportSessions = new JMenuItem("Export Sessions…");
        exportSessions.addActionListener(e -> sidebar.exportRootSessions());
        file.add(exportSessions);
        JMenuItem importSessions = new JMenuItem("Import Sessions…");
        importSessions.addActionListener(e -> sidebar.importRootSessions());
        file.add(importSessions);
        file.addSeparator();
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> {
            saveWindowState();
            shutdownSessions();
            TunnelManager.get().stopAll();
            frame.dispose();
        });
        file.add(quit);

        JMenu terminal = new JMenu("Terminal");
        terminal.add(menuItem("Open Local Shell", TermAction.OPEN_LOCAL));
        terminal.add(menuItem("Split Column", TermAction.SPLIT_COLUMN));
        terminal.add(menuItem("Split Row", TermAction.SPLIT_ROW));
        terminal.add(menuItem("Close Pane", TermAction.CLOSE_PANE));
        terminal.addSeparator();
        terminal.add(menuItem("Duplicate Pane to Split", TermAction.DUPLICATE_PANE_SPLIT));
        terminal.add(menuItem("Duplicate Pane to Tab", TermAction.DUPLICATE_PANE_TAB));
        terminal.addSeparator();
        terminal.add(menuItem("Rename Connection…", TermAction.RENAME_PANE));

        JMenu ssh = new JMenu("SSH");
        ssh.add(menuItem("Open SFTP Browser", TermAction.OPEN_SFTP));
        ssh.add(menuItem("Tunneling…", TermAction.OPEN_TUNNELS));
        ssh.addSeparator();
        JMenuItem agentKeys = new JMenuItem("Show Agent Keys…");
        agentKeys.addActionListener(e -> showAgentKeys());
        ssh.add(agentKeys);

        JMenu settings = new JMenu("Settings");
        settings.add(menuItem("Toggle Light/Dark", TermAction.TOGGLE_THEME));
        settings.addSeparator();
        JMenuItem shortcuts = new JMenuItem("Keyboard Shortcuts…");
        shortcuts.addActionListener(e -> openShortcutsEditor());
        settings.add(shortcuts);
        JMenuItem prefsDialog = new JMenuItem("Preferences…");
        prefsDialog.addActionListener(e -> PreferencesDialog.show(frame));
        settings.add(prefsDialog);

        JMenu view = new JMenu("View");
        // Seeded from the live split rather than a remembered field: buildMenuBar() is re-run
        // wholesale when the keymap or the macro list changes.
        sidebarMenuItem = checkMenuItem("Sessions Sidebar", TermAction.TOGGLE_SIDEBAR,
                split == null || split.isSidebarVisible());
        view.add(sidebarMenuItem);

        JMenu help = new JMenu("Help");
        JMenuItem manual = new JMenuItem("User Manual…");
        manual.addActionListener(e -> openInBrowser(MANUAL_URL));
        help.add(manual);
        help.addSeparator();
        JMenuItem about = new JMenuItem("About " + AppInfo.name() + "…");
        about.addActionListener(e -> showAboutDialog());
        help.add(about);
        JMenuItem licenses = new JMenuItem("Third-Party Licenses…");
        licenses.addActionListener(e -> showThirdPartyLicenses());
        help.add(licenses);

        bar.add(file);
        bar.add(terminal);
        bar.add(ssh);
        bar.add(buildMacrosMenu());
        bar.add(settings);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    /**
     * Modal "About" dialog: application name, build version, author, and the GNU GPL notice
     * the FSF recommends presenting in a GUI "about box".
     */
    private void showAboutDialog() {
        String message = "<html><b>" + AppInfo.name() + "</b><br>"
                + "Version " + AppInfo.version() + "<br><br>"
                + "Copyright &copy; 2026 " + AppInfo.author() + "<br><br>"
                + "This program is free software: you can redistribute it and/or modify it<br>"
                + "under the terms of the GNU General Public License as published by the<br>"
                + "Free Software Foundation, either version 3 of the License, or (at your<br>"
                + "option) any later version.<br><br>"
                + "This program comes with ABSOLUTELY NO WARRANTY. See the GNU General<br>"
                + "Public License for more details &lt;https://www.gnu.org/licenses/&gt;.<br><br>"
                + "Report issues at <a href=\"" + ISSUES_URL + "\">" + ISSUES_URL + "</a><br><br>"
                + "See <b>Help &rarr; Third-Party Licenses</b> for bundled components.</html>";
        JOptionPane.showMessageDialog(
                frame, hyperlinkPane(message), "About " + AppInfo.name(),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Project issue tracker, linked from the About dialog. */
    private static final String ISSUES_URL = "https://github.com/drecaise/jterm/issues";

    /** Online user manual, linked from the Help menu. */
    private static final String MANUAL_URL = "https://drecaise.github.io/jterm/";

    /**
     * Builds an HTML-rendering component whose {@code <a href>} links open in the system browser.
     * A {@link JEditorPane} (unlike the {@link JLabel} {@link JOptionPane} uses for HTML strings)
     * supports hyperlink activation, while still inheriting the dialog's look via the label font.
     */
    private JEditorPane hyperlinkPane(String html) {
        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(null);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            pane.setFont(font);
        }
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openInBrowser(e.getURL() != null ? e.getURL().toString() : e.getDescription());
            }
        });
        return pane;
    }

    /** Opens {@code url} in the host's default browser (see {@link BrowserLauncher}). */
    private void openInBrowser(String url) {
        BrowserLauncher.open(frame, url);
    }

    /**
     * Modal, scrollable dialog listing the bundled open-source libraries and their licenses.
     * The text is read from {@code /third-party-licenses.txt} on the classpath.
     */
    private void showThirdPartyLicenses() {
        String text;
        try (InputStream in = MainWindow.class.getResourceAsStream("/third-party-licenses.txt")) {
            text = in != null
                    ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : "Third-party license information is unavailable.";
        } catch (Exception ex) {
            text = "Third-party license information is unavailable.";
        }

        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIScale.scale(12)));
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(UIScale.scale(new Dimension(640, 480)));

        JDialog dialog = new JDialog(frame, "Third-Party Licenses", true);
        dialog.getContentPane().add(scroll, BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
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

    /** Runs a macro on the focused window's active pane (broadcasting connector, so broadcast applies). */
    private void runMacroOnActivePane(Macro macro) {
        TabPane host = WindowManager.get().focusedTabPane();
        PaneGrid grid = host != null ? host.currentGrid() : null;
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
        tabPane.refreshNewTabTooltip();
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
                } catch (Exception e) {
                    LOG.debug("failed to show agent keys dialog", e);
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

    /**
     * Checkbox variant of {@link #menuItem} for actions that toggle a visible state. The check mark
     * is driven by the model — {@code selected} seeds it and the owning state's change callback
     * re-syncs it, so a toggle from the shortcut or the divider keeps the menu honest. Clicking the
     * item flips its own state first, but the callback then sets it from the model, so the two
     * always agree.
     */
    private JCheckBoxMenuItem checkMenuItem(String label, TermAction action, boolean selected) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(label, selected);
        item.setAccelerator(keymap.strokeFor(action));
        item.addActionListener(e -> handle(action));
        return item;
    }
}
