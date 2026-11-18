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
package com.katmoda.jterm.ui.rdp;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.session.RdpDriveShare;
import com.katmoda.jterm.terminal.rdp.RdpProtocol;
import com.katmoda.jterm.terminal.rdp.drive.DriveNames;
import com.katmoda.jterm.terminal.rdp.drive.ProtectedFolders;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Shared folders" part of an RDP session's Local resources tab: which local folders the remote
 * desktop sees as drives. New rows are read-only (design decision D3). Adding jterm's config folder
 * or {@code ~/.ssh}, or anything inside them, is refused; the home folder, a folder above it or a
 * whole drive needs a confirmation (D4). The same rules are enforced again when connecting, so a
 * hand-edited {@code sessions.json} gains nothing.
 */
public final class SharedFoldersEditor extends JPanel {

    private final List<RdpDriveShare> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    public SharedFoldersEditor(List<RdpDriveShare> initial) {
        super(new BorderLayout(0, UIScale.scale(6)));
        for (RdpDriveShare d : initial) {
            rows.add(d.copy());
        }
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(UIScale.scale(220));
        table.getColumnModel().getColumn(1).setPreferredWidth(UIScale.scale(110));
        table.getColumnModel().getColumn(2).setPreferredWidth(UIScale.scale(70));
        table.setPreferredScrollableViewportSize(new Dimension(UIScale.scale(400), table.getRowHeight() * 4));

        JButton add = new JButton("Add…");
        JButton remove = new JButton("Remove");
        add.addActionListener(e -> addFolder());
        remove.addActionListener(e -> removeSelected());
        remove.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(table.getSelectedRowCount() > 0));

        // GridLayout gives both buttons the widest one's width (a BoxLayout column keeps each at
        // its own); the wrapper keeps them at the top instead of stretched over the table height.
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, UIScale.scale(4)));
        buttons.add(add);
        buttons.add(remove);
        JPanel buttonColumn = new JPanel(new BorderLayout());
        buttonColumn.add(buttons, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(UIScale.scale(6), 0));
        center.add(new JScrollPane(table), BorderLayout.CENTER);
        center.add(buttonColumn, BorderLayout.EAST);

        JLabel warning = new JLabel("<html><body style='width: " + UIScale.scale(400) + "px'>"
                + "The remote computer can read every file in these folders, and Windows may scan them "
                + "by itself (thumbnails, search). Folders not marked read-only can also be changed and "
                + "deleted there. Links inside a folder are never followed, and jterm's own settings "
                + "and your SSH keys are never shared." + (inFlatpak()
                ? "<br><br>This Flatpak can only share folders inside your home folder." : "")
                + "</body></html>");

        setBorder(BorderFactory.createTitledBorder("Shared folders"));
        add(center, BorderLayout.CENTER);
        add(warning, BorderLayout.SOUTH);
    }

    /** The edited list, with names sanitised and unique, as it should be saved. */
    public List<RdpDriveShare> shares() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<String> names = DriveNames.unique(rows.stream().map(RdpDriveShare::getName).toList());
        List<RdpDriveShare> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            RdpDriveShare r = rows.get(i);
            out.add(new RdpDriveShare(r.getPath(), names.get(i), r.isReadOnly()));
        }
        return out;
    }

    private void addFolder() {
        if (rows.size() >= RdpProtocol.MAX_DRIVES) {
            JOptionPane.showMessageDialog(this, "A session can share at most " + RdpProtocol.MAX_DRIVES
                    + " folders.", "Shared folders", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Share a folder with the remote desktop");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        Path folder = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (!Files.isDirectory(folder)) {
            JOptionPane.showMessageDialog(this, folder + " is not a folder.", "Shared folders",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        switch (ProtectedFolders.classify(folder)) {
            case REFUSE -> {
                JOptionPane.showMessageDialog(this, folder + "\nholds jterm's own settings (the password "
                        + "vault) or your SSH keys, so it can't be shared.\nShare a different folder.",
                        "Shared folders", JOptionPane.WARNING_MESSAGE);
                return;
            }
            case CONFIRM -> {
                int choice = JOptionPane.showConfirmDialog(this, folder + "\nis your home folder or a "
                        + "whole drive. The remote computer will be able to read everything in it.\n"
                        + "(jterm's own settings and your SSH keys stay hidden.)\n\nShare it anyway?",
                        "Shared folders", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            case OK -> { }
        }
        if (inFlatpak() && !folder.startsWith(Path.of(System.getProperty("user.home")))) {
            JOptionPane.showMessageDialog(this, "This Flatpak can only share folders inside your home "
                    + "folder.", "Shared folders", JOptionPane.WARNING_MESSAGE);
            return;
        }
        rows.add(new RdpDriveShare(folder.toString(), DriveNames.defaultFor(folder), true));
        model.fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
    }

    private void removeSelected() {
        int[] selected = table.getSelectedRows();
        for (int i = selected.length - 1; i >= 0; i--) {
            rows.remove(table.convertRowIndexToModel(selected[i]));
        }
        model.fireTableDataChanged();
    }

    private static boolean inFlatpak() {
        return System.getenv("FLATPAK_ID") != null || Files.exists(Path.of("/.flatpak-info"));
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Folder", "Name on the remote", "Read-only"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 2 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            RdpDriveShare d = rows.get(r);
            return switch (c) {
                case 0 -> d.getPath();
                case 1 -> d.getName();
                default -> d.isReadOnly();
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            RdpDriveShare d = rows.get(r);
            if (c == 1) {
                d.setName(DriveNames.sanitize(String.valueOf(v)));
            } else if (c == 2) {
                d.setReadOnly(Boolean.TRUE.equals(v));
            }
            fireTableRowsUpdated(r, r);
        }
    }
}
