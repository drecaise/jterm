# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`jterm` — a Java 21 Swing desktop terminal emulator: tabbed windows, a uniform 3×3
splittable pane grid, a saved-sessions sidebar (folders + SSH/RDP sessions with icons),
drag-and-drop session launching, input broadcast, light/dark theming, and SSH with
ssh-agent + key + password auth backed by an encrypted credential vault. Saved RDP
(Remote Desktop) sessions open a full-tab desktop driven by an external FreeRDP process
(see the RDP architecture note below).

## Build & run

**Maven is not on PATH here** — it was downloaded to `/home/mark/apache-maven-3.9.11`. Prefix it:

```bash
export PATH=/home/mark/apache-maven-3.9.11/bin:$PATH
mvn -q compile                       # compile
mvn -q exec:java                     # run from classes (launches the Swing GUI)
mvn -q package -DskipTests           # build the shaded fat jar → target/jterm.jar
java -jar target/jterm.jar           # run the jar
mvn -Pinstaller package              # native installer via jpackage (target/dist)
```

There is **no test suite** yet (`-DskipTests` is just defensive). Verification is done by
building + launching. There is a real display (`DISPLAY=:0`, Wayland), so the GUI launches,
but **no screenshot tool is installed** — verify headlessly by watching the startup log for
the `pty4j native` line and a spawned `/bin/bash -l`, and exercise library/protocol code with
small throwaway `javac`/`java` snippets against the resolved classpath
(`mvn -q -o dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt`).

### Dependency notes (non-obvious)
- **JediTerm is not on Maven Central** — it resolves from the JetBrains repo declared in
  `pom.xml`. `jediterm-core` is an *explicit compile dependency* because `jediterm-ui`
  declares it `runtime`-scoped (you'll get "cannot find symbol" on `TtyConnector`/`Color`
  etc. without it).
- **JNA** (`jna` + `jna-platform`) is an *explicit compile dependency* — it's only `optional`
  via java-keyring so it isn't on our tree otherwise. It's used solely by the RDP embedding code
  (getting an AWT canvas's native handle; Windows `SetParent` reparenting), referenced only from
  the on-demand RDP UI so it loads lazily.
- **RDP needs an external FreeRDP binary at runtime** (not a Maven dep): `xfreerdp`/`sdl-freerdp`
  on Linux/macOS, `wfreerdp.exe`/`sdl-freerdp.exe` on Windows. It is *not* bundled.
  `RdpProcess.resolveBinary()` searches, in order: the `JTERM_FREERDP` env var (full path), then
  next to the running jar (the jar's dir + a `freerdp/` subfolder beside it — so a binary can be
  dropped next to `jterm.jar` without touching PATH), then `PATH`; missing → a friendly
  `RdpUnavailableException` dialog with an install hint.
- When inspecting library APIs before coding against them, `javap`/`unzip -l` on the jars in
  `~/.m2/repository` is the fastest source of truth (done throughout this codebase's history).

## Architecture (the parts that span multiple files)

Package root `com.katmoda.jterm`. Entry point `app.Main` → `app.MainWindow`.

### Terminal sessions are connector-driven
`terminal.TerminalSession` is the abstraction a pane drives. Two impls:
- `terminal.local.LocalSession` — pty4j `PtyProcess`, wrapped by `PtyTtyConnector`
  (extends JediTerm's `ProcessTtyConnector`).
- `terminal.ssh.SshSession` — Apache MINA SSHD `ChannelShell`, wrapped by `SshTtyConnector`
  over the channel's inverted streams.

A `ui.pane.TerminalPane` hosts a JediTerm `JediTermWidget` and calls `setTtyConnector(...)`.
The connector handed to the widget is **not** the session's raw connector — `ui.grid.PaneGrid`
wraps it in `broadcast.BroadcastingTtyConnector` so keystrokes can fan out (see Broadcast).

### The pane grid is a uniform R×C model (not a binary split tree)
`ui.grid.PaneGrid` holds a `TerminalPane[3][3]` plus live `rows`/`cols` (1..3). Cells in
bounds may be empty (re-openable) or hold a pane. Splitting grows a dimension; closing empties
a cell and **collapses a fully-empty trailing row/column** so the grid stays rectangular.
The whole grid is re-laid-out via `GridBagLayout` with equal weights on every change. One
`PaneGrid` per tab (`JTabbedPane` in `MainWindow`).

### Global shortcuts bypass focus via a KeyEventDispatcher
JediTerm consumes key events, so `MainWindow.installShortcutDispatcher()` registers a single
`KeyboardFocusManager` dispatcher that matches `keymap.Keymap` bindings and **consumes** the
event (returns true). Menu items carry the same accelerators only for discoverability — the
dispatcher fires first, preventing double-execution. Bindings load from `keymap.json`
(defaults in `keymap.TermAction`).

### Theming flows through one abstraction
`ui.theme.ThemeManager` applies a FlatLaf LaF and exposes a `ui.theme.ThemeColors` record
(terminal fg/bg + 16 ANSI colors). `ui.theme.JTermSettingsProvider` (a JediTerm
`DefaultSettingsProvider`) and `AnsiPalette` translate those into JediTerm colors. Everything
reads colors *through* `ThemeColors`, so full configurability can be added later without
touching panes. Live terminal recolor on toggle is **not** implemented (JediTerm bakes default
fg/bg into each widget at creation); chrome recolors via FlatLaf `updateUI`.

### Drag-and-drop launches sessions into splits
`dnd.SessionTransferable` (SSH config) and `dnd.LocalTransferable` (local marker) are the drag
payloads. The sidebar tree and the "Local Terminal" button are drag sources; each `TerminalPane`
is a drop target. Drop position decides the split: top 60% → new column, bottom 40% → new row
(`dnd.DropRegion`). SSH connects async then splits; local splits synchronously.

### Sessions, icons, and persistence (Jackson JSON in the OS config dir)
`config.AppPaths` resolves the per-OS config dir (`~/.config/jterm`, `~/Library/Application
Support/jterm`, `%APPDATA%\jterm`). Stored there: `sessions.json` (recursive `session.FolderNode`
/ `session.SshSessionConfig` tree, polymorphic via a `type` discriminator), `icons.json`
(`icon.IconLibrary`: built-in SVGs under `resources/icons/` + user imports copied into
`<config>/icons/`), `keymap.json`, and `credentials.json` (see Security). `SshSessionConfig`
has a stable `id` (UUID) used as the vault key.

### SSH auth & the credential vault (security-critical, has sharp edges)
Auth order is publickey (agent → on-disk keys) then password — MINA tries them automatically;
`SshSession.connect` just registers identities and optionally `addPasswordIdentity`.

- **ssh-agent**: MINA's bundled `UnixAgentFactory` needs Apache APR/tomcat-native (not
  bundled) and reads the socket from *client properties*, not env. So this repo uses a
  custom `terminal.ssh.agent.JdkUnixAgentFactory` + `JdkAgentProxy` — a JDK-native
  Unix-domain-socket agent client (`UnixDomainSocketAddress`) reusing MINA's
  `AbstractAgentProxy` protocol layer. `SshSession.installAgent` also sets the
  `SSH_AUTH_SOCK` client property (with a login-shell fallback for desktop launches).
  **Windows is skipped** (its agent is a named pipe, not a socket) — a known follow-up.
- **Host keys**: `terminal.ssh.JtermKnownHostsVerifier` (TOFU + changed-key warning) against
  `~/.ssh/known_hosts`.
- **Vault**: `security.CredentialVault` stores SSH passwords AES-GCM-encrypted under a random
  vault key, itself wrapped by a PBKDF2 key from the user's master password. `security.VaultManager`
  unlocks it, transparently remembering the master password in the OS keyring via
  `security.MasterPasswordKeyring`. The keyring uses **per-OS native tooling on purpose**:
  `secret-tool` (Linux) and `security` (macOS) CLIs, and java-keyring's JNA backend (Windows
  only). java-keyring's dbus-java Linux backend **hangs** on some setups and is excluded in
  `pom.xml` — do not reintroduce it.

### RDP sessions run an external FreeRDP process in a full tab (not a pane)
A whole remote desktop in a 3×3 split makes no sense, so RDP is the one session type that is *not*
a `GridContent` in a `PaneGrid` — it's its own tab. Tabs are therefore either a `PaneGrid` or an
`rdp.RdpTab`; both implement `ui.TabContent` so `MainWindow.closeTabAt` disposes either uniformly
(no orphan process). `MainWindow.openRdpSession` is the single place that touches RDP, so the
`rdp`/`rdp.embed` packages + JNA load lazily on first open (the SFTP-isolation pattern).

- **No pure-Java RDP client is used** — the maintained-and-secure path is to drive **FreeRDP** as
  a child process. `rdp.RdpProcess` builds the `xfreerdp` command and **feeds the password via
  stdin (`/from-stdin`), never argv** (so it can't leak via `ps`). Host identity defaults to
  FreeRDP's TOFU store (`/cert:tofu`); a session may set `ignoreCertErrors` to use `/cert:ignore`
  (insecure) — needed for self-signed certs whose CN doesn't match the host (e.g. GNOME Remote
  Desktop), since `/cert:tofu` auto-accepts an unknown cert but still refuses a name mismatch.
  NLA/TLS are preserved; clipboard/drive/audio redirection is opt-in per session. Color depth
  defaults to **Auto** (`colorDepth == 0` → no `/bpp` flag at all), because some servers refuse a
  forced depth; 16/24/32 are selectable. FreeRDP's stderr is echoed to the console and kept in a
  ring buffer (`RdpProcess.recentOutput()`) so `RdpTab` shows the failure cause in-tab instead of a
  bare "disconnected".
- **Embedding** (`rdp.embed.WindowEmbedder`, chosen per-OS): **Linux/X11** passes
  `/parent-window:<xid>` (canvas window id via JNA `Native.getComponentID`); **Windows** launches
  `wfreerdp` then `SetParent`-reparents its top-level window into the canvas (JNA `User32`) and
  merges its input queue with the AWT UI thread via `AttachThreadInput` (else the embedded foreign
  window gets no keyboard input); `RdpTab` forwards focus to it via `WindowEmbedder.focus()`.
  **macOS cannot embed a foreign window**, so it runs FreeRDP detached with an in-tab status panel.
  The embedded window is sized to fill the canvas on attach and on every resize (`onResize` →
  `MoveWindow`); the remote desktop tracks that size via `/dynamic-resolution`. Windows reparenting
  (incl. keyboard/resize) and the macOS fallback are coded to spec but **must be verified on those
  OSes** — they can't be tested from this Linux dev box.
- **Credentials** reuse the same `CredentialVault`/`VaultManager`, keyed by `RdpSessionConfig.id`
  exactly like SSH (`MainWindow.rdpPassword` → shared `resolvePassword`).

### Desktop integration (GNOME/Wayland icon)
The window icon (`app.AppIcon`) is set via `setIconImages`, but GNOME ignores it for the
dash/Alt-Tab and matches a `.desktop` file by `WM_CLASS`. So `app.Main` sets the X11 WM_CLASS
to `jterm` via reflection on `sun.awt.X11.XToolkit` (enabled by `Add-Opens:
java.desktop/sun.awt.X11` in the jar manifest), and `packaging/install-desktop-integration.sh`
installs the icon + a `StartupWMClass=jterm` desktop file.

## Conventions
- Java records/sealed-where-it-helps; one public type per file; package-private helpers kept
  next to their user. No Lombok, no DI framework — plain constructors and small singletons
  (`ThemeManager.get()`, `IconLibrary.get()`, `VaultManager.get()`).
- Swing work stays on the EDT; blocking work (SSH connect, agent I/O) runs off it
  (`SwingWorker`), and password/vault prompts are resolved on the EDT *before* the worker.
