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
        FlatSVGIcon icon = new FlatSVGIcon("icons/app.svg", size, size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return image;
    }
}
