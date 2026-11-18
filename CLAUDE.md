# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`jterm` — a Java 21 Swing desktop terminal emulator: tabbed windows, a uniform 3×3
splittable pane grid, a saved-sessions sidebar (folders + SSH/RDP sessions with icons),
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
downloadable workflow artifacts. The Release body is extracted from the commit subjects since the
previous version tag by `.github/release-notes.sh` (run it locally to preview:
`.github/release-notes.sh v1.9.0`) — GitHub's own `generate_release_notes` lists *pull requests*,
and commits here land straight on master, so on its own it produces nothing but a compare link
(it stays enabled so that link is still appended). jpackage is driven directly from the workflow
(the old `-Pinstaller` Maven profile was removed); the Flatpak manifest lives in
`packaging/flatpak/`. The `build-flatpak` job copies the container image's preinstalled
`/var/lib/flatpak` into the writable layer before building: whenever Flathub publishes a newer
SDK/runtime build than the image holds, flatpak-builder updates it, and in the image's lower
overlay layer that fails with "Invalid cross-device link" (first hit 2026-11-13). The whole job can
be reproduced locally with Podman in the same image (`--privileged`).

The `Security` workflow's `packaging-eol` job (`.github/check-packaging-eol.sh`, runnable locally)
fails when the Flatpak runtime or the snap base has gone end of life — neither is a Maven
dependency, and a retired one still builds. Bumping the Flatpak runtime touches **two** places: the
manifest's `runtime-version` and the `freedesktop-<version>` container tag of `build-flatpak` in
`release.yml`.

Its `freerdp` job (`.github/check-freerdp.sh`, runnable locally; `GITHUB_TOKEN` avoids the API rate
limit) fails on a published FreeRDP advisory affecting the bundled version, on a newer 3.x release
more than 14 days old, and on drift between the pins. **Bumping FreeRDP touches two places**:
`native/rdp-helper/FREERDP_VERSION` and the `freerdp` module's tarball url + `sha256` in the
Flatpak manifest (the script verifies the hash against the real tarball).

FreeRDP is built **patched** (`native/rdp-helper/patches/*.patch`, applied by all three
`build-freerdp-*` scripts and listed one by one in the Flatpak manifest: a new patch needs a line
there). Both patches exist because FreeRDP reads **system-wide JSON files**, and on Windows the
system-wide folder is `%ProgramData%\jterm\jterm-rdp-helper\`, which any local user can create
(measured on the VM), so one user of a shared PC could reconfigure every other user's helper:
- `0001` removes `certificates.json`, whose `"ignore": true` accepts any certificate before pins or
  the helper's callback are consulted. Each build script fails if the string `deny-userconfig` is
  still in the built library.
- `0002` makes both JSON loaders return nothing for the system-wide location. That covers
  `settings-defaults-client.json` (any setting as a default, certificate checking and proxies
  included), the CredSSP file (its `SspiModule` names a library that is then *loaded*), the
  NTLM/Negotiate files and `remotefx.json`. Found by running the helper under `strace`; do that
  again after a FreeRDP bump (`strace -f -e trace=openat` around `smoke-test.py`) and look for new
  config names.
A patch that stops applying fails the build; regenerate it against the new tag rather than dropping
it. Not covered: on Linux and macOS WinPR's registry emulation still looks for
`<build prefix>/etc/.../HKLM.reg`, a path only root can create on a user's machine.

Its `macos-deps` job (`.github/check-macos-deps.sh`, same conventions) covers the OpenSSL and cJSON
the macOS helper bundles: a newer OpenSSL patch release in the pinned series (3.5, LTS) or a newer
cJSON, more than 14 days old, fails, as does a pinned sha256 that no longer matches. Both pins
(version + sha256) live in `native/rdp-helper/build-freerdp-macos.sh`; bumping either also means
`THIRD-PARTY-LICENSES.md` and `src/main/resources/third-party-licenses.txt`.

Its `pcsc-lite` job (`.github/check-pcsc-lite.sh`, same conventions; try a candidate with
`PCSC_LITE_CHECK_VERSION=<version>`) covers the pcsc-lite client library the **Flatpak** builds
from a pinned tarball, because the 25.08 runtime has none (RPM/DEB/Snap use the distribution's).
The pin is the module's url + `sha256` in the Flatpak manifest; a bump also means the two license
files. The client must still talk to older host pcscds: 2.5.x speaks protocol 4:6 and falls back
to 4:4 when refused (checked live against 2.2.3), so check that fallback on a bump. Inside the
Flatpak `JnaPcsc` searches `/app/lib` and accepts a read-only `/usr` or `/app` mount in place of
root ownership. Building the Flatpak locally in rootless podman needs `--disable-rofiles-fuse`
(CI doesn't).

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

### A text selection is tracked across scrolls, not dropped

Two things had to change: JediTerm clears the selection whenever the screen scrolls, and it does
not report a `CSI L`/`CSI M` line move to the display at all.

JediTerm 3.70 clears the selection in four places, and only one of them is left alone here:
`TerminalPanel.mousePressed` (a fresh left-click — correct), `handleCopy(unselect)` (correct),
`TerminalPanel.scrollArea` (**unconditional**, so every line of output that pushed the screen up
lost the highlight), and the wheel listener registered inside `addTerminalMouseListener`, which
drops the selection *before* forwarding a wheel event to a mouse-aware program. Panning the
viewport needs no help at all — selection rows are absolute and `paintComponent` intersects them
against `myClientScrollOrigin`, so wheeling through the scrollback already tracks.

`JtermTerminalPanel.scrollArea` fixes the buffer-scroll case with the arithmetic JediTerm itself
uses on resize (`TerminalTextBufferResize` → `selection.shiftY(-screenLinesToMove)`): a scroll
region moving by `dy` renumbers its rows by `dy`, so shifting the selection keeps it on its own
characters. `ui.pane.SelectionScroll` holds that geometry as a pure function (hence unit-tested)
and is deliberately conservative — it shifts only when **both** ends move by the same amount, and
clears otherwise. A selection straddling a partial `CSI r` region, or pushed out of the buffer, is
dropped rather than guessed at; a wrong shift would leave a highlight sitting on text the user
never chose, which a later copy would pick up.

Sharp edges, all commented in place:
- **`super.scrollArea` must still run.** It owns the private `scrollDy` counter that
  `updateScrolling` drains to keep the scrollbar in step, and nothing else can reach it. So the
  selection is captured, `super` clears it, and the shifted copy is put back.
- **The panel is called before the buffer scrolls.** `JediTerminal.scrollArea` runs
  `myDisplay.scrollArea(...)` *then* `myTerminalTextBuffer.scrollArea(...)`, so the history count
  read in the override is the pre-scroll one and the rows about to be appended are added by hand.
  When the scrollback is at its cap those rows evict the oldest history instead of growing it —
  caught a moment later by `linesDiscardedFromHistory`, which sees the real count.
- **The restore must not notify.** `TerminalPane.installCopyOnSelect` copies on *every* selection
  event, so routing a shift through `updateSelection` would re-push the same text to the clipboard
  once per scrolled line, overwriting whatever the user had copied elsewhere. `restoreSelection`
  therefore writes the private `mySelection` field directly; `clearSelectionOnEdt` keeps using the
  setter, because a genuine clear *should* notify. It publishes a freshly built `TerminalSelection`
  rather than mutating one with `shiftY`, so the EDT can never paint a half-shifted selection.
- **The auto-clear compares text, not rows.** `linesChanged(fromIndex)` says only where a change
  *starts*, which is not enough to decide anything: scrolling forward in `less` writes the newly
  exposed line at the bottom of the screen (below the selection, harmless), while scrolling back
  emits a reverse index and writes it at the **top** (above the selection). A "did anything change
  at or above my last row?" rule reads the second as a hit — which is why scrolling up in a pager
  dropped the highlight while scrolling down kept it. So `changeAffectsSelection` is now only a
  cheap filter, and the verdict comes from `verifySelectionSoon` comparing the live selected text
  against `selectedSnapshot`, the text captured when the selection was made. Bursts of output
  coalesce into one check.
- **Reading the selection and its snapshot needs the buffer lock.** `JediTerminal` holds that lock
  across a whole scroll (display first, buffer second), so a verification that doesn't take it can
  land mid-`scrollArea` and see the shifted selection beside a snapshot `super`'s clear has just
  nulled — throwing away a good highlight, intermittently and under load only. `restoreSelection`
  also writes the snapshot *before* the selection field for the same reason.
- **`CSI L`/`CSI M` never reach the display.** `JediTerminal.insertLines`/`deleteLines` call
  `myTerminalTextBuffer` straight out, with no `myDisplay.scrollArea(...)` beside it, so nothing in
  the panel hears about a line move made that way. This is not exotic — it is how vim scrolls
  backwards: `CTRL-E` sets a scroll region and sends a newline at its bottom (a real scroll),
  `CTRL-Y` sets the same region and sends `CSI L` at the top, which is exactly why scrolling down in
  vim kept a selection and scrolling up lost it. `ui.pane.JtermJediTerminal` (installed through
  `JediTermWidget.createTerminal`) overrides both to call
  `JtermTerminalPanel.shiftSelectionForLineChange` first. It must **not** run `super.scrollArea`'s
  `scrollDy` bookkeeping — JediTerm never counted this movement, so adding to it would skew the
  scrollbar — and these rows are **discarded, not appended to history**, even at row 1. That last
  point is why `SelectionScroll` takes an explicit `toHistory` flag instead of inferring it from
  `regionTop == 0 && dy < 0`, which is only true of the `scrollArea` path.
- Three cached reflective handles (`mySelection`, `updateSelection`, `myScrollRegionBottom`) pin
  this to JediTerm 3.70. All degrade to `LOG.debug` + the old clear-on-scroll behaviour rather than
  failing. The region bottom has no public accessor (only `getScrollRegionTop()`), and guessing the
  screen height instead would shift selections sitting on rows that never moved.

What this cannot do: an app that **repaints** rows instead of scrolling them (htop, tmux, vim on a
full redraw) rewrites the content under the selection, so it is cleared — which is the right answer.
Apps that move lines (`less`, `man`, vim line-scrolling either way) are tracked, in the alternate
buffer too, since entering it allocates a fresh full-size history storage.

Verifying this needs no GUI: `JediEmulator` over an `ArrayTerminalDataStream` will replay captured
bytes into a real `JtermTerminalPanel` headlessly. Capture the bytes an app actually emits by
running it under a `pty.fork()` harness and dumping its output — that is how the `less` reverse
index and vim's `CSI L` were found, after guessing wrong about both.

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

**A tab with no panes and no opens in flight closes; a session arriving for a disposed grid is
closed, never placed.** An SSH tab exists before its connect finishes, so every "connect, then
place into this grid" goes through `PaneGrid.openAsync(starter, place)` instead of calling
`connectAsync` directly — **use it for any new async open.** It hands the connect a success and a
failure callback backed by `ui.grid.PendingOpens` (Swing-free, unit-tested). The failure callback
runs after the modal `ErrorDialog` returns, which is what makes a tab opened for a session that
failed close together with its error dialog. It checks emptiness *and* the in-flight count because
a modal dialog still pumps events: the user can fill the empty cell or drop a second session on it
meanwhile. The success callback is the other half — before it, closing a connecting tab by hand
left the late session placed in a removed grid, authenticated and connected with no pane to close
it from. A batch (Open Folder → split tabs, Duplicate Tab) wraps its loop in `holdOpen()`: starting
a connect can block on a credential prompt while an earlier one of the same batch fails, and that
failure would otherwise see an empty grid with nothing else pending and close the tab under the
rest.

### Global shortcuts bypass focus via a KeyEventDispatcher
JediTerm consumes key events, so `MainWindow.installShortcutDispatcher()` registers a single
`KeyboardFocusManager` dispatcher that matches `keymap.Keymap` bindings and **consumes** the
event (returns true). Menu items carry the same accelerators only for discoverability — the
dispatcher fires first, preventing double-execution. Bindings load from `keymap.json`
(defaults in `keymap.TermAction`).

A consumed `KEY_PRESSED` is **unrecoverable** for the program in the pane: JediTerm encodes
control keys from `KEY_PRESSED` only, and its `keyTyped` ignores ISO control characters, so a
bound Ctrl+letter never reaches the terminal as `^X`. That is why `handle(TermAction)` returns
whether the action *applied* and the dispatcher returns that: Open SFTP, when it was on Ctrl+F, was
a silent no-op on a local shell that still ate the key, which in vim/`less` reads as "page-down is
stuck, page-up works". Any default on a bare Ctrl+letter that terminal programs use (Ctrl+T,
Ctrl+W are the same class) is a conflict of this kind; a no-op must let the key through.

**Changing a default in `TermAction` reaches nobody on its own**: `keymap.json` is written with
every default on first run, so existing installs keep the old stroke pinned. `keymap.KeymapMigrations`
is the one-shot upgrade path, versioned by a `schemaVersion` entry *inside* the flat id → stroke map
(older builds ignore the unknown key) and stamped even when nothing moved — same rule as
`SessionStore`. v1 moved Open SFTP from Ctrl+F to Ctrl+Shift+F, because the dispatcher claiming
Ctrl+F on an SSH pane left JediTerm's own find (also Ctrl+F) unreachable there. It compares
**parsed `KeyStroke`s**, since the first-run file says `"control F"` and a save from the shortcut
editor says `"ctrl pressed F"`, and it leaves the binding alone if another action already holds the
new stroke.

### Every way out runs `MainWindow.shutdown()`, and macOS asks first
The close button, File → Quit and the macOS quit handler (`app.AppQuitHandler`: Cmd+Q, Dock → Quit,
logout) all run `MainWindow.shutdown()` (window state, every window's sessions, tunnels, the update
scheduler; once). **Put new teardown there**, not in `windowClosing`: the quit handler exits through
`performQuit` → `System.exit(0)` (the documented default `QuitStrategy`), which never sends
`windowClosing`. The handler is installed only where `Desktop.Action.APP_QUIT_HANDLER` is supported,
leaves RDP full screen before asking, asks only when something remote is open (`app.QuitSummary`,
Swing-free and tested; local shells alone don't ask), and must answer `performQuit`/`cancelQuit`
exactly once (`AppQuitHandlerTest`). Where it is installed, File → Quit asks the same question
(`quitFromMenu`); the window's close button does not. An RDP pane on macOS never forwards a Q with Meta down
(`RdpKeyMap.isMacQuitStroke`), so Cmd+Q can't arrive remotely as Ctrl+Q. On a Mac only File → Quit
has been seen asking (macOS VM); the rest of the list is in the full-screen spec's Cmd+Q item.

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

RDP drags carry `dnd.RdpTransferable` and go through `dnd.RdpDropHandler`, wired per grid by
`TabPane.wireGrid` (so detached windows get it too) from `TerminalServices.newRdpPane`. No
`openAsync`: an `RdpPane` places itself first and connects second. Placement uses
`PaneGrid.placeContentAsSplit` / `placeContentInCell`, which answer `false` when there is no room,
and the handler then opens a new tab — **not** `adoptAsSplit`, whose full-grid fallback replaces
(and closes) the pane dropped on. The tab strip's "+" takes SSH/RDP/Local drops too; its
`DropTarget` must allow COPY as well as MOVE, because the sidebar drags as COPY only. Open Folder
includes RDP (`SessionStore.collectOpenableSessions`), each in its own tab even in split mode.

### RDP sessions run FreeRDP out of process; jterm paints and owns input
Design and measurements: `docs/superpowers/specs/2026-11-07-rdp-session-design.md`. FreeRDP runs
in `native/rdp-helper` (a small C client, built per OS), never in the JVM. Build FreeRDP first
(`build-freerdp-windows.cmd <workdir>` / `build-freerdp-linux.sh <workdir>` /
`build-freerdp-macos.sh <workdir>`, version pinned in `FREERDP_VERSION`, Windows deps by
`vcpkg.json`'s baseline), then the helper (`build-windows.cmd` / `build-linux.sh` /
`build-macos.sh`), which stages it with its libraries and license texts in `build/stage/` — the
directory that installers ship as `rdp/` (`jterm.app/Contents/app/rdp/` on macOS).
`smoke-test.py <helper>` checks a staged helper headlessly (HELLO + a CONNECT to a closed port);
the DMG jobs run it with `--expect-h264` on the helper inside the finished image. Test a change to `install-build-deps-linux.sh` in the
`rockylinux:9` container before committing: those images ship `curl-minimal`, which conflicts with
the `curl` package and broke the EL9 build once. Keep the Windows work dir short (MAX_PATH). Linux bundles
need only libc, OpenSSL 3 and zlib from the system (ICU/systemd are compiled out, cJSON is bundled).
macOS bundles OpenSSL (3.5 LTS, pinned in `build-freerdp-macos.sh`) and cJSON too, built from
source for macOS 11: **not Homebrew**, whose dylibs only load on the macOS version that built them.
Only the Command Line Tools are needed (`install-build-deps-macos.sh` fetches pinned CMake and
Ninja). `build-macos.sh` rewrites every load path to `@rpath` = `@loader_path` and then **re-signs
everything ad hoc**: `install_name_tool` breaks the linker's signature and Apple Silicon kills an
unsigned helper at launch (Intel doesn't, so an Intel test passing proves nothing about arm64).
jpackage's own ad-hoc signing of the `.app` keeps those signatures valid (checked on the VM).
FreeRDP stays out of the JVM: a codec crash or a server-triggered memory bug then can't take down every SSH session, or
reach the unlocked vault. jterm speaks a versioned binary protocol over the helper's stdin/stdout —
`native/rdp-helper/src/protocol.h` is the source of truth, `terminal.rdp.RdpProtocol` its Java
mirror; **change both together**.

`ui.rdp.RdpPane` is a `GridContent` (like `SftpPane`), opened in a new tab by default. It places
itself first and connects second, so a failure leaves the pane showing Reconnect / Close instead of
an error over an empty tab; it removes itself only through Close (`onContentEnded`). Dev runs need
`-Djterm.rdp.helper=<path to jterm-rdp-helper[.exe]>` (with its DLLs beside it); installed builds
look only in the install dir (`terminal.rdp.RdpHelperLocator`) — **never `PATH` or the jar's
directory**, because the helper is handed the password.

Sharp edges, all commented in place:
- **The helper gets an allow-listed environment, never jterm's** (`terminal.rdp.HelperEnvironment`,
  sandbox design layer 1: `docs/superpowers/specs/2026-11-11-rdp-helper-sandbox-design.md`).
  Inheriting handed it `SSH_AUTH_SOCK`, the D-Bus address, `https_proxy` (FreeRDP routes through it
  by itself) and `WTSAPI_LIBRARY` (a library WinPR loads). `HOME`/`USERPROFILE` must stay: FreeRDP
  can't create its settings without a home path. `smoke-test.py` copies the list, keep the two in
  step. `XDG_CONFIG_HOME` is on the list (Unix) so FreeRDP's own folder stays inside jterm's config
  directory, which `AppPaths` resolves from the same variable: without it the Flatpak's helper looked
  in `~/.config/jterm`, the folder of a packaged jterm. `src/sandbox.c` (`sandbox_early`, first in `main`) turns off core dumps and dumpability and
  closes inherited fds. The helper and FreeRDP are built with exploit mitigations (PIE, full RELRO,
  stack protector, FORTIFY, CET; CFG + CETCOMPAT on Windows), and `check-hardening.py` fails the
  build when one goes missing; it runs after every helper build in CI.
- **On Linux the helper locks itself down before `freerdp_connect`** (`sandbox_lockdown`: Landlock
  read-only on the CA store/time zone only and TCP connect to the session's port only, a seccomp
  deny-list, `RLIMIT_FSIZE` 0). Anything FreeRDP opens *after* that point fails, so a change that
  makes it load a library, read a file or open a non-TCP socket during the session breaks quietly
  (a denial is usually just a missing feature, as H.264 was when the probe unloaded its codec).
  Load things in `apply_connect`, never lazily. It can't resolve names either: `src/netroute.c`
  resolves before the lockdown and FreeRDP's `TCPConnect` hook dials the cached addresses, asking
  jterm (`RESOLVE`, protocol v12, `HelperResolver`) for a name it hasn't seen, such as a redirect
  target. The level shows in the pane bar's status tooltip; `smoke-test.py --expect-sandbox full`
  (RPM and DEB jobs) runs the helper's self-test. Tracing it with `strace` needs a preloaded shim
  that ignores `PR_SET_DUMPABLE` (strace can't read a non-dumpable process's strings), and the
  lockdown applies under strace too: trace with `JTERM_HELPER_SELFTEST` unset and expect `EACCES`
  after the lockdown line. Because of `RLIMIT_FSIZE`, a helper started by hand with stderr sent to
  a *file* goes silent at the lockdown: pipe it (`2>&1 | cat > log`).
- **On Windows the helper relaunches itself with a restricted token** (step W2,
  `src/launcher_win.c`: the process jterm starts is only a launcher that waits; the work happens in
  the `--jterm-restricted-child`), then applies W1 at the lockdown point (mitigation policies, low
  integrity; the launcher's job object). The child's main thread impersonates a same-access token
  until `sandbox_lockdown` calls `RevertToSelf`, so anything needing the user's normal access must
  happen before that point. The reverse also exists: OpenSSL's random seeding (CryptoAPI) and Media
  Foundation fail *under* the start-up token on GitHub's Windows Server runner and work under the
  lockdown token, so they run through `sandbox_without_startup_token` (the H.264/AAC probes, the
  RNG seed at start). The Win11 VM doesn't show it; the MSI job's smoke test does (exit 4 = no
  FreeRDP context). Consequences measured on the VM: Windows' SSPI refuses the restricted
  token, so the child sets `WINPR_NATIVE_SSPI=0` and **NLA is WinPR's NTLM on Windows too, no
  Kerberos**; names are resolved before the lockdown (netroute). W1 alone can't stop reading files,
  Credential Manager or the microphone (spec, W1 spike); Win32k lockdown can't be set at run time.
  `JTERM_HELPER_NO_RELAUNCH=1` (not in jterm's allow-list) runs W1 alone, for comparisons. A session
  with **"Use Windows sign-in (Kerberos)"** (`useKerberos`, never exported or imported) gets
  `JTERM_RDP_KERBEROS=1` from `HelperEnvironment` and runs W1 only, with Windows' SSPI: the
  per-session way back to Kerberos.
  The Win11 VM has VS 2022 Build Tools, MinGit and Python in `C:\Tools`; build there with
  `build-freerdp-windows.cmd C:\f` (short path) and `build-windows.cmd`. Start long builds through
  WMI (`Invoke-CimMethod Win32_Process Create`), not in the SSH session: sshd kills a session's
  processes when it ends. Credential Manager and the clipboard only exist in an interactive logon,
  not an SSH one: run such tests as a scheduled task with `/IT` in the RDP-signed-in session.
- **On macOS the helper applies a Seatbelt profile at the lockdown point** (`sandbox.c`,
  `sandbox_init_with_parameters` with a profile string: deprecated, undeclared in the SDK, and what
  browsers use). Deny by default plus `system.sb`; reads of the system libraries, CA store, time
  zone and the helper's own folder; TCP only to the session's ports; and
  `IOSurfaceRootUserClient`, without which every VideoToolbox frame fails (measured: the denial is
  *not* logged, found by bisecting rules with a test build). Names are resolved before the lockdown
  (netroute), as on Linux. A Mac that decodes in hardware may need more than the Intel VM (software
  decoding) can show, so `h264_check_after_lockdown` decodes an embedded 1280×720 frame
  (`h264_test_frame.h`) under the profile and turns H.264 off for the session if it fails: RemoteFX
  instead of a black desktop. The DMG jobs' smoke test fails on that refusal, which is what checks
  Apple Silicon. Denials show in the unified log as launchd `denied lookup … requestor =
  jterm-rdp-helpe[pid]` (the name is truncated); from an SSH session use `/usr/bin/log`, since zsh's
  `log` builtin shadows it. CoreServices, DiskArbitration, power logging and networkd's plist are
  denied on purpose (seen, harmless).
- **Pipe the helper's stderr, never inherit it.** The Windows helper is GUI-subsystem (no console
  window); handed an inherited *console* stderr it has no valid fd 2. It now survives that, but its
  log must be drained or the pipe eventually blocks it.
- **Certificates:** pins (`terminal.rdp.RdpKnownHosts`, `rdp_known_hosts.json`) go to the helper in
  CONNECT and FreeRDP accepts a match before anything else; only a cert that is neither pinned nor
  CA-valid comes back as a prompt. The helper answers FreeRDP "accept for this session" so
  FreeRDP's own store is never written. There is deliberately no "ignore certificate errors". The helper also turns off the legacy **standard RDP
  security** layer (`FreeRDP_RdpSecurity`, on in FreeRDP's library defaults): it has no TLS
  certificate, so pins are never consulted, and a man-in-the-middle selecting it was measured to get
  the connection past negotiation. Refused, negotiation fails (`0x0002000C`, mapped to an explanation
  by `Disconnected.securityNegotiationFailed()`). Don't re-enable it without a per-session, labelled
  opt-in. **NLA is required by default**: CONNECT's `securityFlags` (protocol v3) is 0 unless
  the session's `allowTlsWithoutNla` (Security tab) is on, and only then does the helper enable
  `FreeRDP_TlsSecurity`. NLA protects the password even when a wrong certificate is accepted on
  first use; TLS-only is for xrdp and Windows hosts with NLA off. RDSTLS (GNOME's login handoff) is
  switched on by FreeRDP itself on a redirect, independent of these settings. A server without NLA
  also fails as `0x0002000C`, so `RdpPane.negotiationFailure()` words the message by the setting.
- **Passwords:** vault first, else a prompt; a typed password marked "remember" is saved only after
  `CONNECTED`, and a credential failure (`Disconnected.credentialsRejected()`) re-prompts.
- **Keyboard:** printable keys without Ctrl/Alt/Meta go as Unicode (layout-independent), the rest as
  Windows VKs that the helper maps to scancodes. That mapping is `vk_to_scancode`, not a bare
  `GetVirtualScanCodeFromVirtualKeyCode`: WinPR only searches its extended table when the VK carries
  `KBDEXT`, so a plain lookup returns 0 for Delete, the arrows, the navigation block and both Windows
  keys, and the helper drops a zero scancode (Ctrl+Alt+Del arrived as Ctrl+Alt, Win did nothing). `MainWindow`'s dispatcher claims only Ctrl+Shift
  strokes while an RDP pane has focus (`RdpKeyMap.jtermMayClaim`) — Ctrl+T/W/C belong to the remote. A
  shortcut meant for an RDP pane must therefore be Ctrl+Shift (`TermActionTest` checks Send
  Ctrl+Alt+Del's), and by the time it is claimed its own Ctrl and Shift have already gone down
  remotely — `RdpPane.sendCtrlAltDel` releases them first, or the server sees Ctrl+Shift+Alt+Del.
- **macOS:** Cmd goes as Ctrl (`AppSettings.rdpCmdAsCtrl`, default on; off = the Windows key),
  decided per key event in `RdpPane.cmdAsCtrl()`. Only the modifier is remapped: Meta already makes
  the letter a chord, so it goes as a virtual key. And macOS 15+ **Local Network privacy** fails
  any connection to a LAN address with `EHOSTUNREACH` while its prompt is open, and the helper
  counts as jterm, so its first connect used to fail before the user could answer.
  `terminal.rdp.LocalNetworkAccess` makes a TCP connection from the JVM first (direct and gateway
  routes; through SSH the helper dials loopback) and waits out "no route to host" before starting
  the helper, so a typed password is never asked for twice. One success covers the process. The
  DMG's `Info.plist` gets `NSLocalNetworkUsageDescription` in `release.yml` (app image, PlistBuddy,
  ad-hoc re-sign, then the DMG: jpackage has no option for it, and the edit breaks the signature).
- **Full screen** (design: `docs/superpowers/specs/2026-11-10-rdp-full-screen-design.md`) moves
  only the pane's `DesktopView` into `ui.rdp.FullScreenWindow`; the `RdpPane` stays in its grid
  cell showing a placeholder card, so the connection, clipboard and drives never move. One
  mechanism per OS, each measured in a spike: borderless on Windows (`setFullScreenWindow`
  iconifies on deactivation there), `setFullScreenWindow` on Linux (mutter misplaces a
  monitor-sized undecorated frame, so it maps a small one on the target monitor first and switches
  500 ms later), a native Space on macOS via reflective `com.apple.eawt` (needs the
  `Add-Exports` in the jar manifest and `--add-exports` in the DMG's launcher; falls back to the
  JDK's mode, logged). Sharp edges:
  - **No jterm dialog while full screen**: every dialog in `ui.rdp` takes `RdpPane.dialogParent()`
    as its parent, which leaves full screen first (a remote desktop can draw a fake jterm password
    prompt; a dialog owned by the main window would also open hidden behind the full-screen one).
    `RdpFileDownload` takes a `Supplier<Component>` for that reason. **Route new dialogs through it.**
  - In full screen the dispatcher claims only actions with `TermAction.availableInRdpFullScreen()`
    (toggle and Send Ctrl+Alt+Del); `RdpPane.inFullScreen`/`forFocusOwner` resolve a focus owner
    in that window back to its pane, since it is not inside any grid.
  - Removing the view from the pane's `CardLayout` **hides it** (`removeLayoutComponent` calls
    `next()` while it is still a child), so `enterFullScreen` sets it visible again; without that
    the full-screen desktop was black.
  - No keyboard hook: Alt+Tab, the Windows/Super key and the local Ctrl+Alt+Del stay with the OS.
- **Frames: one write each, painted pixel for pixel.** Both were measured as the cause of stuttering
  video on Windows; don't undo either.
  - The helper's `send_frame` writes a rectangle's pixels in **one** `fwrite`. A write per row made a
    2560×1440 frame 1440 writes, and the pipe Java gives a child on Windows holds 4 KiB, so each
    waited for the reader: 1 frame/s (15 MB/s) by rows against 118 frames/s in one write, same
    bytes. Linux pipes (64 KiB) never showed it, which is why the spike's "the pipe does 1 GB/s"
    was wrong for Windows.
  - `DesktopView.paintComponent` drops the Graphics transform and copies the image when it matches
    the target in device pixels (within 2: the remote width is forced even). Drawn "scaled to the
    component" under a 150% transform, Java2D runs a real bilinear loop (21–31 ms a frame in
    software against 1 ms), holding the lock `blit` needs, which stalls the helper and its frame
    acknowledgements. The clip survives `setTransform` (it is kept in device space; checked).
  - The helper logs `stats …` once a second while frames flow (frames, MB, write time, codec per
    graphics command, counted by wrapping gdi's `SurfaceCommand`); `RdpConnection` parses it
    (`terminal.rdp.RdpStats`, Swing-free) and keeps it **out of the stderr tail**, and the pane adds
    its copy and paint times. Shown by "Show performance" on the status label's menu. Measure with
    it before optimising further. No protocol change: an older helper simply sends no statistics.
  - FreeRDP 3.33 already defaults to network auto-detect, progressive off and frame acknowledgement,
    so `/network:lan` and `/gfx-progressive:off` advice doesn't apply. UDP (`WITH_RDPEUDP`) exists
    but is experimental and not built.
- **Cursor size:** AWT stretches custom cursors to the platform's best size (48×48 at 150%), so the
  remote cursor is padded onto a best-size canvas rather than scaled.
- **GNOME Remote Desktop** ignores the scale factor in the connection data; the helper sends size
  and scale per monitor over Display Control, which is also what resizes the remote desktop to the
  pane. GNOME still renders at 100% for 150% (fractional scaling needs mutter's experimental flag).
- **H.264** is advertised only when the helper's decoder actually starts (Media Foundation on
  Windows). Servers without a hardware encoder (orion: no VA-API driver) still send RemoteFX.
- **Clipboard is per session and opens no channel unless asked** (`redirectClipboard`, plus
  `clipboardToRemote` for the other direction; both go to the helper as CONNECT's `clipboardFlags`,
  protocol v2). With both off the helper never sets `FreeRDP_RedirectClipboard`, so the server cannot
  see that a clipboard channel exists. The helper also enforces each direction itself. Text
  (`CF_UNICODETEXT`), images (`CF_DIB`, else `CF_DIBV5`) and HTML (`CF_HTML`, protocol v6) only,
  32 MiB cap, refused rather than truncated. "HTML Format" has no fixed id: the helper matches the
  server's by *name* and announces its own as `JRDP_LOCAL_HTML_FORMAT_ID`. One remote copy can be
  two fetches (text + HTML) that land on the local clipboard together.
  Remote → local needs no prompt by decision: the opt-in is the protection. Delayed rendering both
  ways: jterm *offers kinds*, and content leaves the machine only when the server pastes
  (`CLIP_REQUEST` → `CLIP_DATA`). `RdpClipboardCodec` (Swing-free) treats the server's bytes as
  hostile: the DIB header is checked against the data present *and* the pixel count against
  `MAX_PIXELS` *before* any pixel buffer exists, and compressed DIBs are refused. The size check alone
  is not enough — a 1-bit DIB decodes to 32× its size (a 31 MiB payload OOMed a 2 GiB heap).
  `ui.rdp.RdpClipboardBridge` owns the system-clipboard side.
  - **A new copy of the same kind is not a `FlavorEvent`.** `SunClipboard.checkChange` returns early
    when the format set is unchanged (read in the bytecode), so text copied over text is silent, and
    a Windows server keeps serving the data it already rendered until it gets a new format list.
    `RdpClipboardBridge.refresh()` re-announces; `RdpPane` calls it on every focus gain. Don't
    "optimise" it away by relying on the listener alone, and don't dedupe offers by kind. It does
    dedupe by *content* (the `fingerprint` below): focus also returns when a jterm dialog closes,
    and re-announcing unchanged content replaced the server's clipboard just as a paste allowed in
    the file prompt was starting (Windows unlocks what it was reading: "unspecified error").
  - **…but a format list is a claim of ownership**: the server replaces its clipboard with ours. So
    after the server announces its own (`onRemoteOffer`, even with no kinds we fetch — e.g. files),
    `offerLocal` stays silent until the local content's digest (`fingerprint`: SHA-256 of the text,
    else the pixels, compared locally, never sent) differs from the one taken then. Without it,
    files copied in the remote Explorer were replaced on the next focus gain. A busy clipboard
    (`IllegalStateException`) skips the announcement rather than announcing "empty".
  - **Connecting doesn't overwrite the local clipboard.** The server's start-up announcement of its
    existing clipboard looks exactly like a new copy. Until `RdpPane` reports the first key or
    mouse *button* sent to the connected session (`onUserInput`, once per connect attempt), a
    remote offer is not fetched and the local clipboard is re-announced instead. Input, not a
    timer: a copy on the remote side always needs input, so a real copy is never dropped.
  - **No echo:** what the bridge wrote for the server is remembered by identity (`written`, set
    *before* `setContents` because the event can fire before it returns).
  - FreeRDP's cliprdr calls run on the RDP thread; the stdin reader thread only queues a `ClipJob`
    and signals `g_clip_event`, like the monitor layout. Don't call the channel from the reader.
- **H.264 decoders, and why Linux downloads one.** Windows uses Media Foundation, macOS has VideoToolbox
  built into FreeRDP 3.33 (`WITH_APPLE_VIDEOTOOLBOX`, part of the OS, nothing to download). On Linux FreeRDP is
  built with `WITH_OPENH264_LOADING` (headers only, from a pinned Cisco source tarball; configure
  insists on a library file, so it gets an empty placeholder) and the packages carry **no codec**:
  Cisco's patent license covers only the binary *Cisco* delivers to the user's machine, separately,
  user-controllable, with the notice shown where it is controlled. A self-built or installer-bundled
  copy has no patent license. So `terminal.rdp.OpenH264` downloads Cisco's `.so.bz2` on opt-in
  (Preferences → General), verifies a **pinned SHA-256** before touching it, and `RdpPane` passes its
  full path to the helper as `JTERM_OPENH264` (`Target.codecLibrary`). The helper `dlopen`s it before
  FreeRDP looks, and FreeRDP's open-by-bare-name then returns that library through its SONAME match.
  **Never `LD_LIBRARY_PATH`**: it outranks the `RUNPATH` FreeRDP's own libraries carry, so libwinpr3,
  libssl and libz could all be loaded from that directory too, in the process holding the password. Every release has the same file
  name (`libopenh264.so.8`), so the install is **stamped** with the pin it was verified against
  (`libopenh264.so.8.sha256`); `isCurrent` compares that stamp, an outdated build is *not* loaded,
  and `updateInBackground` (at startup and each connect) fetches the current one. Without the stamp
  a pin bump never reached anyone who had already enabled the codec. The helper decides per connect
  (`h264_decoder_available`), so enabling needs no restart. The Flatpak downloads it the same way: the 25.08
  runtime has no openh264 extension any more (24.08 had one). **Don't bundle a codec in any installer, and keep the pinned hashes and
  `OH264_VERSION` in step with a version bump** (the Flatpak manifest has the header tarball too).
  The `Security` workflow's `openh264` job (`.github/check-openh264.sh`, runnable locally) fails on an
  advisory affecting the pin, a release more than 14 days newer, or pin drift / hashes that no longer
  match Cisco's files. Bumping OpenH264 touches `OpenH264.java`, `build-freerdp-linux.sh`, the Flatpak
  manifest and `third-party-licenses.txt`; the script checks the first three agree. Pick the version by running
  the script with `OPENH264_CHECK_VERSION=<candidate>` first: 2.6.0 was pinned while 2.7.0 was out.
- **RDP through an SSH session** (`RdpSessionConfig.sshTunnelSessionId`, the dialog's Gateway tab;
  a reference to a saved session rather than a jump-host list **on purpose**, matching Tunneling…,
  see ADR 0013 before "making it consistent" with the SSH session dialog):
  `RdpPane.Services.sshForwarder` (implemented by `MainWindow.openRdpSshForward`, the same
  credential/connect path as `startTunnel`) opens `terminal.ssh.LocalForward`, a loopback forward on
  an ephemeral port that owns its `SshConnect.Connected`. The helper dials `127.0.0.1:<port>` with
  CONNECT's `serverName` (protocol v4) set to the real host, which FreeRDP uses as
  `UserSpecifiedServerName` for the certificate name check and TLS SNI. Sharp edges:
  - The forward belongs to one **connect attempt** (`Listener.forward`) and closes when the helper
    exits or the pane closes. It must stay up for the whole connection: FreeRDP opens several TCP
    connections during negotiation and on reconnect. A tunnel that finishes opening after a Close
    or a newer attempt is closed on arrival (generation check).
  - **Pins stay keyed by the session's host:port**, not the helper's view: through a forward
    `CertRequest.port()` is the random local port, so `askCertificate` substitutes
    `cfg.getPort()`.
  - The tunnel always gets an SSH heartbeat (the session's keep-alive, or 60 s when that is off):
    an idle desktop can send nothing for longer than MINA's idle timeout.
  - Import gives sessions fresh ids, so `SessionExportService.reassignIds` relinks an RDP session
    to an SSH session imported alongside it, and keeps a link pointing outside the import.
  - Kerberos would need the real name as its SPN, which still comes from `127.0.0.1`; NLA falls
    back to NTLM.
  - `LocalForwardTest` runs a real forward through an in-process MINA `SshServer`. Don't
    half-close in such a test: MINA turns the EOF into a full close before the echo returns.
- **RD Gateway** (`RdpSessionConfig.gateway*`, the Gateway tab's third route; exclusive with the SSH
  route, enforced by the dialog and refused by `RdpPane` if both are set). CONNECT's gateway part
  (protocol v5) maps to FreeRDP's `Gateway*` settings with `TSC_PROXY_MODE_DIRECT` and no local
  bypass. Sharp edges:
  - **"Same credentials" is copied in the helper.** FreeRDP applies `GatewayUseSameCredentials`
    only in its command-line parser, which the helper doesn't run; with the flag set, CONNECT
    carries no second copy of the password.
  - **The gateway's certificate uses the same pin list** (`CertificateAcceptedFingerprints` covers
    both), so `RdpPane.pins` sends the desktop's and the gateway's. A gateway prompt carries
    `VERIFY_CERT_FLAG_GATEWAY` (`CertRequest.gateway()`) and pins under the gateway's host:port.
  - A separate gateway password lives under `VaultKeys.rdpGatewayPassword(id)`, is saved only after
    `CONNECTED`, removed with the session, and **not exported**. FreeRDP doesn't say which password
    a rejection was for, so a retry re-prompts for both.
  - Not verified against a real RD Gateway: only that the helper opens HTTPS to the gateway and
    never dials the desktop directly (fake-server smoke test).
- **Drive redirection never uses FreeRDP's drive device** (design:
  `docs/superpowers/specs/2026-11-09-rdp-drives-and-file-copy-design.md`). Its confinement is
  lexical only (`..` rejected, links followed) and it had several 2026 advisories. The helper
  installs an add-in provider over the client's static one (`j_addin_provider`) that answers
  `drive` with a ~150-line forwarding device, `printer` and `smartcard` with jterm's own (see
  Printing and Smart cards below) and *every other* device service (serial, parallel) with nothing.
  Each IRP goes to jterm as `DRIVE_IRP` (protocol v7) and stays
  pending until `DRIVE_IRP_DONE`; CONNECT carries share ids and names, **never a path**. All file
  access is `terminal.rdp.drive` (Swing-free, `RdpDriveServiceTest` drives it with byte-exact
  requests). Sharp edges:
  - **`DriveShare.resolve` is the only path from a server name to a local path.** It walks one
    component at a time with `NOFOLLOW_LINKS` and refuses `isSymbolicLink() || isOther()` (junctions
    and other reparse points are "other" on Windows). Check-then-open is enough because MS-RDPEFS
    has no link-creating operation; don't add one.
  - **On Windows a junction is a directory *and* "other", never a symbolic link.** A check that
    asks only `isSymbolicLink()` and `isDirectory()` lets one through: "Save files…" wrote straight
    through a junction until `RemoteFileTransfer.folder` asked `isOther()` too. Any new "is this a
    real folder" test needs all three. `WindowsFileSystemTest` (Windows only, `mklink /J`, no
    privilege needed) covers shares, local copies and saves; run it on the VM, CI's tests are Linux.
  - **A `Path` can't name an NTFS stream** (`InvalidPathException` for the `:`), so the mark of the
    web (`OriginMark`) is written through `java.io`. The NIO form never wrote one, and the
    best-effort catch hid it: a best-effort write still needs a test that reads the result back.
  - **Protected folders (vault config dir, `~/.ssh`) are compared by identity (`Files.isSameFile`),
    not by string**: a Windows 8.3 short name, a case variant (the case-insensitive fallback on
    Linux) or a bind mount would pass a string check. They are refused as a share and hidden inside
    any share, which is what makes "share my home folder" safe.
  - Deliberate differences from FreeRDP's drive: directories are deleted only when empty (FreeRDP
    deletes recursively); CREATE's allocation size is a hint, never an extension; the volume calls
    itself NTFS (FreeRDP's "FAT32" makes Windows refuse files over 4 GiB); read-only shares answer
    `STATUS_MEDIA_WRITE_PROTECTED`; growth below a free-space floor is `STATUS_DISK_FULL`.
  - **A failed reply still carries its fixed fields** (`Out.failureBody`, mirrored by the helper's
    `drive_failure_body_len` for IRPs it fails itself), or the server can't parse it.
  - Requests for one FileId run in order (`DriveDispatcher`); IRP completion in the helper happens
    under `g_drive_lock`, which the device's `Free` also takes, so a late answer can't complete an
    IRP of a torn-down channel.
  - Reading cmdline.c, turning device redirection on should make FreeRDP add an audio channel
    with its do-nothing `sys:fake` output; the add-in provider lets `fake` through. Against
    Windows 11 no `rdpsnd` appeared in the log with sound off (audio spec, "What was measured"):
    recheck after a FreeRDP bump, since a sound channel the user didn't ask for breaks A4.
  - Shares are left out of session exports *and* ignored in imports (a Jackson mix-in on
    `SessionExportService`'s mapper), so a crafted import can't share a folder.
- **Clipboard file copy, local to remote** (protocol v8, `clipboardFilesToRemote`, off by default,
  only with both clipboard settings on). Same rule as drives: the helper relays cliprdr's
  `FileGroupDescriptorW` list, `FileContents` requests and Lock/Unlock unparsed, and never sees a
  path. `terminal.rdp.files.FileSnapshot` walks the copy once (no links, protected folders left out,
  10 000 entries, depth 32, Windows-valid names of at most 259 characters, else refused outright),
  and the server can then only name an entry of it by index; a file whose size or time changed
  since is not sent. `RdpFileClipboard` ties the user's answer to one *announced copy* and keeps
  the server's locks (16 at most). Sharp edges:
  - **Nothing describes the files before consent.** The prompt (`RdpPane.filePasteConsent`)
    comes on the server's first request for the list, i.e. on paste, not on copy; a "no" holds for
    that copy (Windows retries a refused request twice at once; those are refused silently). A new
    copy is a changed announcement only, which the server can't cause. The snapshot is walked again
    for every paste, so an edited file copied again under the same path goes as it is now.
  - File reads run on their own thread (`rdp-clipboard-files`), never the bridge's, so a 4 MiB
    range can't hold up text and image traffic. The helper caps outstanding requests at 64.
  - Copying a second file list over the first raises no clipboard event (same flavors), exactly
    like text: tests call `refresh()` as the pane does on focus gain.
- **Clipboard file copy, remote to local** (protocol v9, `clipboardFilesToLocal`, off by default,
  with Share clipboard). A server copy holding files is locked at once when the server agreed to
  locking (`CLIP_CAPS`), and only its *list* is fetched (`RemoteFileList`, hostile-input parser);
  the pane shows "Save files… (n files, size)" and nothing is written until the user picks a folder
  (D1). `RemoteClipboardFiles` owns locks, stream ids and generations: without a lock, a new server
  copy fails the old list's reads ("the remote clipboard changed during the copy"); with one, a
  running save keeps reading and the lock is released when it ends. `RemoteFileTransfer` writes:
  folders one level at a time with no link followed, a hidden `.part` name renamed when complete,
  replace only on "Yes", the drive's free-space floor, the downloaded mark. Sharp edges:
  - **Protected folders are matched by path too, ignoring case, before creating anything.**
    Identity alone misses a `~/.ssh` that doesn't exist yet, which a copied
    `.ssh\authorized_keys` saved into the home folder would create with sshd-acceptable modes.
  - A list entry's index is the server's, also for entries left out of the parsed list: reads name
    the server's own positions.
- **Printing never touches a local print system from the helper** (design and spike:
  `docs/superpowers/specs/2026-11-17-rdp-printer-and-smart-card-design.md`, R1–R5). Per session,
  off (`printToJterm`; CONNECT's printer part, protocol v13). The helper announces **one** printer,
  "jterm", with the driver `Microsoft Print To PDF` (`RdpPane.PRINTER_DRIVER`): Windows renders the
  job with it and what reaches the redirected port is a **PDF** (spike: Windows 11 VM and a laptop;
  PostScript from FreeRDP's usual driver would need Ghostscript, the XPS v4 driver never creates a
  printer). Its IRPs travel as `DRIVE_IRP` with device id `PRINTER_DEVICE_ID` (`0xFFFF0001`) and are
  answered by `terminal.rdp.print.RdpPrintService` (Swing-free, tested). Sharp edges:
  - **The write offset is not a position**: Windows' spooler sends 0 for every write of a job
    (measured, and writes reach 103 KiB), so data is appended in arrival order, as FreeRDP does.
  - **The server's printer cache data (`PAKID_PRN_CACHE_DATA`) is accepted and dropped** in
    `j_printer_component`; FreeRDP's own printer writes it to files named from server strings.
    A NULL `CustomComponentRequest` would answer an error instead.
  - Jobs live in `AppPaths.localDataDir()/rdp-print-jobs/<pid>/` (never the roaming `%APPDATA%`),
    owner-only; a folder whose pid no longer runs is swept on first use, so a second running jterm's
    jobs survive. Caps in `PrintJobQueue` (256 MiB a job, 8 / 1 GiB waiting) and the drive design's
    free-space floor; over a cap a job is **dropped, never truncated**. Only `%PDF-` files are kept.
  - **PDFBox is the only parser of a job** (`PdfDocuments`): scratch files, not heap; a thread of its
    own with a 20 s limit (past it: save yes, print no); 2 000 pages. Printing goes through
    `PDFPageable` (shrink to fit, centred: jobs arrive as US Letter), so the OS print system gets
    what Java draws, never the server's document. `pdfbox.fontcache` is pointed at the local data
    folder (PDFBox otherwise writes a font cache file into the home folder).
  - Waiting jobs belong to the **pane** (`PrintJobQueue`, survives reconnects, deleted by
    `closeContent`); the in-progress job belongs to the attempt and dies with it.
  - "Print without asking" (`autoPrintPrinter`, a local printer's name) is capped by
    `AutoPrintLimiter` (50 pages a job, 10 jobs / 10 min per attempt) and **never exported or
    imported** (the mix-in). Nothing ever opens a dialog by itself: jobs wait in the bar.
  - Java's printing needs CUPS on Linux: `libcups` is in the RPM/DEB dependencies (opened at run
    time, so jpackage's scan misses it) and the Flatpak has `--socket=cups`.
- **Smart cards: the helper forwards, jterm speaks PC/SC** (design, spike and live results:
  `docs/superpowers/specs/2026-11-17-rdp-printer-and-smart-card-design.md`, S1–S7). Per session, off
  (`useSmartCards` plus `smartCardReaders`, both **never exported or imported**; CONNECT's
  `smartcardFlags`, protocol v13). The helper announces one `RDPDR_DTYP_SMARTCARD` device and
  forwards its `IRP_MJ_DEVICE_CONTROL`s unparsed as `DRIVE_IRP` with `SMARTCARD_DEVICE_ID`
  (`0xFFFF0002`), so the helper sandbox gains no socket (FreeRDP's own channel would need pcscd's
  socket inside it and parses the NDR in C). `terminal.rdp.smartcard` (Swing-free): `ScardNdr`
  decodes MS-RDPESC calls and encodes replies (tested byte for byte against calls captured from
  Windows 11 and FreeRDP's replies, `windows11-certutil.txt`), `ScardService` runs them (jterm's own
  random 8-byte handles, consent once per connection, PIN guard, in-memory cache, caps), `JnaPcsc`
  calls winscard / PCSC.framework / libpcsclite by absolute path. Sharp edges:
  - **Never log a call's contents**: a VERIFY transmit carries the PIN in clear (measured).
  - **pcsc-lite reports a connected card as NEGOTIABLE, never SPECIFIC; report SPECIFIC** (as WinPR
    does). Passed through, Windows' minidriver took the card for unusable and certutil's private-key
    test failed while everything else worked.
  - **The A/W IOCTL codes don't differ by one bit** (ConnectA `0xAC`, ConnectW `0xB0`), and the codes
    are `0x00090000 | n << 2`: GetReaderIcon is `0x104`, GetDeviceTypeId `0x108`, ReleaseStartedEvent
    `0xE4`. Every reply but the started-event ones is padded to 8 bytes, padding counted in both lengths.
  - **Queues as FreeRDP has them:** one per context (a status change blocks for ever), a second for
    BeginTransaction/Connect/Reconnect (they may wait for another handle's transaction, and the
    EndTransaction freeing them must not queue behind them), and Cancel, IsValidContext and
    ReleaseContext answered at once.
  - **Unpicked readers look absent** (S1): unknown in a status change, and the PnP pseudo-reader
    reports the count of *visible* readers, so plugging in an unpicked one wakes nobody. macOS' PC/SC
    crashes on the PnP name (WinPR's note), so there `supportsPnpNotification()` is false and the
    reader list is polled every second.
  - Feature codes in a `CM_IOCTL_GET_FEATURE_REQUEST` answer pass unchanged (`0x4233xxxx` on CCID
    doesn't fit a Windows code); only the PIN-pad features are kept, and only their codes may be used.
  - pcsc-lite's client library set itself up badly when two first `SCardEstablishContext`s raced
    (`SCARD_F_INTERNAL_ERROR`): `JnaPcsc.establish` is synchronized.
  - Testing without a card: the software PIV card in `~/scard-spike` (README there: PivApplet in
    jCardSim behind vsmartcard's vpcd and a private pcscd in `bwrap`, reached via
    `PCSCLITE_CSOCK_NAME`), and `certutil -scinfo -silent -pin 123456` run in the RDP session as a
    scheduled task with `/IT`.
- **Sound and microphone never touch a device in the helper** (design and measurements:
  `docs/superpowers/specs/2026-11-11-rdp-audio-design.md`, properties A1–A9). Per session, both off
  (`playSound`, `useMicrophone`; CONNECT's `audioFlags`, protocol v10; v11 added `AUDIO_DELAY`, the
  measured delay the helper reports to the server in place of a constant); without a flag the helper
  loads no `rdpsnd`/`audin`, so the server can't tell jterm has sound. `audio.c` registers
  forwarding devices for subsystem `jterm` through the add-in provider, which refuses every other
  sound backend (FreeRDP's PulseAudio/ALSA/WinMM/CoreAudio; Windows FreeRDP is built
  `WITH_WINMM=OFF`). jterm plays and records through Java Sound (`terminal.rdp.audio`, Swing-free,
  tested with fake sinks/sources and a manual clock). Sharp edges:
  - **Never fail the sound device's `Open`**: a FALSE ends `rdpsnd`'s play thread and the sound for
    the whole session. **Decide in `PlayEx`**, not `Open`/`FormatSupported`: rdpsnd offers every PCM
    variant whatever the device says, and hands the device bytes it didn't convert (8-bit,
    6-channel), so `PlayEx` drops anything outside the list.
  - **Never call audin's `receive` from the stdin reader** (it writes to the channel). The reader
    checks and queues `MIC_DATA`; the RDP thread hands it over (`audio_mic_process`), under
    `g_mic_lock`, which `Close` takes too: audin frees `receive`'s user data right after `Close`.
  - **The capture line is closed, not merely ignored**, whenever the microphone may not be heard
    (`MicGate.mayCapture`: stream open, allowed for this connection, not muted), so the OS
    indicator tells the truth; meanwhile `RdpMicrophone` sends clock-paced silence, because a remote
    program that opened the microphone may stall without data. A packet captured while the user
    muted is dropped. The consent question comes once per connection (a new `RdpMicrophone` per
    connect attempt) with "Don't allow" focused, through `dialogParent()`.
  - **`useMicrophone` is never exported and ignored on import** (the same mix-in as `driveShares`).
  - **Servers pick the format, devices refuse some**: Windows asks for a 44.1 kHz *stereo*
    microphone (441-frame packets) even from a mono headset, and on macOS that headset has no stereo
    line, so `JavaSoundSource` opens the other channel count and converts (`ChannelMix`). A machine
    with no audio device at all makes Java Sound throw `IllegalArgumentException`, not
    `LineUnavailableException`: catch `Exception` around opening a line.
  - Windows 11 sends playback in ~186 ms waves (32 KiB), so `JitterBuffer` also waits
    `START_DELAY_MS` after the first byte, and an underrun counts only when the output itself ran
    dry (`AudioSink.queuedMillis`).
  - **The delay is steered on the low point, never the current queue** (`LatencySteering`): with
    waves that large the queue swings by a wave each time one lands, so only the minimum of queue +
    output over a second says how much is surplus. Surplus is *cut* (quiet first, else 20 ms cuts
    with a 5 ms crossfade), counted apart from drops, and **not capped by what is queued**: with
    small steady frames (GNOME, Windows' AAC) the queue is empty at the low point and the surplus
    sits in the 100 ms output (measured: lowest 78, 0 queued), so it is cut from frames still to come. Without it one late wave stayed queued for good
    and the queue crept to the 500 ms cap (simulated: 440 ms dropped at once). Only a dry output
    followed by sound within 250 ms raises the margin: a clip simply ending is not a late wave.
    The 500 ms overflow counts its silent front part as cut too: Windows begins every new sound
    with a burst of over 500 ms (measured: 454 ms dropped per sound, never heard missing).
  - **AAC is decoded in the helper by the system's decoder, never a bundled one** (`aac.h`:
    `aac_mf.c` Media Foundation, `aac_audiotoolbox.c` AudioToolbox, `aac_faad.c` the distribution's
    `libfaad.so.2`, `aac_ffmpeg.c` the Flatpak runtime's FFmpeg), and reaches jterm as PCM: the JVM never parses compressed audio. Windows sends
    `WAVE_FORMAT_AAC_MS` with **`cbSize` 0** and one raw AAC-LC frame per wave (no ADTS), so the
    decoder's AudioSpecificConfig is *built* from rate and channels (`aac_audio_specific_config`),
    and a format carrying its own configuration is not claimed (the server falls back to PCM):
    keep it that way, it is why no server bytes are parsed in C. The decoder **outlives `Close`**:
    Windows' eight AAC formats differ only in bit rate, and a switch is Close + Open for rdpsnd.
    `libfaad` is loaded **only by absolute path** from root-owned system directories, file and
    directories root-owned and not writable by others (never a bare name, an environment variable or
    the install dir); a refused copy is logged as refused. The Flatpak runtime has no libfaad, so the
    manifest builds with `-DJTERM_AAC_FFMPEG=ON`: the runtime's `libavcodec` (headers from the SDK, so
    the soname major and struct layouts match; the library is *opened*, not linked, because it maps
    ~80 libraries) by absolute path from a read-only mount, only inside the Flatpak and only for a
    session with AAC sound. Its libavutil functions come through libavcodec's handle: with the
    `codecs-extra` extension installed the loader takes libavcodec's dependencies from there, and a
    separately opened libavutil was a second copy. Preferences → General turns AAC off
    (`AppSettings.rdpAacAudio`), read at connect.
  - Testing the decoders without the platforms: `aac_faad.c` with a small C harness in a Fedora
    container (`faad2-libs`, and `ffmpeg-free` to make raw AAC-LC frames by stripping ADTS headers);
    `aac_mf.c` with the same harness cross-compiled by MinGW (`mingw64-gcc` in that container,
    `-lmfplat -lmfuuid -lole32 -static`), copied to the Win11 VM and run over SSH (Media Foundation
    works in an SSH session). Windows' decoder conceals a corrupt frame (outputs 23 ms) instead of
    failing it; libfaad fails it. `aac_ffmpeg.c`: a harness compiled with the SDK's gcc in the
    flatpak-builder container image (`flatpak run --command=gcc org.freedesktop.Sdk//25.08`, encoding
    the test frames with libavcodec's own AAC encoder) and run with `flatpak run --command=<harness>
    com.katmoda.jterm`, i.e. inside the sandbox on the real runtime. `aac_audiotoolbox.c` is only checked by the DMG jobs'
    `smoke-test.py --expect-aac` (converter creation) and a live session.
  - jpackage's macOS `Info.plist` already has `NSMicrophoneUsageDescription` (generic text):
    `release.yml` `Set`s jterm's text (`Add` fails on an existing key).
  - **Reading the helper's log from an installed build** needs a workaround: it has no console and
    jterm logs helper lines at DEBUG (`RdpConnection` logger). Windows: run `app\jterm.jar` with a
    full JDK (jpackage strips `java.exe` from the runtime) plus `-Djterm.rdp.helper=<install>\app\rdp\jterm-rdp-helper.exe`
    and `-Dorg.slf4j.simpleLogger.log.com.katmoda.jterm.terminal.rdp.RdpConnection=debug`,
    redirecting stderr. macOS: `open -n -a /Applications/jterm.app --env JAVA_TOOL_OPTIONS=-D… --stdout F
    --stderr F`, which keeps the microphone permission attributed to jterm (started from Terminal,
    macOS asks on Terminal's behalf).
- `RdpSessionConfig` is `@JsonIgnoreProperties(ignoreUnknown = true)`: `JsonStore` fails on unknown
  fields and quarantines the *whole* `sessions.json`. RDP nodes bumped the schema to v2 — an older
  build can't read the file (one-way upgrade).

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

A node whose `type` this build doesn't know (a newer jterm's node type) loads as
`session.UnknownSessionNode`, `SessionNode`'s Jackson `defaultImpl`, instead of failing and
quarantining the whole file. **Invariant: it round-trips losslessly.** `SessionStore` rewrites the
file on every save and once on load, so the placeholder holds the complete JSON object, original
`type` included, and its serializer writes exactly that (`serializeWithType` ignores the type
serializer, which would add a type id of its own). Reaching the `type` at all needs `visible = true`:
without it Jackson consumes the id before a `defaultImpl` deserializer runs. A visible id is also
handed to `FolderNode`/`SshSessionConfig`/`RdpSessionConfig` as a plain property, which they would
reject, hence `@JsonIgnoreProperties("type")` on the interface. A missing, null, non-string or
non-word `type` (pattern `UnknownSessionNode.TYPE_ID`, lowercase words) still fails the file as
before: no jterm wrote it, and Jackson turns a scalar id into text, so it couldn't be written back
exactly. **A future node type's id must match that pattern.** The placeholder can be moved and
deleted, never opened, edited, duplicated or dragged onto a pane; exports leave it out and imports
drop it (untrusted input). `UnknownSessionNodeTest` compares JSON trees across load + save.

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

### Macros: encrypted at rest under the vault key, exportable under a passphrase
`macro.MacroLibrary` (`macros.json`) is schema-versioned by `macro.MacroMigrations`: v0 was a bare
JSON **array**, v1 is `{schemaVersion, macros}`. The sniff is the root token (an array *is* v0), and
the stamp is written on load even when nothing changed — same one-shot rule as `SessionStore`.
Because the schema sniff reads the file itself rather than going through `JsonStore.loadResult`,
`MacroLibrary` repeats that class's `.unreadable-N` handling and sets `loadFailed`, which **refuses
every save** — without it a corrupt file would silently become an empty library whose next save
destroys the preserved original.

When `AppSettings.encryptMacros` is on, only the **steps** are sealed (`macro.MacroCrypto`), into a
`CredentialVault.Blob` on the macro. `id`/`name`/`hotkey` stay plaintext **on purpose**: `MacroLibrary`
is a `static final` singleton loaded at class-init, so sealing the whole file would put a
master-password prompt on the startup path, and the Macros menu and hotkey dispatcher would go dead
while locked. The **invariant is that exactly one of `steps`/`sealedSteps` is populated at a time**,
in memory as well as on disk — there is no decrypted cache alongside the sealed form, so
`Macro.getSteps()` on a sealed macro returns an *empty* list. Resolve through `MacroCrypto.resolved`
(which copies, so reading never flips the library's copy to plaintext) before replaying or editing.

Sharp edges, all commented in place:
- `MacroCrypto.seal` serializes with `writerFor(STEP_LIST)`, **not** `writeValueAsBytes(list)`. A bare
  `List` erases its element type and `MacroStep` is polymorphic, so Jackson omits the `type`
  discriminator — the blob seals fine and can never be opened again. This was a real bug, caught by
  the seal/unseal round-trip test.
- The macro id is bound in as **AAD** (`Macro.sealAad()` → `PassphraseBox.encrypt(..., aad)`). Every
  blob is under the same vault key, so without it anyone who can write `macros.json` could move one
  macro's steps under another's name and hotkey. `CredentialVault.seal/open(byte[], byte[] aad)` is
  the general "encrypt a blob under the vault key" API added for this; the no-AAD `PassphraseBox`
  signatures still delegate with `null` so the vault's own callers are untouched.
- That AAD binding is why **duplication must unseal first**: `Macro.copy()` carries `sealedSteps`
  over as-is, so giving the copy a fresh id would leave a blob whose AAD no longer matches — opened
  never again, and only discovered on some later replay. `macro.MacroDuplicator` (fresh id, cleared
  hotkey — `byHotkey` returns the first match, so a carried-over one would silently shadow — and a
  `name (n)` suffix matching the sidebar's session duplication) therefore *refuses* a sealed macro;
  `MacroManagerDialog.duplicateMacro` resolves through the vault first and lets `save()` re-seal the
  copy under its own id.
- Nothing in `macro` ever sees the master password — it needs only an unlocked vault, which is what
  keeps macros on the *same single secret* as saved SSH passwords.
- Unlock is a UI decision, so `MacroCrypto` is Swing-free and throws instead of prompting. The EDT
  choke points are `MainWindow.resolveMacro` (menu **and** hotkey both funnel through
  `runMacroOnActivePane`), `MacroManagerDialog.resolved/persist`, and `ConnectionService`, which
  resolves the run-on-connect macro in `connectAsync` **before** the `SwingWorker` — `done()` is on
  the EDT so a prompt there would work, but it would appear *after* the session is already up.
- Turning the setting off decrypts *before* `save()`, since `save()` writes whatever form it finds.
  `PreferencesDialog.applyMacroEncryption` reverts the flag if the rewrite fails or is cancelled — a
  flag disagreeing with the file either exposes macros the user protected or bricks protected ones.

**Export/import** (`macro.MacroExportService`, UI in `ui.macro.MacroTransfer`) mirrors
`session.SessionExportService` exactly, including the Swing-free split and wrong-passphrase-returns-
`null`. Two deliberate differences from the sessions export: the encrypt checkbox **defaults on**
(a macro is arbitrary user-typed text, the case where secrets appear unannounced), and **ids are
preserved** rather than reassigned, because `SshSessionConfig.macroId` references them — so a
sessions export and a macros export moved together keep working. That makes collisions possible,
hence `MacroExportService.merge(...)`, a pure function taking a per-id `Conflict` policy.
`MacroHotkeys` (extracted from `MacroEditDialog`) compares **parsed `KeyStroke`s, not stored
strings**: a hotkey persists as `KeyStroke.toString()` ("shift ctrl pressed F1"), and an import
spelled any other way would pass a string-equality conflict check and then be bound to a stroke
`MacroLibrary.byHotkey` (raw string compare) can never match. Imported hotkeys are canonicalized for
the same reason.

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

### The update check treats the GitHub response as hostile input
`update.UpdateScheduler` asks `update.UpdateChecker` once a day whether a newer release exists and
hands any find to `ui.update.UpdateAvailableDialog`. It is a *notification*, never a downloader.

The two design constraints are both non-obvious:

- **Anti-herd scheduling.** Every install must not hit `api.github.com` at once, so nothing uses a
  fixed time: a due check waits a random 30–180 s (which also keeps it off the startup path), and
  each subsequent period is a fresh draw in **20–28 h** rather than a flat 24 h, so long-running
  instances drift apart instead of converging. `AppSettings.lastUpdateCheckEpochSeconds` is
  stamped on **every attempt, success or failure** — that is what makes an offline or
  rate-limited client back off for a day instead of retrying, and what keeps ten launches in a
  morning down to one request. It is written from the checker thread but `save()`d via
  `invokeLater`, since `AppSettings.save()` rewrites the whole file.

  A **launch is throttled more loosely than the recurring period** (`LAUNCH_THROTTLE_SECONDS`, 4 h),
  which is why there are two pure delay functions rather than one: `initialDelaySeconds` (reads the
  stamp; checks whenever it is older than 4 h, else waits out a period measured from that stamp) and
  `recurringDelaySeconds` (a bare 20–28 h draw — the attempt it reschedules from *is* the one that
  just finished, so no stamp is consulted). Both take a `RandomGenerator` so the bands are
  unit-tested. Keying a launch on the full band was a real defect: an attempt landing 54 minutes
  before v1.9.1 shipped left a 1.9.0 install silent for another half-day across restarts. The two
  thresholds must not cross — a launch stricter than staying open inverts the intent, and
  `initialDelaySeconds` would then be able to return a non-positive delay (asserted in the tests).
- **The response drives a browser launch and on-screen text.** `UpdateChecker.safeReleaseUrl`
  re-validates the server-supplied `html_url` (exact host `github.com`, `https`, path under
  `/drecaise/jterm/releases/`) before it can reach `BrowserLauncher` — the URL from the wire is
  never used directly. Release notes go into a **`JTextArea`, never the `hyperlinkPane`
  `JEditorPane`** the About dialog uses: Swing's HTML renderer fetches remote `<img src>`, which
  would leak the viewer's IP the moment the dialog appeared. The body is read through a 1 MiB cap
  (`ofByteArray` is unbounded), redirects are off, and nothing identifying is sent — only the
  `User-Agent` GitHub requires.

The scheduler is armed even when the user has opted out: it makes no request in that state, but
idles hourly re-reading the flag, so **both directions of the Preferences toggle take effect without
a restart**. Scheduled checks are **silent on every failure** (`LOG.debug`); only the explicit
Help → Check for Updates… reports errors. `AppVersion.isUpgrade` answers false unless *both* versions parse, so a
dev build (`AppInfo.version()` returns `"(dev)"` off an unfiltered build) never nags. `AppInfo` was
widened to public for exactly that call.

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
