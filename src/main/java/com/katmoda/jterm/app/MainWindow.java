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

import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.keymap.TermAction;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.macro.MacroRunner;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultKeys;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.session.JumpHostConfig;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.TunnelConfig;
import com.katmoda.jterm.session.TunnelStore;
import com.katmoda.jterm.terminal.SessionFactory;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.local.LocalSession;
import com.katmoda.jterm.terminal.TerminalProfile;
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
import com.katmoda.jterm.ui.sidebar.OpenMode;
import com.katmoda.jterm.ui.sidebar.SessionSidebar;
import com.katmoda.jterm.ui.tabs.TabPane;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.Icon;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Desktop;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
public final class MainWindow implements TerminalWindow, TerminalServices {

    private final JFrame frame = new JFrame("jterm");
    private final SessionStore sessionStore = new SessionStore();
    private final Keymap keymap = Keymap.loadOrDefaults();
    private final TabPane tabPane;

    /** While true, the global terminal-shortcut dispatcher stands down so the editor can capture keys. */
    private boolean shortcutCaptureActive = false;
    private SessionSidebar sidebar;
    private JSplitPane split;
    /** The window's most recent restored-down (non-maximized) bounds, tracked so a maximized exit
     *  still persists the monitor + size to reopen at when un-maximized. */
    private Rectangle lastNormalBounds;

    public MainWindow() {
        // Register before building the tab pane: the WindowManager is the shared registry every
        // window (and the global shortcut dispatcher) consults.
        WindowManager.get().registerMain(this);
        this.tabPane = new TabPane(this, this);
    }

    public void show() {
        sidebar = new SessionSidebar(sessionStore, this::openSshSession,
                this::openLocalInCurrent, this::openWslSession, this::openSftpForConfig);

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, tabPane);
        split.setDividerLocation(AppSettings.get().getSidebarWidth());
        split.setResizeWeight(0.0);

        frame.setJMenuBar(buildMenuBar());
        frame.setLayout(new BorderLayout());
        frame.add(split, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // Tear down any running tunnels cleanly before the process exits.
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveWindowState();
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
            frame.setSize(s.getWindowWidth(), s.getWindowHeight());
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
     * monitor at the same size), and the sidebar (split divider) width. Called on close from both
     * the window's X and the Quit menu item. The divider location is the sidebar's pixel width
     * regardless of window size (a left-anchored horizontal split), so it's captured the same way
     * whether or not the window is maximized; the bounds come from the tracked restored-down
     * geometry so a maximized exit doesn't persist the maximized size.
     */
    private void saveWindowState() {
        AppSettings settings = AppSettings.get();
        boolean maximized = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        settings.setWindowMaximized(maximized);
        if (split != null) {
            settings.setSidebarWidth(split.getDividerLocation());
        }
        Rectangle b = (lastNormalBounds != null) ? lastNormalBounds : frame.getBounds();
        settings.setWindowBounds(b.x, b.y, b.width, b.height);
        settings.save();
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

    /** OPEN_LOCAL shortcut: open a local shell in the focused window's active cell. */
    private void openLocalInFocused() {
        TabPane active = WindowManager.get().focusedTabPane();
        if (active == null) {
            return;
        }
        PaneGrid grid = active.currentGrid();
        if (grid == null) {
            active.addTab();
        } else {
            grid.openLocalInActive();
        }
    }

    private void openLocalInCurrent(OpenMode mode) {
        PaneGrid grid = tabPane.currentGrid();
        if (grid == null) {
            // No open tab (e.g. the startup terminal was suppressed): open one in a fresh tab.
            tabPane.addTab();
            return;
        }
        switch (mode) {
            case SPLIT_COLUMN -> grid.splitColumn();
            case SPLIT_ROW -> grid.splitRow();
            default -> grid.openLocalInActive();
        }
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
            SessionFactory factory = tabPane.sshFactory(cfg);
            switch (mode) {
                case ACTIVE -> grid.placeSessionInActive(session, factory);
                case SPLIT_COLUMN -> grid.splitColumnAndOpen(session, factory);
                case SPLIT_ROW -> grid.splitRowAndOpen(session, factory);
                default -> { }
            }
        });
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
        String password = resolvePassword(cfg);
        String effectiveUser = sessionStore.effectiveUser(cfg);
        String effectiveKeyPath = sessionStore.effectiveKeyPath(cfg);
        String label = (!effectiveUser.isBlank() ? effectiveUser + "@" : "") + cfg.getHost();
        SftpLauncher.openFresh(cfg.getHost(), cfg.getPort(), effectiveUser, password,
                effectiveKeyPath, keyPassphraseProvider(cfg, effectiveKeyPath), label,
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
        PaneGrid grid = tabPane.newGrid();
        tabPane.insertGrid(grid);
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
        return new SessionFactory() {
            @Override
            public void create(Consumer<TerminalSession> onReady) {
                create(onReady, () -> { });
            }

            @Override
            public void create(Consumer<TerminalSession> onReady, Runnable onError) {
                LocalSession session = safeWslSession(distro);
                if (session != null) {
                    onReady.accept(session);
                } else {
                    onError.run();
                }
            }
        };
    }

    /** Connect an SSH session off the EDT, then hand the live session to {@code onConnected} on the EDT. */
    @Override
    public void connectAsync(SshSessionConfig cfg, Consumer<SshSession> onConnected, Runnable onError) {
        // Resolve any passwords on the EDT first — they may unlock the vault or prompt. The
        // target and every jump host are resolved up front so the background connect needs no UI.
        String password = resolvePassword(cfg);
        List<SshConnect.HostHop> jumpHosts = resolveJumpHosts(cfg);
        // Resolve the inherited username / tab color / key path (session → folder chain → global
        // default) on the EDT, since it walks the live session tree. The saved key passphrase (if
        // any) is read from the vault here too, so the background connect needs no UI on attempt 0.
        String effectiveUser = sessionStore.effectiveUser(cfg);
        String effectiveTabColorHex = sessionStore.effectiveTabColorHex(cfg);
        String effectiveKeyPath = sessionStore.effectiveKeyPath(cfg);
        SshConnect.PassphraseProvider passphrases = keyPassphraseProvider(cfg, effectiveKeyPath);
        new SwingWorker<SshSession, Void>() {
            @Override
            protected SshSession doInBackground() throws Exception {
                TerminalProfile profile = AppSettings.get().resolve(cfg.getTerminalType(),
                        cfg.getTerminalCharset(), cfg.getFontFamily(), cfg.getFontSize());
                return SshSession.connect(cfg.getHost(), cfg.getPort(), effectiveUser,
                        cfg.isAgentForwarding(), password, effectiveKeyPath, jumpHosts, passphrases,
                        cfg.getName(), cfg.getIconId(), profile, cfg.getHighlightListId(),
                        effectiveTabColorHex);
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
                    onError.run();
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
     * Resolves the password to try for the target session (EDT): {@code null} if password auth is
     * off. Otherwise the cascade is the session's own saved password (when {@code savePassword}),
     * then the inherited folder/global default password, then a one-time prompt. The inherited
     * defaults apply only when password auth is enabled (so a session never silently authenticates
     * with a shared password it didn't opt into).
     */
    private String resolvePassword(SshSessionConfig cfg) {
        if (!cfg.isPasswordAuth()) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        if (cfg.isSavePassword()) {
            keys.add(VaultKeys.sessionPassword(cfg.getId()));
        }
        keys.addAll(sessionStore.defaultPasswordVaultKeys(cfg));
        String secret = resolveVaultSecret(keys);
        if (secret != null) {
            return secret;
        }
        char[] entered = MasterPasswordDialog.promptSessionPassword(frame, cfg.getName());
        if (entered == null) {
            return null;
        }
        String password = new String(entered);
        java.util.Arrays.fill(entered, '\0');
        return password;
    }

    /**
     * Returns the first secret present (and decryptable) among {@code vaultKeys}, unlocking the
     * vault on demand. {@code null} if none is stored or the unlock is cancelled/fails.
     */
    private String resolveVaultSecret(List<String> vaultKeys) {
        VaultManager vaults = VaultManager.get();
        for (String key : vaultKeys) {
            if (vaults.vault().hasPassword(key)) {
                if (!vaults.ensureUnlocked(frame)) {
                    return null;
                }
                try {
                    return vaults.vault().getPassword(key);
                } catch (VaultException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Resolves the password to try for one host (EDT): {@code null} if password auth is off; a
     * saved password unlocked from the vault (via keyring or prompt); otherwise a one-time prompt.
     * A cancelled prompt yields {@code null}, so connection falls back to agent/key auth.
     */
    private String resolvePassword(String id, boolean passwordAuth, boolean savePassword,
                                   String promptName) {
        if (!passwordAuth) {
            return null;
        }
        VaultManager vaults = VaultManager.get();
        if (savePassword && vaults.vault().hasPassword(id)) {
            if (!vaults.ensureUnlocked(frame)) {
                return null;
            }
            try {
                return vaults.vault().getPassword(id);
            } catch (VaultException e) {
                return null;
            }
        }
        char[] entered = MasterPasswordDialog.promptSessionPassword(frame, promptName);
        if (entered == null) {
            return null;
        }
        String password = new String(entered);
        java.util.Arrays.fill(entered, '\0');
        return password;
    }

    /**
     * Builds the jump-host chain (EDT) for {@code cfg}, resolving each hop's password up front.
     * Hops with a blank host are skipped defensively (the dialog already drops them).
     */
    private List<SshConnect.HostHop> resolveJumpHosts(SshSessionConfig cfg) {
        List<SshConnect.HostHop> hops = new ArrayList<>();
        for (JumpHostConfig jh : cfg.getJumpHosts()) {
            if (jh.getHost() == null || jh.getHost().isBlank()) {
                continue;
            }
            String label = jh.getUser() + "@" + jh.getHost();
            String pw = resolvePassword(jh.getId(), jh.isPasswordAuth(), jh.isSavePassword(), label);
            hops.add(new SshConnect.HostHop(jh.getHost(), jh.getPort(), jh.getUser(), pw,
                    jh.getKeyPath()));
        }
        return hops;
    }

    /**
     * Builds the passphrase provider for a connect (the SSH connect runs off the EDT, so prompts
     * are marshalled onto it). For the session's effective key it tries the saved passphrase first
     * (attempt 0) and offers to remember a newly entered one; for any other key (jump-host keys,
     * auto-discovered {@code ~/.ssh} identities) it simply prompts. A wrong passphrase re-prompts
     * (with an error) until MINA gives up; cancelling skips the key so other auth still applies.
     *
     * @param cfg              the session being connected (its id keys a remembered passphrase)
     * @param effectiveKeyPath the resolved configured key path, or {@code null} if none
     */
    private SshConnect.PassphraseProvider keyPassphraseProvider(SshSessionConfig cfg,
                                                               String effectiveKeyPath) {
        String expectedKey = SshConnect.resolveKeyPath(effectiveKeyPath);
        String savedPassphrase = resolveSavedPassphrase(cfg, effectiveKeyPath);
        return new SshConnect.PassphraseProvider() {
            // Passphrases the user asked to remember, awaiting a successful decrypt to persist.
            private final java.util.Map<String, String> pendingRemember = new java.util.HashMap<>();

            @Override
            public String passphraseFor(String keyPath, int attempt) {
                boolean isSessionKey = expectedKey != null
                        && expectedKey.equals(SshConnect.resolveKeyPath(keyPath));
                if (attempt == 0 && isSessionKey && savedPassphrase != null) {
                    return savedPassphrase; // try the saved one silently first
                }
                String error = attempt > 0 ? "Incorrect passphrase — try again." : null;
                MasterPasswordDialog.KeyPassphraseResult result =
                        promptPassphraseOnEdt(keyPath, error, isSessionKey);
                if (result == null) {
                    return null;
                }
                String passphrase = new String(result.passphrase());
                java.util.Arrays.fill(result.passphrase(), '\0');
                if (result.remember() && isSessionKey) {
                    pendingRemember.put(SshConnect.resolveKeyPath(keyPath), passphrase);
                }
                return passphrase;
            }

            @Override
            public void onAccepted(String keyPath) {
                String passphrase = pendingRemember.remove(SshConnect.resolveKeyPath(keyPath));
                if (passphrase != null) {
                    saveSessionPassphrase(cfg.getId(), passphrase);
                }
            }
        };
    }

    /** Reads the saved passphrase for {@code cfg}'s configured key (cascade), or {@code null}. */
    private String resolveSavedPassphrase(SshSessionConfig cfg, String effectiveKeyPath) {
        if (effectiveKeyPath == null || effectiveKeyPath.isBlank()) {
            return null; // no configured key → nothing to attach a saved passphrase to
        }
        return resolveVaultSecret(sessionStore.keyPassphraseVaultKeys(cfg));
    }

    /** Persists a remembered passphrase at the session level (EDT-marshalled; best-effort). */
    private void saveSessionPassphrase(String sessionId, String passphrase) {
        Runnable save = () -> {
            VaultManager vaults = VaultManager.get();
            if (!vaults.ensureUnlocked(frame)) {
                return;
            }
            try {
                vaults.vault().setPassword(VaultKeys.sessionKeyPassphrase(sessionId),
                        passphrase.toCharArray());
            } catch (VaultException ignored) {
                // Remembering a passphrase is a convenience; a failed save shouldn't break connect.
            }
        };
        runOnEdt(save);
    }

    /** Shows the key-passphrase prompt on the EDT and returns its result (or {@code null}). */
    private MasterPasswordDialog.KeyPassphraseResult promptPassphraseOnEdt(String keyPath,
            String error, boolean allowRemember) {
        MasterPasswordDialog.KeyPassphraseResult[] holder = new MasterPasswordDialog.KeyPassphraseResult[1];
        runOnEdt(() -> holder[0] =
                MasterPasswordDialog.promptKeyPassphrase(frame, keyPath, error, allowRemember));
        return holder[0];
    }

    /** Runs {@code task} synchronously on the EDT (directly if already on it). */
    private void runOnEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (Exception ignored) {
                // Cancelled/interrupted: leave any holder untouched (treated as "no input").
            }
        }
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
        String password = resolvePassword(cfg);
        List<SshConnect.HostHop> jumpHosts = resolveJumpHosts(cfg);
        String effectiveUser = sessionStore.effectiveUser(cfg);
        String effectiveKeyPath = sessionStore.effectiveKeyPath(cfg);
        SshConnect.PassphraseProvider passphrases = keyPassphraseProvider(cfg, effectiveKeyPath);
        new SwingWorker<SshConnect.Connected, Void>() {
            @Override
            protected SshConnect.Connected doInBackground() throws Exception {
                return SshConnect.open(jumpHosts,
                        new SshConnect.HostHop(cfg.getHost(), cfg.getPort(), effectiveUser,
                                password, effectiveKeyPath),
                        passphrases);
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
            case OPEN_SFTP -> openSftpForActivePane();
            case OPEN_TUNNELS -> openTunnelManager();
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
            case DUPLICATE_SESSION -> {
                if (sidebar != null) {
                    sidebar.duplicateSelected();
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
            TunnelManager.get().stopAll();
            frame.dispose();
        });
        file.add(quit);

        JMenu terminal = new JMenu("Terminal");
        terminal.add(menuItem("Open Local Shell", TermAction.OPEN_LOCAL));
        terminal.add(menuItem("Split Column", TermAction.SPLIT_COLUMN));
        terminal.add(menuItem("Split Row", TermAction.SPLIT_ROW));
        terminal.add(menuItem("Close Pane", TermAction.CLOSE_PANE));

        JMenu ssh = new JMenu("SSH");
        ssh.add(menuItem("Open SFTP Browser", TermAction.OPEN_SFTP));
        ssh.add(menuItem("Tunneling…", TermAction.OPEN_TUNNELS));
        ssh.addSeparator();
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

        JMenu help = new JMenu("Help");
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
        bar.add(preferences);
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

    /**
     * Builds an HTML-rendering component whose {@code <a href>} links open in the system browser.
     * A {@link JEditorPane} (unlike the {@link JLabel} {@link JOptionPane} uses for HTML strings)
     * supports hyperlink activation, while still inheriting the dialog's look via the label font.
     */
    private static JEditorPane hyperlinkPane(String html) {
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

    /** Opens {@code url} in the host's default browser, ignoring failures. */
    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (IOException | URISyntaxException ignored) {
            // Best-effort: nothing actionable if the browser can't be launched.
        }
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
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(640, 480));

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
