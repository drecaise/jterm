package com.katmoda.jterm.ui.sftp;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.theme.ThemeColors;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;

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
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private final SftpClient client;
    private final String hostLabel;
    private final Runnable onClose;

    private final EntryTableModel model = new EntryTableModel();
    private final JTable table = new JTable(model);
    private final JLabel pathLabel = new JLabel(" ");

    private Runnable onFocus;
    private Runnable onEnded;
    private Border savedBorder;
    private String cwd = ".";
    private boolean closed;

    /**
     * @param client    an open SFTP client (its channel)
     * @param hostLabel "user@host" shown in the header
     * @param onClose   cleanup run when the pane closes — closes the channel and, for a dedicated
     *                  connection, the whole SSH connection (see {@link SftpLauncher})
     */
    public SftpPane(SftpClient client, String hostLabel, Runnable onClose) {
        super(new BorderLayout());
        this.client = client;
        this.hostLabel = hostLabel;
        this.onClose = onClose;

        add(buildHeader(), BorderLayout.NORTH);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
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

        pathLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.add(bar, BorderLayout.NORTH);
        header.add(pathLabel, BorderLayout.SOUTH);
        return header;
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
            pathLabel.setText(hostLabel + ":" + listing.path());
            model.setEntries(listing.entries());
        }, "List directory");
    }

    private void goUp() {
        loadDir(join(cwd, ".."));
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
        DirEntry sel = selectedEntry();
        if (sel == null || !sel.getAttributes().isRegularFile()) {
            JOptionPane.showMessageDialog(this, "Select a file to download.", "SFTP",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        downloadEntry(sel);
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
        chooser.setDialogTitle("Upload file");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path local = chooser.getSelectedFile().toPath();
        String remote = join(cwd, local.getFileName().toString());
        runAsync(() -> {
            try (OutputStream out = client.write(remote, SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate)) {
                Files.copy(local, out);
            }
            return null;
        }, ignored -> loadDir(cwd), "Upload");
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

    /** Runs {@code work} off the EDT; on success calls {@code onDone} on the EDT, else shows the error. */
    private <T> void runAsync(Callable<T> work, java.util.function.Consumer<T> onDone, String what) {
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
                    ErrorDialog.show(SftpPane.this, "SFTP — " + what, what + " failed:", cause);
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
