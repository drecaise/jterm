# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`jterm` — a Java 21 Swing desktop terminal emulator: tabbed windows, a uniform 3×3
splittable pane grid, a saved-sessions sidebar (folders + SSH sessions with icons),
drag-and-drop session launching, input broadcast, light/dark theming, and SSH with
ssh-agent + key + password auth backed by an encrypted credential vault.

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
