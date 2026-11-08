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

import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.security.CredentialResolver;
import com.katmoda.jterm.session.RdpSessionConfig;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.terminal.rdp.OpenH264;
import com.katmoda.jterm.terminal.rdp.RdpConnection;
import com.katmoda.jterm.terminal.rdp.RdpHelperLocator;
import com.katmoda.jterm.terminal.rdp.RdpKeyMap;
import com.katmoda.jterm.terminal.rdp.RdpKnownHosts;
import com.katmoda.jterm.terminal.rdp.RdpProtocol;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.component.DialogFocus;
import com.katmoda.jterm.ui.component.DropHighlighter;
import com.katmoda.jterm.ui.pane.PaneTitle;
import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.theme.ThemeColors;
import com.formdev.flatlaf.util.UIScale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A remote desktop in a {@code PaneGrid} cell, driven by an out-of-process
 * {@code jterm-rdp-helper} through {@link RdpConnection}.
 *
 * <p>The pane owns the connection lifecycle: it connects itself once placed ({@link #start}),
 * resolves the password on the EDT (vault, else prompt), pins certificates through
 * {@link RdpKnownHosts}, and on a disconnect stays in place with Reconnect / Close — it never
 * removes itself, except through Close (which fires the grid's content-ended callback).</p>
 *
 * <p>Threading: frames are copied into the image on the connection's reader thread under the
 * view's lock; everything else is marshalled to the EDT. A {@link #generation} counter discards
 * late callbacks from a connection that has been replaced by a reconnect.</p>
 */
public final class RdpPane extends JPanel implements GridContent {

    private static final Logger LOG = LoggerFactory.getLogger(RdpPane.class);
    private static final String CARD_VIEW = "view";
    private static final String CARD_MESSAGE = "message";

    /** What the pane needs from the application. */
    public record Services(CredentialResolver credentials, RdpKnownHosts knownHosts,
                           Supplier<RdpHelperLocator.Result> helper) {
    }

    private final RdpSessionConfig cfg;
    private final Services services;
    private final DesktopView view = new DesktopView();
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JPanel center = new JPanel(new CardLayout());
    private final JLabel status = new JLabel();
    private final JButton reconnect = new JButton("Reconnect");
    private final JButton close = new JButton("Close");
    private final JButton ctrlAltDel = new JButton("Ctrl+Alt+Del");
    private final DropHighlighter dropHighlighter = new DropHighlighter(this);
    private final Timer resizeDebounce = new Timer(300, e -> requestRemoteResize());

    private RdpConnection connection;
    /** The current connect attempt's callbacks; owns that attempt's clipboard bridge. */
    private Listener listener;
    private int generation;
    private boolean connected;
    private boolean closed;
    private volatile boolean unicodeInput;
    /** A typed password the user asked to remember, saved once the server accepts it. */
    private char[] pendingRemember;
    private Runnable onFocus;
    private Runnable onEnded;
    private Runnable onTitleChanged;
    /** The user's runtime-only name for this tab (already sanitised), or null for the saved name. */
    private String customName;

    public RdpPane(RdpSessionConfig cfg, Services services) {
        super(new BorderLayout());
        this.cfg = cfg;
        this.services = services;

        message.setForeground(new Color(0xC8C8C8));
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.setBackground(Color.BLACK);
        messagePanel.add(message, BorderLayout.CENTER);
        center.add(view, CARD_VIEW);
        center.add(messagePanel, CARD_MESSAGE);
        add(center, BorderLayout.CENTER);
        add(buildBar(), BorderLayout.SOUTH);

        resizeDebounce.setRepeats(false);
        view.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIT_TO_TAB) {
                    resizeDebounce.restart();
                }
                view.repaint();
            }
        });
        showMessage("Connecting to " + cfg.hostLabel() + "…");
    }

    private JComponent buildBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new javax.swing.BoxLayout(bar, javax.swing.BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        status.setIcon(IconLibrary.get().icon(tabIconId(), 16));
        status.setToolTipText("Right-click to rename this tab");
        status.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                popup(e);
            }

            private void popup(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = new JPopupMenu();
                    JMenuItem rename = new JMenuItem("Rename…");
                    rename.addActionListener(a -> promptRename());
                    menu.add(rename);
                    menu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
        for (JButton b : new JButton[]{ctrlAltDel, reconnect, close}) {
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
        }
        ctrlAltDel.setToolTipText("Send Ctrl+Alt+Del to the remote desktop");
        ctrlAltDel.addActionListener(e -> sendCtrlAltDel());
        reconnect.addActionListener(e -> reconnect());
        close.addActionListener(e -> {
            closeContent();
            if (onEnded != null) {
                onEnded.run(); // the grid removes the cell
            }
        });
        bar.add(status);
        bar.add(Box.createHorizontalGlue());
        bar.add(ctrlAltDel);
        bar.add(reconnect);
        bar.add(close);
        updateBar();
        return bar;
    }

    private void updateBar() {
        String who = (!cfg.getUser().isBlank() ? cfg.getUser() + "@" : "") + cfg.hostLabel();
        status.setText(who + (connected ? "" : " — not connected"));
        ctrlAltDel.setVisible(connected);
        reconnect.setVisible(!connected && connection == null);
        close.setVisible(!connected && connection == null);
    }

    private void showMessage(String text) {
        message.setText("<html><div style='text-align:center'>"
                + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</div></html>");
        ((CardLayout) center.getLayout()).show(center, CARD_MESSAGE);
    }

    // ------------------------------------------------------------------------------ lifecycle

    /** Connects for the first time. Call on the EDT once the pane is placed. */
    public void start() {
        // Let the layout settle first so a fit-to-pane session asks for the real pane size.
        SwingUtilities.invokeLater(() -> connect(null));
    }

    private void reconnect() {
        connect(null);
    }

    /**
     * The desktop gained focus: whoever copied something locally is about to paste, and the JDK does
     * not announce a new copy of the same kind, so tell the server again (see
     * {@link RdpClipboardBridge}).
     */
    private void refreshClipboard() {
        Listener l = listener;
        if (l != null) {
            l.refreshClipboard();
        }
    }

    /** EDT. {@code retryError} non-null means "the last password was rejected: prompt again". */
    private void connect(String retryError) {
        if (closed) {
            return;
        }
        RdpHelperLocator.Result located = services.helper().get();
        if (located instanceof RdpHelperLocator.NotFound nf) {
            showMessage(nf.reason());
            updateBar();
            return;
        }
        Path helper = ((RdpHelperLocator.Found) located).executable();

        showMessage(retryError != null ? retryError : "Connecting to " + cfg.hostLabel() + "…");
        CredentialResolver.RdpPassword pw = services.credentials().resolveRdpPassword(cfg, retryError);
        if (pw == null) {
            showMessage("Connection cancelled.");
            updateBar();
            return;
        }
        clearPendingRemember();
        if (pw.remember()) {
            pendingRemember = pw.password().clone();
        }

        Dimension size = requestedSize();
        int myGeneration = ++generation;
        RdpConnection.Target target = new RdpConnection.Target(cfg.getHost(), cfg.getPort(),
                cfg.getUser(), cfg.getDomain(), pw.password(),
                services.knownHosts().pinsForConnect(cfg.getHost(), cfg.getPort()),
                size.width, size.height, scalePercent(),
                RdpProtocol.clipboardFlags(cfg.isRedirectClipboard(),
                        cfg.isRedirectClipboard() && cfg.isClipboardToRemote()),
                OpenH264.libraryDir(AppSettings.get().isOpenH264Enabled(), OpenH264.defaultDir()));
        try {
            listener = new Listener(myGeneration);
            connection = RdpConnection.start(helper, target, listener);
            listener.attach(connection);
        } catch (Exception e) {
            listener = null;
            Arrays.fill(pw.password(), '\0');
            clearPendingRemember();
            connection = null;
            showMessage("Could not start the RDP helper.");
            updateBar();
            ErrorDialog.show(this, "RDP", "Could not start the RDP helper:", e);
            return;
        }
        updateBar();
    }

    /** Device-pixel size to request: the pane (fit mode) or the configured resolution. */
    private Dimension requestedSize() {
        if (cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIXED) {
            return new Dimension(cfg.getWidth(), cfg.getHeight());
        }
        double s = deviceScale();
        int w = view.getWidth() > 0 ? (int) Math.round(view.getWidth() * s) : 1280;
        int h = view.getHeight() > 0 ? (int) Math.round(view.getHeight() * s) : 800;
        // MS-RDPEDISP bounds; the width must be even.
        w = Math.max(200, Math.min(8192, w)) & ~1;
        h = Math.max(200, Math.min(8192, h));
        return new Dimension(w, h);
    }

    private double deviceScale() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        return gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
    }

    private int scalePercent() {
        return (int) Math.round(deviceScale() * 100);
    }

    private void requestRemoteResize() {
        if (connected && connection != null && cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIT_TO_TAB) {
            Dimension size = requestedSize();
            connection.requestResize(size.width, size.height, scalePercent());
        }
    }

    private void clearPendingRemember() {
        if (pendingRemember != null) {
            Arrays.fill(pendingRemember, '\0');
            pendingRemember = null;
        }
    }

    /** Connection callbacks, bound to the connect attempt that created them. */
    private final class Listener implements RdpConnection.Listener {
        private final int gen;
        /** Null when the session does not share its clipboard. */
        private final RdpClipboardBridge clipboard;
        private volatile RdpConnection conn;

        Listener(int gen) {
            this.gen = gen;
            this.clipboard = cfg.isRedirectClipboard()
                    ? new RdpClipboardBridge(new RdpClipboardBridge.Sink() {
                        @Override
                        public void offer(int kinds) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.offerClipboard(kinds);
                            }
                        }

                        @Override
                        public void data(int kind, boolean ok, byte[] bytes) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.sendClipboardData(kind, ok, bytes);
                            }
                        }

                        @Override
                        public void fetch(int kind) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.fetchClipboard(kind);
                            }
                        }
                    }, Toolkit.getDefaultToolkit().getSystemClipboard(), true, cfg.isClipboardToRemote())
                    : null;
        }

        /** Called once the helper is running: from here the bridge may talk to it. */
        void attach(RdpConnection c) {
            conn = c;
            if (clipboard != null) {
                clipboard.start();
            }
        }

        void refreshClipboard() {
            if (clipboard != null) {
                clipboard.refresh();
            }
        }

        void closeClipboard() {
            if (clipboard != null) {
                clipboard.close();
            }
        }

        private boolean stale() {
            return gen != generation;
        }

        @Override
        public void onMessage(RdpProtocol.Inbound msg) {
            switch (msg) {
                case RdpProtocol.Frame f -> view.blit(f); // reader thread, under the view lock
                case RdpProtocol.Connected c -> SwingUtilities.invokeLater(() -> {
                    if (stale()) {
                        return;
                    }
                    connected = true;
                    unicodeInput = c.unicodeInput();
                    view.allocate(c.width(), c.height());
                    ((CardLayout) center.getLayout()).show(center, CARD_VIEW);
                    updateBar();
                    view.requestFocusInWindow();
                    if (pendingRemember != null) {
                        services.credentials().rememberRdpPassword(cfg, pendingRemember); // zeroes it
                        pendingRemember = null;
                    }
                    requestRemoteResize(); // the pane may have changed size while connecting
                });
                case RdpProtocol.DesktopResized r -> view.allocate(r.width(), r.height());
                case RdpProtocol.PointerNew p -> view.cursors.put(p.id(), toCursor(p));
                case RdpProtocol.PointerFree p -> view.cursors.remove(p.id());
                case RdpProtocol.PointerSet p -> SwingUtilities.invokeLater(() -> {
                    Cursor c = view.cursors.get(p.id());
                    view.setCursor(c != null ? c : Cursor.getDefaultCursor());
                });
                case RdpProtocol.ClipRemoteOffer o -> {
                    if (clipboard != null) {
                        clipboard.onRemoteOffer(o.kinds());
                    }
                }
                case RdpProtocol.ClipRemoteData d -> {
                    if (clipboard != null) {
                        clipboard.onRemoteData(d.kind(), d.ok(), d.data());
                    }
                }
                case RdpProtocol.ClipRequest r -> {
                    if (clipboard != null) {
                        clipboard.onRequest(r.kind());
                    }
                }
                case RdpProtocol.PointerHide h -> SwingUtilities.invokeLater(() -> view.setCursor(blankCursor()));
                case RdpProtocol.PointerDefault d -> SwingUtilities.invokeLater(
                        () -> view.setCursor(Cursor.getDefaultCursor()));
                default -> { }
            }
        }

        @Override
        public boolean verifyCertificate(RdpProtocol.CertRequest req) {
            boolean[] accept = new boolean[1];
            try {
                SwingUtilities.invokeAndWait(() -> accept[0] = !stale() && !closed && askCertificate(req));
            } catch (Exception e) {
                return false;
            }
            return accept[0];
        }

        @Override
        public void onClosed(RdpProtocol.Disconnected last, String stderrTail) {
            closeClipboard(); // this attempt's, whether or not a reconnect has replaced it
            SwingUtilities.invokeLater(() -> {
                if (stale()) {
                    return;
                }
                connection = null;
                connected = false;
                view.releaseAllKeys();
                if (closed) {
                    return;
                }
                if (last != null && last.credentialsRejected()) {
                    clearPendingRemember(); // a rejected password is never saved
                    updateBar();
                    connect("The server rejected the user name or password. Try again.");
                    return;
                }
                clearPendingRemember();
                String why = last == null ? "The RDP helper stopped unexpectedly."
                        : last.code() == 0 ? "Disconnected."
                        : "Disconnected: " + last.message();
                if (last == null && !stderrTail.isBlank()) {
                    LOG.warn("RDP helper for {} ended without a reason; its last output:\n{}",
                            cfg.hostLabel(), stderrTail);
                }
                showMessage(why);
                updateBar();
            });
        }
    }

    /** EDT. Shows the certificate prompt; on accept pins the fingerprint. */
    private boolean askCertificate(RdpProtocol.CertRequest req) {
        String pinned = services.knownHosts().pinFor(req.host(), req.port());
        boolean changed = pinned != null;
        StringBuilder text = new StringBuilder();
        if (changed) {
            text.append("WARNING: the certificate of ").append(req.host()).append(':').append(req.port())
                    .append(" has CHANGED since you trusted it.\n")
                    .append("This can mean someone is intercepting the connection.\n")
                    .append("Only continue if you know the server's certificate was replaced.\n\n")
                    .append("Previously trusted SHA-256:\n").append(pinned).append("\n\n");
        } else {
            text.append("jterm can't verify the identity of ").append(req.host()).append(':')
                    .append(req.port()).append(".\n\n");
        }
        text.append("Common name: ").append(req.commonName())
                .append("\nSubject: ").append(req.subject())
                .append("\nIssuer: ").append(req.issuer())
                .append("\nSHA-256: ").append(req.sha256Fingerprint());
        if (req.nameMismatch()) {
            text.append("\n\nThe certificate was not issued for this host name (common for self-signed "
                    + "certificates, e.g. GNOME Remote Desktop).");
        }
        text.append("\n\nCompare the fingerprint with the one on the server before trusting it.");
        Object[] options = {changed ? "Trust New Certificate" : "Trust", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, text.toString(), "Verify RDP Server",
                JOptionPane.DEFAULT_OPTION, changed ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        if (choice != 0) {
            return false;
        }
        if (!services.knownHosts().pin(req.host(), req.port(), req.sha256Fingerprint())) {
            LOG.warn("could not persist the RDP certificate pin for {}:{}; trusting it for this session only",
                    req.host(), req.port());
        }
        return true;
    }

    // ----------------------------------------------------------------------------- GridContent

    @Override
    public JComponent ui() {
        return this;
    }

    @Override
    public void closeContent() {
        if (closed) {
            return;
        }
        closed = true;
        resizeDebounce.stop();
        clearPendingRemember();
        if (listener != null) {
            listener.closeClipboard();
            listener = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override
    public void applyTheme(ThemeColors theme) {
        // Chrome follows FlatLaf; the desktop itself is the remote's colours.
    }

    @Override
    public void focusContent() {
        view.requestFocusInWindow();
    }

    @Override
    public void setOnFocus(Runnable onFocus) {
        this.onFocus = onFocus;
    }

    @Override
    public void setOnContentEnded(Runnable onEnded) {
        this.onEnded = onEnded;
    }

    @Override
    public void showMoveHint() {
        dropHighlighter.showMoveHint();
    }

    @Override
    public void showDropHint(DropRegion region) {
        dropHighlighter.showDropHint(region);
    }

    @Override
    public void clearDropHint() {
        dropHighlighter.clearDropHint();
    }

    @Override
    public String displayTitle() {
        return customName != null ? customName : cfg.getName();
    }

    @Override
    public void setOnTitleChanged(Runnable onTitleChanged) {
        this.onTitleChanged = onTitleChanged;
    }

    /**
     * Renames this tab. Runtime only, like a terminal pane's rename: the saved session is never
     * touched and the name dies with the pane. The text goes through {@link PaneTitle#sanitizeName}
     * because a tab title is also rendered from pasted text; blank restores the saved name.
     */
    @Override
    public boolean canRename() {
        return true;
    }

    @Override
    public boolean promptRename() {
        JTextField field = new JTextField(customName == null ? "" : customName, 24);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Name for this connection:"), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        JLabel hint = new JLabel("Leave blank to use the saved name.");
        hint.setEnabled(false);
        panel.add(hint, BorderLayout.SOUTH);
        if (DialogFocus.showConfirm(this, panel, "Rename", field) == JOptionPane.OK_OPTION) {
            customName = PaneTitle.sanitizeName(field.getText());
            if (onTitleChanged != null) {
                onTitleChanged.run();
            }
        }
        return true;
    }

    @Override
    public String tabIconId() {
        return cfg.getIconId() != null ? cfg.getIconId() : "builtin/rdp";
    }

    /** Whether keyboard focus is inside an RDP pane (for the shortcut dispatcher's policy). */
    public static boolean ownsFocus(java.awt.Component focusOwner) {
        return focusOwner != null && SwingUtilities.getAncestorOfClass(RdpPane.class, focusOwner) != null;
    }

    // ------------------------------------------------------------------------------ input

    private void send(Runnable action) {
        if (connected && connection != null) {
            action.run();
        }
    }

    private void sendCtrlAltDel() {
        RdpConnection c = connection;
        if (!connected || c == null) {
            return;
        }
        int[] keys = {0xA2, 0xA4, 0x2E};
        for (int vk : keys) {
            c.sendKey(true, vk, 0);
        }
        for (int i = keys.length - 1; i >= 0; i--) {
            c.sendKey(false, keys[i], 0);
        }
        view.requestFocusInWindow();
    }

    private Cursor toCursor(RdpProtocol.PointerNew p) {
        if (p.width() == 0 || p.height() == 0) {
            return Cursor.getDefaultCursor();
        }
        BufferedImage img = new BufferedImage(p.width(), p.height(), BufferedImage.TYPE_INT_ARGB);
        System.arraycopy(p.argb(), 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0,
                p.argb().length);
        // The cursor is in device pixels, like the desktop drawn 1:1. AWT stretches any image to the
        // platform's best cursor size (48x48 at 150% on Windows), doubling a 24x24 cursor; pad it
        // onto a transparent best-size canvas instead, shrinking only if it is larger.
        Dimension best = Toolkit.getDefaultToolkit().getBestCursorSize(p.width(), p.height());
        if (best.width <= 0 || best.height <= 0) {
            return Cursor.getDefaultCursor(); // platform has no custom cursors
        }
        double shrink = Math.min(1.0, Math.min((double) best.width / p.width(), (double) best.height / p.height()));
        BufferedImage padded = new BufferedImage(best.width, best.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = padded.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, (int) Math.round(p.width() * shrink), (int) Math.round(p.height() * shrink), null);
        g.dispose();
        Point hot = new Point((int) Math.min(p.hotX() * shrink, best.width - 1),
                (int) Math.min(p.hotY() * shrink, best.height - 1));
        return Toolkit.getDefaultToolkit().createCustomCursor(padded, hot, "rdp-" + p.id());
    }

    private static Cursor blankCursor;

    private static Cursor blankCursor() {
        if (blankCursor == null) {
            blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "rdp-blank");
        }
        return blankCursor;
    }

    /** Paints the remote framebuffer and turns Swing input into RDP input. */
    private final class DesktopView extends JComponent {

        private BufferedImage image;
        private int[] raster;
        final Map<Integer, Cursor> cursors = new ConcurrentHashMap<>();
        /** Keys sent down (vk << 8 | flags), released on focus loss so nothing stays stuck remotely. */
        private final Set<Integer> down = new HashSet<>();
        /** Java key codes whose press was deferred to keyTyped (sent as Unicode). */
        private final Set<Integer> deferred = new HashSet<>();

        DesktopView() {
            setFocusable(true);
            setFocusTraversalKeysEnabled(false); // Tab belongs to the remote desktop
            setOpaque(true);
            setBackground(Color.BLACK);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    e.consume();
                    if (unicodeInput && !RdpKeyMap.chord(e.getModifiersEx()) && RdpKeyMap.printable(e.getKeyChar())) {
                        deferred.add(e.getKeyCode()); // keyTyped sends it as Unicode
                        return;
                    }
                    RdpKeyMap.WinKey k = RdpKeyMap.toWindows(e.getKeyCode(), e.getKeyLocation());
                    if (k.mapped()) {
                        boolean repeat = !down.add((k.vk() << 8) | k.flags());
                        send(() -> connection.sendKey(true, k.vk(), k.flags() | (repeat ? RdpProtocol.KEY_REPEAT : 0)));
                    }
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    e.consume();
                    if (deferred.remove(e.getKeyCode())) {
                        return;
                    }
                    RdpKeyMap.WinKey k = RdpKeyMap.toWindows(e.getKeyCode(), e.getKeyLocation());
                    if (k.mapped()) {
                        down.remove((k.vk() << 8) | k.flags());
                        send(() -> connection.sendKey(false, k.vk(), k.flags()));
                    }
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    e.consume();
                    char c = e.getKeyChar();
                    if (unicodeInput && !RdpKeyMap.chord(e.getModifiersEx()) && RdpKeyMap.printable(c)) {
                        send(() -> {
                            connection.sendUnicode(true, c);
                            connection.sendUnicode(false, c);
                        });
                    }
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    refreshClipboard();
                    if (onFocus != null) {
                        onFocus.run();
                    }
                }

                @Override
                public void focusLost(FocusEvent e) {
                    releaseAllKeys();
                }
            });
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    button(e, true);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    button(e, false);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    move(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    move(e);
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    int notches = e.getWheelRotation();
                    int base = e.isShiftDown() ? RdpProtocol.PTR_HWHEEL : RdpProtocol.PTR_WHEEL;
                    Point p = toRemote(e.getX(), e.getY());
                    // Java: positive = towards the user (scroll down) = negative RDP rotation.
                    int flags = base | (notches > 0 ? RdpProtocol.WHEEL_DOWN : RdpProtocol.WHEEL_UP);
                    for (int i = 0; i < Math.abs(notches); i++) {
                        send(() -> connection.sendMouse(flags, p.x, p.y));
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        void releaseAllKeys() {
            for (int id : down) {
                send(() -> connection.sendKey(false, id >>> 8, id & 0xFF));
            }
            down.clear();
            deferred.clear();
        }

        private void move(MouseEvent e) {
            Point p = toRemote(e.getX(), e.getY());
            send(() -> connection.sendMouse(RdpProtocol.PTR_MOVE, p.x, p.y));
        }

        private void button(MouseEvent e, boolean pressed) {
            int flag = switch (e.getButton()) {
                case MouseEvent.BUTTON1 -> RdpProtocol.PTR_BUTTON1;
                case MouseEvent.BUTTON3 -> RdpProtocol.PTR_BUTTON2; // RDP button 2 is the right button
                case MouseEvent.BUTTON2 -> RdpProtocol.PTR_BUTTON3;
                default -> 0;
            };
            if (flag != 0) {
                Point p = toRemote(e.getX(), e.getY());
                int flags = flag | (pressed ? RdpProtocol.PTR_DOWN : 0);
                send(() -> connection.sendMouse(flags, p.x, p.y));
            }
        }

        /** Where the desktop is drawn: fit inside the view keeping its aspect ratio, centred. */
        private synchronized Rectangle drawRect() {
            if (image == null || getWidth() <= 0 || getHeight() <= 0) {
                return new Rectangle(0, 0, getWidth(), getHeight());
            }
            double s = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int w = (int) Math.round(image.getWidth() * s), h = (int) Math.round(image.getHeight() * s);
            return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        private Point toRemote(int x, int y) {
            BufferedImage img = image;
            Rectangle r = drawRect();
            if (img == null || r.width <= 0 || r.height <= 0) {
                return new Point(0, 0);
            }
            int rx = (int) ((long) (x - r.x) * img.getWidth() / r.width);
            int ry = (int) ((long) (y - r.y) * img.getHeight() / r.height);
            return new Point(Math.max(0, Math.min(img.getWidth() - 1, rx)),
                    Math.max(0, Math.min(img.getHeight() - 1, ry)));
        }

        synchronized void allocate(int w, int h) {
            if (image == null || image.getWidth() != w || image.getHeight() != h) {
                image = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
                raster = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            }
            repaint();
        }

        /** Reader thread: copy a dirty rectangle into the image and schedule its repaint. */
        void blit(RdpProtocol.Frame f) {
            Rectangle dirty;
            synchronized (this) {
                if (image == null) {
                    return;
                }
                int iw = image.getWidth(), ih = image.getHeight();
                IntBuffer ints = f.pixels().asIntBuffer();
                int copyW = Math.min(f.width(), iw - f.x());
                for (int row = 0; row < f.height() && f.y() + row < ih && copyW > 0; row++) {
                    ints.position(row * f.width());
                    ints.get(raster, (f.y() + row) * iw + f.x(), copyW);
                }
                Rectangle r = drawRect();
                double sx = (double) r.width / iw, sy = (double) r.height / ih;
                dirty = new Rectangle(r.x + (int) (f.x() * sx) - 1, r.y + (int) (f.y() * sy) - 1,
                        (int) Math.ceil(f.width() * sx) + 2, (int) Math.ceil(f.height() * sy) + 2);
            }
            repaint(dirty);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            synchronized (this) {
                if (image == null) {
                    return;
                }
                Rectangle r = drawRect();
                ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, r.x, r.y, r.width, r.height, null);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(UIScale.scale(640), UIScale.scale(400));
        }
    }
}
