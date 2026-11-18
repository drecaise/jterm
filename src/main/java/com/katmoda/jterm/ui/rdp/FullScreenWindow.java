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
package com.katmoda.jterm.ui.rdp;

import com.formdev.flatlaf.util.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * The window a full-screen RDP desktop is shown in: the pane's desktop view plus a
 * {@link FullScreenBar}, on one monitor. Only the view moves here; the {@link RdpPane} stays in its
 * grid cell (design: {@code docs/superpowers/specs/2026-11-10-rdp-full-screen-design.md}).
 *
 * <p>How it covers the monitor differs per OS, each choice measured in that design's spike:</p>
 * <ul>
 *   <li><b>Windows:</b> an undecorated frame with the monitor's bounds. Not
 *       {@code GraphicsDevice.setFullScreenWindow}: the JDK's {@code Win32FSWindowAdapter} iconifies a
 *       full-screen frame whenever another application takes focus, so clicking a window on a second
 *       monitor minimized the desktop. Windows hides the taskbar for a window that covers its monitor.</li>
 *   <li><b>Linux:</b> {@code setFullScreenWindow} (EWMH {@code _NET_WM_STATE_FULLSCREEN}). An
 *       undecorated frame is no option: mutter ignores its requested position and puts it on
 *       whichever monitor it likes.</li>
 *   <li><b>macOS:</b> native full screen in its own Space, through the internal
 *       {@code com.apple.eawt} API (needs {@code --add-exports java.desktop/com.apple.eawt}, set in the
 *       jar manifest and the DMG's launcher). The JDK's mode blocks Cmd+Tab and an undecorated
 *       frame leaves the menu bar and Dock showing. Without the export it falls back to the JDK's
 *       mode, logged.</li>
 * </ul>
 *
 * <p>It is never always-on-top, and closing it through the OS (Alt+F4, the taskbar, leaving a macOS
 * Space by the green button) is reported to the owner as "leave full screen", never as a
 * disconnect.</p>
 */
final class FullScreenWindow {

    private static final Logger LOG = LoggerFactory.getLogger(FullScreenWindow.class);

    /** Root-pane client property naming the {@link RdpPane} whose desktop this window shows. */
    static final String PANE_PROPERTY = "jterm.rdpFullScreenPane";

    /** How long to wait for macOS's exit animation before disposing the window anyway. */
    private static final int MAC_EXIT_TIMEOUT_MS = 3_000;

    /** Linux: how long the window manager gets to place the window before it goes full screen (spike). */
    private static final int EXCLUSIVE_SETTLE_MS = 500;

    private enum Mechanism { BORDERLESS, EXCLUSIVE, MAC_NATIVE }

    /** Told when full screen ended outside jterm's own exit (the OS closed or left it). EDT. */
    interface Owner {
        void fullScreenEnded();
    }

    private final JFrame frame;
    private final FullScreenBar bar;
    private final Owner owner;
    private Mechanism mechanism;
    private GraphicsDevice device;
    /** macOS: whether the window is in its full-screen Space right now. */
    private boolean macInFullScreen;
    /** jterm's own exit has begun; later OS notifications are not reported to the owner. */
    private boolean closing;

    FullScreenWindow(RdpPane pane, String title, List<Image> icons, JComponent view, FullScreenBar bar, Owner owner) {
        this.bar = bar;
        this.owner = owner;
        frame = new JFrame(title);
        frame.setIconImages(icons);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.getRootPane().putClientProperty(PANE_PROPERTY, pane);
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Color.BLACK);
        content.add(view, BorderLayout.CENTER);
        frame.setContentPane(content);
        frame.getContentPane().setBackground(Color.BLACK);
        JLayeredPane layers = frame.getLayeredPane();
        layers.add(bar, JLayeredPane.PALETTE_LAYER);
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                placeBar();
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!closing) {
                    owner.fullScreenEnded(); // Alt+F4 or the taskbar: leave full screen, stay connected
                }
            }
        });
    }

    /** The {@link RdpPane} shown by the full-screen window holding {@code c}, or {@code null}. */
    static RdpPane paneOf(Component c) {
        Window w = c instanceof Window win ? win : c == null ? null : SwingUtilities.getWindowAncestor(c);
        return w instanceof JFrame f && f.getRootPane().getClientProperty(PANE_PROPERTY) instanceof RdpPane p ? p : null;
    }

    /** Covers {@code device} with the window. EDT. */
    void open(GraphicsDevice device) {
        this.device = device;
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        // macOS needs the exit listener as much as the toggle: without it, leaving through the green
        // button would strand the desktop in a plain window. No listener, no native full screen.
        mechanism = SystemInfo.isWindows ? Mechanism.BORDERLESS
                : SystemInfo.isMacOS && MacFullScreen.available() && MacFullScreen.listen(frame, this::macChanged)
                ? Mechanism.MAC_NATIVE
                : Mechanism.EXCLUSIVE;
        switch (mechanism) {
            case BORDERLESS -> {
                frame.setUndecorated(true);
                frame.setBounds(bounds);
                // Win+Shift+Arrow moves the window to another monitor at its old size: fit it there.
                frame.addPropertyChangeListener("graphicsConfiguration", e -> SwingUtilities.invokeLater(this::refit));
                frame.setVisible(true);
                frame.toFront();
            }
            case EXCLUSIVE -> {
                // The sequence the spike measured on GNOME: map a small window on the target monitor,
                // then go full screen once the window manager has placed it. Mapped at the monitor's
                // size, mutter treats it like the borderless case and puts it on whichever monitor it
                // likes; switched before it is placed, the full-screen request is lost.
                frame.setUndecorated(true);
                frame.setBounds(bounds.x + 50, bounds.y + 50,
                        Math.min(600, bounds.width - 100), Math.min(400, bounds.height - 100));
                frame.setVisible(true);
                Timer t = new Timer(EXCLUSIVE_SETTLE_MS, e -> {
                    if (closing || !frame.isDisplayable()) {
                        return;
                    }
                    device.setFullScreenWindow(frame);
                    frame.validate();
                    placeBar();
                    frame.repaint();
                    LOG.info("full screen on {} {}: window {}, in full screen {}", device.getIDstring(), bounds,
                            frame.getBounds(), device.getFullScreenWindow() == frame);
                });
                t.setRepeats(false);
                t.start();
            }
            case MAC_NATIVE -> {
                // A Space opens on the monitor the window is on, so start there; decorated, because
                // only a titled window can enter native full screen.
                frame.getRootPane().putClientProperty("apple.awt.fullscreenable", Boolean.TRUE);
                frame.setBounds(bounds.x + bounds.width / 8, bounds.y + bounds.height / 8,
                        bounds.width * 3 / 4, bounds.height * 3 / 4);
                frame.setVisible(true);
                SwingUtilities.invokeLater(() -> MacFullScreen.toggle(frame));
            }
        }
        placeBar();
        bar.start(frame);
    }

    /**
     * Ends full screen and disposes the window. The caller has already moved the view back into its
     * pane (adding it there removes it from this window). EDT; idempotent.
     */
    void close() {
        if (closing) {
            return;
        }
        closing = true;
        bar.stop();
        switch (mechanism == null ? Mechanism.BORDERLESS : mechanism) {
            case BORDERLESS -> frame.dispose();
            case EXCLUSIVE -> {
                if (device != null && device.getFullScreenWindow() == frame) {
                    device.setFullScreenWindow(null);
                }
                frame.dispose();
            }
            case MAC_NATIVE -> {
                if (!macInFullScreen) {
                    frame.dispose();
                    return;
                }
                // Dispose once the Space has closed; disposing mid-animation can leave an empty Space.
                MacFullScreen.toggle(frame);
                Timer t = new Timer(MAC_EXIT_TIMEOUT_MS, e -> frame.dispose());
                t.setRepeats(false);
                t.start();
            }
        }
    }

    private void macChanged(boolean entered) {
        macInFullScreen = entered;
        if (!entered) {
            if (closing) {
                frame.dispose();
            } else {
                owner.fullScreenEnded(); // the green button or Ctrl+Cmd+F
            }
        }
    }

    /** Whether the window is in front (so a focus change came from inside it). */
    boolean isActive() {
        return frame.isActive();
    }

    private void refit() {
        if (closing || !frame.isDisplayable()) {
            return;
        }
        Rectangle b = frame.getGraphicsConfiguration().getBounds();
        if (!b.equals(frame.getBounds())) {
            frame.setBounds(b);
        }
    }

    private void placeBar() {
        JLayeredPane layers = frame.getLayeredPane();
        java.awt.Dimension d = bar.getPreferredSize();
        int w = Math.min(d.width, layers.getWidth());
        bar.setBounds(Math.max(0, (layers.getWidth() - w) / 2), 0, w, d.height);
    }

    /**
     * macOS native full screen through {@code com.apple.eawt}, reached reflectively because the
     * package isn't exported. Everything fails closed: if any part is missing, {@link #available()}
     * says so once (logged) and the window uses the JDK's mode instead.
     */
    private static final class MacFullScreen {
        private static final Object APP;
        private static final Method TOGGLE;
        private static final Method ADD_LISTENER;
        private static final Class<?> LISTENER;

        static {
            Object app = null;
            Method toggle = null;
            Method add = null;
            Class<?> listener = null;
            if (SystemInfo.isMacOS) {
                try {
                    Class<?> appClass = Class.forName("com.apple.eawt.Application");
                    app = appClass.getMethod("getApplication").invoke(null);
                    toggle = appClass.getMethod("requestToggleFullScreen", Window.class);
                    listener = Class.forName("com.apple.eawt.FullScreenListener");
                    add = Class.forName("com.apple.eawt.FullScreenUtilities")
                            .getMethod("addFullScreenListenerTo", Window.class, listener);
                } catch (Throwable t) {
                    LOG.warn("macOS native full screen is unavailable ({}); using the JDK's full-screen mode, "
                            + "which blocks Cmd+Tab. The launcher needs --add-exports "
                            + "java.desktop/com.apple.eawt=ALL-UNNAMED.", t.toString());
                    app = null;
                }
            }
            APP = app;
            TOGGLE = toggle;
            ADD_LISTENER = add;
            LISTENER = listener;
        }

        static boolean available() {
            return APP != null;
        }

        static void toggle(Window w) {
            try {
                TOGGLE.invoke(APP, w);
            } catch (Throwable t) {
                LOG.warn("macOS full-screen toggle failed", t);
            }
        }

        /**
         * Calls {@code onChange} on the EDT with true on entering, false on leaving full screen.
         * Returns whether the listener could be installed.
         */
        static boolean listen(Window w, java.util.function.Consumer<Boolean> onChange) {
            Object proxy;
            try {
                proxy = Proxy.newProxyInstance(LISTENER.getClassLoader(), new Class<?>[]{LISTENER},
                    (p, method, args) -> {
                        switch (method.getName()) {
                            case "windowEnteredFullScreen" -> SwingUtilities.invokeLater(() -> onChange.accept(true));
                            case "windowExitedFullScreen" -> SwingUtilities.invokeLater(() -> onChange.accept(false));
                            case "hashCode" -> {
                                return System.identityHashCode(p);
                            }
                            case "equals" -> {
                                return p == args[0];
                            }
                            case "toString" -> {
                                return "jterm full-screen listener";
                            }
                            default -> {
                            }
                        }
                        return null;
                    });
            } catch (Throwable t) {
                LOG.warn("could not create the macOS full-screen listener; using the JDK's full-screen mode", t);
                return false;
            }
            try {
                ADD_LISTENER.invoke(null, w, proxy);
                return true;
            } catch (Throwable t) {
                LOG.warn("could not listen for macOS full-screen changes; using the JDK's full-screen mode", t);
                return false;
            }
        }
    }
}
