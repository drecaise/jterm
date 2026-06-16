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
package com.katmoda.jterm.ui;

import com.katmoda.jterm.terminal.ssh.agent.AgentSupport.AgentKey;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

/**
 * Shows the keys the application can use from the local ssh-agent (type, SHA-256 fingerprint
 * and comment). Table cells are selectable so fingerprints can be copied.
 */
public final class AgentKeysDialog {

    private AgentKeysDialog() {
    }

    public static void show(Component parent, List<AgentKey> keys) {
        if (keys.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "The ssh-agent is reachable but has no keys loaded.\nAdd one with:  ssh-add",
                    "SSH Agent Keys", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String[] columns = {"Type", "Fingerprint (SHA256)", "Comment"};
        Object[][] rows = new Object[keys.size()][3];
        for (int i = 0; i < keys.size(); i++) {
            AgentKey k = keys.get(i);
            rows[i][0] = k.type();
            rows[i][1] = k.fingerprint();
            rows[i][2] = k.comment();
        }
        DefaultTableModel model = new DefaultTableModel(rows, columns) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(340);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(700, Math.min(70 + keys.size() * 22, 360)));

        JOptionPane.showMessageDialog(parent, scroll,
                keys.size() + " key(s) available from the ssh-agent", JOptionPane.PLAIN_MESSAGE);
    }
}
