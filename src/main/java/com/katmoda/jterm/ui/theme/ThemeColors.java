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

import java.awt.Color;

/**
 * Immutable color set for one theme (light or dark).
 *
 * <p>Phase 1 ships two hardcoded presets ({@link #LIGHT} / {@link #DARK}). Everything
 * that needs a terminal color reads it through this type, so a future phase can supply
 * a config-driven {@code ThemeColors} without touching any pane or renderer code.</p>
 *
 * @param dark          whether this is a dark theme (selects the FlatLaf base LaF)
 * @param foreground    default terminal foreground
 * @param background    default terminal background
 * @param selectionFg   selection foreground
 * @param selectionBg   selection background
 * @param ansi          the 16 ANSI colors (indices 0..15: normal 0..7, bright 8..15)
 */
public record ThemeColors(
        boolean dark,
        Color foreground,
        Color background,
        Color selectionFg,
        Color selectionBg,
        Color[] ansi
) {

    /** Dark preset (xterm-like on a near-black background). */
    public static final ThemeColors DARK = new ThemeColors(
            true,
            new Color(0xD0D0D0),
            new Color(0x1E1E1E),
            new Color(0xFFFFFF),
            new Color(0x264F78),
            new Color[]{
                    new Color(0x2E2E2E), new Color(0xCC6666), new Color(0x8BC34A), new Color(0xD7BA7D),
                    new Color(0x6A9FD8), new Color(0xB48EAD), new Color(0x56B6C2), new Color(0xC8C8C8),
                    new Color(0x6E6E6E), new Color(0xE06C75), new Color(0xB5E48C), new Color(0xF0D98C),
                    new Color(0x82AAFF), new Color(0xC792EA), new Color(0x80CBC4), new Color(0xFFFFFF)
            }
    );

    /** Light preset (dark text on a near-white background). */
    public static final ThemeColors LIGHT = new ThemeColors(
            false,
            new Color(0x2B2B2B),
            new Color(0xFBFBFB),
            new Color(0x000000),
            new Color(0xACCEF7),
            new Color[]{
                    new Color(0x3B3B3B), new Color(0xC4302B), new Color(0x2E7D32), new Color(0xA8730A),
                    new Color(0x1565C0), new Color(0x8E24AA), new Color(0x00838F), new Color(0xD8D8D8),
                    new Color(0x6E6E6E), new Color(0xE53935), new Color(0x43A047), new Color(0xC9920B),
                    new Color(0x1E88E5), new Color(0xAB47BC), new Color(0x00ACC1), new Color(0xFFFFFF)
            }
    );
}
