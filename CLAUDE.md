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
```

Native installers (Windows `.msi`, macOS `.dmg`) and the Linux `.flatpak` are built by the
`Release` GitHub Actions workflow (`.github/workflows/release.yml`) — on a `v*.*.*` tag push it
attaches all three plus the fat jar to a GitHub Release; manual dispatch builds them as
downloadable workflow artifacts. jpackage is driven directly from the workflow (the old
`-Pinstaller` Maven profile was removed); the Flatpak manifest lives in `packaging/flatpak/`.

There **is** a JUnit 5 suite under `src/test/java` (`mvn -q test`), but it only covers headless
logic — credential/vault resolution, session-store inheritance, SFTP transfer maths, agent socket
trust. Nothing Swing-facing is tested, so a green suite is not evidence the UI works.

Surefire redirects `user.home` to `target/test-home` (`pom.xml`), so tests that reach `AppPaths`
get an empty config dir instead of the developer's real `~/.config/jterm` — needed because
`SessionStore`'s constructor *writes* (schema migrations, below). Don't remove it, and don't write
tests that assume real config data exists.

For anything the suite doesn't reach, verify by building + launching. There is a real display
(`DISPLAY=:0`, Wayland), so the GUI launches, but **no screenshot tool is installed** — verify
headlessly by watching the startup log for the `pty4j native` line and a spawned `/bin/bash -l`,
and exercise library/protocol code with small throwaway `javac`/`java` snippets against the
resolved classpath (`mvn -q -o dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt`).

For SSH protocol behaviour that snippet approach scales further than it looks: `sshd-core` ships
the **server** side too, so an in-process `SshServer` (rejecting publickey, accepting a scripted
password) exercises the real MINA auth state machine end to end. That is how the interactive-auth
fallback was verified — run such a JVM with `-Duser.home=<tempdir>` so it can't touch the real
`~/.ssh/known_hosts` or `~/.config/jterm`.

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

### The Flatpak local shell runs behind a host-side PTY agent (two stacked PTYs)
A sandboxed shell would see the runtime's filesystem, so `terminal.local.FlatpakHost` runs it on
the host via `flatpak-spawn --host`. That alone is **not enough**: pty4j allocates the PTY *inside*
the sandbox and its terminal semantics don't cross the portal. The host shell can't claim a
controlling terminal owned by a sandbox-side session (`cannot set terminal process group` / `no job
control`), `ttyname()` fails because bubblewrap gives the sandbox its own devpts instance
(`tty: ttyname error: No such device`), and SIGWINCH is delivered sandbox-side and never forwarded.

So `resources/flatpak/pty-agent.py` runs on the host, allocates a **second PTY there**
(`pty.fork()` does `setsid` + `TIOCSCTTY` properly), runs the login shell in it, and relays bytes
over the sandbox PTY. The sandbox PTY is demoted to transport **plus the window-size channel** —
`TIOCGWINSZ` on fd 0 still reports live values across the boundary, so the agent polls it and
mirrors changes onto the host PTY. `TerminalSession`/`PtyTtyConnector` are unaware; resize is still
just `process.setWinSize(...)`.

The agent is also the **only** source of the shell's working directory in the sandbox:
`LocalSession.workingDirectory()` returns null under Flatpak because the pty4j child is the
sandbox-side `flatpak-spawn` (whose cwd never changes) and the sandbox cannot see the host's
`/proc`. The agent *can* — the shell is its child — so it polls `/proc/<pid>/cwd` and splices an
**OSC 7** sequence into the relay, which `terminal.cwd.OscCwdScanner` picks up exactly as it would
from a remote shell. Splicing is gated on `Boundary`, a byte-level tracker that only injects when the
stream is between escape sequences *and* between UTF-8 characters; it recognises 7-bit introducers
only, because an 8-bit C1 byte is indistinguishable from a UTF-8 continuation byte and treating one
as CSI would wedge it permanently. Sharp edges, all commented in place:
- The agent must keep fds **blocking**; 0/1/2 share one open file description on a PTY slave, so
  making fd 0 non-blocking to poll it also makes writes to fd 1 `EAGAIN` and drops output.
- Fd 0 goes to **raw** mode, or the sandbox PTY's line discipline double-processes echo/`ONLCR`.
- Dead ends already measured: `setsid --ctty` gets `EPERM` (the sandbox owns the ctty), and
  `script(1)` fixes job control + `ttyname` but freezes the window size — it's the *fallback* when
  the host has no `python3`, ahead of a bare shell.
- Never forward the sandbox's `SSH_AUTH_SOCK` (`/run/flatpak/ssh-auth` doesn't exist on the host).
  The probe reads the host's from `systemctl --user show-environment`; when it can't, the variable
  is left **unset** so rc files that repair it when empty still can.

`FlatpakHost.probe()` gathers login shell + `python3` + `script` + `SSH_AUTH_SOCK` in **one**
`flatpak-spawn` round trip, cached process-wide (it used to run per session start). It needs
`--env=XDG_RUNTIME_DIR` forwarded or `systemctl --user` can't reach the user manager.

The **Snap** has none of this: classic confinement spawns the shell directly, in the same namespace
and devpts as the PTY. Its analogous defect is env leakage, handled by `terminal.local.SnapEnvironment`.

The sandbox also breaks `java.awt.Desktop`: AWT resolves a URL handler through gio, which sees only
the runtime's filesystem, so `isSupported(BROWSE)` is **false** in the Flatpak and every link (Help →
User Manual, the About dialog's) silently did nothing while working in the RPM. `app.BrowserLauncher`
is the single entry point for opening URLs and falls back to `xdg-open` (the runtime's
flatpak-xdg-utils shim → OpenURI portal), then `flatpak-spawn --host xdg-open`, then a dialog showing
the URL — off the EDT, since the portal round trip blocks. **Don't call `Desktop.browse` directly.**

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

### Modal prompts focus their input, via `DialogFocus` rather than `JOptionPane`
`JOptionPane` focuses the **OK button**, so a password prompt can't be typed into until the user
clicks or tabs. Any OK/Cancel dialog whose point is a text field therefore goes through
`ui.component.DialogFocus.showConfirm(parent, message, title, initialFocus)` — same
`OK_OPTION`/`CANCEL_OPTION`/`CLOSED_OPTION` return, so call sites read unchanged. All seven
prompts in `ui.security.MasterPasswordDialog` and the sidebar's folder/session editors use it;
**reach for it instead of `JOptionPane.showConfirmDialog` when adding a prompt.**

It claims focus twice on purpose, because the two mechanisms that decide it are independent: the
dialog's `FocusTraversalPolicy` supplies the component focused on first activation, and
`JOptionPane.selectInitialValue()` (called from the pane's own window-focus listener) then
re-focuses the default button. Dead end already measured: an `AncestorListener` posting
`requestFocusInWindow()` through `invokeLater` — the obvious fix, and what the sidebar used to do
— lands *before* that listener as often as after, and was verified to focus the field in only 2 of
9 prompts. The `initialFocus` client property those prompts used to set was never read by anything
(it is not a FlatLaf property either).

### Theming flows through one abstraction
`ui.theme.ThemeManager` applies a FlatLaf LaF and exposes a `ui.theme.ThemeColors` record
(terminal fg/bg + 16 ANSI colors). `ui.theme.JTermSettingsProvider` (a JediTerm
`DefaultSettingsProvider`) and `AnsiPalette` translate those into JediTerm colors. Everything
reads colors *through* `ThemeColors`, so full configurability can be added later without
touching panes. Live terminal recolor on toggle is **not** implemented (JediTerm bakes default
fg/bg into each widget at creation); chrome recolors via FlatLaf `updateUI`.

`ThemeManager` also owns the **application UI scale/font** (Settings → Appearance;
`AppSettings.uiScalePercent` / `uiFontFamily` / `uiFontSize`) — the chrome only, never the
terminal panes. It drives FlatLaf's `UIScale.setZoomFactor`, which scales fonts *and* the metrics
FlatLaf derives through `UIScale`. Applied in `applyLaf()` (not just `install()`) so it survives a
light/dark toggle, which rebuilds the defaults from scratch. **Order is load-bearing** and is
documented on `applyUiScaleAndFont()`: the unzoomed font size goes in *first* (FlatLaf re-derives
its scale factor from `defaultFont`), the zoom *last* — reversed, the two compound (150% + 18pt
measured 263%). The `setZoomFactor(1f)` reset before the real value is required because the call
returns early on an unchanged value. Read once at startup, hence the restart notice in Preferences.

When sizing anything in pixels, know which side of `UIScale` it is on: `FlatSVGIcon`,
`ScaledImageIcon` (what `TitlePane.iconSize` feeds) and so `IconLibrary.icon(id, size)` all apply
the scale *themselves* — pass design sizes (`SessionIcon.DEFAULT_SIZE`), never `UIScale.scale(16)`,
or icons come out twice as large. Plain Swing values — `setPreferredSize`/`setMinimumSize`, `new
Font(...)` sizes, hand-drawn `Graphics` metrics — are *not* scaled and must be wrapped in
`UIScale.scale(...)`.

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

`sessions.json` is **schema-versioned** via `schemaVersion` on the *root* `FolderNode` (absent = v0;
`@JsonInclude(NON_NULL)` keeps it off sub-folders). `SessionStore`'s constructor runs
`session.SessionMigrations.migrate` and then stamps + saves the version **even when nothing
changed** — that stamp is the only thing making a migration one-shot, so a value the user later
chooses that happens to match what a migration rewrites is safe. v1 exists because per-session
terminal font size gained an "inherit" state (`0`) that the old spinner-based editor could never
produce, leaving every saved session pinned to its seed value of 14 and deaf to Settings →
Terminal Settings → Font Size; the migration clears exactly 14 and nothing else.

Per-session terminal settings inherit **only** session → global, resolved by
`AppSettings.resolve(...)` at `ConnectionService.java` (there is no folder-level tier for these, unlike
user/tab-color/key-path/keep-alive). Blank string or `0` means inherit; `ui.component.TerminalSettingsForm`
is the single editor for all four fields and maps its `(Default)` entry back to those sentinels —
constructed with `allowDefault=false` from Preferences (editing the globals themselves, so it must
never return a sentinel) and `true` from the session dialog. Local sessions skip resolution entirely
(`AppSettings.defaultProfile()`), which is why a bug here shows up as "local follows the preference,
SSH doesn't".

The sidebar's **Quick Connect** field (`ui.sidebar.SessionSidebar.quickConnect`) parses
`[user@]host[:port]` via `session.SshTarget` and hands the resulting **ephemeral**
`SshSessionConfig` to the ordinary `onOpenSsh(..., NEW_TAB)` callback, so it inherits the whole
saved-session path (tab title/icon, async connect, error dialog, restart factory) for free. Two
non-obvious pieces make that safe: the config is never added to `SessionStore`, and
`ancestorsOf` returns an *empty* chain for a node outside the tree — which is precisely why a blank
user/key/keep-alive resolves straight to the global defaults with no extra code. And
`SshSessionConfig.ephemeral` (`@JsonIgnore`, never serialized) tells `security.CredentialResolver`
to suppress the *"remember"* affordance for both passwords and key passphrases: the config's id is
a random UUID that dies with the tab, so a vault entry under it could never be reused or deleted.

### SSH auth & the credential vault (security-critical, has sharp edges)
Auth order is publickey (agent → on-disk keys) then password — MINA tries them automatically;
`SshSession.connect` just registers identities and optionally `addPasswordIdentity`.

- **Interactive fallback**: when agent/key auth is exhausted, `SshConnect` prompts for a password
  rather than failing. This rides MINA's own extension point rather than a reconnect loop:
  `terminal.ssh.JtermUserInteraction` (a MINA `UserInteraction`) is installed on the client, and
  `UserAuthPassword.resolveAttemptedPassword` consults it once the registered password identities
  are used up, while `UserAuthKeyboardInteractive` routes PAM/2FA challenges to it. Because MINA
  only runs a method the server *offers*, a key-only server never produces a prompt — the check
  is free. `security.CredentialResolver.interactiveAuth(cfg)` implements the
  `SshConnect.InteractiveAuth` SPI with Swing prompts; `AppSettings.promptPasswordOnAuthFailure`
  (Settings → General, default on) gates it, read at the *call sites*
  (`ConnectionService.interactiveAuth`) so the resolver stays headless-testable.
  Three sharp edges, all commented in place: MINA's `PASSWORD_PROMPTS` cap is *per method*, so
  `JtermUserInteraction` imposes its own 3-per-hop total; the 30 s `AUTH_TIMEOUT` would kill a
  connect while the user is still typing, so `SshConnect.awaitAuth` waits in slices and only gives
  up on server silence, not think-time; and the prompt blocks a MINA NIO worker, hence the
  `NIO_WORKERS` floor so a jump host's port-forward keeps flowing. A failed hop now throws
  `terminal.ssh.SshAuthException` (`Authentication failed for user@host`) instead of surfacing raw
  MINA text.

- **ssh-agent**: MINA's bundled `UnixAgentFactory` needs Apache APR/tomcat-native (not
  bundled) and reads the socket from *client properties*, not env. So this repo uses a custom
  `terminal.ssh.agent.JdkAgentFactory`, and `terminal.ssh.agent.AgentSupport` picks the
  per-OS agent source(s): on Linux/macOS a JDK-native Unix-domain-socket client
  (`JdkAgentProxy` over `UnixDomainSocketAddress`) reusing MINA's `AbstractAgentProxy`
  protocol layer; on Windows the OpenSSH named-pipe agent (`WindowsPipeAgentProxy`) and/or
  PuTTY **Pageant** (`PageantAgentProxy`), fronted by `CompositeSshAgent` when more than one
  is live. `SshConnect.installAgent` also sets the `SSH_AUTH_SOCK` client property (with a
  login-shell fallback for desktop launches).
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

The jar-manifest `Add-Opens` does **not** reach the jpackage RPM: its launcher starts the app
in classpath mode (`app.classpath`/`app.mainclass` in `jterm.cfg`), where manifest attributes
are ignored. So the `build-rpm` workflow job passes `--java-options "--add-opens=…"` explicitly
and swaps in the shared `packaging/linux/jterm.desktop` (a jpackage resource override with
`StartupWMClass=jterm`) via `--resource-dir` — remove either and GNOME shows two dock icons.
Since jpackage takes only one `--resource-dir`, that job assembles a `rpmres/` dir holding both
that desktop file and the RPM-specific `packaging/rpm/jterm.spec` (based on JDK 21's
`template.spec`): the spec's `%install` relocates the generated desktop entry to an RPM-owned
`/usr/share/applications/jterm.desktop`, replacing the stock `xdg-desktop-menu` scriptlets
that copied `jterm-jterm.desktop` untracked into `/usr/local/share/applications`. The RPM is
built by a matrix over Rocky Linux 9 + 10 containers, so the `%{?dist}` tag in the spec's
`Release` field yields `.el9`/`.el10` from the build host. The **DEB** (`build-deb`, Ubuntu
26.04 container, `jpackage --type deb`) points `--resource-dir` straight at `packaging/linux`.

## Conventions
- Java records/sealed-where-it-helps; one public type per file; package-private helpers kept
  next to their user. No Lombok, no DI framework — plain constructors and small singletons
  (`ThemeManager.get()`, `IconLibrary.get()`, `VaultManager.get()`).
- Swing work stays on the EDT; blocking work (SSH connect, agent I/O) runs off it
  (`SwingWorker`), and password/vault prompts are resolved on the EDT *before* the worker.
