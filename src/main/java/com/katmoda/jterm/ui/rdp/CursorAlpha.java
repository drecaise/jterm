package com.katmoda.jterm.ui.rdp;

/**
 * Makes a remote cursor's alpha channel all-or-nothing for toolkits whose custom cursors are 1-bit
 * masked.
 *
 * <p>On Windows ({@code WCustomCursor}) and X11 ({@code X11CustomCursor}) AWT treats a pixel as
 * transparent only when its alpha is exactly 0. Every other pixel becomes fully opaque, and
 * Windows also draws the colour over black first. Windows' pointer shadow, a soft translucent black
 * outline that arrives in the cursor bitmap the server sends, therefore showed up as a solid black
 * blob around the arrow. Pixels below half alpha are dropped and the rest made opaque, which is how
 * a masked cursor would have drawn them. macOS and Wayland keep real alpha and are left alone.
 */
final class CursorAlpha {

    /** Pixels with less alpha than this are dropped and the rest made opaque. */
    static final int THRESHOLD = 0x80;

    private CursorAlpha() {
    }

    /** Rewrites {@code argb} (non-premultiplied ARGB) in place to alpha 0 or 255. */
    static void binarize(int[] argb) {
        for (int i = 0; i < argb.length; i++) {
            int a = argb[i] >>> 24;
            argb[i] = a < THRESHOLD ? 0 : argb[i] | 0xFF000000;
        }
    }
}
