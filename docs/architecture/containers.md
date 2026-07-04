# Containers

**C4 Level 2.** For a desktop app "containers" is a slightly awkward fit — everything
inside jterm runs in one JVM process. This page names that process and its four OS-native
peers, and states the transport for each.

```mermaid
--8<-- "architecture/model/generated/containers.mmd"
```

## The single JVM container

- **Runtime** — Java 21 (the fat jar targets Java 21; jpackage bundles a JRE with the
  installers).
- **Toolkit** — Swing with FlatLaf. All UI code runs on the EDT; blocking work moves off
  to `SwingWorker`.
- **Terminal engine** — [JediTerm](https://github.com/JetBrains/jediterm) hosts the
  actual PTY buffer, ANSI parsing, and rendering. jterm feeds it a `TtyConnector` and
  reads back through it.
- **Entry point** — `com.katmoda.jterm.app.Main` sets a Linux `WM_CLASS` (via reflection
  on `sun.awt.X11.XToolkit`) so GNOME associates the running window with the installed
  `.desktop` file, then hands off to `MainWindow`.

The JVM process holds every component — there is no separate helper process, no watchdog,
no split of UI from logic.

## OS-native peers

| Peer | Transport from jterm | Notes |
| --- | --- | --- |
| Remote sshd | TCP + Apache MINA SSHD `ClientSession` / `ChannelShell` | Multiplexed for shell, SFTP, and tunnels. |
| ssh-agent | JDK Unix-domain socket, Windows named pipe, or Pageant via `WM_COPYDATA` | See [ADR 0002](adr/0002-custom-jdk-agent-factory.md) for why the JDK client replaces MINA's. |
| OS keyring | Subprocess (`secret-tool`, `security`) or JNA (`java-keyring`, Windows only) | See [ADR 0003](adr/0003-exclude-java-keyring-linux-dbus.md) and [ADR 0005](adr/0005-per-os-keyring-clis.md). |
| Local shell | pty4j `PtyProcess` piped stdin/stdout/stderr | Child inherits environment; login shell (`-l`). |
| Local file system | `JsonStore` atomic write (tmp + `Files.move` REPLACE_EXISTING) | Config dir is per-OS (`~/.config/jterm`, `~/Library/Application Support/jterm`, `%APPDATA%\jterm`). |

## Threading model at this level

- **Swing EDT** — all UI, all dialogs, all credential prompts, all vault crypto.
- **JediTerm reader thread** — pulls bytes off the `TtyConnector` and feeds the terminal
  buffer. Owned by the widget; jterm never blocks it.
- **`SwingWorker` pool** — used for one-shot blocking operations: SSH connect, SFTP
  transfer, session export/import.
- **SSH keep-alive scheduled executor** — one per `SshSession`; sends periodic
  no-op requests to keep NATs and idle-timeout servers happy.

The [Runtime views](runtime-views.md) page shows how these threads hand off during an
SSH connect and during broadcast writes.

## Why not more containers?

There isn't a natural process boundary here. Alternatives were rejected because they buy
nothing:

- Splitting the SSH engine into a helper process would add IPC on the hot path (every
  keystroke) with no security benefit — the vault decryption still has to happen
  somewhere the UI can prompt.
- Keeping the JediTerm engine out-of-process was JetBrains' original plan and the
  library still bears that shape; embedding it in-process is cheaper and lets us
  intercept writes via `BroadcastingTtyConnector` cleanly.
