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
package com.katmoda.jterm.app;

import com.katmoda.jterm.ui.theme.FontResources;
import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.lang.reflect.Field;

/** Application entry point. */
public final class Main {

    /** App id used as the X11 WM_CLASS so GNOME/Wayland can match our {@code .desktop} file. */
    public static final String APP_ID = "jterm";

    private Main() {
    }

    public static void main(String[] args) {
        setLinuxApplicationName(APP_ID);
        FontResources.register();
        SwingUtilities.invokeLater(() -> {
            ThemeManager.get().install();
            new MainWindow().show();
        });
    }

    /**
     * On Linux/X11 (incl. XWayland) the window's WM_CLASS defaults to the main class name.
     * GNOME Shell uses WM_CLASS to match a {@code .desktop} file (via {@code StartupWMClass})
     * for the dash/overview/Alt-Tab icon, so we set a clean, stable value here. Requires the
     * jar manifest's {@code Add-Opens: java.desktop/sun.awt.X11} (see pom.xml); a no-op on
     * other platforms or if reflection is blocked.
     */
    private static void setLinuxApplicationName(String name) {
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Field field = toolkit.getClass().getDeclaredField("awtAppClassName");
            field.setAccessible(true);
            field.set(null, name);
        } catch (Exception ignored) {
            // Not X11, or module not opened — falls back to the default WM_CLASS.
        }
    }
}
