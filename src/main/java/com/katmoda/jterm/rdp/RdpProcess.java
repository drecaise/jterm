package com.katmoda.jterm.rdp;

import com.katmoda.jterm.session.RdpSessionConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Drives an external FreeRDP process for one RDP session. Builds the command line from a
 * {@link RdpSessionConfig}, hands the password to FreeRDP via <em>stdin</em> ({@code /from-stdin})
 * so it never appears in {@code argv}/{@code ps}, and tracks the process for teardown.
 *
 * <p>Host-certificate verification defaults to FreeRDP's own trust-on-first-use store
 * (`{@code /cert:tofu}`, stored under {@code ~/.config/freerdp/known_hosts}) — mirroring jterm's
 * SSH TOFU philosophy. A session may opt in to {@code /cert:ignore} (insecure) when its server's
 * self-signed cert name doesn't match the host. NLA/TLS are preserved (never downgraded), and
 * clipboard/drive/audio redirection is opt-in per session.</p>
 *
 * <p>FreeRDP's stderr (its {@code [ERROR]}/{@code [WARN]} log lines) is both echoed to the console
 * and kept in a small ring buffer ({@link #recentOutput()}) so the RDP tab can show why a
 * connection failed instead of a bare "disconnected".</p>
 *
 * <p>This class and the {@code embed} package are the only references to FreeRDP/JNA in the app,
 * so they load lazily on first RDP use.</p>
 */
public final class RdpProcess {

    // Candidate binary names, in preference order, per OS family.
    private static final List<String> UNIX_BINARIES =
            List.of("xfreerdp", "xfreerdp3", "sdl-freerdp", "freerdp");
    private static final List<String> WINDOWS_BINARIES =
            List.of("wfreerdp.exe", "sdl-freerdp.exe", "freerdp.exe",
                    "wfreerdp", "sdl-freerdp", "freerdp");

    private static final int MAX_CAPTURED_LINES = 60;

    private final Process process;
    private final Deque<String> recentLines = new ArrayDeque<>();

    private RdpProcess(Process process) {
        this.process = process;
        startStderrDrain();
    }

    /** Drains FreeRDP's stderr on a daemon thread: echo to the console + keep the last lines. */
    private void startStderrDrain() {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (recentLines) {
                        recentLines.addLast(line);
                        while (recentLines.size() > MAX_CAPTURED_LINES) {
                            recentLines.removeFirst();
                        }
                    }
                    System.err.println(line);
                }
            } catch (IOException ignored) {
                // Process gone / stream closed — nothing to drain.
            }
        }, "rdp-stderr-" + process.pid());
        t.setDaemon(true);
        t.start();
    }

    /** The last lines FreeRDP wrote to stderr (most useful tail of any failure), newline-joined. */
    public String recentOutput() {
        synchronized (recentLines) {
            return String.join("\n", recentLines);
        }
    }

    /**
     * Start FreeRDP for {@code cfg}. {@code embedderArgs} come from the platform
     * {@link com.katmoda.jterm.rdp.embed.WindowEmbedder} (e.g. {@code /parent-window} on X11).
     * {@code initialWidth/Height} seed the geometry for fit-to-tab sessions. {@code onExit} fires
     * (off the EDT) with the process exit code when FreeRDP terminates.
     */
    public static RdpProcess start(RdpSessionConfig cfg, char[] password, List<String> embedderArgs,
                                   int initialWidth, int initialHeight, IntConsumer onExit)
            throws IOException, RdpUnavailableException {
        String binary = resolveBinary();
        List<String> cmd = buildCommand(binary, cfg, password != null, initialWidth, initialHeight);
        cmd.addAll(embedderArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        // stderr stays a pipe so we can both echo it and keep a tail for the failure panel;
        // stdin stays a pipe so we can feed the password securely.
        Process process = pb.start();

        if (password != null) {
            try (OutputStream stdin = process.getOutputStream()) {
                byte[] bytes = new String(password).getBytes(StandardCharsets.UTF_8);
                stdin.write(bytes);
                stdin.write('\n');
                stdin.flush();
                Arrays.fill(bytes, (byte) 0);
            } finally {
                Arrays.fill(password, '\0');
            }
        }

        if (onExit != null) {
            process.onExit().thenAccept(p -> onExit.accept(p.exitValue()));
        }
        return new RdpProcess(process);
    }

    private static List<String> buildCommand(String binary, RdpSessionConfig cfg,
                                             boolean passwordFromStdin,
                                             int initialWidth, int initialHeight) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add("/v:" + cfg.getHost() + (cfg.getPort() > 0 ? ":" + cfg.getPort() : ""));
        cmd.add("/u:" + cfg.getUser());
        if (!cfg.getDomain().isBlank()) {
            cmd.add("/d:" + cfg.getDomain());
        }

        // Host identity: trust-on-first-use by default. /cert:tofu auto-accepts an unknown cert
        // but still refuses a name mismatch, so a session against a self-signed cert whose CN
        // doesn't match the host needs the opt-in (insecure) /cert:ignore.
        cmd.add(cfg.isIgnoreCertErrors() ? "/cert:ignore" : "/cert:tofu");

        switch (cfg.getSecurityMode()) {
            case NLA -> cmd.add("/sec:nla");
            case TLS -> cmd.add("/sec:tls");
            case AUTO -> { /* let FreeRDP negotiate */ }
        }

        if (cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIXED) {
            cmd.add("/w:" + cfg.getWidth());
            cmd.add("/h:" + cfg.getHeight());
        } else {
            // Embedded in a tab: either renegotiate the remote resolution to the window
            // (/dynamic-resolution — crisp, needs cooperative server/client) or scale the
            // framebuffer to the window (/smart-sizing — always tracks size, may letterbox).
            cmd.add(cfg.getScalingMode() == RdpSessionConfig.ScalingMode.SMART
                    ? "/smart-sizing" : "/dynamic-resolution");
            if (initialWidth > 0 && initialHeight > 0) {
                cmd.add("/w:" + initialWidth);
                cmd.add("/h:" + initialHeight);
            }
        }
        // Color depth: 0 means Auto — omit /bpp entirely (some servers reject a forced depth).
        if (cfg.getColorDepth() > 0) {
            cmd.add("/bpp:" + cfg.getColorDepth());
        }

        if (cfg.isRedirectClipboard()) {
            cmd.add("+clipboard");
        }
        if (cfg.isRedirectDrives()) {
            cmd.add("/drives");
        }
        if (cfg.isRedirectAudio()) {
            cmd.add("/sound");
        }
        if (!cfg.getGateway().isBlank()) {
            cmd.add("/gateway:g:" + cfg.getGateway());
        }

        if (passwordFromStdin) {
            cmd.add("/from-stdin");
        }
        System.out.println("rdp command: "+cmd.toString());
        return cmd;
    }

    /**
     * Locate a FreeRDP binary, or throw with an OS-appropriate install hint. Resolution order:
     * <ol>
     *   <li>the {@code JTERM_FREERDP} environment variable, if it points at an executable;</li>
     *   <li>next to the running jar — the jar's own directory and a {@code freerdp/} subfolder of
     *       it — so a binary can simply be dropped beside {@code jterm.jar} without touching PATH;</li>
     *   <li>the system {@code PATH}.</li>
     * </ol>
     */
    public static String resolveBinary() throws RdpUnavailableException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        List<String> names = windows ? WINDOWS_BINARIES : UNIX_BINARIES;

        String override = System.getenv("JTERM_FREERDP");
        if (override != null && !override.isBlank()) {
            File f = new File(override);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }

        File appDir = applicationDir();
        if (appDir != null) {
            for (File dir : List.of(appDir, new File(appDir, "freerdp"))) {
                String found = findInDir(dir, names);
                if (found != null) {
                    return found;
                }
            }
        }

        String found = findOnPath(names);
        if (found != null) {
            return found;
        }
        throw new RdpUnavailableException(installHint(os));
    }

    /** The directory of the running jar (or the classes dir in a dev run), or {@code null}. */
    private static File applicationDir() {
        try {
            java.net.URL loc = RdpProcess.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return null;
            }
            File f = new File(loc.toURI());
            // Packaged: f is jterm.jar → use its directory. Dev run: f is target/classes → use it.
            return f.isFile() ? f.getParentFile() : f;
        } catch (Exception e) {
            return null;
        }
    }

    private static String findInDir(File dir, List<String> names) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        for (String name : names) {
            File candidate = new File(dir, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findOnPath(List<String> names) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            String found = findInDir(new File(dir), names);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String installHint(String os) {
        if (os.contains("mac") || os.contains("darwin")) {
            return "FreeRDP was not found. Install it with: brew install freerdp";
        }
        if (os.contains("win")) {
            return "FreeRDP was not found. Install FreeRDP and put wfreerdp.exe on your PATH, "
                    + "or drop it next to jterm.jar (or in a freerdp\\ folder beside it), "
                    + "or set JTERM_FREERDP to its full path.";
        }
        return "FreeRDP was not found. Install it, e.g.: apt install freerdp2-x11 "
                + "(or freerdp3) / dnf install freerdp — or set JTERM_FREERDP to its full path.";
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public void destroyForcibly() {
        process.destroyForcibly();
    }

    public Process process() {
        return process;
    }
}
