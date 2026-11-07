/*
 * Throwaway test harness for jterm-rdp-helper (phase-1 spike). Not part of the jterm build.
 *
 *   java RdpSpike.java <helper.exe> <connection.properties> [--snapshot out.png] [--no-prompts]
 *
 * connection.properties: host, port (3389), user, domain, password, width/height (logical px).
 * Accepted certificate pins are kept in "<connection.properties>.known" (host:port=sha256:...).
 * --no-prompts rejects unknown certificates instead of asking, printing the fingerprint so it can
 * be confirmed out of band and added to the .known file.
 */
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RdpSpike {

    // protocol.h
    static final int HELLO = 0x01, CONNECT = 0x02, KEY = 0x03, UNICODE = 0x04, MOUSE = 0x05,
            CERT_REPLY = 0x06, RESIZE = 0x07, BYE = 0x0F;
    static final int HELLO_REPLY = 0x81, CONNECTED = 0x82, FRAME = 0x83, DESKTOP_RESIZED = 0x84,
            POINTER_NEW = 0x85, POINTER_FREE = 0x86, POINTER_SET = 0x87, POINTER_HIDE = 0x88,
            POINTER_DEFAULT = 0x89, CERT_REQUEST = 0x8A, DISCONNECTED = 0x8F;
    static final int KEY_EXT = 0x01, KEY_NOT_EXT = 0x02, KEY_REPEAT = 0x04;
    static final int PTR_MOVE = 0x0800, PTR_DOWN = 0x8000, PTR_B1 = 0x1000, PTR_B2 = 0x2000,
            PTR_B3 = 0x4000, PTR_WHEEL = 0x0200, PTR_HWHEEL = 0x0400;
    static final int CERT_FLAG_CHANGED = 0x40, CERT_FLAG_MISMATCH = 0x80;

    final Process helper;
    final DataOutputStream out;
    final DataInputStream in;
    final Properties conn;
    final Path knownFile;
    final Properties known = new Properties();
    final boolean noPrompts;
    final Path snapshot;

    final DesktopView canvas = new DesktopView();
    final JLabel status = new JLabel("Connecting…");
    final JFrame frame = new JFrame();
    volatile boolean unicodeInput;
    final AtomicLong frames = new AtomicLong(), bytes = new AtomicLong();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RdpSpike <helper> <connection.properties> [--snapshot f.png] [--no-prompts]");
            System.exit(2);
        }
        Path snap = null;
        boolean noPrompts = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--snapshot")) snap = Path.of(args[++i]);
            else if (args[i].equals("--no-prompts")) noPrompts = true;
        }
        new RdpSpike(Path.of(args[0]), Path.of(args[1]), snap, noPrompts).start();
    }

    RdpSpike(Path helperExe, Path connFile, Path snapshot, boolean noPrompts) throws IOException {
        this.conn = new Properties();
        try (Reader r = Files.newBufferedReader(connFile)) {
            conn.load(r);
        }
        this.knownFile = Path.of(connFile + ".known");
        if (Files.exists(knownFile)) {
            try (Reader r = Files.newBufferedReader(knownFile)) {
                known.load(r);
            }
        }
        this.noPrompts = noPrompts;
        this.snapshot = snapshot;
        ProcessBuilder pb = new ProcessBuilder(helperExe.toAbsolutePath().toString());
        pb.environment().putIfAbsent("WLOG_LEVEL", "WARN");
        // Pipe (not INHERIT): a GUI-subsystem helper can't use an inherited console handle.
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        helper = pb.start();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(helper.getErrorStream()))) {
                for (String line; (line = r.readLine()) != null; ) System.err.println(line);
            } catch (IOException ignored) {
            }
        }, "helper-stderr");
        drain.setDaemon(true);
        drain.start();
        out = new DataOutputStream(new BufferedOutputStream(helper.getOutputStream()));
        in = new DataInputStream(new BufferedInputStream(helper.getInputStream(), 1 << 16));
    }

    void start() throws Exception {
        double scale = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getDefaultTransform().getScaleX();
        int lw = Integer.parseInt(conn.getProperty("width", "1280"));
        int lh = Integer.parseInt(conn.getProperty("height", "800"));
        int dw = (int) Math.round(lw * scale), dh = (int) Math.round(lh * scale);
        canvas.allocate(dw, dh);
        canvas.setPreferredSize(new Dimension(lw, lh));

        String host = conn.getProperty("host");
        int port = Integer.parseInt(conn.getProperty("port", "3389"));
        String pin = known.getProperty(host + ":" + port, "");

        send(HELLO, b -> b.u16(1));
        send(CONNECT, b -> {
            b.str(host).u16(port).str(conn.getProperty("user", "")).str(conn.getProperty("domain", ""))
                    .str(conn.getProperty("password", "")).str(pin)
                    .u16(dw).u16(dh).u16(Integer.parseInt(System.getProperty("remoteScale", String.valueOf(Math.round(scale * 100)))));
        });
        System.err.printf("spike: requested %dx%d device px (scale %.2f), pin=%s%n", dw, dh, scale,
                pin.isEmpty() ? "none" : "yes");

        SwingUtilities.invokeAndWait(() -> {
            frame.setTitle("RDP spike — " + host);
            JToolBar bar = new JToolBar();
            bar.setFloatable(false);
            JButton cad = new JButton("Ctrl+Alt+Del");
            cad.setFocusable(false);
            cad.addActionListener(e -> sendCtrlAltDel());
            JButton kill = new JButton("Kill helper");
            kill.setFocusable(false);
            kill.addActionListener(e -> {
                helper.destroyForcibly();
                status.setText("Helper killed — JVM still alive");
            });
            bar.add(cad);
            bar.add(kill);
            bar.addSeparator();
            bar.add(status);
            frame.add(bar, BorderLayout.NORTH);
            frame.add(canvas, BorderLayout.CENTER);
            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    try {
                        send(BYE, b -> { });
                        out.close();
                    } catch (IOException ignored) {
                    }
                    System.exit(0);
                }
            });
            frame.setVisible(true);
            canvas.requestFocusInWindow();
        });

        Thread stats = new Thread(this::statsLoop, "stats");
        stats.setDaemon(true);
        stats.start();
        helper.onExit().thenAccept(p -> System.err.println("spike: helper exited with " + p.exitValue()));
        readLoop();
    }

    // ---------------------------------------------------------------------------------- output

    final class Msg {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Msg u8(int v) { buf.write(v); return this; }
        Msg u16(int v) { buf.write(v & 0xFF); buf.write((v >> 8) & 0xFF); return this; }
        Msg str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            u16(b.length);
            buf.writeBytes(b);
            return this;
        }
    }

    interface Body { void write(Msg m); }

    synchronized void send(int type, Body body) throws IOException {
        Msg m = new Msg();
        body.write(m);
        byte[] payload = m.buf.toByteArray();
        out.writeInt(Integer.reverseBytes(1 + payload.length));
        out.write(type);
        out.write(payload);
        out.flush();
        Arrays.fill(payload, (byte) 0); // CONNECT carries the password
    }

    void sendQuiet(int type, Body body) {
        try {
            send(type, body);
        } catch (IOException e) {
            // helper gone; the read loop reports it
        }
    }

    // ----------------------------------------------------------------------------------- input

    void readLoop() {
        byte[] buf = new byte[1 << 20];
        try {
            while (true) {
                int len = Integer.reverseBytes(in.readInt());
                int type = in.readUnsignedByte();
                int n = len - 1;
                if (n > buf.length) buf = new byte[Math.max(n, buf.length * 2)];
                in.readFully(buf, 0, n);
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                handle(type, bb, n);
            }
        } catch (EOFException e) {
            System.err.println("spike: helper closed the stream");
        } catch (Exception e) {
            e.printStackTrace();
        }
        SwingUtilities.invokeLater(() -> {
            if (!status.getText().startsWith("Disconnected") && !status.getText().startsWith("Helper killed"))
                status.setText("Helper gone");
        });
    }

    static String str(ByteBuffer bb) {
        int n = bb.getShort() & 0xFFFF;
        byte[] b = new byte[n];
        bb.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    void handle(int type, ByteBuffer bb, int n) throws Exception {
        switch (type) {
            case HELLO_REPLY -> System.err.printf("spike: helper protocol v%d, FreeRDP %s%n",
                    bb.getShort() & 0xFFFF, str(bb));
            case CONNECTED -> {
                int w = bb.getShort() & 0xFFFF, h = bb.getShort() & 0xFFFF;
                unicodeInput = bb.get() != 0;
                canvas.allocate(w, h);
                System.err.printf("spike: CONNECTED %dx%d unicodeInput=%s%n", w, h, unicodeInput);
                SwingUtilities.invokeLater(() -> status.setText("Connected " + w + "×" + h
                        + (unicodeInput ? "" : " (scancode-only keyboard)")));
            }
            case DESKTOP_RESIZED -> {
                int w = bb.getShort() & 0xFFFF, h = bb.getShort() & 0xFFFF;
                canvas.allocate(w, h);
                System.err.printf("spike: DESKTOP_RESIZED %dx%d%n", w, h);
            }
            case FRAME -> {
                int x = bb.getShort() & 0xFFFF, y = bb.getShort() & 0xFFFF;
                int w = bb.getShort() & 0xFFFF, h = bb.getShort() & 0xFFFF;
                canvas.blit(x, y, w, h, bb);
                frames.incrementAndGet();
                bytes.addAndGet(n + 5L);
            }
            case POINTER_NEW -> {
                int id = bb.getInt();
                int w = bb.getShort() & 0xFFFF, h = bb.getShort() & 0xFFFF;
                int hx = bb.getShort() & 0xFFFF, hy = bb.getShort() & 0xFFFF;
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                bb.asIntBuffer().get(px, 0, w * h);
                // The remote cursor is in device pixels, like the framebuffer we draw 1:1. AWT
                // stretches any image to the platform's best cursor size (48x48 at 150% on
                // Windows), which doubled a 24x24 cursor. Pad it onto a transparent best-size
                // canvas instead; only shrink if it is larger than that.
                Dimension best = Toolkit.getDefaultToolkit().getBestCursorSize(w, h);
                double shrink = Math.min(1.0, Math.min((double) best.width / w, (double) best.height / h));
                BufferedImage padded = new BufferedImage(Math.max(1, best.width), Math.max(1, best.height),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = padded.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, (int) Math.round(w * shrink), (int) Math.round(h * shrink), null);
                g.dispose();
                Point hot = new Point((int) Math.min(hx * shrink, padded.getWidth() - 1),
                        (int) Math.min(hy * shrink, padded.getHeight() - 1));
                canvas.cursors.put(id, Toolkit.getDefaultToolkit().createCustomCursor(padded, hot, "rdp-" + id));
            }
            case POINTER_FREE -> canvas.cursors.remove(bb.getInt());
            case POINTER_SET -> {
                Cursor c = canvas.cursors.get(bb.getInt());
                SwingUtilities.invokeLater(() -> canvas.setCursor(c != null ? c : Cursor.getDefaultCursor()));
            }
            case POINTER_HIDE -> SwingUtilities.invokeLater(() -> canvas.setCursor(canvas.blankCursor()));
            case POINTER_DEFAULT -> SwingUtilities.invokeLater(() -> canvas.setCursor(Cursor.getDefaultCursor()));
            case CERT_REQUEST -> onCertRequest(str(bb), bb.getShort() & 0xFFFF, str(bb), str(bb),
                    str(bb), str(bb), bb.getInt());
            case DISCONNECTED -> {
                int code = bb.getInt();
                String msg = str(bb);
                System.err.printf("spike: DISCONNECTED 0x%08X %s%n", code, msg);
                SwingUtilities.invokeLater(() -> status.setText(
                        String.format("Disconnected: %s (0x%08X)", msg, code)));
            }
            default -> System.err.printf("spike: unknown message 0x%02X (%d bytes)%n", type, n);
        }
    }

    void onCertRequest(String host, int port, String cn, String subject, String issuer, String fp,
                       int flags) throws Exception {
        String key = host + ":" + port;
        String pinned = known.getProperty(key);
        boolean changed = pinned != null || (flags & CERT_FLAG_CHANGED) != 0;
        System.err.printf("spike: CERT_REQUEST %s cn=%s subject=%s issuer=%s sha256=%s flags=0x%X%s%n",
                key, cn, subject, issuer, fp, flags, changed ? " ** DIFFERS FROM PINNED **" : "");
        boolean accept = false;
        if (!noPrompts) {
            String text = (changed ? "WARNING: this server's certificate has CHANGED since you last accepted it.\n"
                    + "This can mean someone is intercepting the connection.\n\n" : "")
                    + "Server: " + key + "\nCommon name: " + cn + "\nSubject: " + subject
                    + "\nIssuer: " + issuer + "\nSHA-256: " + fp
                    + ((flags & CERT_FLAG_MISMATCH) != 0 ? "\n\nThe certificate name does not match the host." : "")
                    + "\n\nTrust this certificate and remember it?";
            boolean[] result = new boolean[1];
            SwingUtilities.invokeAndWait(() -> result[0] = JOptionPane.showConfirmDialog(frame, text,
                    "Verify RDP server", JOptionPane.YES_NO_OPTION,
                    changed ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
            accept = result[0];
        } else {
            System.err.println("spike: --no-prompts → rejected. To trust it after confirming the fingerprint, add:");
            System.err.println("  " + key.replace(":", "\\:") + "=sha256:" + fp);
        }
        if (accept) {
            known.setProperty(key, "sha256:" + fp);
            try (Writer w = Files.newBufferedWriter(knownFile)) {
                known.store(w, "RDP spike pinned certificates");
            }
        }
        boolean a = accept;
        send(CERT_REPLY, b -> b.u8(a ? 1 : 0));
    }

    // ----------------------------------------------------------------------------- diagnostics

    void statsLoop() {
        long lastF = 0, lastB = 0;
        while (true) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            long f = frames.get(), b = bytes.get();
            if (f != lastF)
                System.err.printf("spike: %.1f rects/s, %.2f MB/s over the pipe%n",
                        (f - lastF) / 5.0, (b - lastB) / 5.0 / (1 << 20));
            lastF = f;
            lastB = b;
            if (snapshot != null && f > 0) canvas.writeSnapshot(snapshot);
        }
    }

    // --------------------------------------------------------------------------- keyboard/mouse

    void sendKey(boolean down, int winVk, int flags) {
        sendQuiet(KEY, b -> b.u8(down ? 1 : 0).u16(winVk).u8(flags));
    }

    void sendCtrlAltDel() {
        sendKey(true, 0xA2, 0);
        sendKey(true, 0xA4, 0);
        sendKey(true, 0x2E, 0);
        sendKey(false, 0x2E, 0);
        sendKey(false, 0xA4, 0);
        sendKey(false, 0xA2, 0);
    }

    /** Java virtual key + location → Windows virtual key and extended-key flags; vk 0 = unmapped. */
    static int[] toWindowsVk(int vk, int loc) {
        boolean numpad = loc == KeyEvent.KEY_LOCATION_NUMPAD;
        boolean right = loc == KeyEvent.KEY_LOCATION_RIGHT;
        int notExtIfPad = numpad ? KEY_NOT_EXT : 0;
        return switch (vk) {
            case KeyEvent.VK_ENTER -> new int[] { 0x0D, numpad ? KEY_EXT : 0 };
            case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_TAB, KeyEvent.VK_PAUSE, KeyEvent.VK_CAPS_LOCK,
                 KeyEvent.VK_ESCAPE, KeyEvent.VK_SPACE, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK -> new int[] { vk, 0 };
            case KeyEvent.VK_CLEAR -> new int[] { 0x0C, 0 };
            case KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_END, KeyEvent.VK_HOME,
                 KeyEvent.VK_LEFT, KeyEvent.VK_UP, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN -> new int[] { vk, notExtIfPad };
            case KeyEvent.VK_KP_LEFT -> new int[] { 0x25, KEY_NOT_EXT };
            case KeyEvent.VK_KP_UP -> new int[] { 0x26, KEY_NOT_EXT };
            case KeyEvent.VK_KP_RIGHT -> new int[] { 0x27, KEY_NOT_EXT };
            case KeyEvent.VK_KP_DOWN -> new int[] { 0x28, KEY_NOT_EXT };
            case KeyEvent.VK_INSERT -> new int[] { 0x2D, notExtIfPad };
            case KeyEvent.VK_DELETE -> new int[] { 0x2E, notExtIfPad };
            case KeyEvent.VK_PRINTSCREEN -> new int[] { 0x2C, 0 };
            case KeyEvent.VK_SHIFT -> new int[] { right ? 0xA1 : 0xA0, 0 };
            case KeyEvent.VK_CONTROL -> new int[] { right ? 0xA3 : 0xA2, 0 };
            case KeyEvent.VK_ALT -> new int[] { right ? 0xA5 : 0xA4, 0 };
            case KeyEvent.VK_ALT_GRAPH -> new int[] { 0xA5, 0 };
            case KeyEvent.VK_WINDOWS -> new int[] { right ? 0x5C : 0x5B, 0 };
            case KeyEvent.VK_CONTEXT_MENU -> new int[] { 0x5D, 0 };
            case KeyEvent.VK_SEMICOLON -> new int[] { 0xBA, 0 };
            case KeyEvent.VK_EQUALS -> new int[] { 0xBB, 0 };
            case KeyEvent.VK_COMMA -> new int[] { 0xBC, 0 };
            case KeyEvent.VK_MINUS -> new int[] { 0xBD, 0 };
            case KeyEvent.VK_PERIOD -> new int[] { 0xBE, 0 };
            case KeyEvent.VK_SLASH -> new int[] { 0xBF, 0 };
            case KeyEvent.VK_BACK_QUOTE -> new int[] { 0xC0, 0 };
            case KeyEvent.VK_OPEN_BRACKET -> new int[] { 0xDB, 0 };
            case KeyEvent.VK_BACK_SLASH -> new int[] { 0xDC, 0 };
            case KeyEvent.VK_CLOSE_BRACKET -> new int[] { 0xDD, 0 };
            case KeyEvent.VK_QUOTE -> new int[] { 0xDE, 0 };
            case KeyEvent.VK_LESS -> new int[] { 0xE2, 0 };
            default -> {
                if ((vk >= '0' && vk <= '9') || (vk >= 'A' && vk <= 'Z')) yield new int[] { vk, 0 };
                if (vk >= KeyEvent.VK_NUMPAD0 && vk <= KeyEvent.VK_DIVIDE) yield new int[] { vk, 0 };
                if (vk >= KeyEvent.VK_F1 && vk <= KeyEvent.VK_F12) yield new int[] { vk, 0 };
                if (vk >= KeyEvent.VK_F13 && vk <= KeyEvent.VK_F24) yield new int[] { 0x7C + (vk - KeyEvent.VK_F13), 0 };
                yield new int[] { 0, 0 };
            }
        };
    }

    static boolean printable(char c) {
        return c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c);
    }

    // --------------------------------------------------------------------------------- canvas

    final class DesktopView extends JComponent {
        private BufferedImage image;
        private int[] raster;
        final Map<Integer, Cursor> cursors = new ConcurrentHashMap<>();
        private final Set<Long> down = new HashSet<>();      // (winVk<<8 | flags) currently held
        private final Set<Integer> deferred = new HashSet<>(); // Java keycodes sent as Unicode instead
        private Cursor blank;

        DesktopView() {
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);
            setOpaque(true);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    boolean chord = e.isControlDown() || e.isAltDown() || e.isMetaDown();
                    if (unicodeInput && !chord && printable(e.getKeyChar())) {
                        deferred.add(e.getKeyCode()); // keyTyped sends it as Unicode (layout-independent)
                        e.consume();
                        return;
                    }
                    int[] m = toWindowsVk(e.getKeyCode(), e.getKeyLocation());
                    if (m[0] != 0) {
                        long id = ((long) m[0] << 8) | m[1];
                        boolean repeat = !down.add(id);
                        sendKey(true, m[0], m[1] | (repeat ? KEY_REPEAT : 0));
                    }
                    e.consume();
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    if (deferred.remove(e.getKeyCode())) {
                        e.consume();
                        return;
                    }
                    int[] m = toWindowsVk(e.getKeyCode(), e.getKeyLocation());
                    if (m[0] != 0) {
                        down.remove(((long) m[0] << 8) | m[1]);
                        sendKey(false, m[0], m[1]);
                    }
                    e.consume();
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    char c = e.getKeyChar();
                    boolean chord = e.isControlDown() || e.isAltDown() || e.isMetaDown();
                    if (unicodeInput && !chord && printable(c)) {
                        sendQuiet(UNICODE, b -> b.u8(1).u16(c));
                        sendQuiet(UNICODE, b -> b.u8(0).u16(c));
                    }
                    e.consume();
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    // Never leave modifiers stuck down on the remote side.
                    for (long id : down) sendKey(false, (int) (id >> 8), (int) (id & 0xFF));
                    down.clear();
                    deferred.clear();
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
                    int base = e.isShiftDown() ? PTR_HWHEEL : PTR_WHEEL;
                    int[] p = toDevice(e.getX(), e.getY());
                    for (int i = 0; i < Math.abs(notches); i++) {
                        // Java: positive = towards the user (scroll down) = negative RDP rotation.
                        int flags = base | (notches > 0 ? 0x188 : 0x078);
                        sendQuiet(MOUSE, b -> b.u16(flags).u16(p[0]).u16(p[1]));
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);

            // Ask the server to re-lay out its desktop to the pane (Display Control), debounced so
            // dragging a window edge sends one request when it settles, not hundreds.
            javax.swing.Timer resizeDebounce = new javax.swing.Timer(300, e -> requestRemoteResize());
            resizeDebounce.setRepeats(false);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    resizeDebounce.restart();
                }
            });
        }

        /** Pane size in device pixels + the screen scale, as a RESIZE request. EDT. */
        void requestRemoteResize() {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            double s = getGraphicsConfiguration() != null
                    ? getGraphicsConfiguration().getDefaultTransform().getScaleX() : 1.0;
            int dw = (int) Math.round(getWidth() * s), dh = (int) Math.round(getHeight() * s);
            int pct = Integer.parseInt(System.getProperty("remoteScale", String.valueOf(Math.round(s * 100))));
            System.err.printf("spike: RESIZE request %dx%d @ %d%%%n", dw, dh, pct);
            sendQuiet(RESIZE, b -> b.u16(dw).u16(dh).u16(pct));
        }

        Cursor blankCursor() {
            if (blank == null)
                blank = Toolkit.getDefaultToolkit().createCustomCursor(
                        new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "blank");
            return blank;
        }

        int[] toDevice(int x, int y) {
            BufferedImage img = image;
            if (img == null || getWidth() == 0 || getHeight() == 0) return new int[] { 0, 0 };
            int dx = (int) ((long) x * img.getWidth() / getWidth());
            int dy = (int) ((long) y * img.getHeight() / getHeight());
            return new int[] { Math.max(0, Math.min(img.getWidth() - 1, dx)),
                    Math.max(0, Math.min(img.getHeight() - 1, dy)) };
        }

        void move(MouseEvent e) {
            int[] p = toDevice(e.getX(), e.getY());
            sendQuiet(MOUSE, b -> b.u16(PTR_MOVE).u16(p[0]).u16(p[1]));
        }

        void button(MouseEvent e, boolean pressed) {
            int flag = switch (e.getButton()) {
                case MouseEvent.BUTTON1 -> PTR_B1;
                case MouseEvent.BUTTON3 -> PTR_B2; // RDP button 2 is the right button
                case MouseEvent.BUTTON2 -> PTR_B3;
                default -> 0;
            };
            if (flag == 0) return;
            int[] p = toDevice(e.getX(), e.getY());
            int flags = flag | (pressed ? PTR_DOWN : 0);
            sendQuiet(MOUSE, b -> b.u16(flags).u16(p[0]).u16(p[1]));
        }

        synchronized void allocate(int w, int h) {
            if (image != null && image.getWidth() == w && image.getHeight() == h) return;
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            raster = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            repaint();
        }

        void blit(int x, int y, int w, int h, ByteBuffer bb) {
            synchronized (this) {
                int iw = image.getWidth(), ih = image.getHeight();
                var ints = bb.asIntBuffer();
                for (int row = 0; row < h; row++) {
                    int pos = row * w;
                    if (y + row < ih && x < iw) {
                        ints.position(pos);
                        ints.get(raster, (y + row) * iw + x, Math.min(w, iw - x));
                    }
                }
            }
            // Device rect → logical rect, padded by a pixel for rounding.
            int cw = getWidth(), ch = getHeight();
            BufferedImage img = image;
            if (cw > 0 && ch > 0) {
                double sx = (double) cw / img.getWidth(), sy = (double) ch / img.getHeight();
                repaint((int) (x * sx) - 1, (int) (y * sy) - 1, (int) Math.ceil(w * sx) + 2, (int) Math.ceil(h * sy) + 2);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            synchronized (this) {
                if (image == null) return;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            }
        }

        void writeSnapshot(Path p) {
            BufferedImage copy;
            synchronized (this) {
                if (image == null) return;
                copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                copy.getGraphics().drawImage(image, 0, 0, null);
            }
            try {
                ImageIO.write(copy, "png", p.toFile());
            } catch (IOException e) {
                System.err.println("spike: snapshot failed: " + e);
            }
        }
    }
}
