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
package com.katmoda.jterm.ui.component;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The reusable terminal-settings editor (Terminal Type, Font, Font Size, Charset), shared by the
 * SSH session dialog and the application Preferences. Both edit the same four fields, so the form
 * lives in one place to keep them from drifting.
 *
 * <p>When {@code allowDefault} is true (per-session editing) the Type/Font/Charset choosers offer a
 * leading {@link #DEFAULT_LABEL} entry meaning "inherit the application default"; selecting it makes
 * the corresponding getter return an empty string. When false (editing the application defaults
 * themselves) only concrete values are offered.</p>
 */
public final class TerminalSettingsForm {

    /** The "inherit application default" sentinel shown in the choosers. */
    public static final String DEFAULT_LABEL = "(Default)";

    /** Terminal types offered (the combo is editable for anything not listed). */
    private static final String[] TERMINAL_TYPES = {
            "xterm-256color", "xterm", "xterm-color", "vt100", "vt220", "vt320",
            "ansi", "linux", "screen", "screen-256color", "tmux-256color", "rxvt-unicode"
    };

    /** Common stream charsets offered (filtered to those the JVM supports). */
    private static final String[] COMMON_CHARSETS = {
            "UTF-8", "US-ASCII", "ISO-8859-1", "ISO-8859-15", "windows-1252",
            "GBK", "GB2312", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR", "KOI8-R"
    };

    private final boolean allowDefault;
    private final JComboBox<String> terminalType;
    private final JComboBox<String> font;
    private final JSpinner fontSize;
    private final JComboBox<String> charset;
    private final JPanel panel;

    /**
     * @param allowDefault whether to offer a "(Default)" / inherit option for Type, Font and Charset
     * @param type         initial terminal type ({@code blank} → "(Default)" or the built-in default)
     * @param charsetName  initial charset ({@code blank} → "(Default)" or UTF-8)
     * @param family       initial font family ({@code blank} → "(Default)" when allowed)
     * @param size         initial font size in points ({@code <= 0} → 14)
     */
    public TerminalSettingsForm(boolean allowDefault, String type, String charsetName,
                                String family, int size) {
        this.allowDefault = allowDefault;

        this.terminalType = editableCombo(TERMINAL_TYPES);
        this.terminalType.setSelectedItem(isBlank(type) ? defaultText("xterm-256color") : type);

        this.font = new JComboBox<>();
        if (allowDefault) {
            font.addItem(DEFAULT_LABEL);
        }
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            font.addItem(f);
        }
        if (isBlank(family)) {
            font.setSelectedItem(allowDefault ? DEFAULT_LABEL : font.getItemAt(0));
        } else {
            if (((DefaultComboBoxModel<String>) font.getModel()).getIndexOf(family) < 0) {
                font.addItem(family); // keep an unavailable saved family selectable
            }
            font.setSelectedItem(family);
        }

        this.fontSize = new JSpinner(new SpinnerNumberModel(size > 0 ? size : 14, 6, 72, 1));

        this.charset = editableCombo(supportedCharsets());
        this.charset.setSelectedItem(isBlank(charsetName) ? defaultText("UTF-8") : charsetName);

        this.panel = new JPanel(new GridLayout(0, 2, 6, 6));
        row("Terminal Type:", terminalType);
        row("Font:", font);
        row("Font Size:", fontSize);
        row("Terminal Charset:", charset);
    }

    /** The Swing component to drop into a dialog. */
    public JComponent component() {
        return panel;
    }

    /** Selected terminal type, or {@code ""} when inheriting the default. */
    public String terminalType() {
        return resolved(comboText(terminalType));
    }

    /** Selected charset, or {@code ""} when inheriting the default. */
    public String charset() {
        return resolved(comboText(charset));
    }

    /** Selected font family, or {@code ""} when inheriting the default. */
    public String fontFamily() {
        Object value = font.getSelectedItem();
        return (value == null || DEFAULT_LABEL.equals(value)) ? "" : value.toString();
    }

    /** Selected font size in points (always concrete). */
    public int fontSize() {
        return (Integer) fontSize.getValue();
    }

    private String defaultText(String concrete) {
        return allowDefault ? DEFAULT_LABEL : concrete;
    }

    /** Maps the "(Default)" sentinel back to an empty (inherit) value. */
    private String resolved(String text) {
        return (allowDefault && DEFAULT_LABEL.equals(text)) ? "" : text;
    }

    private void row(String label, JComponent field) {
        panel.add(new JLabel(label));
        panel.add(field);
    }

    private JComboBox<String> editableCombo(String[] items) {
        List<String> values = new ArrayList<>();
        if (allowDefault) {
            values.add(DEFAULT_LABEL);
        }
        values.addAll(List.of(items));
        JComboBox<String> combo = new JComboBox<>(values.toArray(new String[0]));
        combo.setEditable(true);
        return combo;
    }

    private static String comboText(JComboBox<String> combo) {
        Object value = combo.getSelectedItem();
        return value == null ? "" : value.toString().trim();
    }

    private static String[] supportedCharsets() {
        List<String> supported = new ArrayList<>();
        for (String name : COMMON_CHARSETS) {
            if (Charset.isSupported(name)) {
                supported.add(name);
            }
        }
        return supported.toArray(new String[0]);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
