package com.katmoda.jterm.terminal.local;

import com.jediterm.terminal.TtyConnector;
import com.katmoda.jterm.terminal.TerminalSession;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A local shell running in a pseudo-terminal via pty4j. Created by keyboard
 * splits and the sidebar's "Local Terminal" entry.
 */
public final class LocalSession implements TerminalSession {

    private final PtyProcess process;
    private final PtyTtyConnector connector;
    private final String title;

    private LocalSession(PtyProcess process, String title) {
        this.process = process;
        this.connector = new PtyTtyConnector(process, title);
        this.title = title;
    }

    /** Starts a login/interactive shell in {@code workingDir} (or the user's home if null). */
    public static LocalSession start(Path workingDir) throws IOException {
        String dir = (workingDir != null ? workingDir : Path.of(System.getProperty("user.home", "."))).toString();

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.putIfAbsent("TERM_PROGRAM", "jterm");

        PtyProcess process = new PtyProcessBuilder(defaultShellCommand())
                .setEnvironment(env)
                .setDirectory(dir)
                .setInitialColumns(80)
                .setInitialRows(24)
                .start();

        String label = (workingDir != null) ? lastSegment(workingDir) : "local";
        return new LocalSession(process, label);
    }

    private static String[] defaultShellCommand() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String comspec = System.getenv("COMSPEC");
            return new String[]{comspec != null && !comspec.isBlank() ? comspec : "cmd.exe"};
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "/bin/bash";
        }
        // Login shell so the user's normal environment is loaded.
        return new String[]{shell, "-l"};
    }

    private static String lastSegment(Path p) {
        Path name = p.getFileName();
        return name != null ? name.toString() : p.toString();
    }

    @Override
    public TtyConnector connector() {
        return connector;
    }

    @Override
    public String title() {
        String cwd = readWorkingDirectory();
        return cwd != null ? cwd : title;
    }

    /** Live working directory of the shell (Linux /proc), with $HOME shown as {@code ~}. */
    private String readWorkingDirectory() {
        try {
            Path link = Path.of("/proc", Long.toString(process.pid()), "cwd");
            if (Files.exists(link)) {
                String path = Files.readSymbolicLink(link).toString();
                String home = System.getProperty("user.home");
                if (home != null && path.startsWith(home)) {
                    path = "~" + path.substring(home.length());
                }
                return path;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        process.destroy();
    }

    /** Used by the connector helper. */
    static java.nio.charset.Charset charset() {
        return StandardCharsets.UTF_8;
    }
}
