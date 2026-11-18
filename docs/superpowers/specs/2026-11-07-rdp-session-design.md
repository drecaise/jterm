# RDP session type — investigation & design

**Status:** Implemented on branch `feature/rdp-session` for Windows, every Linux target and macOS
(arm64 and x86_64 DMGs; live on an Intel macOS VM, Apple Silicon not yet run). Revision 4. See [Implementation status](#implementation-status-2026-11-09).
**Date:** 2026-11-07, status updated 2026-11-10

## Goal

Add `rdp` as a saved-session type next to `ssh`: it lives in the sidebar tree, opens into a tab
like any other session, and shows the remote desktop inside jterm on every platform jterm ships
for.

## Implementation status (2026-11-09)

The design below is kept as written, with inline notes where the build went another way. This
section is the summary.

### By phase

| Phase | Status |
| --- | --- |
| 1. Spike (Windows) | **Done.** All four exit criteria met; see [Spike status](#spike-status). |
| 2. MVP | **Done.** Model, editor and sidebar ported; `RdpPane`; vault; certificate TOFU; reconnect; graceful degradation; Windows packaging. |
| 3. Quality | **Done**, with two changes of plan: the host key was superseded by the Ctrl+Shift shortcut policy, and the clipboard covers images as well as text. Dynamic resize, HiDPI scaling and Ctrl+Alt+Del (button and shortcut) are in. |
| 4. Reach | **Done** (macOS live on Intel only). Helper bundled in the MSI, both DMGs, both RPMs, the DEB, the Flatpak and the snap; `freerdp` and `openh264` jobs in the `Security` workflow; user docs (`docs/rdp-sessions.md`); ADR 0012; RDP in the architecture model. macOS helper built from pinned FreeRDP, OpenSSL 3.5 and cJSON sources (`build-freerdp-macos.sh`, `build-macos.sh`), ad-hoc re-signed, smoke-tested from inside each finished DMG. |
| 5. jterm-specific wins | **RDP through an SSH session done** (2026-11-09): the session's Gateway tab names a saved SSH session, whose jump hosts, keys, agent and vault password apply; a loopback forward per connect attempt, certificate checked against the real host (`UserSpecifiedServerName`, protocol v4). **RD Gateway done** (2026-11-09): gateway host/port, desktop or separate credentials (vault-saved after connect), gateway certificate pinned like the desktop's, gateway always used (protocol v5). **Not verified against a real RD Gateway.** |

### Built beyond the plan

- **H.264 on every built target:** Media Foundation on Windows; on Linux, Cisco's OpenH264
  downloaded on opt-in (pinned hash, stamped install, refreshed when the pin moves, loaded by full
  path rather than `LD_LIBRARY_PATH`); the Flatpak uses the runtime's openh264 extension.
- **Clipboard** (revision 3), text, HTML and images, opt-in per session, including: the server's
  clipboard at connect is not fetched until the user types or clicks in the session, and a
  re-announce on focus never takes the clipboard back from a remote copy jterm did not fetch.
- **Drag-and-drop and Open Folder:** an RDP session dropped on a pane opens as a split (a new tab
  when that direction is full), on an empty cell fills it, on the tab strip's "+" opens a new tab;
  Open Folder includes RDP sessions, each in its own tab.
- **Rename** an RDP tab like a terminal tab.
- **macOS:** Cmd sent as Ctrl (Preferences toggle, off = the Windows key), and a wait for macOS's
  Local Network permission before the helper's first connect, with the reason for it in the DMG's
  `Info.plist` (`NSLocalNetworkUsageDescription`).

- **Shared folders** (2026-11-09, design `2026-11-09-rdp-drives-and-file-copy-design.md`):
  jterm's own forwarding drive device instead of FreeRDP's, every file request answered by
  `terminal.rdp.drive`; protected folders refused and hidden.
- **Clipboard file copy** both ways (same design): paste local files on the remote desktop after
  per-copy consent; save remote copies with "Save files…".
- **Full screen** (2026-11-10, design `2026-11-10-rdp-full-screen-design.md`, open item 7 below):
  live-tested on Linux, Windows and macOS installer tests pending.

### Built differently from the design

- **Java classes.** No `RdpHelper` or `RdpLauncher`: `RdpConnection` starts the helper and owns its
  pipes, `RdpHelperLocator` finds it, `RdpProtocol` mirrors `protocol.h`. `RdpPane` places itself
  first and connects second, so there is no connect-then-place launcher.
- **Wire protocol.** `native/rdp-helper/src/protocol.h` is the source of truth (version 9:
  clipboard flags in v2, security flags in v3, server name in v4, RD Gateway in v5, HTML clipboard
  in v6, shared folders in v7, clipboard files local to remote in v8, remote to local in v9). There is no
  `AUTH_REQUEST`/`AUTH_REPLY`: a rejected password ends the attempt with
  `Disconnected.credentialsRejected()` and the pane re-prompts.
- **Graceful degradation.** Without a helper, the pane says so instead of connecting, rather than
  RDP actions being disabled with a tooltip.
- **Helper override.** Only the developer property `-Djterm.rdp.helper`; there is no Preferences
  setting, so the fat jar has no RDP.
- **Shared-memory frames** dropped: the pipe measured about 1 GB/s. *(Corrected 2026-11-11: that
  figure was for Linux and for one write per frame. On Windows, written a row at a time as the
  helper did, the pipe managed 15 MB/s. See [Video performance](#video-performance-2026-11-11).)*
- **Prototype fields** not carried: security mode, colour depth, drive/audio redirection, gateway,
  `ignoreCertErrors`. Unknown fields are ignored, so files the prototype wrote still load.

### Open items

1. ~~**Security: standard RDP security is not refused.**~~ **Fixed 2026-11-09.** The helper left
   FreeRDP's defaults, which allow standard RDP security as well as TLS and NLA. That mode has no
   TLS certificate, so the pin check never ran. Measured against a fake server on localhost, the old
   helper proceeded into standard RDP security both when the server selected it and when it sent no
   negotiation data. The helper now sets `FreeRDP_RdpSecurity` to false; both cases fail negotiation
   (`0x0002000C`) and the pane explains why. Followed the same day by the per-session setting:
   NLA is required unless the session allows TLS without NLA.
2. **macOS.** Helper built: one per DMG architecture, `@loader_path` bundle, VideoToolbox for
   H.264 (the smoke test requires it). Status on the Intel VM (macOS 26, from a locally built DMG,
   2026-11-09):
   - **Works live:** connecting to the Windows 11 VM and using the desktop.
   - **Local Network permission:** the first connect of the earlier build failed while macOS asked
     "find devices on your local network?", and Reconnect then worked. The wait added for it
     (`LocalNetworkAccess`) works: with access turned off and back on in System Settings, the pane
     waited and connected by itself. The prompt itself (and its new `Info.plist` text) has not been
     seen again, because macOS remembered the earlier answer.
   - **Cmd as Ctrl: built, not verified.** Win+C through Proxmox's noVNC from a Windows laptop did
     not copy remotely, so either noVNC doesn't deliver that key as Cmd or the mapping doesn't
     work; the real Ctrl key works. Needs a check of which `KeyEvent` arrives (or a real Mac
     keyboard).
   - **Still to do:** a run on real Apple Silicon (the only place a signing mistake shows), the
     DMG jobs in CI (never run: helper build, `Info.plist` edit, ad-hoc re-sign, in-DMG smoke
     test), and the download path (Gatekeeper quarantine on the helper, untested because the VM's
     DMG never came from the internet). Cmd+Q: a quit handler has since been built
     (`app.AppQuitHandler`); on the macOS VM only File → Quit has been seen asking; the check list is the full-screen spec's Cmd+Q
     item. The DMG jobs have since run in CI (Release runs on 2026-11-12, both architectures, with
     the in-DMG smoke test).
3. **Phase 5 follow-ups.** Both routes are built. RD Gateway is untested against a real gateway
   (only a fake-server smoke test), and a separately saved gateway password is not exported.
   Through SSH, Kerberos can't work (the SPN still comes from `127.0.0.1`); NLA falls back to NTLM.
   Gateway through SSH (both routes at once) is not supported.
4. **Not built from the design:** a Preferences override for the helper. (Printing and smart
   cards were built on 2026-11-17, jterm's own way: one "jterm" printer whose jobs arrive as PDFs
   and wait in the pane's bar, and smart-card calls answered by jterm through PC/SC with the helper
   only forwarding; see `2026-11-17-rdp-printer-and-smart-card-design.md`.) (The `defaultImpl` placeholder for future node types was built on
   2026-11-11: `UnknownSessionNode`, see below. Drive redirection and clipboard file copy both
   ways, with clip-data locking, were built and verified live on 2026-11-09/10; see
   `2026-11-09-rdp-drives-and-file-copy-design.md`, which lists their own open items. Sound and
   the microphone were built and verified live on 2026-11-11/12; see
   `2026-11-11-rdp-audio-design.md`.)
5. **Clipboard formats not verified live:** CF_HTML and `CF_DIBV5`-only servers (built
   2026-11-09, protocol v6).
6. **Not verified live:** clipboard with sharing off and with "Remote can paste mine" off; GNOME
   Remote Desktop as the clipboard server; a transfer near the 32 MiB cap; the 2026-11-08/09
   clipboard changes (connect behaviour, refocus ownership); the OpenH264 preload with Cisco's real
   library; drag-and-drop and Open Folder by hand; the Send Ctrl+Alt+Del shortcut (the toolbar's
   Ctrl+Alt+Del and Windows-key buttons work since the extended-key fix of 2026-11-10).
7. **Full screen** (designed and built 2026-11-10, live-tested on Linux; Windows and macOS
   pending): see `2026-11-10-rdp-full-screen-design.md`. One pane's desktop in its own window on
   one monitor, a mechanism per OS (borderless on Windows, EWMH on Linux, a native Space on macOS), no keyboard
   hook, Ctrl+Shift+F11 plus a hot-edge bar, and no jterm dialog ever shown while full screen.
8. **GNOME fractional scaling.** GNOME Remote Desktop still renders 150% as 100%; the likely cause
   (mutter's `scale-monitor-framebuffer`) is unconfirmed.
9. **A test build once ignored its window's Close button** (2026-11-10, after an RDP session had
   been disconnected by the server). The event thread was idle, so it was not frozen; whether a
   close handler or a hidden dialog swallowed it is unknown. Not seen since.

## Baseline facts

- **Java 21.** `maven.compiler.release` is 21, every CI job installs Temurin 21, the Flatpak uses
  the `openjdk21` SDK extension and the snap stages `openjdk-21-jre`. Nothing in this design needs
  a newer JDK.
- **Release targets** (`.github/workflows/release.yml`):
  - Windows x64 MSI
  - macOS DMGs: arm64 on `macos-latest`, x86_64 on `macos-15-intel`
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
| D. Launch an external client in its own window | Only as a fallback "Open externally…" item. **Dropped (2026-11-09):** not built and not needed now the helper works on every target that has one. |

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
  *(Done: implemented, and recorded in ADR 0012.)*
  Java can't capture the Windows key or Alt+Tab, so the pane header gets **Send Ctrl+Alt+Del** and
  **Send Win** buttons. *(Ctrl+Alt+Del is done, as a button in the pane's bottom bar and as the
  rebindable Send Ctrl+Alt+Del action, default Ctrl+Shift+Delete. Send Win is a "Win" button in
  the same bar, a tap of the left Windows key (no shortcut, and no way to hold it for a chord).)*
- macOS: map Cmd to Ctrl for the remote side by default, with a toggle. *(Done: Preferences →
  General → "Cmd acts as Ctrl in RDP", on by default; off sends Cmd as the Windows key.
  `RdpKeyMap.toWindows(code, location, metaAsCtrl)`. Cmd+Q now goes to a quit handler that asks
  first, `app.AppQuitHandler`; seen through File → Quit on the macOS VM, Cmd+Q itself not yet.)*

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
| Server exploits the client | The helper process boundary. A crash shows as `DISCONNECTED`, and the JVM and vault are untouched. *(Done.)* |
| Bundled FreeRDP goes stale | **Required, since FreeRDP is bundled everywhere:** the `Security` workflow (like `packaging-eol`) fails when the pinned FreeRDP version is older than the latest 3.x patch release or has a published CVE. A FreeRDP security release then means a jterm release. Build FreeRDP with `WITH_VERBOSE_WINPR_ASSERT=OFF` for releases. *(Done: the `freerdp` job, plus an `openh264` job for the Linux codec.)* |
| Man-in-the-middle | TOFU keyed by host:port with a **pinned SHA-256 fingerprint** in a jterm store under `AppPaths`. A changed fingerprint shows a blocking warning, mirroring `JtermKnownHostsVerifier`. A name mismatch is a first-use prompt, not a reason to switch verification off. *(Done: `RdpKnownHosts`, `rdp_known_hosts.json`; standard RDP security, which would bypass it, is refused.)* |
| Credential exposure | Passwords come from `CredentialVault`, keyed by session id. `CredentialResolver` gets a small interface shared by SSH and RDP configs. Passwords travel only over the pipe; char arrays are zeroed after the write. Never logged (the prototype prints the full command line). *(Done. A typed password is saved only after the server accepts it.)* |
| Downgrade | Default security is NLA. TLS-only or RDP security must be chosen explicitly per session and labelled insecure. *(Done, 2026-11-09: NLA is required by default; TLS without NLA only when the session's "Allow TLS without NLA" (Security tab) is on, with the risk explained there; standard RDP security is refused outright, with no option, so no downgrade can bypass the certificate pin.)* |
| Server reaches local resources | Drive, clipboard, audio, printer and smart-card redirection are **off by default** and per session. Drive redirection names the exact folder shared (never "all drives") and warns that the server can read and write it. *(Clipboard done, per session and off by default; the other redirections are not built, so nothing else is shared.)* |
| Planted executables or DLLs | Helper resolved by absolute path from the install directory only. On Windows the helper's DLLs sit beside it, which the loader searches first. *(Done. On Linux the downloaded OpenH264 is loaded by full path, never via `LD_LIBRARY_PATH`.)* |
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
  types degrade instead of failing. *(Done: `ignoreUnknown` and schema v2. The `defaultImpl`
  placeholder is done too (2026-11-11): from this build on, a node with an unknown `type` loads
  as `session.UnknownSessionNode`, is kept verbatim and written back unchanged, and can only be
  moved or deleted. It does not help builds older than it.)*

## Platform matrix

| Target | FreeRDP | Helper build | Notes |
| --- | --- | --- | --- |
| Windows x64 MSI | Bundled: FreeRDP 3.33, OpenSSL, zlib, MSVC runtime, about 12 MB | `windows-latest`: vcpkg + CMake, as in `~/git/freerdp/build.cmd` | Proven locally. Cache the build in CI. |
| macOS arm64 DMG | Bundled dylibs, built on the runner | CMake on `macos-latest` | **Built**, for both arm64 and x86_64 DMGs. OpenSSL and cJSON from pinned sources (Homebrew's dylibs are tied to the build machine's macOS), `install_name_tool` to `@rpath` = `@loader_path`, then ad-hoc `codesign`. The DMG is unsigned; if it becomes notarized, the helper needs a real signature (and the hardened runtime) too. |
| Rocky 9 RPM | Bundled, built in the `rockylinux:9` job | Same job | Links the system OpenSSL 3; RPATH `$ORIGIN`. |
| Rocky 10 RPM | Bundled, built in the `rockylinux:10` job | Same job | As above. |
| Ubuntu 26.04 DEB | Bundled, built in the `ubuntu:26.04` job | Same job | As above. |
| Flatpak | FreeRDP as a manifest module, pinned to the same version | Manifest module | No new permissions for video and input (`--share=network` already present). Sound added `--socket=pulseaudio` (audio spec). |
| Snap core24 | Bundled as a snap part (CMake), pinned to the same version | Snap part | Classic confinement. |

All targets build FreeRDP from the same pinned tag with the same CMake options as
`~/git/freerdp/build.cmd`. Cache the FreeRDP build per target, keyed on that tag, so a normal
release doesn't rebuild it.
| Fat jar | Not included | — | RDP disabled with a hint, unless Preferences points at a helper. *(No Preferences override was built: the pane reports the helper missing.)* |

Linux runs Swing through XWayland, so lightweight painting behaves the same on X11 and Wayland
sessions. The helper builds against the FreeRDP 3 C API, which keeps its ABI stable within the
major version (`libfreerdp3.so.3`). Building it in each target's own container avoids any mismatch.

**Graceful degradation:** if the helper is missing or `HELLO` fails, RDP actions are disabled with
the reason in a tooltip. RDP sessions still load, can be edited, and can be exported. *(Built
differently: the pane opens and says why it cannot connect; sessions load, edit and export as
planned.)*

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

| Area | Change | Status (2026-11-09) |
| --- | --- | --- |
| `session/SessionNode`, `RdpSessionConfig` | `permits`, subtype, `ignoreUnknown`. | Done. |
| `session/SessionStore`, `SessionExportService`, `SessionMigrations` | Collect/export/duplicate RDP nodes; schema v2. | Done. |
| `security/CredentialResolver` | Accept a shared config interface instead of `SshSessionConfig`. | Done (RDP password resolution and remember-after-connect). |
| `ui/sidebar/SessionSidebar`, `SessionNodeRenderer` | New/edit/duplicate/drag; open modes; glyph. | Done. |
| `dnd/` + `PaneGrid.installDnd` | `RdpTransferable`, or generalise `SessionTransferable`. | Done: `RdpTransferable` + `RdpDropHandler`; drops on panes, empty cells and "+". |
| `app/MainWindow`, `ui/tabs/TabPane` | `openRdpSession(cfg, mode)` with new-tab default; Open Folder includes RDP. | Done. |
| `keymap/TermAction` | Host key, Send Ctrl+Alt+Del. Needs a `KeymapMigrations` step only if a default changes. | Send Ctrl+Alt+Del done (Ctrl+Shift+Delete, no migration needed). Host key dropped: superseded by the Ctrl+Shift policy. |
| `release.yml`, `packaging/*`, `security.yml` | Helper builds and bundling; FreeRDP CVE/staleness check. | Done for MSI, RPMs, DEB, Flatpak, snap; `freerdp` and `openh264` jobs. Not for the DMG. |
| `docs/` | `rdp-sessions.md`, an ADR ("RDP via an out-of-process FreeRDP helper"), and the architecture model. | Done: `rdp-sessions.md`, ADR 0012, Components (RDP) and the RDP connect view. |

## Phased plan

1. **Spike, Windows, about 3 days.** *(Done.)* Build the helper and protocol against the local 3.33 build,
   plus a throwaway Swing frame. Exit criteria:
   - a usable desktop with a correct cursor
   - typing works on a non-US layout
   - pipe frame throughput measured with full-screen video
   - killing the helper leaves the JVM unaffected
2. **MVP:** port the model, editor and sidebar from the prototype; `RdpPane`; vault; TOFU;
   reconnect; graceful degradation; Windows packaging. *(Done.)*
3. **Quality:** dynamic resize, HiDPI scaling, host key, text clipboard, Ctrl+Alt+Del. *(Done; the
   host key was superseded by the Ctrl+Shift policy, and the clipboard also carries images.)*
4. **Reach:** macOS, Linux and Flatpak/snap packaging; Security-workflow check; user docs; ADR.
   *(Done; macOS verified on Intel only.)*
5. **jterm-specific wins:** RDP through an SSH jump host. A `TunnelManager` local forward plus
   `localhost:<port>` reuses `JumpHostConfig`, the agent and the vault. Also RD Gateway. *(SSH done,
   as a reference to a saved SSH session rather than a separate jump-host list, so a session's
   chain is defined once; see ADR 0013. RD Gateway done, untested against a real gateway.)*

## Decisions (2026-11-07)

1. RDP opens as a single-cell grid in a new tab (`GridContent`), not the prototype's `RdpTab`.
2. In an RDP pane, only Ctrl+Shift+… jterm shortcuts are intercepted.
3. FreeRDP is bundled on every target.
4. Clipboard sharing is per session and off by default; see [Clipboard](#clipboard-revision-3).

## Clipboard (revision 3)

**Scope:** text (`CF_UNICODETEXT`), images (`CF_DIB`, or `CF_DIBV5` from a server without it) and
HTML (`CF_HTML`, added in protocol v6), through FreeRDP's `cliprdr` channel. Files, RTF and every
other format are ignored. Protocol version 2 (v6 for HTML).

**HTML (2026-11-09).** "HTML Format" is a registered format, so its id is per session: the helper
learns the server's from the format list by name, and announces its own as `0xD010`. Remote → local
fetches text and HTML as one copy (two fetches, applied together; a part that fails or times out is
left out), and the local clipboard gets the plain text plus the HTML document
(`allHtmlFlavor`) and the fragment (`selectionHtmlFlavor`/`fragmentHtmlFlavor`). Local → remote
offers HTML whenever the clipboard has a `text/html` String flavor and builds the CF_HTML header on
paste, marking the fragment from existing `StartFragment` comments, else the body, else the whole
input. The server's CF_HTML is hostile input: offsets outside the payload or reversed are ignored,
the header is read only to the first `<` and at most 64 KiB, and UTF-8 errors are replaced.

**CF_DIBV5.** The helper fetches `CF_DIBV5` only when the server offers no `CF_DIB` (Windows offers
both, synthesising one from the other). The JDK's BMP reader takes the 124-byte header, masks and
alpha included; the codec tests cover bottom-up `BI_BITFIELDS` with alpha (Windows' shape), top-down
`BI_RGB` and a header whose profile points past the data. Local → remote still offers only
`CF_DIB`, which Windows turns into `CF_DIBV5` itself.

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

**Connecting does not overwrite the local clipboard.** When the channel opens, the server announces
the clipboard it already holds, in a format list indistinguishable from a fresh copy; fetched, it
replaced whatever the user had copied locally before connecting. The protocol has no flag for
"new", so the bridge uses the user's input instead: a remote copy takes a key press or a mouse
click, and the start-up announcement precedes both. Until the pane reports the first key or button
sent to the connected session (`RdpClipboardBridge.onUserInput`; mouse moves do not count), a
server announcement is not fetched and the local clipboard is announced again, so it wins on both
sides, as with mstsc. Not a timer: a server announcing late, after the user is already working in
the session, falls back to the earlier behaviour rather than losing a real copy. A reconnect is a
new bridge, so it starts unarmed again.

**Limits.** 32 MiB per transfer either way, refused rather than truncated. Before any pixel buffer
is allocated, the DIB header is checked against the data present, the pixel count against 16 Mi
(what a 16-bit DIB inside the transfer limit holds), and compressed DIBs (RLE, embedded JPEG/PNG)
are refused. The pixel cap is not redundant with the size check: a 1-bit DIB decodes to 32 times its
size, and a 31 MiB one (all zeros, so nearly free under RDP compression) threw `OutOfMemoryError`
with a 2 GiB heap before the cap existed. The server's payload is untrusted: that is the helper-process
boundary doing its job, but the Java decoder must be defensive too.

**Echo.** What was written for the server is remembered by object identity, and the clipboard event
it causes is not announced back.

**A new copy is not always an event.** `SunClipboard.checkChange` returns early when the set of
formats is unchanged (read from the JDK's bytecode), so copying new text over old text sends no
`FlavorEvent`. A Windows server caches what it has rendered until it receives a new format list. So
the pane re-announces whenever the remote desktop gains focus (`RdpClipboardBridge.refresh`), which
is when someone who copied locally is about to paste.

**But a format list takes the remote clipboard.** Receiving one, the server replaces its own
clipboard with the client's. Re-announcing unconditionally therefore broke copying inside the
session: files copied in the remote Explorer (never fetched) were replaced by the local clipboard as
soon as the user switched away and back. So once the server has announced its clipboard, the bridge
re-announces only when the local content differs from a SHA-256 digest taken at that moment (text,
else image pixels), compared locally and never sent. A local clear while the server owns the
clipboard is not announced either, and the digest is moved to the server's content when it is
written locally, so a clipboard manager re-owning that text does not bounce it back as plain text.

**Not done:** file copy (`CB_STREAM_FILECLIP_ENABLED`, needs FUSE or a virtual-file layer),
clip-data locking.

**Verified:** unit tests for the protocol, the codec and the bridge (against a private
`Clipboard`), and the helper's v2 handshake and `CONNECT` parsing against a closed port.

**Live (2026-11-08)**, against Windows 11 Pro 23H2 (a Proxmox VM) from jterm on Rocky Linux 10
(Wayland, via XWayland), with sharing on: text both ways, text copied over text (the focus
re-announce), and images both ways all worked. Not exercised live: sharing off, `Remote can paste
mine` off, GNOME Remote Desktop as the server, and a transfer near the 32 MiB cap.

**Later changes (2026-11-08/09), unit-tested only:** the image pixel cap, the ownership-aware
re-announce, and leaving the local clipboard alone at connect (described above). None of these has
been exercised against a live server yet.

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
  binary, runtime-loaded) on Linux. *(Windows and Linux done; on Linux the binary is downloaded on
  opt-in, verified against a pinned hash, and loaded by full path. The Flatpak uses the runtime's
  openh264 extension. VideoToolbox is in the macOS helper and was seen decoding live on 2026-11-12:
  the x64 DMG on the Intel VM showed `31 fps · H.264 4:4:4` from the Windows 11 VM in "Show
  performance". Not yet verified live on Linux with
  Cisco's real library.)*

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

## Video performance (2026-11-11)

**Report:** a YouTube clip on the Windows 11 VM (H.264 preferred by group policy) played smoothly
in mstsc and stuttered in jterm, both on a Windows 11 laptop.

**Ruled out from FreeRDP 3.33's source:** network auto-detect, the connection type, progressive
off and frame acknowledgement are already library defaults. UDP transport exists
(`WITH_RDPEUDP`), experimental and not built here; on a LAN it is not the difference.

**Measured** (a stand-in writer and reader over a process pipe, 2560×1440 frames):

| | one write per row (the helper's old pattern) | one write per frame |
| --- | --- | --- |
| Linux | 200 frames/s | 173 frames/s |
| Windows 11 VM | **1.0 frames/s (15 MB/s)** | 118 frames/s |

The pipe Java creates for a child on Windows holds 4 KiB. The spike's 7–35 MB/s "demand" during
video was this ceiling. Software painting of the same frame: 21–31 ms scaled bilinear (what
`paintComponent` did on any display above 100%), 1 ms copied 1:1.

**Built:**
1. Statistics: the helper's once-a-second `stats` log line plus the pane's copy and paint times,
   shown by "Show performance" (`RdpStats`).
2. One write per frame in `send_frame`.
3. Pixel-for-pixel painting when the image matches the pane in device pixels.

**Verified live on 2026-11-12** (the readout, during the sound tests): the MSI on a Windows laptop,
connected to the Windows 11 VM, showed `31–32 fps · H.264 4:4:4 · 72–111 MB/s · pipe 1.7–2.0 ms
(max 3–11) · copy 0.5 ms · paint 0.8 ms (max 1)`. So the real helper on Windows keeps up after the
change, the VM does send H.264 (4:4:4), and painting costs under a millisecond on a real machine.
The macOS VM (no GPU) painted the same stream in 12.5 ms (max 16) at 31 fps. GNOME Remote Desktop on
orion still sent RemoteFX progressive (35 fps, 41–47 MB/s, paint under 1 ms). The pipe shows no
backlog (about 2 ms a frame), so 4 and 5 below wait until someone sees stutter.

**Next, by the numbers:**
4. Latest-wins sending: a writer thread in the helper sends the accumulated dirty region when the
   pipe is free, so a slow reader drops intermediate frames instead of stalling decoding and frame
   acknowledgements.
5. Only if still needed: a shared-memory framebuffer; FreeRDP's UDP transport.
6. Audio redirection is a separate feature, since built: `2026-11-11-rdp-audio-design.md`.
