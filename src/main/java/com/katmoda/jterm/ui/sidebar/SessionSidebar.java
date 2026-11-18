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
package com.katmoda.jterm.ui.sidebar;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.dnd.FolderTransferable;
import com.katmoda.jterm.dnd.LocalTransferable;
import com.katmoda.jterm.dnd.RdpTransferable;
import com.katmoda.jterm.dnd.SessionTransferable;
import com.katmoda.jterm.dnd.UnknownNodeTransferable;
import com.katmoda.jterm.dnd.WslTransferable;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.highlight.HighlightLibrary;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.session.EncryptedSessionExport;
import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.JumpHostConfig;
import com.katmoda.jterm.session.RdpSessionConfig;
import com.katmoda.jterm.session.SessionExport;
import com.katmoda.jterm.session.SessionExportService;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.session.SshTarget;
import com.katmoda.jterm.session.UnknownSessionNode;
import com.katmoda.jterm.session.WslDistroNode;
import com.katmoda.jterm.terminal.wsl.WslDistributions;
import com.katmoda.jterm.security.CredentialVault;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultKeys;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.security.MasterPasswordDialog;
import com.katmoda.jterm.ui.component.DialogFocus;
import com.katmoda.jterm.ui.component.FormGrid;
import com.katmoda.jterm.ui.component.FileNames;
import com.katmoda.jterm.ui.component.FooterFileChooser;
import com.katmoda.jterm.ui.component.HighlightListCombo;
import com.katmoda.jterm.ui.component.JumpHostsForm;
import com.katmoda.jterm.ui.component.KeepAliveField;
import com.katmoda.jterm.ui.component.KeyFileField;
import com.katmoda.jterm.ui.component.TabColorPicker;
import com.katmoda.jterm.ui.component.TerminalSettingsForm;
import com.katmoda.jterm.ui.component.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.ButtonGroup;
import javax.swing.JRadioButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragSource;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Left sidebar: a recursive tree of saved folders/SSH sessions plus a fixed,
 * uneditable "Local Terminal" entry at the bottom.
 *
 * <p>Opening a session is delegated to callbacks the main window supplies, so the
 * sidebar stays decoupled from tab/pane wiring.</p>
 */
public final class SessionSidebar extends JPanel {

    private static final Logger LOG = LoggerFactory.getLogger(SessionSidebar.class);

    private static final String QUICK_CONNECT_HINT =
            "<html>Connect to a host without saving a session.<br>"
            + "The user name and port are optional — they default to the global user name "
            + "(Settings ▸ Session defaults) and port 22.</html>";

    private final SessionStore store;
    private final JTree tree;
    private final DefaultTreeModel model;

    /** Ad-hoc {@code [user@]host[:port]} entry pinned under the tree. */
    private final JTextField quickConnectField = new JTextField();

    private final BiConsumer<SshSessionConfig, OpenMode> onOpenSsh;
    private final Consumer<OpenMode> onOpenLocal;
    private final BiConsumer<String, OpenMode> onOpenWsl;
    private final Consumer<SshSessionConfig> onOpenSftp;
    private final BiConsumer<FolderNode, FolderOpenMode> onOpenFolder;
    private final BiConsumer<RdpSessionConfig, OpenMode> onOpenRdp;

    /**
     * The pinned, non-editable "WSL" folder of runtime-detected WSL2 distributions, or
     * {@code null} off Windows. It lives only in memory — never added to {@link SessionStore} —
     * so it's never serialized and never offered the saved-session edit actions.
     */
    private final FolderNode wslFolder;

    /** Guards the expand/collapse listener while we programmatically restore saved state. */
    private boolean syncingExpansion;

    /** True while the current gesture on the "Local Terminal" button is a drag, not a click. */
    private boolean localDragged;

    public SessionSidebar(SessionStore store,
                          BiConsumer<SshSessionConfig, OpenMode> onOpenSsh,
                          Consumer<OpenMode> onOpenLocal,
                          BiConsumer<String, OpenMode> onOpenWsl,
                          Consumer<SshSessionConfig> onOpenSftp,
                          BiConsumer<FolderNode, FolderOpenMode> onOpenFolder,
                          BiConsumer<RdpSessionConfig, OpenMode> onOpenRdp) {
        super(new BorderLayout());
        this.store = store;
        this.onOpenSsh = onOpenSsh;
        this.onOpenLocal = onOpenLocal;
        this.onOpenWsl = onOpenWsl;
        this.onOpenSftp = onOpenSftp;
        this.onOpenFolder = onOpenFolder;
        this.onOpenRdp = onOpenRdp;
        this.wslFolder = isWindows() ? new FolderNode("WSL") : null;

        this.model = new DefaultTreeModel(buildRoot());
        this.tree = new JTree(model);
        tree.setRootVisible(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new SessionNodeRenderer(wslFolder));

        // Drag a saved SSH session out onto a pane, or onto another folder to move it.
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                SessionNode node = selectedNode();
                if (node instanceof SshSessionConfig ssh) {
                    return new SessionTransferable(ssh);
                }
                if (node instanceof RdpSessionConfig rdp) {
                    return new RdpTransferable(rdp); // folder moves, and opening onto a pane or "+"
                }
                if (node instanceof FolderNode folder
                        && folder != store.root() && folder != wslFolder) {
                    return new FolderTransferable(folder); // root / WSL folder stay put
                }
                if (node instanceof WslDistroNode wsl) {
                    return new WslTransferable(wsl.distro());
                }
                if (node instanceof UnknownSessionNode unknown) {
                    return new UnknownNodeTransferable(unknown); // moves only: nothing to launch
                }
                return null;
            }

            @Override
            public boolean canImport(TransferSupport support) {
                if (!support.isDrop()) {
                    return false;
                }
                FolderNode target = dropTargetFolder(support);
                if (target == null) {
                    return false;
                }
                if (support.isDataFlavorSupported(SessionTransferable.SESSION_FLAVOR)
                        || support.isDataFlavorSupported(RdpTransferable.RDP_FLAVOR)
                        || support.isDataFlavorSupported(UnknownNodeTransferable.UNKNOWN_NODE_FLAVOR)) {
                    return true;
                }
                if (support.isDataFlavorSupported(FolderTransferable.FOLDER_FLAVOR)) {
                    try {
                        FolderNode dragged = (FolderNode) support.getTransferable()
                                .getTransferData(FolderTransferable.FOLDER_FLAVOR);
                        return canMoveFolder(dragged, target);
                    } catch (Exception e) {
                        LOG.debug("folder drag validation failed", e);
                        return false;
                    }
                }
                return false;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    FolderNode target = dropTargetFolder(support);
                    if (support.isDataFlavorSupported(FolderTransferable.FOLDER_FLAVOR)) {
                        FolderNode dragged = (FolderNode) support.getTransferable()
                                .getTransferData(FolderTransferable.FOLDER_FLAVOR);
                        return moveFolder(dragged, target);
                    }
                    if (support.isDataFlavorSupported(RdpTransferable.RDP_FLAVOR)) {
                        return moveSession((SessionNode) support.getTransferable()
                                .getTransferData(RdpTransferable.RDP_FLAVOR), target);
                    }
                    if (support.isDataFlavorSupported(UnknownNodeTransferable.UNKNOWN_NODE_FLAVOR)) {
                        return moveSession((SessionNode) support.getTransferable()
                                .getTransferData(UnknownNodeTransferable.UNKNOWN_NODE_FLAVOR), target);
                    }
                    SshSessionConfig dragged = (SshSessionConfig) support.getTransferable()
                            .getTransferData(SessionTransferable.SESSION_FLAVOR);
                    return moveSession(dragged, target);
                } catch (Exception e) {
                    LOG.warn("sidebar drop failed", e);
                    return false;
                }
            }
        });

        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                onExpansionChanged(event.getPath(), true);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                onExpansionChanged(event.getPath(), false);
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
                // Only open on a double-click that lands on a node's content area. Clicking a
                // folder's expand/collapse handle (or empty space) has no path here, so it must
                // not fall through to opening the still-selected session.
                if (e.getClickCount() == 2 && !e.isPopupTrigger()
                        && tree.getPathForLocation(e.getX(), e.getY()) != null) {
                    openSelected();
                }
            }
        });

        // Quick Connect and the Local Terminal button share the strip under the tree: both open a
        // brand-new session, as opposed to the toolbar above, which edits the saved tree.
        JPanel south = new JPanel(new BorderLayout());
        south.add(buildQuickConnect(), BorderLayout.NORTH);
        south.add(buildLocalEntry(), BorderLayout.SOUTH);
        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        applyExpansionState();

        if (wslFolder != null) {
            refreshWsl(); // populate the WSL folder on startup (off the EDT)
        }
    }

    // ---- quick connect ----

    /** "Quick Connect" caption over a {@code [user@]host[:port]} field; Enter opens a new tab. */
    private JComponent buildQuickConnect() {
        JLabel caption = new JLabel("Quick Connect");
        caption.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));

        quickConnectField.putClientProperty("JTextField.placeholderText", "user@host:port");
        quickConnectField.setToolTipText(QUICK_CONNECT_HINT);
        quickConnectField.addActionListener(e -> quickConnect());
        // Any edit clears a stale "bad target" outline, so the field doesn't stay red while the
        // user is fixing it.
        quickConnectField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                clearQuickConnectError();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                clearQuickConnectError();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                clearQuickConnectError();
            }
        });

        JPanel panel = new JPanel(new BorderLayout());
        // Breathing room on both sides: above, off the tree's border; below, off the Local Terminal
        // button (which contributes 4px of its own, so 6 here reads the same as the 6 on top).
        panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        panel.add(caption, BorderLayout.NORTH);
        panel.add(quickConnectField, BorderLayout.CENTER);
        return panel;
    }

    /** Focuses and selects the Quick Connect field (the QUICK_CONNECT shortcut). */
    public void focusQuickConnect() {
        quickConnectField.requestFocusInWindow();
        quickConnectField.selectAll();
    }

    /** Parses the field and opens the target in a new tab; a bad target is flagged in place. */
    private void quickConnect() {
        String input = quickConnectField.getText().trim();
        if (input.isEmpty()) {
            return;
        }
        SshTarget target;
        try {
            target = SshTarget.parse(input);
        } catch (IllegalArgumentException e) {
            quickConnectField.putClientProperty("JComponent.outline", "error");
            quickConnectField.setToolTipText(e.getMessage());
            return;
        }
        // Reuses the saved-session open path, so the tab gets its title/icon and the async connect,
        // error dialog and pane-restart factory all behave exactly as for a saved session.
        onOpenSsh.accept(ephemeralConfig(target), OpenMode.NEW_TAB);
        quickConnectField.selectAll(); // retyping replaces the target
    }

    private void clearQuickConnectError() {
        if (quickConnectField.getClientProperty("JComponent.outline") != null) {
            quickConnectField.putClientProperty("JComponent.outline", null);
            quickConnectField.setToolTipText(QUICK_CONNECT_HINT);
        }
    }

    /**
     * Builds a throwaway config for {@code target}. It is never added to the store and never
     * serialized, and it is flagged {@linkplain SshSessionConfig#isEphemeral() ephemeral} so no
     * secret is ever remembered against its (unreachable) id. Password auth stays off, so auth is
     * ssh-agent → the global default key → the usual interactive prompt. Everything it leaves
     * blank — user, key path, keep-alive, terminal profile — resolves through the normal
     * inheritance chain, which for a config outside the tree is exactly the global defaults.
     */
    private SshSessionConfig ephemeralConfig(SshTarget target) {
        SshSessionConfig cfg = new SshSessionConfig();
        cfg.setEphemeral(true);
        cfg.setHost(target.host());
        cfg.setPort(target.port());
        cfg.setUser(target.user());
        cfg.setName(target.label(store.effectiveUser(cfg)));
        return cfg;
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        bar.add(iconButton("icons/folder-plus.svg", "New folder", e -> newFolder()));
        bar.add(iconButton("icons/terminal-plus.svg", "New SSH session", e -> newSsh()));
        bar.add(iconButton("icons/rdp-plus.svg", "New RDP session", e -> newRdp()));
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
        local.setToolTipText("Click to open in a new tab (or fill the empty pane), "
                + "or drag onto a pane to split");

        // NEW_TAB rather than ACTIVE: a click must never close the session in the focused pane.
        local.addActionListener(e -> {
            // exportAsDrag below swallows the mouse release, so the button still fires its action
            // once the drop finishes. Left alone that opens a tab on top of the split the drop just
            // created — invisible while this action was ACTIVE (it merely replaced the fresh pane
            // with an identical shell), obvious now that it opens a tab.
            if (localDragged) {
                localDragged = false;
                return;
            }
            onOpenLocal.accept(OpenMode.NEW_TAB);
        });
        local.setComponentPopupMenu(buildLocalPopup());

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
        MouseAdapter dragGesture = new MouseAdapter() {
            private Point pressedAt;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt = e.getPoint();
                localDragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // Only past the platform's drag threshold, so a shaky click stays a click rather
                // than becoming a zero-distance drag whose action then gets swallowed above.
                if (localDragged || pressedAt == null
                        || e.getPoint().distance(pressedAt) < DragSource.getDragThreshold()) {
                    return;
                }
                localDragged = true;
                local.getTransferHandler().exportAsDrag(local, e, TransferHandler.COPY);
            }
        };
        local.addMouseListener(dragGesture);
        local.addMouseMotionListener(dragGesture);

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

    /** The saved tree, with the runtime WSL folder appended at the end when it has distros. */
    private DefaultMutableTreeNode buildRoot() {
        DefaultMutableTreeNode root = buildNode(store.root());
        if (wslFolder != null && !wslFolder.getChildren().isEmpty()) {
            root.add(buildNode(wslFolder));
        }
        return root;
    }

    private void rebuild() {
        model.setRoot(buildRoot());
        applyExpansionState();
        store.save();
    }

    /** Re-detects WSL2 distributions off the EDT and rebuilds the WSL folder when done. */
    private void refreshWsl() {
        if (wslFolder == null) {
            return;
        }
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return WslDistributions.listVersion2();
            }

            @Override
            protected void done() {
                List<String> distros;
                try {
                    distros = get();
                } catch (Exception e) {
                    LOG.debug("WSL distribution detection failed", e);
                    return;
                }
                wslFolder.getChildren().clear();
                for (String distro : distros) {
                    wslFolder.getChildren().add(new WslDistroNode(distro));
                }
                rebuild();
            }
        }.execute();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    // ---- folder expansion state (remembered across restarts) ----

    /** Restores each folder's saved expanded/collapsed state onto the freshly built tree. */
    private void applyExpansionState() {
        syncingExpansion = true;
        try {
            applyExpansion((DefaultMutableTreeNode) model.getRoot());
        } finally {
            syncingExpansion = false;
        }
    }

    /**
     * Expands or collapses {@code tn} per its folder's flag, descending only into expanded
     * folders — JTree won't render a collapsed folder's children, and {@code expandPath}
     * would otherwise force their (collapsed) ancestors open. Nested state for a folder that
     * is reopened later is reapplied lazily in {@link #onExpansionChanged}.
     */
    private void applyExpansion(DefaultMutableTreeNode tn) {
        if (!(tn.getUserObject() instanceof FolderNode folder)) {
            return;
        }
        TreePath path = new TreePath(tn.getPath());
        if (folder.isExpanded()) {
            tree.expandPath(path);
            for (int i = 0; i < tn.getChildCount(); i++) {
                applyExpansion((DefaultMutableTreeNode) tn.getChildAt(i));
            }
        } else {
            tree.collapsePath(path);
        }
    }

    private void onExpansionChanged(TreePath path, boolean expanded) {
        if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode tn)
                || !(tn.getUserObject() instanceof FolderNode folder)) {
            return;
        }
        folder.setExpanded(expanded);
        if (syncingExpansion) {
            return; // programmatic restore — already persisted, and recursion is handled there
        }
        store.save();
        if (expanded) {
            // Now that the children are visible, reapply their own saved expansion state.
            syncingExpansion = true;
            try {
                for (int i = 0; i < tn.getChildCount(); i++) {
                    applyExpansion((DefaultMutableTreeNode) tn.getChildAt(i));
                }
            } finally {
                syncingExpansion = false;
            }
        }
    }

    // ---- moving sessions between folders ----

    /** Resolves a tree drop location to the folder a session would land in, or {@code null}. */
    private FolderNode dropTargetFolder(TransferHandler.TransferSupport support) {
        if (!(support.getDropLocation() instanceof JTree.DropLocation dl) || dl.getPath() == null
                || !(dl.getPath().getLastPathComponent() instanceof DefaultMutableTreeNode tn)) {
            return null;
        }
        Object uo = tn.getUserObject();
        if (uo instanceof FolderNode folder) {
            return folder == wslFolder ? null : folder; // can't drop into the runtime WSL folder
        }
        if (uo instanceof SshSessionConfig || uo instanceof RdpSessionConfig || uo instanceof UnknownSessionNode) {
            return parentFolderOf((SessionNode) uo); // dropping onto a session targets its folder
        }
        return null;
    }

    /** Reparents {@code session} into {@code target}. Returns whether anything changed. */
    private boolean moveSession(SessionNode session, FolderNode target) {
        FolderNode current = parentFolderOf(session);
        if (current == null || target == null || target == current) {
            return false;
        }
        current.getChildren().remove(session);
        target.getChildren().add(session);
        rebuild();
        return true;
    }

    /**
     * Whether {@code dragged} may be re-parented into {@code target} without breaking the tree
     * invariant. Rejects moving the root or WSL folder, dropping a folder onto itself, a no-op
     * drop back into the current parent, and — critically — dropping a folder into itself or one
     * of its own descendants, which would create a cycle and blow up tree traversal / save.
     */
    private boolean canMoveFolder(FolderNode dragged, FolderNode target) {
        if (dragged == null || target == null
                || dragged == store.root() || dragged == wslFolder) {
            return false;
        }
        if (target == dragged) {
            return false; // dropping onto itself
        }
        if (store.ancestorsOf(target).contains(dragged)) {
            return false; // target sits below dragged → cycle
        }
        FolderNode current = parentFolderOf(dragged);
        return current != null && target != current; // skip no-op move into current parent
    }

    /** Reparents {@code dragged} into {@code target}. Returns whether anything changed. */
    private boolean moveFolder(FolderNode dragged, FolderNode target) {
        if (!canMoveFolder(dragged, target)) {
            return false;
        }
        FolderNode current = parentFolderOf(dragged);
        current.getChildren().remove(dragged);
        target.getChildren().add(dragged);
        rebuild();
        selectNode(dragged);
        return true;
    }

    /** Finds the folder directly containing {@code node}, or null if it's the root / not found. */
    private FolderNode parentFolderOf(SessionNode node) {
        List<FolderNode> ancestors = store.ancestorsOf(node);
        return ancestors.isEmpty() ? null : ancestors.get(ancestors.size() - 1);
    }

    /** A folder choice in the edit dialog's drop-down; indented to show nesting depth. */
    private record FolderOption(FolderNode folder, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private List<FolderOption> folderOptions() {
        List<FolderOption> options = new ArrayList<>();
        collectFolderOptions(store.root(), 0, options);
        return options;
    }

    private void collectFolderOptions(FolderNode folder, int depth, List<FolderOption> out) {
        out.add(new FolderOption(folder, "    ".repeat(depth) + folder.getName()));
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                collectFolderOptions(sub, depth + 1, out);
            }
        }
    }

    // ---- import / export (JSON) ----

    /** Exports the whole saved-sessions tree (the root folder). Used by the File menu. */
    public void exportRootSessions() {
        exportFolder(store.root());
    }

    /** Imports a sessions export into the root of the saved-sessions tree. Used by the File menu. */
    public void importRootSessions() {
        importSessions(store.root());
    }

    /**
     * Writes {@code folder} and its subtree to a user-chosen JSON file. A checkbox offers to
     * also include saved passwords; opting in first requires the master password, after which
     * the plaintext passwords are embedded in the file.
     */
    private void exportFolder(FolderNode folder) {
        JCheckBox includeCreds = new JCheckBox("Include saved credentials (passwords)");
        includeCreds.setToolTipText("Embeds plaintext passwords; requires the master password");
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Sessions");
        chooser.setSelectedFile(new File(FileNames.safe(folder.getName(), "sessions") + ".json"));
        // Not setAccessory: that would stand the checkbox beside the file list instead of under it
        // (see FooterFileChooser), leaving the browser a fraction of the dialog width.
        chooser.setPreferredSize(UIScale.scale(new Dimension(700, 460)));
        if (FooterFileChooser.showSaveDialog(this, chooser, includeCreds)
                != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    target.getFileName() + " already exists.\nDo you want to replace it?",
                    "Export Sessions", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        SessionExport export = new SessionExport();
        export.folder = folder;
        export.root = (folder == store.root());
        if (includeCreds.isSelected() && !collectCredentials(folder, export.credentials)) {
            return; // user cancelled the master-password prompt
        }

        if (!writeExport(target, export)) {
            return; // cancelled, or a failure that was already reported
        }
        JOptionPane.showMessageDialog(this, "Exported to:\n" + target,
                "Export Sessions", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Writes {@code export} to {@code target} via {@link SessionExportService}. If it carries saved
     * passwords the whole document is encrypted under a user-supplied passphrase (AES-GCM);
     * otherwise it is written as plaintext JSON, which holds no secrets. Returns {@code false} if
     * the user cancels the passphrase prompt or the write fails (a message has already been shown).
     */
    private boolean writeExport(Path target, SessionExport export) {
        char[] passphrase = null;
        if (!export.credentials.isEmpty()) {
            passphrase = MasterPasswordDialog.promptCreateExportPassphrase(this);
            if (passphrase == null) {
                return false;
            }
        }
        try {
            if (passphrase == null) {
                SessionExportService.writePlain(target, export);
            } else {
                SessionExportService.writeEncrypted(target, export, passphrase);
            }
            return true;
        } catch (Exception e) {
            ErrorDialog.show(this, "Export Sessions", "Export failed:", e);
            return false;
        }
    }

    /**
     * Gathers plaintext passwords for every saved session under {@code folder} into {@code out}.
     * Requires the master password up front; returns {@code false} if the user cancels it.
     */
    private boolean collectCredentials(FolderNode folder, Map<String, String> out) {
        CredentialVault vault = VaultManager.get().vault();
        if (!vault.isInitialized()) {
            JOptionPane.showMessageDialog(this, "There are no saved credentials to export.",
                    "Export Sessions", JOptionPane.INFORMATION_MESSAGE);
            return true; // nothing to include — export the sessions without credentials
        }
        if (!verifyMasterPassword(vault)) {
            return false;
        }
        try {
            SessionExportService.collectCredentials(folder, vault, out);
        } catch (VaultException e) {
            ErrorDialog.show(this, "Export Sessions", "Could not read saved credentials:", e);
            return false;
        }
        return true;
    }

    /** Prompts for and verifies the master password, unlocking the vault. */
    private boolean verifyMasterPassword(CredentialVault vault) {
        String error = null;
        while (true) {
            char[] master = MasterPasswordDialog.promptEnter(this, error);
            if (master == null) {
                return false;
            }
            try {
                if (vault.unlock(master)) {
                    return true;
                }
                error = "Incorrect master password — try again.";
            } catch (VaultException e) {
                error = "Could not unlock the vault.";
            } finally {
                Arrays.fill(master, '\0');
            }
        }
    }

    /**
     * Reads a JSON export and adds its folder (with fresh session ids) under {@code target}.
     * Any embedded credentials are stored into the local vault, keyed by the new ids.
     */
    private void importSessions(FolderNode target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Sessions");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        SessionExport export = readExport(chooser.getSelectedFile());
        if (export == null) {
            return; // cancelled, or a failure that was already reported
        }
        if (export.folder == null) {
            JOptionPane.showMessageDialog(this, "That file is not a valid sessions export.",
                    "Import Sessions", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Reassign session ids so imports never collide with existing vault entries, remapping
        // any imported credentials onto the new ids.
        Map<String, String> remapped = new LinkedHashMap<>();
        SessionExportService.reassignIds(export.folder, export.credentials, remapped);
        if (!remapped.isEmpty()) {
            importCredentials(remapped);
        }

        // A root export carries the top-level "Sessions" container; merge its children into the
        // destination so re-importing doesn't nest a "Sessions" folder inside the root. Any other
        // folder is added as a child of the destination.
        if (export.root) {
            target.getChildren().addAll(export.folder.getChildren());
        } else {
            target.getChildren().add(export.folder);
        }
        rebuild();
        String imported = export.root ? "the exported sessions" : "\"" + export.folder.getName() + "\"";
        JOptionPane.showMessageDialog(this, "Imported " + imported + ".",
                "Import Sessions", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Reads a session export, transparently decrypting an {@link EncryptedSessionExport} envelope
     * (prompting for its passphrase) or parsing a plaintext {@link SessionExport}. Returns
     * {@code null} if the user cancels the passphrase prompt or the file can't be read (a message
     * is shown for failures, but not for a plain cancel).
     */
    private SessionExport readExport(File file) {
        try {
            if (SessionExportService.isEncrypted(file)) {
                return readEncryptedExport(file);
            }
            return SessionExportService.readPlain(file);
        } catch (Exception e) {
            ErrorDialog.show(this, "Import Sessions", "Import failed:", e);
            return null;
        }
    }

    /** Prompts for the passphrase and decrypts an encrypted export, retrying on a wrong passphrase. */
    private SessionExport readEncryptedExport(File file) throws Exception {
        EncryptedSessionExport envelope = SessionExportService.readEnvelope(file);
        String error = null;
        while (true) {
            char[] passphrase = MasterPasswordDialog.promptExportPassphrase(this, error);
            if (passphrase == null) {
                return null; // cancelled
            }
            try {
                SessionExport export = SessionExportService.openEnvelope(envelope, passphrase);
                if (export != null) {
                    return export;
                }
                error = "Incorrect passphrase — try again.";
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    private void importCredentials(Map<String, String> credentials) {
        if (!VaultManager.get().ensureUnlocked(this)) {
            return; // sessions still import; their passwords just won't be stored
        }
        SessionExportService.storeCredentials(VaultManager.get().vault(), credentials);
    }

    // ---- actions ----

    private void openSelected() {
        SessionNode node = selectedNode();
        if (node instanceof SshSessionConfig ssh) {
            onOpenSsh.accept(ssh, OpenMode.NEW_TAB);
        } else if (node instanceof RdpSessionConfig rdp) {
            onOpenRdp.accept(rdp, OpenMode.NEW_TAB);
        } else if (node instanceof WslDistroNode wsl) {
            onOpenWsl.accept(wsl.distro(), OpenMode.NEW_TAB);
        }
    }

    /**
     * Duplicates the selected SSH session via the same prefilled "Duplicate SSH Session"
     * dialog as the right-click menu. No-op when the selection is a folder, a WSL node, or empty.
     */
    public void duplicateSelected() {
        if (selectedNode() instanceof SshSessionConfig ssh) {
            duplicateSession(ssh);
        } else if (selectedNode() instanceof RdpSessionConfig rdp) {
            duplicateRdp(rdp);
        }
    }

    /** Moves the selected node one position earlier among its siblings. */
    public void moveSelectedUp() {
        moveSelected(-1);
    }

    /** Moves the selected node one position later among its siblings. */
    public void moveSelectedDown() {
        moveSelected(1);
    }

    /**
     * Reorders the selected node within its parent folder by {@code delta} (-1 up, +1 down).
     * Runtime WSL nodes and the root are not reorderable; out-of-range moves are no-ops.
     */
    private void moveSelected(int delta) {
        SessionNode node = selectedNode();
        if (node == null || node == store.root()
                || node instanceof WslDistroNode || node == wslFolder) {
            return;
        }
        FolderNode parent = parentFolderOf(node);
        if (parent == null) {
            return;
        }
        List<SessionNode> siblings = parent.getChildren();
        int i = siblings.indexOf(node);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= siblings.size()) {
            return;
        }
        siblings.set(i, siblings.get(j));
        siblings.set(j, node);
        rebuild();
        selectNode(node);
    }

    /** Selects (and scrolls to) the tree row backing {@code node}, after a rebuild. */
    private void selectNode(SessionNode node) {
        DefaultMutableTreeNode match = findTreeNode((DefaultMutableTreeNode) model.getRoot(), node);
        if (match != null) {
            TreePath path = new TreePath(match.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private DefaultMutableTreeNode findTreeNode(DefaultMutableTreeNode tn, SessionNode node) {
        if (tn.getUserObject() == node) {
            return tn;
        }
        for (int i = 0; i < tn.getChildCount(); i++) {
            DefaultMutableTreeNode found = findTreeNode((DefaultMutableTreeNode) tn.getChildAt(i), node);
            if (found != null) {
                return found;
            }
        }
        return null;
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

    /** Context menu for the "Local Terminal" button, mirroring the saved-session open options. */
    private JPopupMenu buildLocalPopup() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem openTab = new JMenuItem("Open in New Tab");
        openTab.addActionListener(a -> onOpenLocal.accept(OpenMode.NEW_TAB));
        JMenuItem openActive = new JMenuItem("Open in Active Pane");
        openActive.addActionListener(a -> onOpenLocal.accept(OpenMode.ACTIVE));
        JMenu split = new JMenu("Open in Split Pane");
        JMenuItem right = new JMenuItem("Split Right (new column)");
        right.addActionListener(a -> onOpenLocal.accept(OpenMode.SPLIT_COLUMN));
        JMenuItem below = new JMenuItem("Split Below (new row)");
        below.addActionListener(a -> onOpenLocal.accept(OpenMode.SPLIT_ROW));
        split.add(right);
        split.add(below);
        menu.add(openTab);
        menu.add(openActive);
        menu.add(split);
        return menu;
    }

    private JPopupMenu buildPopup() {
        JPopupMenu menu = new JPopupMenu();
        SessionNode node = selectedNode();

        if (node instanceof WslDistroNode wsl) {
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(a -> onOpenWsl.accept(wsl.distro(), OpenMode.NEW_TAB));
            JMenuItem openActive = new JMenuItem("Open in Active Pane");
            openActive.addActionListener(a -> onOpenWsl.accept(wsl.distro(), OpenMode.ACTIVE));
            JMenu split = new JMenu("Open in Split Pane");
            JMenuItem right = new JMenuItem("Split Right (new column)");
            right.addActionListener(a -> onOpenWsl.accept(wsl.distro(), OpenMode.SPLIT_COLUMN));
            JMenuItem below = new JMenuItem("Split Below (new row)");
            below.addActionListener(a -> onOpenWsl.accept(wsl.distro(), OpenMode.SPLIT_ROW));
            split.add(right);
            split.add(below);
            JMenuItem refresh = new JMenuItem("Refresh WSL");
            refresh.addActionListener(a -> refreshWsl());
            menu.add(open);
            menu.add(openActive);
            menu.add(split);
            menu.addSeparator();
            menu.add(refresh);
            return menu;
        }
        if (wslFolder != null && node == wslFolder) {
            JMenuItem refresh = new JMenuItem("Refresh WSL");
            refresh.addActionListener(a -> refreshWsl());
            menu.add(refresh);
            return menu;
        }

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

            JMenuItem sftp = new JMenuItem("Open SFTP Browser");
            sftp.addActionListener(a -> onOpenSftp.accept(ssh));

            JMenuItem edit = new JMenuItem("Edit…");
            edit.addActionListener(a -> editSsh(ssh));
            JMenuItem duplicate = new JMenuItem("Duplicate");
            duplicate.addActionListener(a -> duplicateSession(ssh));
            JMenuItem delete = new JMenuItem("Delete");
            delete.addActionListener(a -> deleteSelected());
            menu.add(open);
            menu.add(openActive);
            menu.add(split);
            menu.add(sftp);
            menu.addSeparator();
            menu.add(edit);
            menu.add(duplicate);
            menu.add(delete);
            addMoveItems(menu, ssh);
        } else if (node instanceof RdpSessionConfig rdp) {
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(a -> onOpenRdp.accept(rdp, OpenMode.NEW_TAB));
            JMenuItem openActive = new JMenuItem("Open in Active Pane");
            openActive.addActionListener(a -> onOpenRdp.accept(rdp, OpenMode.ACTIVE));
            JMenu split = new JMenu("Open in Split Pane");
            JMenuItem right = new JMenuItem("Split Right (new column)");
            right.addActionListener(a -> onOpenRdp.accept(rdp, OpenMode.SPLIT_COLUMN));
            JMenuItem below = new JMenuItem("Split Below (new row)");
            below.addActionListener(a -> onOpenRdp.accept(rdp, OpenMode.SPLIT_ROW));
            split.add(right);
            split.add(below);
            JMenuItem edit = new JMenuItem("Edit…");
            edit.addActionListener(a -> editRdp(rdp));
            JMenuItem duplicate = new JMenuItem("Duplicate");
            duplicate.addActionListener(a -> duplicateRdp(rdp));
            JMenuItem delete = new JMenuItem("Delete");
            delete.addActionListener(a -> deleteSelected());
            menu.add(open);
            menu.add(openActive);
            menu.add(split);
            menu.addSeparator();
            menu.add(edit);
            menu.add(duplicate);
            menu.add(delete);
            addMoveItems(menu, rdp);
        } else if (node instanceof UnknownSessionNode unknown) {
            // Saved by a newer jterm: this build can't open, edit or copy it, only keep it where the
            // user wants it or remove it.
            JMenuItem delete = new JMenuItem("Delete");
            delete.addActionListener(a -> deleteSelected());
            menu.add(delete);
            addMoveItems(menu, unknown);
        } else {
            JMenuItem newFolder = new JMenuItem("New Folder…");
            newFolder.addActionListener(a -> newFolder());
            JMenuItem newSsh = new JMenuItem("New SSH Session…");
            newSsh.addActionListener(a -> newSsh());
            JMenuItem newRdp = new JMenuItem("New RDP Session…");
            newRdp.addActionListener(a -> newRdp());
            menu.add(newFolder);
            menu.add(newSsh);
            menu.add(newRdp);
            if (node instanceof FolderNode folder) {
                if (!SessionStore.collectOpenableSessions(folder).isEmpty()) {
                    JMenuItem openSplit = new JMenuItem("Open All in Split Panes");
                    openSplit.addActionListener(a -> onOpenFolder.accept(folder, FolderOpenMode.SPLIT_TABS));
                    JMenuItem openTabs = new JMenuItem("Open All in Separate Tabs");
                    openTabs.addActionListener(a -> onOpenFolder.accept(folder, FolderOpenMode.SEPARATE_TABS));
                    menu.addSeparator();
                    menu.add(openSplit);
                    menu.add(openTabs);
                }
                JMenuItem export = new JMenuItem("Export Sessions…");
                export.addActionListener(a -> exportFolder(folder));
                JMenuItem importInto = new JMenuItem("Import Sessions…");
                importInto.addActionListener(a -> importSessions(folder));
                menu.addSeparator();
                menu.add(export);
                menu.add(importInto);
                if (folder != store.root()) {
                    JMenuItem edit = new JMenuItem("Edit Folder…");
                    edit.addActionListener(a -> editFolder(folder));
                    JMenuItem delete = new JMenuItem("Delete");
                    delete.addActionListener(a -> deleteSelected());
                    menu.addSeparator();
                    menu.add(edit);
                    menu.add(delete);
                    addMoveItems(menu, folder);
                }
            }
        }
        return menu;
    }

    /** Appends "Move Up"/"Move Down" items, disabled at the ends of {@code node}'s sibling list. */
    private void addMoveItems(JPopupMenu menu, SessionNode node) {
        FolderNode parent = parentFolderOf(node);
        int idx = (parent != null) ? parent.getChildren().indexOf(node) : -1;
        int count = (parent != null) ? parent.getChildren().size() : 0;

        JMenuItem up = new JMenuItem("Move Up");
        up.setEnabled(idx > 0);
        up.addActionListener(a -> moveSelectedUp());
        JMenuItem down = new JMenuItem("Move Down");
        down.setEnabled(idx >= 0 && idx < count - 1);
        down.addActionListener(a -> moveSelectedDown());

        menu.addSeparator();
        menu.add(up);
        menu.add(down);
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
        FolderNode dest = showSshDialog(cfg, "New SSH Session", targetFolder());
        if (dest != null) {
            dest.getChildren().add(cfg);
            rebuild();
        }
    }

    /**
     * Opens the add-session dialog prefilled with a copy of {@code original}, its name suffixed
     * with the next free {@code (n)} counter. The copy gets a fresh id and lands in the same
     * folder unless the user picks another.
     */
    private void duplicateSession(SshSessionConfig original) {
        SshSessionConfig copy = copyOf(original);
        copy.setName(uniqueDuplicateName(original.getName()));
        FolderNode parent = parentFolderOf(original);
        FolderNode dest = showSshDialog(copy, "Duplicate SSH Session",
                parent != null ? parent : store.root());
        if (dest != null) {
            dest.getChildren().add(copy);
            rebuild();
        }
    }

    /** Copies every editable setting (but not the id or any saved vault password). */
    private static SshSessionConfig copyOf(SshSessionConfig src) {
        SshSessionConfig copy = new SshSessionConfig();
        copy.setName(src.getName());
        copy.setIconId(src.getIconId());
        copy.setHost(src.getHost());
        copy.setPort(src.getPort());
        copy.setUser(src.getUser());
        copy.setAgentForwarding(src.isAgentForwarding());
        copy.setPasswordAuth(src.isPasswordAuth());
        copy.setSavePassword(src.isSavePassword());
        copy.setKeyPath(src.getKeyPath());
        copy.setTerminalType(src.getTerminalType());
        copy.setTerminalCharset(src.getTerminalCharset());
        copy.setFontFamily(src.getFontFamily());
        copy.setFontSize(src.getFontSize());
        copy.setMacroId(src.getMacroId());
        copy.setHighlightListId(src.getHighlightListId());
        copy.setTabColorHex(src.getTabColorHex());
        List<JumpHostConfig> hops = new ArrayList<>();
        for (JumpHostConfig srcHop : src.getJumpHosts()) {
            JumpHostConfig hop = new JumpHostConfig(); // fresh id → its own (empty) vault entry
            hop.setHost(srcHop.getHost());
            hop.setPort(srcHop.getPort());
            hop.setUser(srcHop.getUser());
            hop.setPasswordAuth(srcHop.isPasswordAuth());
            hop.setSavePassword(srcHop.isSavePassword());
            hop.setKeyPath(srcHop.getKeyPath());
            hops.add(hop);
        }
        copy.setJumpHosts(hops);
        return copy;
    }

    /**
     * Builds a unique name by appending {@code (n)} to {@code original} (minus any counter it
     * already carries), choosing the smallest {@code n} ≥ 1 not already in use anywhere.
     */
    private String uniqueDuplicateName(String original) {
        String base = original.replaceFirst("\\s*\\(\\d+\\)$", "");
        Set<String> taken = new HashSet<>();
        collectNames(store.root(), taken);
        for (int n = 1; ; n++) {
            String candidate = base + " (" + n + ")";
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    private void collectNames(FolderNode folder, Set<String> out) {
        for (SessionNode child : folder.getChildren()) {
            out.add(child.getName());
            if (child instanceof FolderNode sub) {
                collectNames(sub, out);
            }
        }
    }

    private void editSsh(SshSessionConfig ssh) {
        FolderNode current = parentFolderOf(ssh);
        FolderNode dest = showSshDialog(ssh, "Edit SSH Session", current);
        if (dest == null) {
            return;
        }
        if (current != null && dest != current) {
            current.getChildren().remove(ssh);
            dest.getChildren().add(ssh);
        }
        rebuild();
    }

    private void editFolder(FolderNode folder) {
        if (showFolderDialog(folder, "Edit Folder")) {
            rebuild();
        }
    }

    // ---- RDP sessions ----

    private void newRdp() {
        RdpSessionConfig cfg = new RdpSessionConfig();
        FolderNode dest = showRdpDialog(cfg, "New RDP Session", targetFolder());
        if (dest != null) {
            dest.getChildren().add(cfg);
            rebuild();
        }
    }

    private void editRdp(RdpSessionConfig rdp) {
        FolderNode current = parentFolderOf(rdp);
        FolderNode dest = showRdpDialog(rdp, "Edit RDP Session", current);
        if (dest == null) {
            return;
        }
        if (current != null && dest != current) {
            current.getChildren().remove(rdp);
            dest.getChildren().add(rdp);
        }
        rebuild();
    }

    /** Like {@link #duplicateSession}: a prefilled dialog for a copy with a fresh id and no password. */
    private void duplicateRdp(RdpSessionConfig original) {
        RdpSessionConfig copy = original.copyWithNewId();
        copy.setName(uniqueDuplicateName(original.getName()));
        FolderNode parent = parentFolderOf(original);
        FolderNode dest = showRdpDialog(copy, "Duplicate RDP Session",
                parent != null ? parent : store.root());
        if (dest != null) {
            dest.getChildren().add(copy);
            rebuild();
        }
    }

    /**
     * Tabbed form dialog ("Basic settings", "Display", "Local resources"); mutates {@code cfg} on OK. Returns the
     * folder chosen for the session, or {@code null} if cancelled.
     */
    private FolderNode showRdpDialog(RdpSessionConfig cfg, String title, FolderNode initialFolder) {
        JTextField name = new JTextField(cfg.getName());
        JTextField host = new JTextField(cfg.getHost());
        JTextField port = new JTextField(String.valueOf(cfg.getPort()));
        JTextField user = new JTextField(cfg.getUser());
        JTextField domain = new JTextField(cfg.getDomain());
        domain.putClientProperty("JTextField.placeholderText", "(none)");

        boolean hasSaved = cfg.isSavePassword()
                && VaultManager.get().vault().hasPassword(VaultKeys.sessionPassword(cfg.getId()));
        ToggleSwitch savePassword = new ToggleSwitch(cfg.isSavePassword());
        JPasswordField password = new JPasswordField();
        password.putClientProperty("JTextField.placeholderText",
                hasSaved ? "(leave blank to keep saved)" : "(prompt on connect)");
        Runnable syncPassword = () -> password.setEnabled(savePassword.isSelected());
        savePassword.addActionListener(a -> syncPassword.run());
        syncPassword.run();

        String[] iconId = {cfg.getIconId()};
        Icon fallback = IconLibrary.get().icon("builtin/rdp", 16);
        JButton iconBtn = new JButton();
        updateIconButton(iconBtn, iconId[0], fallback);
        iconBtn.addActionListener(a -> {
            String picked = IconPickerDialog.pick(this, deletedId -> {
                revertDeletedIcon(deletedId);
                if (deletedId.equals(iconId[0])) {
                    iconId[0] = null;
                    updateIconButton(iconBtn, iconId[0], fallback);
                }
            });
            if (picked != null) {
                iconId[0] = picked.isEmpty() ? null : picked;
                updateIconButton(iconBtn, iconId[0], fallback);
            }
        });

        List<FolderOption> folders = folderOptions();
        JComboBox<FolderOption> folderCombo = new JComboBox<>(folders.toArray(new FolderOption[0]));
        for (FolderOption option : folders) {
            if (option.folder() == initialFolder) {
                folderCombo.setSelectedItem(option);
                break;
            }
        }

        FormGrid basic = new FormGrid();
        basic.row("Name:", name);
        basic.row("Folder:", folderCombo);
        basic.row("Host:", host);
        basic.row("Port:", port);
        basic.row("User:", user);
        basic.row("Domain:", domain);
        basic.row("Icon:", iconBtn);
        basic.row("Save password:", savePassword);
        basic.row("Password:", password);

        JComboBox<String> sizing = new JComboBox<>(new String[]{"Fit to pane", "Fixed resolution"});
        sizing.setSelectedIndex(cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIXED ? 1 : 0);
        JTextField width = new JTextField(String.valueOf(cfg.getWidth()));
        JTextField height = new JTextField(String.valueOf(cfg.getHeight()));
        Runnable syncSizing = () -> {
            boolean fixed = sizing.getSelectedIndex() == 1;
            width.setEnabled(fixed);
            height.setEnabled(fixed);
        };
        sizing.addActionListener(a -> syncSizing.run());
        syncSizing.run();
        sizing.setToolTipText("Fit to pane asks the server to resize its desktop whenever the pane "
                + "changes size; a fixed resolution is scaled to fit the pane.");

        FormGrid display = new FormGrid();
        display.row("Desktop size:", sizing);
        display.row("Width (pixels):", width);
        display.row("Height (pixels):", height);

        ToggleSwitch shareClipboard = new ToggleSwitch(cfg.isRedirectClipboard());
        ToggleSwitch clipboardToRemote = new ToggleSwitch(cfg.isClipboardToRemote());
        ToggleSwitch filesToRemote = new ToggleSwitch(cfg.isClipboardFilesToRemote());
        ToggleSwitch filesToLocal = new ToggleSwitch(cfg.isClipboardFilesToLocal());
        Runnable syncClipboard = () -> {
            clipboardToRemote.setEnabled(shareClipboard.isSelected());
            filesToLocal.setEnabled(shareClipboard.isSelected());
            filesToRemote.setEnabled(shareClipboard.isSelected() && clipboardToRemote.isSelected());
        };
        shareClipboard.addActionListener(a -> syncClipboard.run());
        clipboardToRemote.addActionListener(a -> syncClipboard.run());
        syncClipboard.run();
        shareClipboard.setToolTipText("Text and images the remote desktop copies are placed on this "
                + "computer's clipboard, without asking.");
        clipboardToRemote.setToolTipText("Lets the remote desktop paste what is on this computer's "
                + "clipboard. Nothing is sent until an application there pastes. Off: sharing runs one "
                + "way, remote to local.");
        filesToLocal.setToolTipText("Files and folders copied on the remote desktop can be saved on this "
                + "computer with \"Save files...\" in the session's bar. Nothing is written until you "
                + "pick a folder.");
        filesToRemote.setToolTipText("Lets the remote desktop paste files and folders copied on this "
                + "computer. Each copy asks before anything is sent; links are never followed, and "
                + "jterm's own settings and your SSH keys are never included.");

        ToggleSwitch playSound = new ToggleSwitch(cfg.isPlaySound());
        playSound.setToolTipText("Plays the remote desktop's sound through this computer's speakers. "
                + "Off: the remote desktop is told there is no sound device here.");
        ToggleSwitch useMicrophone = new ToggleSwitch(cfg.isUseMicrophone());
        useMicrophone.setToolTipText("Lets the remote desktop ask for this computer's microphone. Until you "
                + "allow it, the remote desktop hears silence. Off: it is told there is no microphone here.");
        JLabel microphoneHint = new JLabel("jterm asks before the remote computer can listen, once per connection.");
        microphoneHint.setEnabled(false);

        ToggleSwitch printToJterm = new ToggleSwitch(cfg.isPrintToJterm());
        printToJterm.setToolTipText("Gives the remote desktop a printer called \"jterm\". What is printed to it "
                + "arrives here as a PDF and waits in the session's bar. Off: the remote desktop sees no printer.");
        ToggleSwitch printerIsDefault = new ToggleSwitch(cfg.isPrinterIsDefault());
        printerIsDefault.setToolTipText("Makes \"jterm\" the default printer in the remote session.");
        String askEachTime = "Ask each time";
        javax.swing.JComboBox<String> autoPrint = new javax.swing.JComboBox<>();
        autoPrint.addItem(askEachTime);
        for (javax.print.PrintService ps : javax.print.PrintServiceLookup.lookupPrintServices(null, null)) {
            autoPrint.addItem(ps.getName());
        }
        if (!cfg.getAutoPrintPrinter().isBlank()) {
            boolean present = false;
            for (int i = 0; i < autoPrint.getItemCount(); i++) {
                present |= autoPrint.getItemAt(i).equals(cfg.getAutoPrintPrinter());
            }
            if (!present) {
                autoPrint.addItem(cfg.getAutoPrintPrinter()); // not connected now: keep the choice
            }
            autoPrint.setSelectedItem(cfg.getAutoPrintPrinter());
        }
        autoPrint.setToolTipText("<html>\"Ask each time\": every job waits until you print, save or discard it.<br>"
                + "A printer: jobs print there straight away, up to "
                + com.katmoda.jterm.terminal.rdp.print.AutoPrintLimiter.MAX_PAGES + " pages a job and "
                + com.katmoda.jterm.terminal.rdp.print.AutoPrintLimiter.MAX_JOBS
                + " jobs in 10 minutes;<br>anything more waits for you. Only for servers you trust.</html>");
        Runnable syncPrinter = () -> {
            printerIsDefault.setEnabled(printToJterm.isSelected());
            autoPrint.setEnabled(printToJterm.isSelected());
        };
        printToJterm.addActionListener(a -> syncPrinter.run());
        syncPrinter.run();
        JLabel printerHint = new JLabel("Print jobs wait in the session's bar until you print or save them.");
        printerHint.setEnabled(false);

        ToggleSwitch useSmartCards = new ToggleSwitch(cfg.isUseSmartCards());
        useSmartCards.setToolTipText("Lets the remote desktop use smart cards in the readers ticked below. "
                + "Off: the remote desktop sees no smart-card reader.");
        com.katmoda.jterm.ui.rdp.SmartCardReadersEditor smartCardReaders =
                new com.katmoda.jterm.ui.rdp.SmartCardReadersEditor(cfg.getSmartCardReaders());
        Runnable syncSmartCards = () -> smartCardReaders.setEnabled(useSmartCards.isSelected());
        useSmartCards.addActionListener(a -> syncSmartCards.run());
        syncSmartCards.run();
        JLabel smartCardHint = new JLabel("<html><body style='width: " + UIScale.scale(340) + "px'>jterm asks "
                + "before the remote computer can use a card, once per connection. A PIN you type in the remote "
                + "desktop is visible to that computer.</body></html>");
        smartCardHint.setEnabled(false);

        FormGrid resourcesForm = new FormGrid();
        resourcesForm.row("Share clipboard:", shareClipboard);
        resourcesForm.row("Copy files to this computer:", filesToLocal);
        resourcesForm.row("Remote can paste mine:", clipboardToRemote);
        resourcesForm.row("Remote can paste my files:", filesToRemote);
        resourcesForm.row("Play sound on this computer:", playSound);
        resourcesForm.row("Use this computer's microphone:", useMicrophone);
        resourcesForm.row("", microphoneHint);
        resourcesForm.row("Print through jterm:", printToJterm);
        resourcesForm.row("Make it the default printer:", printerIsDefault);
        resourcesForm.row("Print without asking on:", autoPrint);
        resourcesForm.row("", printerHint);
        resourcesForm.row("Use smart cards:", useSmartCards);
        resourcesForm.row("Readers:", smartCardReaders);
        resourcesForm.row("", smartCardHint);
        com.katmoda.jterm.ui.rdp.SharedFoldersEditor sharedFolders =
                new com.katmoda.jterm.ui.rdp.SharedFoldersEditor(cfg.getDriveShares());
        JPanel resources = new JPanel(new BorderLayout(0, UIScale.scale(10)));
        resources.add(resourcesForm, BorderLayout.NORTH);
        resources.add(sharedFolders, BorderLayout.CENTER);

        ToggleSwitch allowTls = new ToggleSwitch(cfg.isAllowTlsWithoutNla());
        allowTls.setToolTipText("Also connect to servers that offer TLS but not Network Level "
                + "Authentication, such as xrdp.");
        FormGrid securityForm = new FormGrid();
        securityForm.row("Allow TLS without NLA:", allowTls);
        JLabel securityHint = new JLabel("<html><body style='width: " + UIScale.scale(340) + "px'>"
                + "Off (the default), the server must support Network Level Authentication: it proves "
                + "it knows your password before the password is sent, which protects it even if a "
                + "fake server's certificate is accepted on first connect.<br><br>"
                + "Turn it on for servers that can't do NLA, such as xrdp. The server's certificate "
                + "is still checked. The older RDP security layer, which can't check it, is always "
                + "refused.</body></html>");
        securityHint.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), 0, 0, 0));
        // Windows only: the other systems' helpers have no Kerberos at all, and no restricted token.
        boolean onWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        ToggleSwitch useKerberos = new ToggleSwitch(cfg.isUseKerberos());
        useKerberos.setToolTipText("Sign in with Windows' own authentication, which can use Kerberos. "
                + "Needed for servers in a domain that refuses NTLM.");
        JLabel kerberosHint = new JLabel("<html><body style='width: " + UIScale.scale(340) + "px'>"
                + "jterm normally signs in with NTLM, from a helper that can't read your files or saved "
                + "passwords. Windows won't let such a restricted program use its own sign-in, so with "
                + "Kerberos on, this session's helper runs with fewer restrictions. Use it only for "
                + "servers that need it.</body></html>");
        kerberosHint.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), 0, 0, 0));
        if (onWindows) {
            securityForm.row("Use Windows sign-in (Kerberos):", useKerberos);
        }
        JPanel securityHints = new JPanel();
        securityHints.setLayout(new javax.swing.BoxLayout(securityHints, javax.swing.BoxLayout.Y_AXIS));
        securityHint.setAlignmentX(0f);
        kerberosHint.setAlignmentX(0f);
        securityHints.add(securityHint);
        if (onWindows) {
            securityHints.add(kerberosHint);
        }
        JPanel security = new JPanel(new BorderLayout());
        security.add(securityForm, BorderLayout.NORTH);
        security.add(securityHints, BorderLayout.CENTER);

        // Gateway: how the desktop is reached. Directly, through a saved SSH session (its jump
        // hosts, keys and saved password apply), or through an RD Gateway: one route at a time.
        // An SSH session id that no longer resolves stays visible as "missing" rather than
        // silently turning into a direct connection.
        List<SshChoice> sshChoices = new ArrayList<>();
        SshChoice selectedSsh = null;
        for (SshSessionConfig ssh : SessionStore.collectSshSessions(store.root())) {
            SshChoice choice = new SshChoice(ssh.getId(), ssh.getName() + "  (" + ssh.getHost()
                    + (ssh.getPort() != 22 ? ":" + ssh.getPort() : "") + ")");
            sshChoices.add(choice);
            if (ssh.getId().equals(cfg.getSshTunnelSessionId())) {
                selectedSsh = choice;
            }
        }
        if (cfg.isViaSsh() && selectedSsh == null) {
            selectedSsh = new SshChoice(cfg.getSshTunnelSessionId(), "(missing SSH session)");
            sshChoices.add(selectedSsh);
        }
        JComboBox<SshChoice> viaSsh = new JComboBox<>(sshChoices.toArray(new SshChoice[0]));
        if (selectedSsh != null) {
            viaSsh.setSelectedItem(selectedSsh);
        }

        JRadioButton routeDirect = new JRadioButton("Directly");
        JRadioButton routeSsh = new JRadioButton("Through an SSH session");
        // "experimental": built against FreeRDP's gateway code and a fake server, never run against a
        // real RD Gateway. Drop the word (here, in the hint below and in docs/rdp-sessions.md) once it has.
        JRadioButton routeGateway = new JRadioButton("Through an RD Gateway (experimental)");
        ButtonGroup routes = new ButtonGroup();
        routes.add(routeDirect);
        routes.add(routeSsh);
        routes.add(routeGateway);
        (cfg.isViaGateway() ? routeGateway : cfg.isViaSsh() ? routeSsh : routeDirect).setSelected(true);
        routeSsh.setEnabled(!sshChoices.isEmpty());
        routeSsh.setToolTipText(sshChoices.isEmpty() ? "You have no saved SSH sessions." : null);

        JTextField gwHost = new JTextField(cfg.getGatewayHost());
        gwHost.putClientProperty("JTextField.placeholderText", "gateway.example.com");
        JTextField gwPort = new JTextField(String.valueOf(cfg.getGatewayPort()));
        ToggleSwitch gwSame = new ToggleSwitch(cfg.isGatewaySameCredentials());
        gwSame.setToolTipText("Sign in to the gateway with the desktop's user name, domain and password "
                + "(the usual setup).");
        JTextField gwUser = new JTextField(cfg.getGatewayUser());
        JTextField gwDomain = new JTextField(cfg.getGatewayDomain());
        gwDomain.putClientProperty("JTextField.placeholderText", "(none)");
        boolean gwHasSaved = cfg.isSaveGatewayPassword() && VaultManager.get().vault()
                .hasPassword(VaultKeys.rdpGatewayPassword(cfg.getId()));
        ToggleSwitch gwSavePassword = new ToggleSwitch(cfg.isSaveGatewayPassword());
        JPasswordField gwPassword = new JPasswordField();
        gwPassword.putClientProperty("JTextField.placeholderText",
                gwHasSaved ? "(leave blank to keep saved)" : "(prompt on connect)");

        Runnable syncRoute = () -> {
            viaSsh.setEnabled(routeSsh.isSelected());
            boolean gw = routeGateway.isSelected();
            gwHost.setEnabled(gw);
            gwPort.setEnabled(gw);
            gwSame.setEnabled(gw);
            boolean own = gw && !gwSame.isSelected();
            gwUser.setEnabled(own);
            gwDomain.setEnabled(own);
            gwSavePassword.setEnabled(own);
            gwPassword.setEnabled(own && gwSavePassword.isSelected());
        };
        for (javax.swing.AbstractButton b : List.of(routeDirect, routeSsh, routeGateway, gwSame, gwSavePassword)) {
            b.addActionListener(a -> syncRoute.run());
        }
        syncRoute.run();

        JPanel routePanel = new JPanel(new GridLayout(0, 1, 0, UIScale.scale(2)));
        routePanel.add(routeDirect);
        routePanel.add(routeSsh);
        routePanel.add(routeGateway);
        FormGrid gatewayForm = new FormGrid();
        gatewayForm.row("SSH session:", viaSsh);
        gatewayForm.row("Gateway host:", gwHost);
        gatewayForm.row("Gateway port:", gwPort);
        gatewayForm.row("Use desktop credentials:", gwSame);
        gatewayForm.row("Gateway user:", gwUser);
        gatewayForm.row("Gateway domain:", gwDomain);
        gatewayForm.row("Save gateway password:", gwSavePassword);
        gatewayForm.row("Gateway password:", gwPassword);
        JLabel gatewayHint = new JLabel("<html><body style='width: " + UIScale.scale(340) + "px'>"
                + "<b>Through an SSH session</b>, the host and port on the Basic settings tab are "
                + "reached from the SSH server: use <b>localhost</b> for a desktop on the SSH server "
                + "itself. The SSH session's own jump hosts, keys and saved password are used.<br><br>"
                + "<b>Through an RD Gateway</b>, the gateway forwards the connection over HTTPS, as "
                + "in a company network. Its certificate is checked and pinned like the desktop's."
                + " This route is experimental: it has not been tested against a real gateway yet."
                + "<br><br>Either way, the desktop's certificate is checked against the host name you "
                + "entered.</body></html>");
        gatewayHint.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), 0, 0, 0));
        JPanel gatewayTop = new JPanel(new BorderLayout(0, UIScale.scale(8)));
        gatewayTop.add(routePanel, BorderLayout.NORTH);
        gatewayTop.add(gatewayForm, BorderLayout.CENTER);
        JPanel gateway = new JPanel(new BorderLayout());
        gateway.add(gatewayTop, BorderLayout.NORTH);
        gateway.add(gatewayHint, BorderLayout.CENTER);

        FormGrid.alignLabels(basic, display, resourcesForm, securityForm, gatewayForm);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Basic settings", FormGrid.tab(basic));
        tabs.addTab("Display", FormGrid.tab(display));
        tabs.addTab("Local resources", FormGrid.tab(resources, true));
        tabs.addTab("Security", FormGrid.tab(security));
        tabs.addTab("Gateway", FormGrid.tab(gateway));
        applySessionDialogWidth(tabs);

        int result = DialogFocus.showConfirm(this, tabs, title, name);
        if (result != JOptionPane.OK_OPTION) {
            java.util.Arrays.fill(password.getPassword(), '\0');
            return null;
        }
        cfg.setName(blankToDefault(name.getText(), "rdp"));
        cfg.setHost(host.getText());
        cfg.setUser(user.getText());
        cfg.setDomain(domain.getText());
        cfg.setIconId(iconId[0]);
        try {
            cfg.setPort(Integer.parseInt(port.getText().trim()));
        } catch (NumberFormatException ex) {
            cfg.setPort(RdpSessionConfig.DEFAULT_PORT);
        }
        cfg.setWidthMode(sizing.getSelectedIndex() == 1
                ? RdpSessionConfig.WidthMode.FIXED : RdpSessionConfig.WidthMode.FIT_TO_TAB);
        try {
            cfg.setWidth(Integer.parseInt(width.getText().trim()));
            cfg.setHeight(Integer.parseInt(height.getText().trim()));
        } catch (NumberFormatException ex) {
            // keep the previous size
        }
        cfg.setRedirectClipboard(shareClipboard.isSelected());
        cfg.setClipboardToRemote(clipboardToRemote.isSelected());
        cfg.setClipboardFilesToRemote(filesToRemote.isSelected());
        cfg.setClipboardFilesToLocal(filesToLocal.isSelected());
        cfg.setPlaySound(playSound.isSelected());
        cfg.setUseMicrophone(useMicrophone.isSelected());
        cfg.setPrintToJterm(printToJterm.isSelected());
        cfg.setPrinterIsDefault(printerIsDefault.isSelected());
        Object autoPrinter = autoPrint.getSelectedItem();
        cfg.setAutoPrintPrinter(autoPrinter == null || askEachTime.equals(autoPrinter) ? "" : autoPrinter.toString());
        cfg.setUseSmartCards(useSmartCards.isSelected());
        cfg.setSmartCardReaders(smartCardReaders.readers());
        cfg.setDriveShares(sharedFolders.shares());
        cfg.setAllowTlsWithoutNla(allowTls.isSelected());
        if (onWindows) {
            cfg.setUseKerberos(useKerberos.isSelected());
        }
        // One route at a time: choosing one clears the other's setting.
        SshChoice via = (SshChoice) viaSsh.getSelectedItem();
        cfg.setSshTunnelSessionId(routeSsh.isSelected() && via != null ? via.id() : "");
        cfg.setGatewayHost(routeGateway.isSelected() ? gwHost.getText() : "");
        try {
            cfg.setGatewayPort(Integer.parseInt(gwPort.getText().trim()));
        } catch (NumberFormatException ex) {
            cfg.setGatewayPort(RdpSessionConfig.DEFAULT_GATEWAY_PORT);
        }
        cfg.setGatewaySameCredentials(gwSame.isSelected());
        cfg.setGatewayUser(gwUser.getText());
        cfg.setGatewayDomain(gwDomain.getText());
        boolean ownGatewayPassword = routeGateway.isSelected() && !gwSame.isSelected()
                && gwSavePassword.isSelected();
        cfg.setSaveGatewayPassword(applyVaultPassword(VaultKeys.rdpGatewayPassword(cfg.getId()),
                ownGatewayPassword, gwPassword.getPassword()));
        // On: a typed password is stored, a blank field keeps any saved one. Off: forget it.
        cfg.setSavePassword(applyVaultPassword(VaultKeys.sessionPassword(cfg.getId()),
                savePassword.isSelected(), password.getPassword()));

        FolderOption chosen = (FolderOption) folderCombo.getSelectedItem();
        return chosen != null ? chosen.folder() : initialFolder;
    }

    /** Shared name + icon dialog for creating and editing folders. Mutates {@code folder} on OK. */
    private boolean showFolderDialog(FolderNode folder, String title) {
        JTextField name = new JTextField(folder.getName());
        name.setCaretPosition(0);
        String[] iconId = {folder.getIconId()};
        Icon fallback = IconLibrary.get().icon("builtin/folder", 16);
        JButton iconBtn = new JButton();
        updateIconButton(iconBtn, iconId[0], fallback);
        iconBtn.addActionListener(a -> {
            String picked = IconPickerDialog.pick(this, deletedId -> {
                revertDeletedIcon(deletedId);
                if (deletedId.equals(iconId[0])) {
                    iconId[0] = null;
                    updateIconButton(iconBtn, iconId[0], fallback);
                }
            });
            if (picked != null) {
                iconId[0] = picked.isEmpty() ? null : picked;
                updateIconButton(iconBtn, iconId[0], fallback);
            }
        });

        // Per-folder default username and tab color, inherited by sessions/sub-folders beneath this
        // folder. Blank inherits from an ancestor folder, then the global default.
        JTextField user = new JTextField(folder.getUser() != null ? folder.getUser() : "");
        user.putClientProperty("JTextField.placeholderText",
                "(inherit: " + store.inheritedUser(folder) + ")");
        TabColorPicker tabColor = new TabColorPicker(folder.getTabColorHex(), "Inherit");

        // Per-folder default key path + secrets (key passphrase and default password), inherited by
        // sessions beneath this folder. Blank inherits; blank secret fields keep any saved value.
        KeyFileField keyFile = new KeyFileField(folder.getKeyPath());
        keyFile.setPlaceholder("(inherit: " + describeKeyPath(store.inheritedKeyPath(folder)) + ")");
        KeepAliveField keepAlive =
                new KeepAliveField(folder.getKeepAliveSeconds(), store.inheritedKeepAliveSeconds(folder));
        JPasswordField keyPassphrase = new JPasswordField();
        keyPassphrase.putClientProperty("JTextField.placeholderText",
                VaultManager.get().vault().hasPassword(VaultKeys.folderKeyPassphrase(folder.getId()))
                        ? "(leave blank to keep saved)" : "(none)");
        JPasswordField defaultPassword = new JPasswordField();
        defaultPassword.putClientProperty("JTextField.placeholderText",
                VaultManager.get().vault().hasPassword(VaultKeys.folderPassword(folder.getId()))
                        ? "(leave blank to keep saved)" : "(none)");

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Name:"));
        form.add(name);
        form.add(new JLabel("Icon:"));
        form.add(iconBtn);
        form.add(new JLabel("Default user:"));
        form.add(user);
        form.add(new JLabel("Default tab color:"));
        form.add(tabColor.component());
        form.add(new JLabel("Default key file:"));
        form.add(keyFile.component());
        form.add(new JLabel("Keep connection alive:"));
        form.add(keepAlive.component());
        form.add(new JLabel("Default key passphrase:"));
        form.add(keyPassphrase);
        form.add(new JLabel("Default password:"));
        form.add(defaultPassword);

        int result = DialogFocus.showConfirm(this, form, title, name);
        if (result != JOptionPane.OK_OPTION) {
            return false;
        }
        folder.setName(blankToDefault(name.getText(), "Folder"));
        folder.setIconId(iconId[0]);
        folder.setUser(user.getText());
        folder.setTabColorHex(tabColor.hex());
        folder.setKeyPath(keyFile.path());
        folder.setKeepAliveSeconds(keepAlive.value());
        applyVaultSecret(VaultKeys.folderKeyPassphrase(folder.getId()), keyPassphrase.getPassword());
        applyVaultSecret(VaultKeys.folderPassword(folder.getId()), defaultPassword.getPassword());
        return true;
    }

    /**
     * After a custom icon is deleted from the picker, reverts every folder/session that referenced
     * it back to its type default (a null id), then persists and refreshes the tree.
     */
    private void revertDeletedIcon(String iconId) {
        if (clearIconRefs(store.root(), iconId)) {
            store.save();
        }
        rebuild();
    }

    /** Recursively nulls out any {@code iconId} reference under {@code folder}; returns whether any changed. */
    private boolean clearIconRefs(FolderNode folder, String iconId) {
        boolean changed = false;
        if (iconId.equals(folder.getIconId())) {
            folder.setIconId(null);
            changed = true;
        }
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                changed |= clearIconRefs(sub, iconId);
            } else if (iconId.equals(child.getIconId())) {
                child.setIconId(null);
                changed = true;
            }
        }
        return changed;
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
            removeVaultSecrets(node);
            parent.getChildren().remove(node);
            rebuild();
        }
    }

    /**
     * Tabbed form dialog ("Basic settings" + "Terminal Settings"); mutates {@code cfg} on OK.
     * Returns the folder the user chose to place the session in, or {@code null} if cancelled.
     * {@code initialFolder} is preselected in the folder drop-down.
     */
    private FolderNode showSshDialog(SshSessionConfig cfg, String title, FolderNode initialFolder) {
        // ---- Basic settings ----
        JTextField name = new JTextField(cfg.getName());
        JTextField host = new JTextField(cfg.getHost());
        JTextField port = new JTextField(String.valueOf(cfg.getPort()));
        JTextField user = new JTextField(cfg.getUser());
        // Placeholder ("(inherit: …)") is set by refreshInheritedHints below, against the folder
        // the session will land in (the session may not be in the tree yet — new/duplicate).
        ToggleSwitch agent = new ToggleSwitch(cfg.isAgentForwarding());

        ToggleSwitch passwordAuth = new ToggleSwitch(cfg.isPasswordAuth());
        JPasswordField password = new JPasswordField();
        password.putClientProperty("JTextField.placeholderText",
                cfg.isSavePassword() ? "(leave blank to keep saved)" : "(prompt on connect)");
        Runnable syncPasswordEnabled = () -> password.setEnabled(passwordAuth.isSelected());
        passwordAuth.addActionListener(a -> syncPasswordEnabled.run());
        syncPasswordEnabled.run();

        // Keep-alive: a three-state dropdown (Inherit / Disabled / Enabled) + interval spinner.
        // The Inherit row shows what it resolves to in the chosen folder (refreshed below when the
        // folder selection changes).
        KeepAliveField keepAlive = new KeepAliveField(cfg.getKeepAliveSeconds(),
                store.effectiveKeepAliveSeconds(initialFolder));

        KeyFileField keyFile = new KeyFileField(cfg.getKeyPath());
        // Placeholder set by refreshInheritedHints below (resolved against the chosen folder).
        // Passphrase for an encrypted key, saved (vault-encrypted) at the session level. Blank keeps
        // any saved one; with none saved the user is prompted at connect time.
        JPasswordField keyPassphrase = new JPasswordField();
        keyPassphrase.putClientProperty("JTextField.placeholderText",
                VaultManager.get().vault().hasPassword(VaultKeys.sessionKeyPassphrase(cfg.getId()))
                        ? "(leave blank to keep saved)" : "(prompt on connect)");

        String[] iconId = {cfg.getIconId()};
        Icon fallback = IconLibrary.get().icon("builtin/server", 16);
        JButton iconBtn = new JButton();
        updateIconButton(iconBtn, iconId[0], fallback);
        iconBtn.addActionListener(a -> {
            String picked = IconPickerDialog.pick(this, deletedId -> {
                revertDeletedIcon(deletedId);
                if (deletedId.equals(iconId[0])) {
                    iconId[0] = null;
                    updateIconButton(iconBtn, iconId[0], fallback);
                }
            });
            if (picked != null) {
                iconId[0] = picked.isEmpty() ? null : picked;
                updateIconButton(iconBtn, iconId[0], fallback);
            }
        });

        List<FolderOption> folders = folderOptions();
        JComboBox<FolderOption> folderCombo = new JComboBox<>(folders.toArray(new FolderOption[0]));
        for (FolderOption option : folders) {
            if (option.folder() == initialFolder) {
                folderCombo.setSelectedItem(option);
                break;
            }
        }
        // The "(inherit: …)" hints depend on which folder the session will live in — resolve them
        // against the currently selected folder, and re-resolve whenever that selection changes.
        Runnable refreshInheritedHints = () -> {
            FolderOption sel = (FolderOption) folderCombo.getSelectedItem();
            FolderNode dest = sel != null ? sel.folder() : initialFolder;
            user.putClientProperty("JTextField.placeholderText",
                    "(inherit: " + store.effectiveUser(dest) + ")");
            user.repaint();
            keyFile.setPlaceholder("(inherit: " + describeKeyPath(store.effectiveKeyPath(dest)) + ")");
            keepAlive.setInheritedHint(store.effectiveKeepAliveSeconds(dest));
        };
        folderCombo.addActionListener(a -> refreshInheritedHints.run());
        refreshInheritedHints.run();

        JComboBox<MacroOption> macroCombo = buildMacroCombo(cfg.getMacroId());
        JComboBox<HighlightListCombo.Option> highlightCombo = HighlightListCombo.perSession(
                cfg.getHighlightListId(), HighlightLibrary.get().lists());

        // Custom tab color: a swatch to pick, plus Clear to inherit (folder → global → theme default).
        TabColorPicker tabColor = new TabColorPicker(cfg.getTabColorHex(), "Inherit");

        FormGrid basic = new FormGrid();
        basic.row("Name:", name);
        basic.row("Folder:", folderCombo);
        basic.row("Host:", host);
        basic.row("Port:", port);
        basic.row("User:", user);
        basic.row("Icon:", iconBtn);
        basic.row("Forward ssh-agent:", agent);
        basic.row("Keep connection alive:", keepAlive.component());
        basic.row("Key file:", keyFile.component());
        basic.row("Key passphrase:", keyPassphrase);
        basic.row("Password auth:", passwordAuth);
        basic.row("Password:", password);
        basic.row("Run macro on connect:", macroCombo);
        basic.row("Output highlighting:", highlightCombo);
        basic.row("Tab color:", tabColor.component());

        // ---- Terminal settings ---- (blank fields inherit the application defaults)
        TerminalSettingsForm terminalSettings = new TerminalSettingsForm(true,
                cfg.getTerminalType(), cfg.getTerminalCharset(), cfg.getFontFamily(), cfg.getFontSize());

        // ---- Jump hosts ---- (up to 4 chained bastions; empty means a direct connection)
        JumpHostsForm jumpHostsForm = new JumpHostsForm(cfg.getJumpHosts());

        FormGrid.alignLabels(basic, terminalSettings.component());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Basic settings", FormGrid.tab(basic));
        tabs.addTab("Terminal Settings", FormGrid.tab(terminalSettings.component()));
        tabs.addTab("Jump Hosts", FormGrid.tab(jumpHostsForm.component(), true));
        applySessionDialogWidth(tabs);

        int result = DialogFocus.showConfirm(this, tabs, title, name);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        cfg.setName(blankToDefault(name.getText(), "ssh"));
        cfg.setHost(host.getText().trim());
        cfg.setUser(user.getText().trim());
        cfg.setAgentForwarding(agent.isSelected());
        cfg.setKeepAliveSeconds(keepAlive.value());
        cfg.setIconId(iconId[0]);
        try {
            cfg.setPort(Integer.parseInt(port.getText().trim()));
        } catch (NumberFormatException ex) {
            cfg.setPort(22);
        }
        cfg.setTerminalType(terminalSettings.terminalType());
        cfg.setFontFamily(terminalSettings.fontFamily());
        cfg.setFontSize(terminalSettings.fontSize());
        cfg.setTerminalCharset(terminalSettings.charset());
        MacroOption macro = (MacroOption) macroCombo.getSelectedItem();
        cfg.setMacroId(macro != null ? macro.id() : null);
        cfg.setHighlightListId(HighlightListCombo.selectedId(highlightCombo));
        cfg.setTabColorHex(tabColor.hex());
        cfg.setKeyPath(keyFile.path());
        applyVaultSecret(VaultKeys.sessionKeyPassphrase(cfg.getId()), keyPassphrase.getPassword());
        applyPasswordSettings(cfg, passwordAuth.isSelected(), password.getPassword());
        applyJumpHosts(cfg, jumpHostsForm.results());

        FolderOption chosen = (FolderOption) folderCombo.getSelectedItem();
        return chosen != null ? chosen.folder() : initialFolder;
    }

    /** A macro choice in the "Run macro on connect" combo; a null {@code id} is "(none)". */
    private record MacroOption(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Builds the run-on-connect combo: "(none)" plus every macro, preselecting {@code currentId}. */
    private static JComboBox<MacroOption> buildMacroCombo(String currentId) {
        JComboBox<MacroOption> combo = new JComboBox<>();
        MacroOption none = new MacroOption(null, "(none)");
        combo.addItem(none);
        combo.setSelectedItem(none);
        for (Macro macro : MacroLibrary.get().macros()) {
            MacroOption option = new MacroOption(macro.getId(), macro.getName());
            combo.addItem(option);
            if (macro.getId().equals(currentId)) {
                combo.setSelectedItem(option);
            }
        }
        return combo;
    }


    /**
     * Minimum width of the SSH and RDP session dialogs, in design pixels (scaled at use). A tabbed
     * pane is as wide as its widest page, and the SSH pages hold wide forms (the terminal-settings
     * font list, jump hosts) where the RDP pages are short fields in a grid, so left alone the RDP
     * dialog opened visibly narrower. One shared floor keeps them alike without measuring a
     * throwaway SSH form on every open; the SSH dialog may still grow past it where the installed
     * fonts have longer names (448 px measured for "Red Hat Display ExtraBold Italic").
     */
    private static final int SESSION_DIALOG_MIN_WIDTH = 480;

    private static void applySessionDialogWidth(JTabbedPane tabs) {
        Dimension pref = tabs.getPreferredSize();
        tabs.setPreferredSize(new Dimension(Math.max(pref.width, UIScale.scale(SESSION_DIALOG_MIN_WIDTH)),
                pref.height));
    }

    /** An entry of the RDP dialog's "Through SSH session" list: an SSH session id and its label. */
    private record SshChoice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Persists the password-auth choice and reconciles the encrypted vault: a typed password is
     * saved to the vault, a blank field keeps any existing saved password, and turning password
     * auth off clears it.
     */
    private void applyPasswordSettings(SshSessionConfig cfg, boolean passwordAuth, char[] entered) {
        cfg.setPasswordAuth(passwordAuth);
        cfg.setSavePassword(applyVaultPassword(cfg.getId(), passwordAuth, entered));
    }

    /**
     * Reconciles the encrypted vault for one host (the main session or a jump host). With password
     * auth on, a newly typed password is stored (a blank field keeps whatever is already saved —
     * the user falls back to the connect-time prompt when nothing is saved); with password auth
     * off any saved password is cleared. Clears {@code entered} and returns whether a saved
     * password now exists (false if the master-password setup was cancelled or the save failed).
     */
    private boolean applyVaultPassword(String id, boolean passwordAuth, char[] entered) {
        VaultManager vaults = VaultManager.get();
        boolean saved;
        if (passwordAuth && entered.length > 0) {
            saved = false;
            if (vaults.ensureUnlocked(this)) {
                try {
                    vaults.vault().setPassword(id, entered);
                    saved = true;
                } catch (VaultException e) {
                    ErrorDialog.show(this, "jterm", "Could not save the password:", e);
                }
            }
        } else if (passwordAuth) {
            saved = vaults.vault().hasPassword(id); // blank field: keep any existing saved password
        } else {
            vaults.vault().removePassword(id);
            saved = false;
        }
        java.util.Arrays.fill(entered, '\0');
        return saved;
    }

    /**
     * Saves a typed secret (key passphrase / default password) to the vault under {@code vaultKey};
     * a blank field keeps any already-saved value (cleared explicitly only by deleting the owner).
     * Clears {@code entered} afterwards.
     */
    private void applyVaultSecret(String vaultKey, char[] entered) {
        if (entered.length > 0 && VaultManager.get().ensureUnlocked(this)) {
            try {
                VaultManager.get().vault().setPassword(vaultKey, entered);
            } catch (VaultException e) {
                ErrorDialog.show(this, "jterm", "Could not save the secret:", e);
            }
        }
        java.util.Arrays.fill(entered, '\0');
    }

    private static String describeKeyPath(String keyPath) {
        return (keyPath != null && !keyPath.isBlank()) ? keyPath : "default identities";
    }

    /**
     * Best-effort removal of every vault secret owned by {@code node} (and, for a folder, its
     * descendants): session passwords + key passphrases, folder default password + key passphrase,
     * and jump-host passwords. Removal never forces a master-password prompt (it only edits the
     * encrypted map), so it is safe to call on delete even with the vault locked.
     */
    private void removeVaultSecrets(SessionNode node) {
        CredentialVault vault = VaultManager.get().vault();
        if (node instanceof SshSessionConfig ssh) {
            vault.removePassword(VaultKeys.sessionPassword(ssh.getId()));
            vault.removePassword(VaultKeys.sessionKeyPassphrase(ssh.getId()));
            for (JumpHostConfig jh : ssh.getJumpHosts()) {
                vault.removePassword(jh.getId());
            }
        } else if (node instanceof RdpSessionConfig rdp) {
            vault.removePassword(VaultKeys.sessionPassword(rdp.getId()));
            vault.removePassword(VaultKeys.rdpGatewayPassword(rdp.getId()));
        } else if (node instanceof FolderNode folder) {
            vault.removePassword(VaultKeys.folderPassword(folder.getId()));
            vault.removePassword(VaultKeys.folderKeyPassphrase(folder.getId()));
            for (SessionNode child : folder.getChildren()) {
                removeVaultSecrets(child);
            }
        }
    }

    /**
     * Replaces the session's jump-host list with the dialog's edited hops, persisting each hop's
     * vault password and clearing the vault entry of any hop that was removed.
     */
    private void applyJumpHosts(SshSessionConfig cfg, List<JumpHostsForm.Result> results) {
        java.util.Set<String> keptIds = new java.util.HashSet<>();
        List<JumpHostConfig> hops = new ArrayList<>();
        for (JumpHostsForm.Result result : results) {
            JumpHostConfig jh = result.config();
            jh.setSavePassword(applyVaultPassword(jh.getId(), jh.isPasswordAuth(), result.password()));
            hops.add(jh);
            keptIds.add(jh.getId());
        }
        // Drop saved passwords for hops that were removed from this session.
        for (JumpHostConfig old : cfg.getJumpHosts()) {
            if (!keptIds.contains(old.getId())) {
                VaultManager.get().vault().removePassword(old.getId());
            }
        }
        cfg.setJumpHosts(hops);
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
