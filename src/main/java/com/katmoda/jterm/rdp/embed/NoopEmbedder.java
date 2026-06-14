package com.katmoda.jterm.rdp.embed;

import java.awt.Canvas;
import java.util.List;

/**
 * macOS embedder — a no-op. Cocoa provides no way to embed another process's window into our own,
 * so the RDP tab runs FreeRDP as a detached window and shows an in-tab status panel instead of a
 * canvas. {@link #canEmbed()} returns {@code false} so callers take the detached path.
 */
final class NoopEmbedder implements WindowEmbedder {

    @Override
    public boolean canEmbed() {
        return false;
    }

    @Override
    public List<String> launchArgs(Canvas canvas) {
        return List.of();
    }

    @Override
    public void attach(Canvas canvas, Process process) {
        // Detached window; nothing to embed.
    }

    @Override
    public void onResize(Canvas canvas, int width, int height) {
    }
}
