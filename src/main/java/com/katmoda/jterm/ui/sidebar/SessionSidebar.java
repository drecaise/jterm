package com.katmoda.jterm.ui.sidebar;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.dnd.LocalTransferable;
import com.katmoda.jterm.dnd.SessionTransferable;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.ui.component.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Left sidebar: a recursive tree of saved folders/SSH sessions plus a fixed,
 * uneditable "Local Terminal" entry at the bottom.
 *
 * <p>Opening a session is delegated to callbacks the main window supplies, so the
 * sidebar stays decoupled from tab/pane wiring.</p>
 */
public final class SessionSidebar extends JPanel {

    private final SessionStore store;
    private final JTree tree;
    private final DefaultTreeModel model;

    private final BiConsumer<SshSessionConfig, OpenMode> onOpenSsh;
    private final Runnable onOpenLocal;

    public SessionSidebar(SessionStore store,
                          BiConsumer<SshSessionConfig, OpenMode> onOpenSsh,
                          Runnable onOpenLocal) {
        super(new BorderLayout());
        this.store = store;
        this.onOpenSsh = onOpenSsh;
        this.onOpenLocal = onOpenLocal;

        this.model = new DefaultTreeModel(buildNode(store.root()));
        this.tree = new JTree(model);
        tree.setRootVisible(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new SessionNodeRenderer());

        // Drag a saved SSH session out onto a pane.
        tree.setDragEnabled(true);
        tree.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                return (selectedNode() instanceof SshSessionConfig ssh)
                        ? new SessionTransferable(ssh) : null;
            }
        });

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowMenu(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !e.isPopupTrigger()) {
                    openSelected();
                }
            }
        });

        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
        add(buildLocalEntry(), BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        bar.add(iconButton("icons/folder-plus.svg", "New folder", e -> newFolder()));
        bar.add(iconButton("icons/terminal-plus.svg", "New SSH session", e -> newSsh()));
        return bar;
    }

    private JButton iconButton(String resource, String tooltip, ActionListener action) {
        JButton button = new JButton(new FlatSVGIcon(resource, 18, 18));
        button.setToolTipText(tooltip);
        button.addActionListener(action);
        return button;
    }

    private JComponent buildLocalEntry() {
        JButton local = new JButton("⊕  Local Terminal");
        local.setHorizontalAlignment(JButton.LEFT);
        local.setToolTipText("Click to open in the active pane, or drag onto a pane to split");

        local.addActionListener(e -> onOpenLocal.run());

        // Drag onto a pane to split-and-open a local shell (mirrors saved SSH sessions).
        local.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                return new LocalTransferable();
            }
        });
        local.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                local.getTransferHandler().exportAsDrag(local, e, TransferHandler.COPY);
            }
        });

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        wrap.add(local, BorderLayout.CENTER);
        return wrap;
    }

    // ---- tree model building ----

    private DefaultMutableTreeNode buildNode(SessionNode node) {
        DefaultMutableTreeNode tn = new DefaultMutableTreeNode(node);
        if (node instanceof FolderNode folder) {
            for (SessionNode child : folder.getChildren()) {
                tn.add(buildNode(child));
            }
        }
        return tn;
    }

    private void rebuild() {
        model.setRoot(buildNode(store.root()));
        store.save();
    }

    // ---- actions ----

    private void openSelected() {
        SessionNode node = selectedNode();
        if (node instanceof SshSessionConfig ssh) {
            onOpenSsh.accept(ssh, OpenMode.NEW_TAB);
        }
    }

    private void maybeShowMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path != null) {
            tree.setSelectionPath(path);
        }
        buildPopup().show(tree, e.getX(), e.getY());
    }

    private JPopupMenu buildPopup() {
        JPopupMenu menu = new JPopupMenu();
        SessionNode node = selectedNode();

        if (node instanceof SshSessionConfig ssh) {
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(a -> onOpenSsh.accept(ssh, OpenMode.NEW_TAB));

            JMenuItem openActive = new JMenuItem("Open in Active Pane");
            openActive.addActionListener(a -> onOpenSsh.accept(ssh, OpenMode.ACTIVE));

            JMenu split = new JMenu("Open in Split Pane");
            JMenuItem right = new JMenuItem("Split Right (new column)");
            right.addActionListener(a -> onOpenSsh.accept(ssh, OpenMode.SPLIT_COLUMN));
            JMenuItem below = new JMenuItem("Split Below (new row)");
            below.addActionListener(a -> onOpenSsh.accept(ssh, OpenMode.SPLIT_ROW));
            split.add(right);
            split.add(below);

            JMenuItem edit = new JMenuItem("Edit…");
            edit.addActionListener(a -> editSsh(ssh));
            JMenuItem delete = new JMenuItem("Delete");
            delete.addActionListener(a -> deleteSelected());
            menu.add(open);
            menu.add(openActive);
            menu.add(split);
            menu.addSeparator();
            menu.add(edit);
            menu.add(delete);
        } else {
            JMenuItem newFolder = new JMenuItem("New Folder…");
            newFolder.addActionListener(a -> newFolder());
            JMenuItem newSsh = new JMenuItem("New SSH Session…");
            newSsh.addActionListener(a -> newSsh());
            menu.add(newFolder);
            menu.add(newSsh);
            if (node instanceof FolderNode folder && folder != store.root()) {
                JMenuItem edit = new JMenuItem("Edit Folder…");
                edit.addActionListener(a -> editFolder(folder));
                JMenuItem delete = new JMenuItem("Delete");
                delete.addActionListener(a -> deleteSelected());
                menu.addSeparator();
                menu.add(edit);
                menu.add(delete);
            }
        }
        return menu;
    }

    private void newFolder() {
        FolderNode folder = new FolderNode("Folder");
        if (showFolderDialog(folder, "New Folder")) {
            targetFolder().getChildren().add(folder);
            rebuild();
        }
    }

    private void newSsh() {
        SshSessionConfig cfg = new SshSessionConfig();
        if (showSshDialog(cfg, "New SSH Session")) {
            targetFolder().getChildren().add(cfg);
            rebuild();
        }
    }

    private void editSsh(SshSessionConfig ssh) {
        if (showSshDialog(ssh, "Edit SSH Session")) {
            rebuild();
        }
    }

    private void editFolder(FolderNode folder) {
        if (showFolderDialog(folder, "Edit Folder")) {
            rebuild();
        }
    }

    /** Shared name + icon dialog for creating and editing folders. Mutates {@code folder} on OK. */
    private boolean showFolderDialog(FolderNode folder, String title) {
        JTextField name = new JTextField(folder.getName());
        String[] iconId = {folder.getIconId()};
        Icon fallback = IconLibrary.get().icon("builtin/folder", 16);
        JButton iconBtn = new JButton();
        updateIconButton(iconBtn, iconId[0], fallback);
        iconBtn.addActionListener(a -> {
            String picked = IconPickerDialog.pick(this);
            if (picked != null) {
                iconId[0] = picked.isEmpty() ? null : picked;
                updateIconButton(iconBtn, iconId[0], fallback);
            }
        });

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Name:"));
        form.add(name);
        form.add(new JLabel("Icon:"));
        form.add(iconBtn);

        int result = JOptionPane.showConfirmDialog(this, form, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return false;
        }
        folder.setName(blankToDefault(name.getText(), "Folder"));
        folder.setIconId(iconId[0]);
        return true;
    }

    /** Shows the chosen icon, or {@code fallback} (the type default) when none is set. */
    private void updateIconButton(JButton button, String iconId, Icon fallback) {
        button.setIcon(iconId != null ? IconLibrary.get().icon(iconId, 16) : fallback);
        button.setText(null);
        button.setToolTipText("Click to choose an icon");
    }

    private void deleteSelected() {
        SessionNode node = selectedNode();
        FolderNode parent = selectedParentFolder();
        if (node == null || parent == null || node == store.root()) {
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete \"" + node.getName() + "\"?", "Delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            parent.getChildren().remove(node);
            rebuild();
        }
    }

    /** Terminal types offered in the dialog (the combo is editable for anything not listed). */
    private static final String[] TERMINAL_TYPES = {
            "xterm-256color", "xterm", "xterm-color", "vt100", "vt220", "vt320",
            "ansi", "linux", "screen", "screen-256color", "tmux-256color", "rxvt-unicode"
    };

    /** Common stream charsets offered in the dialog (filtered to those the JVM supports). */
    private static final String[] COMMON_CHARSETS = {
            "UTF-8", "US-ASCII", "ISO-8859-1", "ISO-8859-15", "windows-1252",
            "GBK", "GB2312", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR", "KOI8-R"
    };

    private static final String DEFAULT_FONT_LABEL = "(Default)";

    /**
     * Tabbed form dialog ("Basic settings" + "Terminal Settings"); mutates {@code cfg} on OK.
     * Returns whether the user confirmed.
     */
    private boolean showSshDialog(SshSessionConfig cfg, String title) {
        // ---- Basic settings ----
        JTextField name = new JTextField(cfg.getName());
        JTextField host = new JTextField(cfg.getHost());
        JTextField port = new JTextField(String.valueOf(cfg.getPort()));
        JTextField user = new JTextField(cfg.getUser());
        ToggleSwitch agent = new ToggleSwitch(cfg.isAgentForwarding());

        ToggleSwitch passwordAuth = new ToggleSwitch(cfg.isPasswordAuth());
        JPasswordField password = new JPasswordField();
        password.putClientProperty("JTextField.placeholderText",
                cfg.isSavePassword() ? "(leave blank to keep saved)" : "");
        ToggleSwitch savePassword = new ToggleSwitch(cfg.isSavePassword());
        Runnable syncPasswordEnabled = () -> {
            boolean on = passwordAuth.isSelected();
            password.setEnabled(on);
            savePassword.setEnabled(on);
        };
        passwordAuth.addActionListener(a -> syncPasswordEnabled.run());
        syncPasswordEnabled.run();

        String[] iconId = {cfg.getIconId()};
        Icon fallback = IconLibrary.get().icon("builtin/server", 16);
        JButton iconBtn = new JButton();
        updateIconButton(iconBtn, iconId[0], fallback);
        iconBtn.addActionListener(a -> {
            String picked = IconPickerDialog.pick(this);
            if (picked != null) {
                iconId[0] = picked.isEmpty() ? null : picked;
                updateIconButton(iconBtn, iconId[0], fallback);
            }
        });

        JPanel basic = formPanel();
        row(basic, "Name:", name);
        row(basic, "Host:", host);
        row(basic, "Port:", port);
        row(basic, "User:", user);
        row(basic, "Icon:", iconBtn);
        row(basic, "Forward ssh-agent:", agent);
        row(basic, "Password auth:", passwordAuth);
        row(basic, "Password:", password);
        row(basic, "Save password:", savePassword);

        // ---- Terminal settings ----
        JComboBox<String> terminalType = new JComboBox<>(TERMINAL_TYPES);
        terminalType.setEditable(true);
        terminalType.setSelectedItem(blankToDefault(cfg.getTerminalType(), "xterm-256color"));

        JComboBox<String> font = new JComboBox<>();
        font.addItem(DEFAULT_FONT_LABEL);
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            font.addItem(family);
        }
        font.setSelectedItem((cfg.getFontFamily() == null || cfg.getFontFamily().isBlank())
                ? DEFAULT_FONT_LABEL : cfg.getFontFamily());

        int initialSize = cfg.getFontSize() > 0 ? cfg.getFontSize() : 14;
        JSpinner fontSize = new JSpinner(new SpinnerNumberModel(initialSize, 6, 72, 1));

        JComboBox<String> charset = new JComboBox<>(supportedCharsets());
        charset.setEditable(true);
        charset.setSelectedItem(blankToDefault(cfg.getTerminalCharset(), "UTF-8"));

        JPanel terminal = formPanel();
        row(terminal, "Terminal Type:", terminalType);
        row(terminal, "Font:", font);
        row(terminal, "Font Size:", fontSize);
        row(terminal, "Terminal Charset:", charset);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Basic settings", basic);
        tabs.addTab("Terminal Settings", terminal);

        int result = JOptionPane.showConfirmDialog(this, tabs, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return false;
        }
        cfg.setName(blankToDefault(name.getText(), "ssh"));
        cfg.setHost(host.getText().trim());
        cfg.setUser(user.getText().trim());
        cfg.setAgentForwarding(agent.isSelected());
        cfg.setIconId(iconId[0]);
        try {
            cfg.setPort(Integer.parseInt(port.getText().trim()));
        } catch (NumberFormatException ex) {
            cfg.setPort(22);
        }
        cfg.setTerminalType(blankToDefault(comboText(terminalType), "xterm-256color"));
        Object selectedFont = font.getSelectedItem();
        cfg.setFontFamily((selectedFont == null || DEFAULT_FONT_LABEL.equals(selectedFont))
                ? "" : selectedFont.toString());
        cfg.setFontSize((Integer) fontSize.getValue());
        cfg.setTerminalCharset(blankToDefault(comboText(charset), "UTF-8"));
        applyPasswordSettings(cfg, passwordAuth.isSelected(), savePassword.isSelected(), password.getPassword());
        return true;
    }

    private static JPanel formPanel() {
        return new JPanel(new GridLayout(0, 2, 6, 6));
    }

    private static void row(JPanel form, String label, JComponent field) {
        form.add(new JLabel(label));
        form.add(field);
    }

    private static String comboText(JComboBox<String> combo) {
        Object value = combo.getSelectedItem();
        return value == null ? "" : value.toString().trim();
    }

    /** The {@link #COMMON_CHARSETS} the running JVM actually supports. */
    private static String[] supportedCharsets() {
        List<String> supported = new ArrayList<>();
        for (String name : COMMON_CHARSETS) {
            if (Charset.isSupported(name)) {
                supported.add(name);
            }
        }
        return supported.toArray(new String[0]);
    }

    /**
     * Persists the password-auth choice and reconciles the encrypted vault: stores a newly
     * entered password, or clears any saved one when saving is turned off.
     */
    private void applyPasswordSettings(SshSessionConfig cfg, boolean passwordAuth,
                                       boolean savePassword, char[] entered) {
        cfg.setPasswordAuth(passwordAuth);
        boolean save = passwordAuth && savePassword;
        cfg.setSavePassword(save);

        VaultManager vaults = VaultManager.get();
        if (save && entered.length > 0) {
            if (vaults.ensureUnlocked(this)) {
                try {
                    vaults.vault().setPassword(cfg.getId(), entered);
                } catch (VaultException e) {
                    JOptionPane.showMessageDialog(this,
                            "Could not save the password:\n" + e.getMessage(),
                            "jterm", JOptionPane.ERROR_MESSAGE);
                    cfg.setSavePassword(false);
                }
            } else {
                cfg.setSavePassword(false); // user cancelled master-password setup
            }
        } else if (!save) {
            vaults.vault().removePassword(cfg.getId());
        }
        java.util.Arrays.fill(entered, '\0');
    }

    private static String blankToDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim();
    }

    // ---- selection helpers ----

    private SessionNode selectedNode() {
        DefaultMutableTreeNode tn = selectedTreeNode();
        return (tn != null && tn.getUserObject() instanceof SessionNode sn) ? sn : null;
    }

    private DefaultMutableTreeNode selectedTreeNode() {
        TreePath path = tree.getSelectionPath();
        return (path != null) ? (DefaultMutableTreeNode) path.getLastPathComponent() : null;
    }

    /** Folder to add new items into: the selection if it's a folder, else its parent, else root. */
    private FolderNode targetFolder() {
        SessionNode node = selectedNode();
        if (node instanceof FolderNode folder) {
            return folder;
        }
        FolderNode parent = selectedParentFolder();
        return parent != null ? parent : store.root();
    }

    private FolderNode selectedParentFolder() {
        DefaultMutableTreeNode tn = selectedTreeNode();
        if (tn == null) {
            return store.root();
        }
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) tn.getParent();
        if (parent != null && parent.getUserObject() instanceof FolderNode folder) {
            return folder;
        }
        return store.root();
    }
}
