# RDP session type — investigation & design

**Status:** Investigation, revision 3 (branch `feature/rdp-session`)
**Date:** 2026-11-07

## Goal

Add `rdp` as a saved-session type next to `ssh`: it lives in the sidebar tree, opens into a tab
like any other session, and shows the remote desktop inside jterm on every platform jterm ships
for.

## Baseline facts

- **Java 21.** `maven.compiler.release` is 21, every CI job installs Temurin 21, the Flatpak uses
  the `openjdk21` SDK extension and the snap stages `openjdk-21-jre`. Nothing in this design needs
  a newer JDK.
- **Release targets** (`.github/workflows/release.yml`):
  - Windows x64 MSI
  - macOS DMG built on `macos-latest`, so **Apple Silicon (arm64) only**
  - Rocky Linux 9 and 10 x86_64 RPMs
  - Ubuntu 26.04 amd64 DEB
  - Flatpak (freedesktop 25.08)
  - Snap (core24, classic confinement)
  - the fat jar
- **Prior art:** `origin/feature-rdp-sessions` (one commit, 2026-06-14, based on master as of
  2026-06-13). It drives an external `xfreerdp`/`wfreerdp` process and embeds its window
  (`/parent-window` on X11; `SetParent` + `AttachThreadInput` on Windows; detached on macOS) in a
  dedicated full-tab `RdpTab`. Its own notes say the Windows and macOS paths are unverified. Parts
  worth reusing are listed under "Reuse from the prototype" below.

## Decision summary

| Topic | Decision |
| --- | --- |
| Where it lives | `RdpPane implements GridContent`. **Open in a new tab by default** (a one-cell grid looks like a full tab); splits are allowed but never the default. |
| RDP engine | **FreeRDP in a separate helper process** (`jterm-rdp-helper`, small C program). jterm paints the frames and owns all input. |
| Java ↔ helper | Versioned binary messages over the helper's stdin/stdout. No JNA, no extra Java dependency. |
| Host identity | jterm-owned TOFU store that pins the certificate fingerprint. No "ignore certificate errors" switch. |
| FreeRDP supply | **Bundled on every target** (decided 2026-11-07): one tested FreeRDP version everywhere, and jterm owns its security updates. |
| Shortcuts in an RDP pane | **Only Ctrl+Shift+… jterm shortcuts are intercepted** (decided 2026-11-07); every other stroke goes to the remote desktop. |
| Java version | Java 21 is enough. Java 25 would change nothing here, because the helper design has no native bindings in Java. The only gain would be deterministic unmapping if the shared-memory fallback is ever needed. |

## Where it lives: `GridContent`, opened as a tab

RDP is not a byte stream, so it is not a `TerminalSession`. The grid already hosts a non-terminal
cell: `ui.sftp.SftpPane implements GridContent`. Terminal-only behaviour (broadcast, the restart
screen, highlight, find, save output) is reached through `instanceof TerminalPane`, so an
`RdpPane` opts out of it for free.

The prototype instead made RDP its own tab type (`ui.TabContent`, `rdp.RdpTab`), on the grounds
that "a whole remote desktop in a 3×3 split makes no sense". The sizing point is valid, but a
second tab type costs more than it saves. Every tab feature on master is written against
`PaneGrid`: detach/re-attach, drag-tab-to-window, `decorateTab`, close handling and the window
topology. Each of them would need an `RdpTab` branch. Opening RDP as a **single-cell grid in a new
tab** gives the full-tab experience and inherits all of that unchanged. Splits stay possible, for
example a 2×2 wall of scaled-down consoles, but no menu item defaults to them. *This reverses a
choice the prototype made, so the repo owner should confirm it.*

## Engine options

| Option | Verdict |
| --- | --- |
| A. Embed an external FreeRDP client's window (the prototype) | Rejected: see below. |
| B1. libfreerdp **in-process** via a C shim + JNA (revision 1 of this spec) | Rejected on isolation grounds: see below. |
| **B2. libfreerdp in a helper process; jterm paints the frames and sends input** | **Recommended.** |
| C. Pure-Java RDP stack | None is viable. They are unmaintained and lack NLA/CredSSP and TLS 1.2+. |
| D. Launch an external client in its own window | Only as a fallback "Open externally…" item. |

### Why not A (window embedding)

- **jterm loses the keyboard.** All shortcuts go through one `KeyboardFocusManager` dispatcher. A
  foreign native window owns focus, so jterm shortcuts and macros stop working while it's focused.
- **`AttachThreadInput` ties jterm's UI thread to FreeRDP's.** Once the input queues are merged, a
  hung or busy FreeRDP can freeze the AWT toolkit thread, and so the whole app, which defeats the
  point of running it as a separate process. Re-parenting a top-level window owned by another
  process with `SetParent` is also fragile. The prototype finds the window by polling
  `EnumWindows` with a sleep, which is a race.
- **Executable planting.** `RdpProcess.resolveBinary()` runs the first `wfreerdp.exe`/`xfreerdp`
  it finds via `$JTERM_FREERDP`, **the jar's own directory**, or `PATH`, and then writes the
  password to it. A jar run from `Downloads` next to a planted `wfreerdp.exe` hands that file the
  password.
- **Weakens certificate checks.** It offers `/cert:ignore` because `/cert:tofu` refuses a name
  mismatch. That disables host verification entirely, instead of pinning the fingerprint once.
- **Not uniform:** detached on macOS, X11-only embedding on Linux, and heavyweight windows that
  paint over the grid's drop hints and borders.

### Why not B1 (in-process libfreerdp)

- **A FreeRDP crash kills jterm.** A segfault in a codec takes down every open SSH session in the
  process.
- **A hostile server becomes code inside jterm.** FreeRDP regularly ships fixes for
  memory-corruption bugs a malicious *server* can trigger in the client (out-of-bounds reads and
  writes in codecs and channels). In-process, such a bug runs inside the JVM that holds the
  **unlocked vault key**, the master password and live SSH agent connections. Out of process, the
  damage is limited to a helper that knows only one RDP password.
- It also needs JNA at compile time (pty4j brings JNA only at `runtime` scope), careful Windows DLL
  search-order handling, and native callbacks re-entering the JVM.

## B2 design

### `jterm-rdp-helper` (C, built per target in CI)

A minimal FreeRDP 3 client:

- `freerdp_client_context_new` → `PreConnect` loads channels → `PostConnect` calls
  `gdi_init(instance, PIXEL_FORMAT_BGRX32)` → `EndPaint` sends the GDI invalid region.
- It registers `Pointer_New/Set/Free` callbacks (`graphics.h`), because GDI does **not** draw the
  cursor into the framebuffer. Without them the remote cursor shape (I-beam, resize, busy) is lost.
- It loops on `freerdp_get_event_handles` / `WaitForMultipleObjects` /
  `freerdp_check_event_handles`, with stdin as one of the waited handles.

Every API named here was checked against the headers of a FreeRDP 3.33.0 build.

Hardening rules:

- **Exits when stdin reaches end-of-file.** If jterm dies, the helper dies, so it can never be
  orphaned.
- **Secrets arrive only over the pipe**, never in argv, the environment or logs.
- **Built as a GUI-subsystem exe on Windows.** A console-subsystem child of `javaw` pops up a
  console window.
- **Resolved only from the app's own install directory** (jpackage `app` dir, Flatpak `/app`, snap
  `$SNAP`), by absolute path. No `PATH`, jar-directory or environment-variable search. A user
  override exists only as an explicit Preferences setting, which is what the fat jar uses.

### Wire protocol (stdin/stdout, little-endian, length-prefixed)

| Direction | Message |
| --- | --- |
| jterm → helper | `HELLO(version)`, `CONNECT(host, port, user, domain, password, gateway…, w, h, scale%)`, `KEY(down, scancode)`, `UNICODE(down, codepoint)`, `MOUSE(flags, x, y)`, `RESIZE(w, h, scale%)`, `CERT_REPLY(decision)`, `AUTH_REPLY(user, domain, password)`, `CLIP_*`, `BYE` |
| helper → jterm | `HELLO(version, freerdpVersion)`, `CONNECTED(w, h)`, `FRAME(x, y, w, h, pixels)`, `DESKTOP_RESIZED(w, h)`, `POINTER(id, w, h, hotX, hotY, argb)` / `POINTER_SET(id)` / `POINTER_HIDE`, `CERT_REQUEST(host, port, subject, issuer, sha256, changed)`, `AUTH_REQUEST(reason)`, `DISCONNECTED(code, message)`, `CLIP_*` |

`FRAME` carries only the dirty rectangle. Ordinary desktop work is a few KB per update. A
worst-case full-screen video at 1920×1080×4 bytes × 30 fps is about 250 MB/s, well within a local
pipe but worth measuring in the spike. If it shows up in profiles, the fallback is a
shared-memory framebuffer with `FRAME` reduced to a rectangle notice. A version mismatch in `HELLO`
is a clear error rather than undefined behaviour.

### Java side (no native bindings)

| Class (proposed) | Role |
| --- | --- |
| `session.RdpSessionConfig` | The `SessionNode`; fields under "Persistence" below. |
| `terminal.rdp.RdpHelper` | Locates and launches the helper and speaks the protocol. Owns a reader thread; `stdin` writes are serialised. |
| `terminal.rdp.RdpConnection` | Turns protocol events into callbacks. Certificate and auth requests are answered on the EDT while the helper waits, which is the `JtermUserInteraction` pattern. |
| `ui.rdp.RdpPane` | `JPanel implements GridContent`. A `BufferedImage` (`TYPE_INT_RGB`; BGRX32 read as a little-endian int is `0x00RRGGBB`), dirty-rect blits with coalesced `repaint(rect)`, custom `Cursor`s from `POINTER`, a reconnect overlay, and header/connection bars like `SftpPane`. |
| `ui.rdp.RdpLauncher` | Connects off the EDT and places the pane on the EDT (the `SftpLauncher` / `placeSftp` pattern). |

### Keyboard and shortcuts

- Map `KeyEvent` (extended key code + key location) to Windows virtual keys in a Java table, then
  to RDP scancodes in the helper with WinPR's `GetVirtualScanCodeFromVirtualKeyCode(vk, 4)`. Fall
  back to `UNICODE` from `keyTyped` for dead keys and non-US layouts.
- Release every held key when the pane loses focus, or modifiers stay stuck on the remote side.
- **Shortcut policy (decided).** While an RDP pane has focus, the global dispatcher intercepts only
  bindings that include **both Ctrl and Shift**. Everything else (Ctrl+T, Ctrl+W, Ctrl+C, F-keys
  and so on) goes to the remote desktop. This is the ADR 0010 "applied or fall through" rule with
  one extra condition: in an RDP pane, a binding without Ctrl+Shift counts as not applicable.
  Default bindings on a bare Ctrl+letter will therefore not work there, by design. It needs an ADR.
  Java can't capture the Windows key or Alt+Tab, so the pane header gets **Send Ctrl+Alt+Del** and
  **Send Win** buttons.
- macOS: map Cmd to Ctrl for the remote side by default, with a toggle.

### HiDPI and resize

- Swing paints in logical pixels; Windows at 150% and every Retina Mac render more device pixels.
  Request the remote desktop at **device** pixels and send `scale%` (FreeRDP's
  `DesktopScaleFactor`) so remote text stays crisp at a readable size. Then draw the image with the
  inverse of the `Graphics2D` transform.
- When the server supports the Display Control channel (Windows 8.1 / Server 2012 R2 and later),
  debounced pane resizes become `RESIZE`. Otherwise the fixed desktop is scaled to fit, keeping its
  aspect ratio.

## Security

**Threat model:** a hostile or compromised RDP server; a network attacker in the middle; other
local users.

| Concern | Mitigation |
| --- | --- |
| Server exploits the client | The helper process boundary. A crash shows as `DISCONNECTED`, and the JVM and vault are untouched. |
| Bundled FreeRDP goes stale | **Required, since FreeRDP is bundled everywhere:** the `Security` workflow (like `packaging-eol`) fails when the pinned FreeRDP version is older than the latest 3.x patch release or has a published CVE. A FreeRDP security release then means a jterm release. Build FreeRDP with `WITH_VERBOSE_WINPR_ASSERT=OFF` for releases. |
| Man-in-the-middle | TOFU keyed by host:port with a **pinned SHA-256 fingerprint** in a jterm store under `AppPaths`. A changed fingerprint shows a blocking warning, mirroring `JtermKnownHostsVerifier`. A name mismatch is a first-use prompt, not a reason to switch verification off. |
| Credential exposure | Passwords come from `CredentialVault`, keyed by session id. `CredentialResolver` gets a small interface shared by SSH and RDP configs. Passwords travel only over the pipe; char arrays are zeroed after the write. Never logged (the prototype prints the full command line). |
| Downgrade | Default security is NLA. TLS-only or RDP security must be chosen explicitly per session and labelled insecure. |
| Server reaches local resources | Drive, clipboard, audio, printer and smart-card redirection are **off by default** and per session. Drive redirection names the exact folder shared (never "all drives") and warns that the server can read and write it. |
| Planted executables or DLLs | Helper resolved by absolute path from the install directory only. On Windows the helper's DLLs sit beside it, which the loader searches first. |
| Frame data on disk | None. If shared memory is ever adopted, it must be an anonymous or owner-only (0600) mapping that is removed on close. |

## Persistence

- `@JsonSubTypes.Type(value = RdpSessionConfig.class, name = "rdp")`: the same type name and
  **field names as the prototype**, so any sessions saved by that branch keep loading. The fields
  are `id, name, iconId, host, port, user, domain, passwordAuth, savePassword, widthMode,
  scalingMode, width, height, colorDepth, securityMode, redirectClipboard, redirectDrives,
  redirectAudio, gateway`.
- `ignoreCertErrors` is dropped (replaced by fingerprint pinning).
- **`JsonStore`'s `ObjectMapper` is Jackson's default, so `FAIL_ON_UNKNOWN_PROPERTIES` is on.** Any
  unknown field anywhere fails the whole `sessions.json`, which is then quarantined as
  `.unreadable-N` and the user sees an empty tree. This applies to a dropped field read back from
  an old file, and to an older jterm reading a newer file. Annotate
  `@JsonIgnoreProperties(ignoreUnknown = true)` on `RdpSessionConfig`, as `TunnelConfig` already
  does.
- An **older jterm still fails on the unknown `rdp` type id** and quarantines the tree. That
  annotation can't fix it after the fact. Bump the root `schemaVersion` to v2, call it a one-way
  upgrade in the release notes, and separately consider a `defaultImpl` placeholder so future node
  types degrade instead of failing.

## Platform matrix

| Target | FreeRDP | Helper build | Notes |
| --- | --- | --- | --- |
| Windows x64 MSI | Bundled: FreeRDP 3.33, OpenSSL, zlib, MSVC runtime, about 12 MB | `windows-latest`: vcpkg + CMake, as in `~/git/freerdp/build.cmd` | Proven locally. Cache the build in CI. |
| macOS arm64 DMG | Bundled dylibs, built on the runner | CMake on `macos-latest` | Rewrite library paths to `@loader_path` (`install_name_tool`) or use `dylibbundler`. The DMG is unsigned today; if it becomes notarized, the helper needs signing too. An arm64-only DMG matches the existing app. |
| Rocky 9 RPM | Bundled, built in the `rockylinux:9` job | Same job | Links the system OpenSSL 3; RPATH `$ORIGIN`. |
| Rocky 10 RPM | Bundled, built in the `rockylinux:10` job | Same job | As above. |
| Ubuntu 26.04 DEB | Bundled, built in the `ubuntu:26.04` job | Same job | As above. |
| Flatpak | FreeRDP as a manifest module, pinned to the same version | Manifest module | No new permissions for video and input (`--share=network` already present). Audio later needs `--socket=pulseaudio`. |
| Snap core24 | Bundled as a snap part (CMake), pinned to the same version | Snap part | Classic confinement. |

All targets build FreeRDP from the same pinned tag with the same CMake options as
`~/git/freerdp/build.cmd`. Cache the FreeRDP build per target, keyed on that tag, so a normal
release doesn't rebuild it.
| Fat jar | Not included | — | RDP disabled with a hint, unless Preferences points at a helper. |

Linux runs Swing through XWayland, so lightweight painting behaves the same on X11 and Wayland
sessions. The helper builds against the FreeRDP 3 C API, which keeps its ABI stable within the
major version (`libfreerdp3.so.3`). Building it in each target's own container avoids any mismatch.

**Graceful degradation:** if the helper is missing or `HELLO` fails, RDP actions are disabled with
the reason in a tooltip. RDP sessions still load, can be edited, and can be exported.

## Reuse from the prototype (`origin/feature-rdp-sessions`)

**Keep:**
- the `RdpSessionConfig` model, for schema compatibility
- the editor dialog and sidebar wiring in `SessionSidebar` (about 255 lines)
- `rdp.svg` / `rdp-plus.svg`
- vault reuse (`MainWindow.rdpPassword`)
- `docs/building-freerdp-windows.md`

**Drop:** `RdpProcess`'s binary resolution, `/cert:ignore`, the `embed/` package, `RdpTab` /
`TabContent`, and the explicit JNA dependency.

The branch is about five months behind master and touches `MainWindow`, `SessionSidebar` and
`PaneGrid`. Port these pieces onto the new branch rather than merging it.

## Touch points on master

| Area | Change |
| --- | --- |
| `session/SessionNode`, `RdpSessionConfig` | `permits`, subtype, `ignoreUnknown`. |
| `session/SessionStore`, `SessionExportService`, `SessionMigrations` | Collect/export/duplicate RDP nodes; schema v2. |
| `security/CredentialResolver` | Accept a shared config interface instead of `SshSessionConfig`. |
| `ui/sidebar/SessionSidebar`, `SessionNodeRenderer` | New/edit/duplicate/drag; open modes; glyph. |
| `dnd/` + `PaneGrid.installDnd` | `RdpTransferable`, or generalise `SessionTransferable`. |
| `app/MainWindow`, `ui/tabs/TabPane` | `openRdpSession(cfg, mode)` with new-tab default; Open Folder includes RDP. |
| `keymap/TermAction` | Host key, Send Ctrl+Alt+Del. Needs a `KeymapMigrations` step only if a default changes. |
| `release.yml`, `packaging/*`, `security.yml` | Helper builds and bundling; FreeRDP CVE/staleness check. |
| `docs/` | `rdp-sessions.md`, an ADR ("RDP via an out-of-process FreeRDP helper"), and the architecture model. |

## Phased plan

1. **Spike, Windows, about 3 days.** Build the helper and protocol against the local 3.33 build,
   plus a throwaway Swing frame. Exit criteria:
   - a usable desktop with a correct cursor
   - typing works on a non-US layout
   - pipe frame throughput measured with full-screen video
   - killing the helper leaves the JVM unaffected
2. **MVP:** port the model, editor and sidebar from the prototype; `RdpPane`; vault; TOFU;
   reconnect; graceful degradation; Windows packaging.
3. **Quality:** dynamic resize, HiDPI scaling, host key, text clipboard, Ctrl+Alt+Del.
4. **Reach:** macOS, Linux and Flatpak/snap packaging; Security-workflow check; user docs; ADR.
5. **jterm-specific wins:** RDP through an SSH jump host. A `TunnelManager` local forward plus
   `localhost:<port>` reuses `JumpHostConfig`, the agent and the vault. Also RD Gateway.

## Decisions (2026-11-07)

1. RDP opens as a single-cell grid in a new tab (`GridContent`), not the prototype's `RdpTab`.
2. In an RDP pane, only Ctrl+Shift+… jterm shortcuts are intercepted.
3. FreeRDP is bundled on every target.
4. Clipboard sharing is per session and off by default; see [Clipboard](#clipboard-revision-3).

## Clipboard (revision 3)

**Scope:** text (`CF_UNICODETEXT`) and images (`CF_DIB`), through FreeRDP's `cliprdr` channel. Files,
HTML, RTF and every other format are ignored. Protocol version 2.

**Policy (decided 2026-11-08).** Two session settings, both off unless the user turns them on:

| Setting | Field | Default | Effect |
| --- | --- | --- | --- |
| Share clipboard | `redirectClipboard` | off | Opens the `cliprdr` channel. What the server copies lands on the local clipboard **without a prompt**. |
| Remote can paste mine | `clipboardToRemote` | on (only meaningful with sharing on) | The server may also paste what is on the local clipboard. Off: sharing is one-way, remote to local. |

The earlier idea of an explicit "copy from remote" action was dropped. The common case is a user
connecting to their own machine, where a confirmation step is friction without a threat. The
protection is the opt-in itself: with sharing off, `CONNECT` carries `clipboardFlags = 0`, the helper
never sets `FreeRDP_RedirectClipboard`, and the server cannot see that a clipboard channel exists.
The helper enforces both directions on its own, so a bug on the Java side cannot widen what a
session allows.

**Delayed rendering both ways.** Nothing is copied until it is wanted.

- *Local to remote:* jterm announces a **kind** (`CLIP_OFFER`); the helper turns it into a format
  list. The bytes are read from the local clipboard only when the server pastes
  (`CLIP_REQUEST` → `CLIP_DATA`). Whatever sits on the clipboard goes nowhere unless a remote
  application asks for it, and the server can only ask for a format that was offered, one request at
  a time.
- *Remote to local:* the server's format list becomes `CLIP_REMOTE_OFFER`; jterm answers `CLIP_FETCH`
  for text, else an image, and the helper requests that single format (`CLIP_REMOTE_DATA`).

**Limits.** 32 MiB per transfer either way, refused rather than truncated. Before any pixel buffer
is allocated, the DIB header is checked against the data present, and compressed DIBs (RLE,
embedded JPEG/PNG) are refused. The server's payload is untrusted: that is the helper-process
boundary doing its job, but the Java decoder must be defensive too.

**Echo.** What was written for the server is remembered by object identity, and the clipboard event
it causes is not announced back.

**A new copy is not always an event.** `SunClipboard.checkChange` returns early when the set of
formats is unchanged (read from the JDK's bytecode), so copying new text over old text sends no
`FlavorEvent`. A Windows server caches what it has rendered until it receives a new format list. So
the pane re-announces whenever the remote desktop gains focus (`RdpClipboardBridge.refresh`), which
is when someone who copied locally is about to paste.

**Not done:** file copy (`CB_STREAM_FILECLIP_ENABLED`, needs FUSE or a virtual-file layer), CF_HTML,
`CF_DIBV5`-only servers (the JDK's BMP reader's support for 124-byte V5 headers is unverified, and
Windows always also offers `CF_DIB`), clip-data locking.

**Verified:** unit tests for the protocol, the codec and the bridge (against a private
`Clipboard`), and the helper's v2 handshake and `CONNECT` parsing against a closed port.
**Not yet verified against a live server.**

## Spike status

The helper (`native/rdp-helper/`) and a throwaway Swing harness (`native/rdp-helper/spike/`) are
built on Windows against FreeRDP 3.33.0. Verified so far without a server:

- the HELLO handshake
- FreeRDP logging goes to stderr only, so the protocol stream stays clean
- a structured `DISCONNECTED` with FreeRDP's error code
- the helper exits on its own afterwards, with no orphan

**Live session verified (2026-11-07)** against GNOME Remote Desktop in remote-login mode on Rocky
Linux 10 (`orion`):

- **Certificate:** the self-signed `CN=GNOME` certificate was referred to jterm as a name mismatch
  (flags `0x80`) and rejected until its SHA-256 fingerprint was confirmed on the server.
- **Pinning:** once pinned, it was accepted silently, including across GNOME's RDSTLS handoff from
  the system daemon to the session.
- **Display:** 1920×1200 device pixels at 150% scaling, with the graphics pipeline active, the
  server advertising Unicode input, and the GDM greeter rendered correctly through the pipe.

**Interactive (user, same day):**
- **Input:** GDM login and typing work through the Unicode path, and Ctrl+C reaches remote apps.
- **Video:** a full-screen YouTube video in Firefox moved 7–35 MB/s through the pipe (4–93
  rectangles/s).
- **Pipe capacity:** benchmarked separately on the same machine, the pipe plus the Java blit code
  sustains **about 1,065 MB/s (about 121 full 1920×1200 frames/s)**. The pipe is not the
  bottleneck, so the shared-memory fallback is dropped.
- **The real limit is upstream.** The bundled FreeRDP has no H.264 (no OpenH264/FFmpeg), so GNOME
  Remote Desktop can't use its AVC codecs and falls back to RemoteFX progressive. That is heavy to
  encode on the server and to decode in software. **Adding H.264 to the bundled FreeRDP is the
  biggest video-performance lever.** OpenH264 loaded at runtime (`WITH_OPENH264_LOADING`) is the
  usual way to stay clear of the H.264 patent issues.

**Video codec follow-up:**
- **Client side fixed.** FreeRDP was rebuilt with `WITH_MEDIA_FOUNDATION=ON`, the Windows built-in
  H.264 decoder. The helper now probes for a working decoder (`h264_context_new`) and only then
  sets `GfxH264`/`GfxAVC444`/`GfxAVC444v2`, which FreeRDP leaves off for library clients. The
  server now confirms H.264 as allowed (flags `0x82`).
- **orion still sends only RemoteFX progressive.** The only hardware-encoder line in its journal
  is a failed NVENC load, so GNOME Remote Desktop apparently found no usable Intel (VA-API) encoder
  even though the Core Ultra 5 228V has one.
- **Per-OS H.264 plan:** Media Foundation on Windows, VideoToolbox on macOS, OpenH264 (Cisco
  binary, runtime-loaded) on Linux.

**Display Control:** GNOME ignores the scale factor in the connection's core data; 100% and 200%
gave byte-identical screens. The helper now handles the Display Control channel itself:
- it sends the monitor layout (size plus scale) when the server's capabilities arrive,
- jterm can send `RESIZE` (protocol message `0x07`), and
- the RDP thread sends layouts, woken by its own event handle.

Dynamic resize is verified: the remote desktop follows the pane. GNOME still renders at 100% when
sent 150%. The likely cause is that mutter needs its `scale-monitor-framebuffer` experimental
feature for fractional scales (not yet confirmed).

Found and fixed during the spike: a GUI-subsystem helper given an inherited *console* stderr has
no usable fd 2. The helper now discards logs in that case instead of exiting, and the host must
pipe and drain the helper's stderr rather than inherit it.
