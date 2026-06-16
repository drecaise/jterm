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

import com.formdev.flatlaf.extras.FlatSVGIcon;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the application icon ({@code icons/app.svg}) to a set of raster sizes for the
 * window/taskbar. The OS/WM picks the closest size, so several are provided.
 */
final class AppIcon {

    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256};

    // Title bars pick the small rasters; the GNOME dash / Windows taskbar use the large ones.
    // The SVG fills its box edge-to-edge (no padding), which looks right in the dash/taskbar but
    // a touch large in the title bar — so the small sizes are inset slightly to shrink only there.
    private static final int TITLEBAR_MAX_SIZE = 24;
    private static final double TITLEBAR_INSET_FRACTION = 0.15;

    private AppIcon() {
    }

    /** Icon images at common sizes, largest last. */
    static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int size : SIZES) {
            images.add(render(size));
        }
        return images;
    }

    /** A single rendering at the requested size. */
    static Image render(int size) {
        int inset = size <= TITLEBAR_MAX_SIZE ? (int) Math.round(size * TITLEBAR_INSET_FRACTION) : 0;
        int glyph = size - inset * 2;
        FlatSVGIcon icon = new FlatSVGIcon("icons/app.svg", glyph, glyph);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        icon.paintIcon(null, g, inset, inset);
        g.dispose();
        return image;
    }
}
