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
package com.katmoda.jterm.ui.theme;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.katmoda.jterm.config.AppSettings;

import java.awt.Font;

/**
 * Supplies JediTerm with colors and font derived from the current {@link ThemeColors}.
 *
 * <p>Colors are read <i>live</i>: {@code getDefaultForeground}/{@code getDefaultBackground} and the
 * ANSI palette all reflect the {@link #theme} field, which {@link #setTheme} can swap at runtime.
 * The terminal panel reads these on every paint, so calling {@code setTheme} + repaint recolors a
 * running terminal — including already-printed text, because default-pen cells store no explicit
 * color (see {@link #getDefaultStyle()}) and are resolved against this provider when painted.</p>
 */
public final class JTermSettingsProvider extends DefaultSettingsProvider {

    private ThemeColors theme;
    private ColorPalette palette;
    private final Font font;

    public JTermSettingsProvider(ThemeColors theme) {
        this(theme, null, 0);
    }

    /**
     * @param fontFamily a specific installed font family, or {@code null}/blank to use the
     *                   application default ({@link AppSettings#getDefaultFontFamily()})
     * @param fontSize   point size, or {@code <= 0} to use the application default
     */
    public JTermSettingsProvider(ThemeColors theme, String fontFamily, int fontSize) {
        this.theme = theme;
        this.palette = new AnsiPalette(theme);
        AppSettings settings = AppSettings.get();
        int size = fontSize > 0 ? fontSize : settings.getDefaultFontSize();
        String family = (fontFamily != null && !fontFamily.isBlank())
                ? fontFamily : settings.getDefaultFontFamily();
        this.font = resolveFont(family, size);
    }

    /** Uses the requested family when it's installed; otherwise auto-picks a monospaced font. */
    private static Font resolveFont(String fontFamily, int size) {
        if (fontFamily != null && !fontFamily.isBlank() && isAvailable(fontFamily)) {
            return new Font(fontFamily, Font.PLAIN, size);
        }
        return resolveMonospacedFont(size);
    }

    private static boolean isAvailable(String family) {
        for (String name : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            if (name.equalsIgnoreCase(family)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picks a genuinely monospaced installed font (JediTerm warns about the logical
     * "Monospaced" family), falling back to the logical family if none are present.
     */
    private static Font resolveMonospacedFont(int size) {
        String[] preferred = {
                "JetBrains Mono", "DejaVu Sans Mono", "Liberation Mono",
                "Noto Sans Mono", "Consolas", "Menlo", "Courier New"
        };
        java.util.Set<String> available = new java.util.HashSet<>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames()));
        for (String family : preferred) {
            if (available.contains(family)) {
                return new Font(family, Font.PLAIN, size);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** Swaps the active theme; the next repaint re-resolves all terminal colors against it. */
    public void setTheme(ThemeColors theme) {
        this.theme = theme;
        this.palette = new AnsiPalette(theme);
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return palette;
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return terminalColor(theme.foreground());
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return terminalColor(theme.background());
    }

    /**
     * The style applied to cells written with no explicit color (the terminal's "default pen").
     * We return {@link TextStyle#EMPTY} (no colors) on purpose: a cell with no foreground/background
     * is resolved at paint time against {@link #getDefaultForeground()} / {@link #getDefaultBackground()},
     * so default-pen text follows live theme changes instead of baking in colors at write time.
     * (JediTerm's stock default is a fixed black-on-white style, which is why we override it.)
     *
     * <p>Note: with no colors stored, {@code StyleState.getDefault*} would NPE for inverse-video
     * cells; {@code ThemeAwareStyleState} supplies non-null theme colors there.</p>
     */
    @Override
    public TextStyle getDefaultStyle() {
        return TextStyle.EMPTY;
    }

    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(terminalColor(theme.selectionFg()), terminalColor(theme.selectionBg()));
    }

    @Override
    public Font getTerminalFont() {
        return font;
    }

    @Override
    public float getTerminalFontSize() {
        return font.getSize();
    }

    private static TerminalColor terminalColor(java.awt.Color c) {
        return new TerminalColor(c.getRed(), c.getGreen(), c.getBlue());
    }
}
