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

import com.jediterm.core.Color;
import com.jediterm.terminal.emulator.ColorPalette;

/**
 * A {@link ColorPalette} backed by a {@link ThemeColors} set: indices 0..15 come
 * from the theme's ANSI colors; indices 16..255 follow the standard xterm 256-color
 * cube + grayscale ramp so full-color programs render correctly.
 */
final class AnsiPalette extends ColorPalette {

    private final Color[] ansi16;

    AnsiPalette(ThemeColors theme) {
        java.awt.Color[] src = theme.ansi();
        this.ansi16 = new Color[16];
        for (int i = 0; i < 16; i++) {
            this.ansi16[i] = toJediColor(src[i]);
        }
    }

    @Override
    protected Color getForegroundByColorIndex(int colorIndex) {
        return colorFor(colorIndex);
    }

    @Override
    protected Color getBackgroundByColorIndex(int colorIndex) {
        return colorFor(colorIndex);
    }

    private Color colorFor(int index) {
        if (index < 0) {
            return ansi16[0];
        }
        if (index < 16) {
            return ansi16[index];
        }
        if (index < 232) {
            int i = index - 16;
            int r = level((i / 36) % 6);
            int g = level((i / 6) % 6);
            int b = level(i % 6);
            return new Color(r, g, b);
        }
        if (index < 256) {
            int v = 8 + (index - 232) * 10;
            return new Color(v, v, v);
        }
        return ansi16[15];
    }

    private static int level(int step) {
        return step == 0 ? 0 : 55 + step * 40;
    }

    static Color toJediColor(java.awt.Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue());
    }
}
