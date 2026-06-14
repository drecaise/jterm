package com.katmoda.jterm.rdp.embed;

import java.awt.Canvas;
import java.util.List;
import java.util.Locale;

/**
 * Strategy for embedding an external FreeRDP window inside a Swing {@link Canvas}.
 *
 * <p>Two integration points, because the platforms differ:</p>
 * <ul>
 *   <li>{@link #launchArgs(Canvas)} — extra FreeRDP CLI args that target the canvas <em>before</em>
 *       launch. On X11 this is {@code /parent-window:<xid>}; FreeRDP then draws straight into our
 *       window.</li>
 *   <li>{@link #attach(Canvas, Process)} — reparent the already-running process's window
 *       <em>after</em> launch. On Windows this is a {@code SetParent} call; X11 needs nothing here.</li>
 * </ul>
 *
 * <p>Platforms that cannot embed another process's window (macOS) return {@code false} from
 * {@link #canEmbed()}; the RDP tab then runs FreeRDP detached and shows a status panel.</p>
 *
 * <p>All implementations and the JNA calls they rely on live in this package and are referenced
 * only from the on-demand RDP UI, so they load lazily on first RDP use.</p>
 */
public interface WindowEmbedder {

    /** Whether this platform supports embedding the FreeRDP window into the canvas. */
    boolean canEmbed();

    /** FreeRDP CLI args needed to target {@code canvas} before launch; may be empty. */
    List<String> launchArgs(Canvas canvas);

    /** Reparent {@code process}'s window into {@code canvas} after launch; no-op where unneeded. */
    void attach(Canvas canvas, Process process) throws Exception;

    /** Reposition/resize the embedded child window to fill the canvas. */
    void onResize(Canvas canvas, int width, int height);

    /** Forward keyboard focus to the embedded window. Windows needs this; others no-op. */
    default void focus() {
    }

    /** Undo any input/window attachment before teardown. Default no-op. */
    default void detach() {
    }

    /** Pick the embedder for the current OS. */
    static WindowEmbedder forCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new Win32Embedder();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new NoopEmbedder();
        }
        return new X11Embedder();
    }
}
