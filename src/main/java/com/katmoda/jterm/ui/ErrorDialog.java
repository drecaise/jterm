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

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Error dialog whose text is selectable and copyable (unlike a plain
 * {@link JOptionPane#showMessageDialog} message). Shows the full exception cause chain — handy
 * for things like SSH negotiation errors where the detail lists the offered algorithms — and
 * offers an explicit <b>Copy</b> button.
 */
public final class ErrorDialog {

    private ErrorDialog() {
    }

    public static void show(Component parent, String title, String header, Throwable error) {
        String text = buildText(header, error);

        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(560, 240));

        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null));

        JOptionPane.showOptionDialog(parent, scroll, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.ERROR_MESSAGE, null, new Object[]{copy, "Close"}, "Close");
    }

    private static String buildText(String header, Throwable error) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isBlank()) {
            sb.append(header).append("\n\n");
        }
        Throwable cause = error;
        Throwable previous = null;
        int guard = 0;
        while (cause != null && cause != previous && guard++ < 12) {
            sb.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            sb.append('\n');
            previous = cause;
            cause = cause.getCause();
        }
        return sb.toString().strip();
    }
}
