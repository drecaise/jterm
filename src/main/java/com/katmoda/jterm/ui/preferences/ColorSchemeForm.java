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
package com.katmoda.jterm.ui.preferences;

import com.katmoda.jterm.ui.component.PaletteSwatch;
import com.katmoda.jterm.ui.theme.ThemeColorStore;
import com.katmoda.jterm.ui.theme.ThemeColors;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The Preferences "Colors" editor: lets the user retune the terminal palette (foreground,
 * background, selection fg/bg and the 16 ANSI colors) for both the dark and light schemes,
 * independently. A scheme selector switches which one is shown; edits to each scheme are kept in
 * an in-memory working copy, and {@link #commit()} writes both to {@link ThemeColorStore}.
 *
 * <p>The swatches always start from {@link ThemeColorStore#effective(boolean)} (preset + any saved
 * overrides); the per-scheme <b>Reset to defaults</b> button restores the built-in preset. The
 * store keeps only the slots that differ, so untouched colors keep following the built-in defaults.</p>
 */
public final class ColorSchemeForm {

    // Working buffer layout: 0=fg, 1=bg, 2=selFg, 3=selBg, 4..19=ansi 0..15.
    private static final int FG = 0;
    private static final int BG = 1;
    private static final int SEL_FG = 2;
    private static final int SEL_BG = 3;
    private static final int ANSI_BASE = 4;
    private static final int SLOTS = ANSI_BASE + 16;

    private static final String[] ANSI_NAMES = {
            "Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White"
    };

    private final JPanel root = new JPanel(new GridBagLayout());

    private final PaletteSwatch fg;
    private final PaletteSwatch bg;
    private final PaletteSwatch selFg;
    private final PaletteSwatch selBg;
    private final PaletteSwatch[] ansi = new PaletteSwatch[16];

    /**
     * Mutable color buffers — one per scheme — seeded from the store (preset + saved overrides).
     * The swatch values mirror the buffer for the shown scheme; switching schemes captures the
     * swatches back into the old buffer and loads the other.
     */
    private final Color[] darkBuf;
    private final Color[] lightBuf;

    private boolean showingDark;

    public ColorSchemeForm() {
        ThemeColorStore store = ThemeColorStore.get();
        this.darkBuf = toBuffer(store.effective(true));
        this.lightBuf = toBuffer(store.effective(false));
        this.showingDark = ThemeManager.get().isDark();

        Color[] init = showingDark ? darkBuf : lightBuf;
        this.fg = new PaletteSwatch(init[FG], "Foreground");
        this.bg = new PaletteSwatch(init[BG], "Background");
        this.selFg = new PaletteSwatch(init[SEL_FG], "Selection text");
        this.selBg = new PaletteSwatch(init[SEL_BG], "Selection background");
        for (int i = 0; i < 16; i++) {
            ansi[i] = new PaletteSwatch(init[ANSI_BASE + i], ansiName(i));
        }

        build();
    }

    /** The control to add to a form/tab. */
    public JPanel component() {
        return root;
    }

    /** Persists both schemes' working copies to {@link ThemeColorStore}. */
    public void commit() {
        captureInto(showingDark ? darkBuf : lightBuf);
        ThemeColorStore store = ThemeColorStore.get();
        store.setScheme(true, fromBuffer(true, darkBuf));
        store.setScheme(false, fromBuffer(false, lightBuf));
    }

    private void build() {
        JComboBox<String> scheme = new JComboBox<>(new String[]{"Dark", "Light"});
        scheme.setSelectedIndex(showingDark ? 0 : 1);
        JButton reset = new JButton("Reset to defaults");

        scheme.addActionListener(a -> {
            boolean wantDark = scheme.getSelectedIndex() == 0;
            if (wantDark == showingDark) {
                return;
            }
            captureInto(showingDark ? darkBuf : lightBuf);
            showingDark = wantDark;
            loadFrom(showingDark ? darkBuf : lightBuf);
        });
        reset.addActionListener(a -> {
            Color[] preset = toBuffer(showingDark ? ThemeColors.DARK : ThemeColors.LIGHT);
            System.arraycopy(preset, 0, showingDark ? darkBuf : lightBuf, 0, SLOTS);
            loadFrom(preset);
        });

        int row = 0;

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints t = new GridBagConstraints();
        t.gridx = 0;
        t.anchor = GridBagConstraints.WEST;
        t.insets = new Insets(0, 0, 0, 6);
        top.add(new JLabel("Scheme:"), t);
        t.gridx = 1;
        top.add(scheme, t);
        t.gridx = 2;
        t.weightx = 1;
        t.anchor = GridBagConstraints.EAST;
        t.insets = new Insets(0, 16, 0, 0);
        top.add(reset, t);
        addRow(row++, top, 2);

        addSwatchRow(row++, "Foreground:", fg);
        addSwatchRow(row++, "Background:", bg);
        addSwatchRow(row++, "Selection text:", selFg);
        addSwatchRow(row++, "Selection background:", selBg);

        JLabel ansiHeader = new JLabel("ANSI colors");
        ansiHeader.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        addRow(row++, ansiHeader, 2);
        addRow(row++, ansiGrid(), 2);

        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    }

    /** The 2×8 ANSI grid: column headers, then a "Normal" (0–7) and a "Bright" (8–15) row. */
    private JPanel ansiGrid() {
        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 3, 2, 3);

        for (int c = 0; c < 8; c++) {
            g.gridx = c + 1;
            g.gridy = 0;
            g.anchor = GridBagConstraints.CENTER;
            JLabel h = new JLabel(ANSI_NAMES[c]);
            h.setFont(h.getFont().deriveFont(h.getFont().getSize2D() - 1f));
            grid.add(h, g);
        }
        addAnsiRow(grid, g, 1, "Normal", 0);
        addAnsiRow(grid, g, 2, "Bright", 8);
        return grid;
    }

    private void addAnsiRow(JPanel grid, GridBagConstraints g, int gridy, String label, int base) {
        g.gridx = 0;
        g.gridy = gridy;
        g.anchor = GridBagConstraints.EAST;
        grid.add(new JLabel(label), g);
        g.anchor = GridBagConstraints.CENTER;
        for (int c = 0; c < 8; c++) {
            g.gridx = c + 1;
            grid.add(ansi[base + c].component(), g);
        }
    }

    private void addSwatchRow(int row, String label, PaletteSwatch swatch) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(3, 0, 3, 10);
        root.add(new JLabel(label), g);
        g.gridx = 1;
        g.weightx = 1;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(3, 0, 3, 0);
        root.add(swatch.component(), g);
    }

    private void addRow(int row, java.awt.Component comp, int width) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.gridwidth = width;
        g.weightx = 1;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(3, 0, 3, 0);
        root.add(comp, g);
    }

    /** Reads the live swatch colors into {@code buf}. */
    private void captureInto(Color[] buf) {
        buf[FG] = fg.color();
        buf[BG] = bg.color();
        buf[SEL_FG] = selFg.color();
        buf[SEL_BG] = selBg.color();
        for (int i = 0; i < 16; i++) {
            buf[ANSI_BASE + i] = ansi[i].color();
        }
    }

    /** Pushes {@code buf} into the live swatches. */
    private void loadFrom(Color[] buf) {
        fg.setColor(buf[FG]);
        bg.setColor(buf[BG]);
        selFg.setColor(buf[SEL_FG]);
        selBg.setColor(buf[SEL_BG]);
        for (int i = 0; i < 16; i++) {
            ansi[i].setColor(buf[ANSI_BASE + i]);
        }
    }

    private static Color[] toBuffer(ThemeColors t) {
        Color[] buf = new Color[SLOTS];
        buf[FG] = t.foreground();
        buf[BG] = t.background();
        buf[SEL_FG] = t.selectionFg();
        buf[SEL_BG] = t.selectionBg();
        for (int i = 0; i < 16; i++) {
            buf[ANSI_BASE + i] = t.ansi()[i];
        }
        return buf;
    }

    private static ThemeColors fromBuffer(boolean dark, Color[] buf) {
        Color[] ansi = new Color[16];
        System.arraycopy(buf, ANSI_BASE, ansi, 0, 16);
        return new ThemeColors(dark, buf[FG], buf[BG], buf[SEL_FG], buf[SEL_BG], ansi);
    }

    private static String ansiName(int i) {
        return (i >= 8 ? "Bright " : "") + ANSI_NAMES[i % 8];
    }
}
