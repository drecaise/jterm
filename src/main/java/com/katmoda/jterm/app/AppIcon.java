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
