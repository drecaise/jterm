package com.katmoda.jterm.rdp.embed;

import com.sun.jna.Native;

import java.awt.Canvas;
import java.util.List;

/**
 * Linux/X11 embedder. Gets the canvas's native X11 window id and asks FreeRDP to reparent itself
 * into it via {@code /parent-window:<xid>}. FreeRDP draws directly into our window and tracks its
 * size, so no post-launch reparenting or manual resize is needed.
 */
final class X11Embedder implements WindowEmbedder {

    @Override
    public boolean canEmbed() {
        return true;
    }

    @Override
    public List<String> launchArgs(Canvas canvas) {
        long xid = Native.getComponentID(canvas);
        // FreeRDP accepts the parent window id in either decimal or 0x-hex form.
        return List.of("/parent-window:" + xid);
    }

    @Override
    public void attach(Canvas canvas, Process process) {
        // Nothing to do: FreeRDP already reparented itself via /parent-window.
    }

    @Override
    public void onResize(Canvas canvas, int width, int height) {
        // FreeRDP follows the parent window size itself (with /dynamic-resolution).
    }
}
