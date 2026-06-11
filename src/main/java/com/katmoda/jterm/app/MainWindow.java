package com.katmoda.jterm.app;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.keymap.TermAction;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.terminal.TerminalProfile;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.terminal.ssh.SshSession;
import com.katmoda.jterm.terminal.ssh.agent.AgentSupport;
import com.katmoda.jterm.ui.AgentKeysDialog;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.grid.PaneGrid;
import com.katmoda.jterm.ui.pane.TerminalPane;
import com.katmoda.jterm.ui.preferences.PreferencesDialog;
import com.katmoda.jterm.ui.preferences.ShortcutsDialog;
import com.katmoda.jterm.ui.security.MasterPasswordDialog;
import com.katmoda.jterm.ui.sidebar.OpenMode;
import com.katmoda.jterm.ui.sidebar.SessionSidebar;
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
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Taskbar;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
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
    private final FlatSVGIcon terminalIconLight = new FlatSVGIcon("icons/terminal-light.svg", 16, 16);
    private final FlatSVGIcon terminalIconDark = new FlatSVGIcon("icons/terminal-dark.svg", 16, 16);
    private JButton plusButton;

    private int tabCounter = 0;
    /** While true, the global terminal-shortcut dispatcher stands down so the editor can capture keys. */
    private boolean shortcutCaptureActive = false;

    public void show() {
        SessionSidebar sidebar = new SessionSidebar(sessionStore, this::openSshSession, this::openLocalInCurrent);

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
        grid.initEmpty();
        connectAsync(cfg, grid::placeSessionInActive);
    }

    private PaneGrid newGrid() {
        PaneGrid grid = new PaneGrid();
        // A session dropped on a pane connects off-EDT, then splits + opens at that pane.
        grid.setDropHandler((target, region, cfg) ->
                connectAsync(cfg, session -> grid.splitFromPaneAndOpen(target, region, session)));
        grid.setOnActiveChanged(() -> decorateTab(grid));
        return grid;
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
        if (session instanceof SshSession ssh) {
            tabs.setIconAt(idx, iconFor(ssh.iconId()));
            tabs.setTitleAt(idx, ssh.title());
        } else {
            tabs.setIconAt(idx, terminalTabIcon());
            Object base = grid.getClientProperty("baseTitle");
            tabs.setTitleAt(idx, base != null ? base.toString() : session.title());
        }
    }

    private Icon iconFor(String iconId) {
        String id = (iconId != null && !iconId.isBlank()) ? iconId : "builtin/server";
        return IconLibrary.get().icon(id, 16);
    }

    /** The local-terminal tab icon, picked to contrast with the current theme's tab strip. */
    private Icon terminalTabIcon() {
        return ThemeManager.get().isDark() ? terminalIconLight : terminalIconDark;
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
            switch (mode) {
                case ACTIVE -> grid.placeSessionInActive(session);
                case SPLIT_COLUMN -> grid.splitColumnAndOpen(session);
                case SPLIT_ROW -> grid.splitRowAndOpen(session);
                default -> { }
            }
        });
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
                        cfg.isAgentForwarding(), password, cfg.getName(), cfg.getIconId(), profile);
            }

            @Override
            protected void done() {
                try {
                    onConnected.accept(get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    ErrorDialog.show(frame, "jterm", "SSH connection failed:", cause);
                }
            }
        }.execute();
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
        }
    }

    // ---- menu ----

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("New Tab", TermAction.NEW_TAB));
        file.add(menuItem("Close Tab", TermAction.CLOSE_TAB));
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
        bar.add(preferences);
        return bar;
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
