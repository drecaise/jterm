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

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

/**
 * Registers fonts bundled under {@code resources/fonts/} with the {@link GraphicsEnvironment}
 * so they are usable by family name everywhere (terminal widgets and the font pickers).
 *
 * <p>{@link #DEFAULT_TERMINAL_FONT_FAMILY} is the family the app falls back to when a session
 * has no explicit font; it is bundled here so the default looks identical on every machine.</p>
 */
public final class FontResources {

    /** Family name of the bundled default terminal font ({@code fonts/AdwaitaMonoNerdFontMono-Regular.ttf}). */
    public static final String DEFAULT_TERMINAL_FONT_FAMILY = "AdwaitaMono Nerd Font Mono";

    private static final String[] BUNDLED = {"fonts/AdwaitaMonoNerdFontMono-Regular.ttf"};

    private FontResources() {
    }

    /** Loads and registers every bundled font; safe to call once at startup. Best-effort. */
    public static void register() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String resource : BUNDLED) {
            try (InputStream in = FontResources.class.getClassLoader().getResourceAsStream(resource)) {
                if (in != null) {
                    ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
                }
            } catch (Exception ignored) {
                // A missing/invalid bundled font shouldn't stop startup; the monospaced
                // auto-pick fallback in JTermSettingsProvider still applies.
            }
        }
    }
}
