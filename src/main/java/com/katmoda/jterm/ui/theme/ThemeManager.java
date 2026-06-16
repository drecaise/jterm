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

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.katmoda.jterm.config.AppSettings;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the active {@link ThemeColors} and applies the matching FlatLaf
 * look-and-feel. Listeners are notified on every theme change so chrome
 * (tabs, sidebar, menus) can refresh via {@code updateComponentTreeUI}.
 *
 * <p>FlatLaf draws its own window decorations (custom title bar) so the app icon and title
 * appear consistently across platforms — notably on GNOME, where the native title bar shows
 * no app icon. The icon is rendered beside the title text.</p>
 */
public final class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();

    private final List<Consumer<ThemeColors>> listeners = new CopyOnWriteArrayList<>();
    private ThemeColors current = ThemeColors.DARK;

    private ThemeManager() {
    }

    public static ThemeManager get() {
        return INSTANCE;
    }

    /** Installs the LaF for the current theme. Call once before building UI (before any frame). */
    public void install() {
        // Restore the persisted light/dark choice before any UI is built.
        current = AppSettings.get().isDarkTheme() ? ThemeColors.DARK : ThemeColors.LIGHT;
        // FlatLaf-drawn title bar so we control the window icon + title (GNOME shows neither
        // natively). Keep the menu bar below the title bar rather than embedded in it.
        System.setProperty("flatlaf.useWindowDecorations", "true");
        System.setProperty("flatlaf.menuBarEmbedded", "false");
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
        applyLaf();
    }

    public ThemeColors current() {
        return current;
    }

    public boolean isDark() {
        return current.dark();
    }

    public void setDark(boolean dark) {
        ThemeColors next = dark ? ThemeColors.DARK : ThemeColors.LIGHT;
        if (next == current) {
            return;
        }
        current = next;
        AppSettings.get().setDarkTheme(dark);
        AppSettings.get().save();
        applyLaf();
        for (Consumer<ThemeColors> l : listeners) {
            l.accept(current);
        }
    }

    public void toggle() {
        setDark(!current.dark());
    }

    /** Register a callback fired (on the EDT) whenever the theme changes. */
    public void addListener(Consumer<ThemeColors> listener) {
        listeners.add(listener);
    }

    private void applyLaf() {
        Runnable apply = () -> {
            if (current.dark()) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            // Re-apply our UI defaults after each setup() (setup resets UIManager).
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("TitlePane.showIcon", true);
            UIManager.put("TitlePane.showIconBesideTitle", true);
            // Larger title-bar icon (default 16); trim vertical margins so the bar height holds.
            UIManager.put("TitlePane.iconSize", new Dimension(22, 22));
            UIManager.put("TitlePane.iconMargins", new Insets(2, 8, 2, 8));
            // Darker tab strip so the (lighter) selected card tab stands out. Derive it from
            // Panel.background (which setup() resets to the active theme each time) rather than
            // TabbedPane.background — the latter is the key we override here, so reading it back
            // would feed our own persistent override in and the strip would never follow the theme.
            Color tabStrip = UIManager.getColor("Panel.background");
            if (tabStrip != null) {
                UIManager.put("TabbedPane.background", darken(tabStrip, 0.88));
            }
            FlatLightLaf.updateUI();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            SwingUtilities.invokeLater(apply);
        }
    }

    /**
     * Multiply RGB by {@code factor} (&lt;1 darkens), preserving alpha. Returns a
     * {@link ColorUIResource}: Swing only re-installs a component's color on a LaF change when the
     * current value is a {@code UIResource}, so a plain {@code Color} here would make the tab strip
     * stick on its first theme and never follow later toggles.
     */
    private static Color darken(Color c, double factor) {
        return new ColorUIResource(new Color(
                (int) Math.round(c.getRed() * factor),
                (int) Math.round(c.getGreen() * factor),
                (int) Math.round(c.getBlue() * factor),
                c.getAlpha()));
    }
}
