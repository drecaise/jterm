package com.katmoda.jterm.ui.sidebar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.dnd.LocalTransferable;
import com.katmoda.jterm.dnd.SessionTransferable;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.SessionExport;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.security.CredentialVault;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.ui.security.MasterPasswordDialog;
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
import javax.swing.TransferHandler;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Left sidebar: a recursive tree of saved folders/SSH sessions plus a fixed,
 * uneditable "Local Terminal" entry at the bottom.
 *
 * <p>Opening a session is delegated to callbacks the main window supplies, so the
 * sidebar stays decoupled from tab/pane wiring.</p>
 */
public final class SessionSidebar extends JPanel {

    private static final ObjectMapper EXPORT_MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final SessionStore store;
    private final JTree tree;
    private final DefaultTreeModel model;

    private final BiConsumer<SshSessionConfig, OpenMode> onOpenSsh;
    private final Runnable onOpenLocal;

    /** Guards the expand/collapse listener while we programmatically restore saved state. */
    private boolean syncingExpansion;

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
                return (selectedNode() instanceof SshSessionConfig ssh)
                        ? new SessionTransferable(ssh) : null;
            }

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop()
                        && support.isDataFlavorSupported(SessionTransferable.SESSION_FLAVOR)
                        && dropTargetFolder(support) != null;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    SshSessionConfig dragged = (SshSessionConfig) support.getTransferable()
                            .getTransferData(SessionTransferable.SESSION_FLAVOR);
                    return moveSession(dragged, dropTargetFolder(support));
                } catch (Exception e) {
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
                if (e.getClickCount() == 2 && !e.isPopupTrigger()) {
                    openSelected();
                }
            }
        });

        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
        add(buildLocalEntry(), BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        applyExpansionState();
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
        applyExpansionState();
        store.save();
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
            return folder;
        }
        if (uo instanceof SshSessionConfig ssh) {
            return parentFolderOf(ssh); // dropping onto a session targets its containing folder
        }
        return null;
    }

    /** Reparents {@code session} into {@code target}. Returns whether anything changed. */
    private boolean moveSession(SshSessionConfig session, FolderNode target) {
        FolderNode current = parentFolderOf(session);
        if (current == null || target == null || target == current) {
            return false;
        }
        current.getChildren().remove(session);
        target.getChildren().add(session);
        rebuild();
        return true;
    }

    /** Finds the folder directly containing {@code node}, searching the whole tree. */
    private FolderNode parentFolderOf(SessionNode node) {
        return findParent(store.root(), node);
    }

    private FolderNode findParent(FolderNode folder, SessionNode node) {
        for (SessionNode child : folder.getChildren()) {
            if (child == node) {
                return folder;
            }
            if (child instanceof FolderNode sub) {
                FolderNode found = findParent(sub, node);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        chooser.setSelectedFile(new File(safeFileName(folder.getName()) + ".json"));
        chooser.setAccessory(includeCreds);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        SessionExport export = new SessionExport();
        export.folder = folder;
        if (includeCreds.isSelected() && !collectCredentials(folder, export.credentials)) {
            return; // user cancelled the master-password prompt
        }

        Path target = chooser.getSelectedFile().toPath();
        try {
            EXPORT_MAPPER.writeValue(target.toFile(), export);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Export failed:\n" + e.getMessage(),
                    "Export Sessions", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this, "Exported to:\n" + target,
                "Export Sessions", JOptionPane.INFORMATION_MESSAGE);
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
            collectCredentials(folder, vault, out);
        } catch (VaultException e) {
            JOptionPane.showMessageDialog(this, "Could not read saved credentials:\n" + e.getMessage(),
                    "Export Sessions", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    private void collectCredentials(FolderNode folder, CredentialVault vault, Map<String, String> out)
            throws VaultException {
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                collectCredentials(sub, vault, out);
            } else if (child instanceof SshSessionConfig ssh && ssh.isSavePassword()) {
                String password = vault.getPassword(ssh.getId());
                if (password != null) {
                    out.put(ssh.getId(), password);
                }
            }
        }
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

        SessionExport export;
        try {
            export = EXPORT_MAPPER.readValue(chooser.getSelectedFile(), SessionExport.class);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Import failed:\n" + e.getMessage(),
                    "Import Sessions", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (export == null || export.folder == null) {
            JOptionPane.showMessageDialog(this, "That file is not a valid sessions export.",
                    "Import Sessions", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Reassign session ids so imports never collide with existing vault entries, remapping
        // any imported credentials onto the new ids.
        Map<String, String> remapped = new LinkedHashMap<>();
        reassignIds(export.folder, export.credentials, remapped);
        if (!remapped.isEmpty()) {
            importCredentials(remapped);
        }

        target.getChildren().add(export.folder);
        rebuild();
        JOptionPane.showMessageDialog(this, "Imported \"" + export.folder.getName() + "\".",
                "Import Sessions", JOptionPane.INFORMATION_MESSAGE);
    }

    private void reassignIds(FolderNode folder, Map<String, String> oldCreds, Map<String, String> newCreds) {
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                reassignIds(sub, oldCreds, newCreds);
            } else if (child instanceof SshSessionConfig ssh) {
                String oldId = ssh.getId();
                String newId = UUID.randomUUID().toString();
                ssh.setId(newId);
                String password = (oldCreds != null) ? oldCreds.get(oldId) : null;
                if (password != null) {
                    newCreds.put(newId, password);
                }
            }
        }
    }

    private void importCredentials(Map<String, String> credentials) {
        if (!VaultManager.get().ensureUnlocked(this)) {
            return; // sessions still import; their passwords just won't be stored
        }
        CredentialVault vault = VaultManager.get().vault();
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            char[] password = entry.getValue().toCharArray();
            try {
                vault.setPassword(entry.getKey(), password);
            } catch (VaultException ignored) {
                // Skip a single bad entry rather than abort the whole import.
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** Sanitises a folder name for use as a default file name. */
    private static String safeFileName(String name) {
        String cleaned = name.replaceAll("[^a-zA-Z0-9-_]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "sessions" : cleaned;
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
            JMenuItem duplicate = new JMenuItem("Duplicate");
            duplicate.addActionListener(a -> duplicateSession(ssh));
            JMenuItem delete = new JMenuItem("Delete");
            delete.addActionListener(a -> deleteSelected());
            menu.add(open);
            menu.add(openActive);
            menu.add(split);
            menu.addSeparator();
            menu.add(edit);
            menu.add(duplicate);
            menu.add(delete);
        } else {
            JMenuItem newFolder = new JMenuItem("New Folder…");
            newFolder.addActionListener(a -> newFolder());
            JMenuItem newSsh = new JMenuItem("New SSH Session…");
            newSsh.addActionListener(a -> newSsh());
            menu.add(newFolder);
            menu.add(newSsh);
            if (node instanceof FolderNode folder) {
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
                }
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
        copy.setTerminalType(src.getTerminalType());
        copy.setTerminalCharset(src.getTerminalCharset());
        copy.setFontFamily(src.getFontFamily());
        copy.setFontSize(src.getFontSize());
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

        List<FolderOption> folders = folderOptions();
        JComboBox<FolderOption> folderCombo = new JComboBox<>(folders.toArray(new FolderOption[0]));
        for (FolderOption option : folders) {
            if (option.folder() == initialFolder) {
                folderCombo.setSelectedItem(option);
                break;
            }
        }

        JComboBox<MacroOption> macroCombo = buildMacroCombo(cfg.getMacroId());

        JPanel basic = formPanel();
        row(basic, "Name:", name);
        row(basic, "Folder:", folderCombo);
        row(basic, "Host:", host);
        row(basic, "Port:", port);
        row(basic, "User:", user);
        row(basic, "Icon:", iconBtn);
        row(basic, "Forward ssh-agent:", agent);
        row(basic, "Password auth:", passwordAuth);
        row(basic, "Password:", password);
        row(basic, "Save password:", savePassword);
        row(basic, "Run macro on connect:", macroCombo);

        // ---- Terminal settings ---- (blank fields inherit the application defaults)
        TerminalSettingsForm terminalSettings = new TerminalSettingsForm(true,
                cfg.getTerminalType(), cfg.getTerminalCharset(), cfg.getFontFamily(), cfg.getFontSize());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Basic settings", basic);
        tabs.addTab("Terminal Settings", terminalSettings.component());

        int result = JOptionPane.showConfirmDialog(this, tabs, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
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
        cfg.setTerminalType(terminalSettings.terminalType());
        cfg.setFontFamily(terminalSettings.fontFamily());
        cfg.setFontSize(terminalSettings.fontSize());
        cfg.setTerminalCharset(terminalSettings.charset());
        MacroOption macro = (MacroOption) macroCombo.getSelectedItem();
        cfg.setMacroId(macro != null ? macro.id() : null);
        applyPasswordSettings(cfg, passwordAuth.isSelected(), savePassword.isSelected(), password.getPassword());

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

    private static JPanel formPanel() {
        return new JPanel(new GridLayout(0, 2, 6, 6));
    }

    private static void row(JPanel form, String label, JComponent field) {
        form.add(new JLabel(label));
        form.add(field);
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
