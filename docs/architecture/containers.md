# Containers

**C4 Level 2.** For a desktop app "containers" is a slightly awkward fit. Almost everything runs
in one JVM process; the exception is RDP, whose protocol engine runs in a helper process of its
own, one per connection. This page names both and the OS-native peers, and states the transport for
each.

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

The JVM process holds every component except the RDP protocol engine — no watchdog, no split of
UI from logic.

## The RDP helper container

`jterm-rdp-helper` (`native/rdp-helper/`) is a small C client built per OS that embeds FreeRDP 3.
jterm starts one per RDP connection and speaks a versioned binary protocol with it over its
stdin/stdout; the helper sends dirty rectangles and cursor shapes, jterm paints them and sends
keyboard, mouse and resize input back. It is resolved only from the install directory, never from
`PATH`, because `CONNECT` carries the password. See
[Components — RDP](components-rdp.md) and [ADR 0012](adr/0012-rdp-via-out-of-process-freerdp.md).

## OS-native peers

| Peer | Transport from jterm | Notes |
| --- | --- | --- |
| Remote sshd | TCP + Apache MINA SSHD `ClientSession` / `ChannelShell` | Multiplexed for shell, SFTP, and tunnels. |
| ssh-agent | JDK Unix-domain socket, Windows named pipe, or Pageant via `WM_COPYDATA` | See [ADR 0002](adr/0002-custom-jdk-agent-factory.md) for why the JDK client replaces MINA's. |
| OS keyring | Subprocess (`secret-tool`, `security`) or JNA (`java-keyring`, Windows only) | See [ADR 0003](adr/0003-exclude-java-keyring-linux-dbus.md) and [ADR 0005](adr/0005-per-os-keyring-clis.md). |
| Local shell | pty4j `PtyProcess` piped stdin/stdout/stderr | Child inherits environment; login shell (`-l`). |
| Local file system | `JsonStore` atomic write (tmp + `Files.move` REPLACE_EXISTING) | Config dir is per-OS (`~/.config/jterm`, `~/Library/Application Support/jterm`, `%APPDATA%\jterm`). |
| Remote desktop server | From the **helper**: RDP over TCP with TLS and NLA (FreeRDP) | The JVM never opens this connection itself. |
| Cisco OpenH264 host | HTTPS download, pinned SHA-256 | Linux only, on the user's opt-in; see [Components — RDP](components-rdp.md). |

## Threading model at this level

- **Swing EDT** — all UI, all dialogs, all credential prompts, all vault crypto.
- **JediTerm reader thread** — pulls bytes off the `TtyConnector` and feeds the terminal
  buffer. Owned by the widget; jterm never blocks it.
- **`SwingWorker` pool** — used for one-shot blocking operations: SSH connect, SFTP
  transfer, session export/import.
- **SSH keep-alive scheduled executor** — one per `SshSession`; sends periodic
  no-op requests to keep NATs and idle-timeout servers happy.
- **RDP reader, stderr and reaper threads** — three per running helper; the reader hands frames
  and events to the EDT. A session that shares its clipboard adds one `rdp-clipboard` thread.

The [Runtime views](runtime-views.md) page shows how these threads hand off during an
SSH connect and during broadcast writes.

## Why not more containers?

For SSH and the terminal there isn't a natural process boundary. RDP is the exception, and the
reason is specific: FreeRDP is native code that parses data a *server* controls and regularly fixes
memory-safety bugs, so it runs where a crash or exploit cannot take down every SSH session or reach
the unlocked vault ([ADR 0012](adr/0012-rdp-via-out-of-process-freerdp.md)). The other
alternatives were rejected because they buy nothing:

- Splitting the SSH engine into a helper process would add IPC on the hot path (every
  keystroke) with no security benefit — the vault decryption still has to happen
  somewhere the UI can prompt.
- Keeping the JediTerm engine out-of-process was JetBrains' original plan and the
  library still bears that shape; embedding it in-process is cheaper and lets us
  intercept writes via `BroadcastingTtyConnector` cleanly.
