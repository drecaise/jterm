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
package com.katmoda.jterm.ui.sftp;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.dnd.PaneTransferable;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.SessionIcon;
import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.theme.ThemeColors;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Remote-only SFTP file browser hosted in a {@link com.katmoda.jterm.ui.grid.PaneGrid} cell.
 * Lists the current remote directory in a table; a toolbar offers Up / Refresh / Download /
 * Upload / New Folder / Rename / Delete. Transfers use a native file-chooser to pick the local
 * side. All SFTP I/O runs off the EDT.
 *
 * <p>This class (and the {@code sshd-sftp} library it uses) is loaded only when the SFTP feature
 * is first triggered, keeping it out of the resident set until used.</p>
 */
public final class SftpPane extends JPanel implements GridContent {

    private static final DateTimeFormatter MODIFIED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private SftpClient client;
    private final String hostLabel;
    private final String iconId;
    private Runnable onClose;
    private final Callable<SftpLauncher.SftpConnection> reconnector;

    private final EntryTableModel model = new EntryTableModel();
    private final JTable table = new JTable(model);
    private final JTextField pathField = new JTextField();
    private JLabel connectionLabel;

    private Runnable onFocus;
    private Runnable onEnded;
    private Border savedBorder;
    private String cwd = ".";
    private boolean closed;

    /** Accumulated keystrokes for type-to-select, reset after {@link #TYPE_AHEAD_RESET_MS} of idle. */
    private final StringBuilder typeAhead = new StringBuilder();
    private long lastTypeAhead;
    private static final long TYPE_AHEAD_RESET_MS = 1000;
    /** True while a reconnect is in flight, so concurrent operation failures don't each spawn one. */
    private boolean reconnecting;

    /**
     * @param client      an open SFTP client (its channel)
     * @param hostLabel   "user@host" shown in the header and bottom connection bar
     * @param iconId      the connection's icon-library id for the bottom bar (may be {@code null})
     * @param onClose     cleanup run when the pane closes — closes the channel and, for a dedicated
     *                    connection, the whole SSH connection (see {@link SftpLauncher})
     * @param reconnector rebuilds the connection after a drop; returns a fresh client + matching
     *                    cleanup (see {@link SftpLauncher})
     */
    public SftpPane(SftpClient client, String hostLabel, String iconId, Runnable onClose,
                    Callable<SftpLauncher.SftpConnection> reconnector) {
        super(new BorderLayout());
        this.client = client;
        this.hostLabel = hostLabel;
        this.iconId = iconId;
        this.onClose = onClose;
        this.reconnector = reconnector;

        add(buildHeader(), BorderLayout.NORTH);
        add(buildConnectionBar(), BorderLayout.SOUTH);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        // Enter opens the selected entry (descend into a folder / download a file). Override JTable's
        // default Enter binding, which otherwise just moves the selection to the next row.
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "jterm.open");
        table.getActionMap().put("jterm.open", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        // Type-to-select, like a file manager: jump to the first entry whose name starts with the
        // typed prefix; repeating a single letter cycles through its matches.
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (c >= 0x20 && c != 0x7f) {
                    typeAheadSelect(c);
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Resolve the home/start directory, then list it.
        loadDir(".");
    }

    private JComponent buildHeader() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("Up", "up", this::goUp));
        bar.add(button("Refresh", "refresh", () -> loadDir(cwd)));
        bar.addSeparator();
        bar.add(button("Download", "download", this::download));
        bar.add(button("Upload", "upload", this::upload));
        bar.addSeparator();
        bar.add(button("New Folder", "new-folder", this::newFolder));
        bar.add(button("Rename", "rename", this::rename));
        bar.add(button("Delete", "delete", this::delete));
        bar.add(Box.createHorizontalGlue());
        bar.add(button("Close SFTP", "close", this::requestClose));

        // An editable path field holding just the remote path (the connection is identified by the
        // bottom bar). Enter navigates; the field is rewritten to the canonical path on a load.
        pathField.setToolTipText("Type a remote path and press Enter to navigate");
        pathField.addActionListener(e -> loadDir(pathField.getText().trim()));
        JPanel pathRow = new JPanel(new BorderLayout());
        pathRow.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        pathRow.add(pathField, BorderLayout.CENTER);

        JPanel header = new JPanel(new BorderLayout());
        header.add(bar, BorderLayout.NORTH);
        header.add(pathRow, BorderLayout.SOUTH);
        return header;
    }

    /** A bottom bar showing the connection's icon + name, mirroring the terminal pane's title bar. */
    private JComponent buildConnectionBar() {
        JLabel label = new JLabel(hostLabel, SessionIcon.forIconId(iconId, 16), JLabel.LEADING);
        label.setIconTextGap(6);
        this.connectionLabel = label;
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bar.add(label, BorderLayout.CENTER);
        installBarDragSource(bar, label);
        return bar;
    }

    /** Flip the bottom bar to a transient "Reconnecting…" status, or back to the host label. */
    private void setBarStatus(String status) {
        if (connectionLabel == null) {
            return;
        }
        connectionLabel.setText(status != null ? status : hostLabel);
    }

    /**
     * Makes the connection bar a drag handle that carries this live SFTP pane, so it can be dropped
     * on the "+" (its own tab), onto another pane, or onto another window — the SFTP channel stays
     * open across the move. Mirrors {@code TerminalPane.installPaneDragSource}; the table keeps its
     * own selection behaviour since only the bar is a drag source.
     */
    private void installBarDragSource(JComponent... handles) {
        DragGestureListener listener = dge -> {
            try {
                dge.startDrag(null, new PaneTransferable(this));
            } catch (InvalidDnDOperationException ignored) {
                // Another drag is already in flight; ignore this gesture.
            }
        };
        DragSource ds = DragSource.getDefaultDragSource();
        for (JComponent handle : handles) {
            ds.createDefaultDragGestureRecognizer(handle, DnDConstants.ACTION_MOVE, listener);
        }
    }

    private JButton button(String text, String iconName, Runnable action) {
        // Icon-only with the label as a tooltip, so the toolbar fits even when the pane is split
        // down to a third of the row.
        JButton b = new JButton(actionIcon(iconName));
        b.setToolTipText(text);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * A 16px monochrome toolbar icon, recolored to the button foreground at paint time so it tracks
     * light/dark theme switches. Lives under {@code icons/actions/} — a subfolder the icon picker's
     * discovery skips, so these never appear as selectable session icons.
     */
    private static Icon actionIcon(String name) {
        FlatSVGIcon icon = new FlatSVGIcon("icons/actions/" + name + ".svg", 16, 16);
        return icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> {
            Color fg = UIManager.getColor("Button.foreground");
            return fg != null ? fg : c;
        }));
    }

    // ---- navigation & actions ----

    private void loadDir(String dir) {
        runAsync(() -> {
            String canonical = client.canonicalPath(dir);
            List<DirEntry> entries = new ArrayList<>();
            for (DirEntry e : client.readDir(canonical)) {
                if (".".equals(e.getFilename())) {
                    continue; // keep ".." for navigation, drop the self entry
                }
                entries.add(e);
            }
            entries.sort(ENTRY_ORDER);
            return new Listing(canonical, entries);
        }, listing -> {
            cwd = listing.path();
            pathField.setText(listing.path());
            model.setEntries(listing.entries());
        }, () -> pathField.setText(cwd), "List directory");
    }

    private void goUp() {
        loadDir(join(cwd, ".."));
    }

    /**
     * Move the selection to match the typed character(s). Prefixes accumulate while typing quickly
     * (reset after {@link #TYPE_AHEAD_RESET_MS} idle); pressing the same single key again cycles to
     * the next entry starting with that letter. Matches the real filename, not the glyph-prefixed
     * cell text.
     */
    private void typeAheadSelect(char c) {
        int rows = model.getRowCount();
        if (rows == 0) {
            return;
        }
        char lc = Character.toLowerCase(c);
        long now = System.currentTimeMillis();
        if (now - lastTypeAhead > TYPE_AHEAD_RESET_MS) {
            typeAhead.setLength(0);
        }
        lastTypeAhead = now;

        int start;
        if (typeAhead.length() == 0) {
            typeAhead.append(lc);
            start = 0;
        } else if (typeAhead.length() == 1 && typeAhead.charAt(0) == lc) {
            // Same single key again → cycle from just past the current selection.
            start = table.getSelectedRow() + 1;
        } else {
            typeAhead.append(lc);
            start = 0;
        }

        String prefix = typeAhead.toString();
        for (int i = 0; i < rows; i++) {
            int row = (start + i) % rows;
            if (model.entryAt(row).getFilename().toLowerCase().startsWith(prefix)) {
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
                return;
            }
        }
    }

    private void openSelected() {
        DirEntry sel = selectedEntry();
        if (sel == null) {
            return;
        }
        if (sel.getAttributes().isDirectory()) {
            loadDir(join(cwd, sel.getFilename()));
        } else if (sel.getAttributes().isRegularFile()) {
            downloadEntry(sel);
        }
    }

    private void download() {
        List<DirEntry> entries = selectedEntries();
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select one or more items to download.", "SFTP",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // A single file keeps the "choose the destination filename" flow; anything else (multiple
        // items, or a directory) downloads into a chosen folder, recursing as needed.
        if (entries.size() == 1 && entries.get(0).getAttributes().isRegularFile()) {
            downloadEntry(entries.get(0));
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Download to folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        SftpTransfer.download(this, client, cwd, entries, chooser.getSelectedFile().toPath(), null);
    }

    private void downloadEntry(DirEntry entry) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Download to");
        chooser.setSelectedFile(new java.io.File(entry.getFilename()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path local = chooser.getSelectedFile().toPath();
        String remote = join(cwd, entry.getFilename());
        runAsync(() -> {
            try (InputStream in = client.read(remote)) {
                Files.copy(in, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        }, ignored -> { }, "Download");
    }

    private void upload() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Upload files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        if (files.length == 0) {
            return;
        }
        SftpTransfer.upload(this, client, cwd, List.of(files), () -> loadDir(cwd));
    }

    private void newFolder() {
        String name = prompt("New folder name:", "");
        if (name == null || name.isBlank()) {
            return;
        }
        String remote = join(cwd, name.trim());
        runAsync(() -> {
            client.mkdir(remote);
            return null;
        }, ignored -> loadDir(cwd), "New folder");
    }

    private void rename() {
        DirEntry sel = selectedEntry();
        if (sel == null) {
            return;
        }
        String name = prompt("Rename to:", sel.getFilename());
        if (name == null || name.isBlank() || name.equals(sel.getFilename())) {
            return;
        }
        String from = join(cwd, sel.getFilename());
        String to = join(cwd, name.trim());
        runAsync(() -> {
            client.rename(from, to);
            return null;
        }, ignored -> loadDir(cwd), "Rename");
    }

    private void delete() {
        DirEntry sel = selectedEntry();
        if (sel == null) {
            return;
        }
        boolean dir = sel.getAttributes().isDirectory();
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete " + (dir ? "folder" : "file") + " \"" + sel.getFilename() + "\"?",
                "SFTP", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        String remote = join(cwd, sel.getFilename());
        runAsync(() -> {
            if (dir) {
                client.rmdir(remote);
            } else {
                client.remove(remote);
            }
            return null;
        }, ignored -> loadDir(cwd), "Delete");
    }

    private String prompt(String message, String initial) {
        return (String) JOptionPane.showInputDialog(this, message, "SFTP",
                JOptionPane.PLAIN_MESSAGE, null, null, initial);
    }

    /** Close this SFTP pane: the grid removes the cell, closes the channel and collapses/closes the
     *  tab. Mirrors the "Close Pane" shortcut (Ctrl+Up) but as a visible toolbar button. */
    private void requestClose() {
        if (onEnded != null) {
            onEnded.run();
        }
    }

    private DirEntry selectedEntry() {
        int row = table.getSelectedRow();
        return row >= 0 ? model.entryAt(row) : null;
    }

    /** All selected entries except the ".." navigation row; used for multi-item transfers. */
    private List<DirEntry> selectedEntries() {
        List<DirEntry> out = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            DirEntry e = model.entryAt(row);
            if (!"..".equals(e.getFilename())) {
                out.add(e);
            }
        }
        return out;
    }

    /** Runs {@code work} off the EDT; on success calls {@code onDone} on the EDT, else shows the error. */
    private <T> void runAsync(Callable<T> work, java.util.function.Consumer<T> onDone, String what) {
        runAsync(work, onDone, null, what);
    }

    /**
     * Runs {@code work} off the EDT; on success calls {@code onDone} on the EDT, else shows the error
     * and runs {@code onError} (when non-null) so the caller can roll back UI state (e.g. reset the
     * path field after a failed navigation).
     */
    private <T> void runAsync(Callable<T> work, java.util.function.Consumer<T> onDone,
                              Runnable onError, String what) {
        runAsync(work, onDone, onError, what, false);
    }

    /**
     * Core of {@link #runAsync}. {@code retried} guards the single auto-reconnect: a connection drop
     * detected on the first attempt rebuilds the connection and re-runs {@code work} once with
     * {@code retried=true}, so a second failure surfaces normally instead of looping.
     */
    private <T> void runAsync(Callable<T> work, java.util.function.Consumer<T> onDone,
                              Runnable onError, String what, boolean retried) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    onDone.accept(get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    // A dropped connection on the first attempt: transparently reconnect and retry once.
                    if (!retried && connectionLost() && !reconnecting) {
                        reconnectThenRetry(work, onDone, onError, what);
                        return;
                    }
                    ErrorDialog.show(SftpPane.this, "SFTP — " + what, what + " failed:", cause);
                    if (onError != null) {
                        onError.run();
                    }
                }
            }
        }.execute();
    }

    /**
     * True when the failure was a dropped link rather than an ordinary error (permission denied,
     * missing file, …). Decided from client/session state, not by parsing exception messages.
     */
    private boolean connectionLost() {
        return !client.isOpen()
                || client.getClientSession() == null
                || !client.getClientSession().isOpen();
    }

    /**
     * Rebuilds the connection off the EDT (showing a "Reconnecting…" status), then re-runs the failed
     * operation once. On a reconnect failure, restores the bar and surfaces the original operation's
     * error via the normal failure path (the retried run with a still-dead client falls through to the
     * error dialog).
     */
    private <T> void reconnectThenRetry(Callable<T> work, java.util.function.Consumer<T> onDone,
                                        Runnable onError, String what) {
        reconnecting = true;
        setBarStatus("Reconnecting…");
        new SwingWorker<SftpLauncher.SftpConnection, Void>() {
            @Override
            protected SftpLauncher.SftpConnection doInBackground() throws Exception {
                return reconnector.call();
            }

            @Override
            protected void done() {
                reconnecting = false;
                setBarStatus(null);
                if (closed) {
                    return;
                }
                try {
                    SftpLauncher.SftpConnection conn = get();
                    SftpClient oldClient = client;
                    Runnable oldClose = onClose;
                    client = conn.client();
                    onClose = conn.onClose();
                    // Release the dead connection: close its (stale) channel, then tear down whatever
                    // that path owned — the old dedicated SSH connection for a fresh open, a no-op for
                    // the shared-session path.
                    try {
                        oldClient.close();
                    } catch (IOException ignored) {
                    }
                    if (oldClose != null) {
                        oldClose.run();
                    }
                    runAsync(work, onDone, onError, what, true);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    ErrorDialog.show(SftpPane.this, "SFTP — Reconnect", "Reconnect failed:", cause);
                    if (onError != null) {
                        onError.run();
                    }
                }
            }
        }.execute();
    }

    /** Join a remote path with a child segment (POSIX "/"), letting the server canonicalize "..". */
    private static String join(String base, String child) {
        if (base.endsWith("/")) {
            return base + child;
        }
        return base + "/" + child;
    }

    // ---- GridContent ----

    @Override
    public JComponent ui() {
        return this;
    }

    @Override
    public void closeContent() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            client.close();
        } catch (IOException ignored) {
        }
        if (onClose != null) {
            onClose.run();
        }
    }

    @Override
    public void applyTheme(ThemeColors theme) {
        // Standard Swing chrome follows FlatLaf, which is refreshed globally on a theme toggle.
    }

    @Override
    public void focusContent() {
        table.requestFocusInWindow();
    }

    @Override
    public void setOnFocus(Runnable onFocus) {
        this.onFocus = onFocus;
        // Marking the SFTP cell active when any part of it is clicked.
        installFocusForwarder();
    }

    @Override
    public void setOnContentEnded(Runnable onEnded) {
        this.onEnded = onEnded;
    }

    private void installFocusForwarder() {
        MouseAdapter forward = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (onFocus != null) {
                    onFocus.run();
                }
            }
        };
        table.addMouseListener(forward);
        addMouseListener(forward);
    }

    @Override
    public void showMoveHint() {
        if (savedBorder == null) {
            savedBorder = getBorder();
        }
        setBorder(BorderFactory.createLineBorder(accent(), 3));
    }

    @Override
    public void showDropHint(DropRegion region) {
        if (savedBorder == null) {
            savedBorder = getBorder();
        }
        Color accent = accent();
        setBorder(region == DropRegion.COLUMN
                ? BorderFactory.createMatteBorder(0, 0, 0, 4, accent)
                : BorderFactory.createMatteBorder(0, 0, 4, 0, accent));
    }

    @Override
    public void clearDropHint() {
        if (savedBorder != null) {
            setBorder(savedBorder);
            savedBorder = null;
        }
    }

    @Override
    public String displayTitle() {
        return "SFTP: " + hostLabel;
    }

    private static Color accent() {
        Color c = UIManager.getColor("Component.focusColor");
        return c != null ? c : new Color(0x4A90D9);
    }

    // ---- table model + helpers ----

    private static final Comparator<DirEntry> ENTRY_ORDER = (a, b) -> {
        // ".." first, then directories, then files; each group alphabetical.
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        return a.getFilename().compareToIgnoreCase(b.getFilename());
    };

    private static int rank(DirEntry e) {
        if ("..".equals(e.getFilename())) {
            return 0;
        }
        return e.getAttributes().isDirectory() ? 1 : 2;
    }

    private record Listing(String path, List<DirEntry> entries) {
    }

    private static final class EntryTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Size", "Modified", "Permissions"};
        private List<DirEntry> entries = new ArrayList<>();

        void setEntries(List<DirEntry> entries) {
            this.entries = entries;
            fireTableDataChanged();
        }

        DirEntry entryAt(int row) {
            return entries.get(row);
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DirEntry e = entries.get(rowIndex);
            SftpClient.Attributes a = e.getAttributes();
            return switch (columnIndex) {
                case 0 -> e.getFilename();
                case 1 -> a.isDirectory() ? "" : humanSize(a.getSize());
                case 2 -> formatTime(a);
                case 3 -> permissions(a);
                default -> "";
            };
        }
    }

    /** Folder/file cue via a leading glyph (no icon dependency). */
    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            EntryTableModel m = (EntryTableModel) table.getModel();
            DirEntry e = m.entryAt(row);
            boolean dir = "..".equals(e.getFilename()) || e.getAttributes().isDirectory();
            setText((dir ? "📁 " : "📄 ") + e.getFilename());
            return this;
        }
    }

    private static String formatTime(SftpClient.Attributes a) {
        java.nio.file.attribute.FileTime t = a.getModifyTime();
        if (t == null) {
            return "";
        }
        return MODIFIED_FMT.format(Instant.ofEpochMilli(t.toMillis()));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024.0;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }

    /** Render the low 9 permission bits as "rwxr-xr-x". */
    private static String permissions(SftpClient.Attributes a) {
        int mode = a.getPermissions();
        char[] flags = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        StringBuilder sb = new StringBuilder(a.isDirectory() ? "d" : "-");
        for (int i = 0; i < 9; i++) {
            boolean set = (mode & (1 << (8 - i))) != 0;
            sb.append(set ? flags[i] : '-');
        }
        return sb.toString();
    }
}
