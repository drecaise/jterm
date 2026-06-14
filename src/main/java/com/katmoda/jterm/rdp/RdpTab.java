package com.katmoda.jterm.rdp;

import com.katmoda.jterm.rdp.embed.WindowEmbedder;
import com.katmoda.jterm.session.RdpSessionConfig;
import com.katmoda.jterm.ui.TabContent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;

/**
 * A full-tab RDP desktop, backed by an external FreeRDP process (see {@link RdpProcess}). This is
 * the single entry point the rest of the app references for RDP; everything that touches FreeRDP
 * or the JNA embedding code lives here and in the {@code rdp}/{@code rdp.embed} packages, so those
 * classes load only when an RDP session is first opened.
 *
 * <p>On Linux/X11 and Windows the FreeRDP window is embedded into a {@link Canvas} that fills the
 * tab; on macOS (where embedding a foreign window is impossible) FreeRDP runs detached and the tab
 * shows a status panel. The same status panel is used for the "connecting", "disconnected" and
 * "error" states, always offering Reconnect / Close.</p>
 *
 * <p>RDP always occupies its own tab (never a split pane) — a whole remote desktop in a grid cell
 * makes no sense — so this implements {@link TabContent} rather than the grid's
 * {@code GridContent}.</p>
 */
public final class RdpTab extends JPanel implements TabContent {

    private final RdpSessionConfig cfg;
    private final Supplier<char[]> passwordSupplier;
    private final WindowEmbedder embedder;

    private final Canvas canvas; // null on platforms that can't embed (macOS)
    private Runnable onClose; // set after the tab is inserted (it needs its own tab index)
    private RdpProcess process;
    private boolean connecting;
    private boolean disposed;

    public RdpTab(RdpSessionConfig cfg, Supplier<char[]> passwordSupplier) {
        super(new BorderLayout());
        this.cfg = cfg;
        this.passwordSupplier = passwordSupplier;
        this.embedder = WindowEmbedder.forCurrentOs();

        if (embedder.canEmbed()) {
            this.canvas = new Canvas();
            canvas.setBackground(java.awt.Color.BLACK);
            canvas.setFocusable(true);
            add(canvas, BorderLayout.CENTER);
            // Connect once the canvas is realized and sized; later resizes follow the embedder.
            canvas.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    System.out.println("RDP-EMBED: canvas resized " + canvas.getWidth() + "x"
                            + canvas.getHeight() + " connecting=" + connecting
                            + " process=" + (process != null));
                    if (connecting || process != null) {
                        embedder.onResize(canvas, canvas.getWidth(), canvas.getHeight());
                    } else if (canvas.isShowing() && canvas.getWidth() > 0 && canvas.getHeight() > 0) {
                        connect();
                    }
                }
            });
            canvas.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    canvas.requestFocusInWindow();
                    embedder.focus(); // hand keyboard focus to the embedded FreeRDP window
                }
            });
            // Forward focus to the embedded window whenever the canvas itself gains it.
            canvas.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    embedder.focus();
                }
            });
        } else {
            this.canvas = null;
            showStatus("Connecting to " + displayTitle() + " in a separate window…", false);
            // No canvas to wait for; start the detached process right away.
            SwingUtilities.invokeLater(this::connect);
        }
    }

    /** Sets the action that closes this tab (wired by the main window once the tab is inserted). */
    public void setCloseAction(Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Give keyboard focus to the embedded remote desktop. Called when this tab becomes active or the
     * window is re-activated — clicks on the embedded native window are consumed by it, so AWT never
     * sees them and can't re-focus on its own.
     */
    public void focusEmbedded() {
        if (canvas != null && process != null) {
            canvas.requestFocusInWindow();
            embedder.focus();
        }
    }

    /** Human-readable label for the tab title. */
    public String displayTitle() {
        if (cfg.getName() != null && !cfg.getName().isBlank()) {
            return cfg.getName();
        }
        String user = cfg.getUser();
        return (user != null && !user.isBlank() ? user + "@" : "") + cfg.getHost();
    }

    private void connect() {
        if (disposed || connecting || process != null) {
            return;
        }
        connecting = true;
        char[] password = passwordSupplier != null ? passwordSupplier.get() : null;
        List<String> args = (canvas != null) ? embedder.launchArgs(canvas) : List.of();
        int w = (canvas != null) ? canvas.getWidth() : 0;
        int h = (canvas != null) ? canvas.getHeight() : 0;

        new Thread(() -> {
            try {
                RdpProcess proc = RdpProcess.start(cfg, password, args, w, h, this::onProcessExit);
                if (canvas != null) {
                    embedder.attach(canvas, proc.process()); // Win32 polls for the window here
                }
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    if (disposed) {
                        proc.destroyForcibly();
                        return;
                    }
                    process = proc;
                    if (canvas == null) {
                        showStatus("Connected to " + displayTitle() + " (separate window).", false);
                    } else {
                        // Re-fit to the canvas's settled size and grab keyboard focus.
                        embedder.onResize(canvas, canvas.getWidth(), canvas.getHeight());
                        canvas.requestFocusInWindow();
                        embedder.focus();
                    }
                });
            } catch (RdpUnavailableException e) {
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    showStatus(e.getMessage(), true);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    showStatus("Failed to start RDP: " + e.getMessage(), true);
                });
            }
        }, "rdp-launch-" + cfg.getHost()).start();
    }

    /** Off-EDT callback when FreeRDP exits. */
    private void onProcessExit(int exitCode) {
        SwingUtilities.invokeLater(() -> {
            if (disposed) {
                return;
            }
            // Read the captured FreeRDP output before dropping the process reference, so the panel
            // can show why it failed (cert mismatch, auth failure, …) instead of a bare message.
            String detail = (process != null) ? process.recentOutput() : null;
            process = null;
            String msg = exitCode == 0
                    ? "Disconnected from " + displayTitle() + "."
                    : "Connection to " + displayTitle() + " ended (FreeRDP exit code " + exitCode + ").";
            showStatus(msg, true, detail);
        });
    }

    private void showStatus(String message, boolean reconnectable) {
        showStatus(message, reconnectable, null);
    }

    /**
     * Swap the center component for a status panel with a message and Reconnect / Close buttons,
     * optionally showing {@code detail} (the tail of FreeRDP's output) in a scrollable area so a
     * failure's cause is visible in-app. On embedding platforms a reconnect re-arms the canvas
     * listener so the next resize reconnects.
     */
    private void showStatus(String message, boolean reconnectable, String detail) {
        if (canvas != null) {
            remove(canvas);
        }
        removeAll();

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        JLabel title = new JLabel(displayTitle());
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.putClientProperty("FlatLaf.styleClass", "h2");

        JLabel msg = new JLabel(message, SwingConstants.CENTER);
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        if (reconnectable) {
            JButton reconnect = new JButton("Reconnect");
            reconnect.addActionListener(e -> reconnect());
            buttons.add(reconnect);
        }
        JButton close = new JButton("Close tab");
        close.addActionListener(e -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        buttons.add(close);
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(Box.createVerticalGlue());
        box.add(title);
        box.add(Box.createVerticalStrut(8));
        box.add(msg);
        box.add(Box.createVerticalStrut(16));
        box.add(buttons);
        if (detail != null && !detail.isBlank()) {
            JTextArea log = new JTextArea(detail);
            log.setEditable(false);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            JScrollPane scroll = new JScrollPane(log);
            scroll.setPreferredSize(new Dimension(640, 180));
            scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
            scroll.setAlignmentX(Component.CENTER_ALIGNMENT);
            box.add(Box.createVerticalStrut(16));
            box.add(new JLabel("FreeRDP output:"));
            box.add(Box.createVerticalStrut(4));
            box.add(scroll);
        }
        box.add(Box.createVerticalGlue());

        add(box, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void reconnect() {
        if (disposed || connecting || process != null) {
            return;
        }
        if (canvas != null) {
            removeAll();
            add(canvas, BorderLayout.CENTER);
            revalidate();
            repaint();
            // A reconnect after the canvas is already sized won't fire componentResized, so kick it.
            if (canvas.isShowing() && canvas.getWidth() > 0 && canvas.getHeight() > 0) {
                SwingUtilities.invokeLater(this::connect);
            }
        } else {
            showStatus("Connecting to " + displayTitle() + " in a separate window…", false);
            SwingUtilities.invokeLater(this::connect);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1024, 700);
    }

    @Override
    public void dispose() {
        disposed = true;
        embedder.detach();
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }
}
