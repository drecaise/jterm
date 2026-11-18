# Components — RDP

**C4 Level 3, RDP grouping.** `ui.rdp`, `terminal.rdp`, the RDP drop hook in `dnd`, and the one
piece of the design that is not in the JVM: the `jterm-rdp-helper` process. Why FreeRDP runs out of
process at all is [ADR 0012](adr/0012-rdp-via-out-of-process-freerdp.md).

```mermaid
--8<-- "architecture/model/generated/components-rdp.mmd"
```

## The split: trust in the JVM, protocol in the helper

Everything that decides *whether* to trust something stays in jterm: the password (vault or
prompt), the certificate pins, the clipboard policy and the codec choice. The helper gets one
`CONNECT` message carrying the results and does the RDP work: TLS and NLA, the graphics pipeline,
codecs, the clipboard channel. A memory-safety bug a malicious server triggers in FreeRDP lands in a
process that knows one password and nothing else; jterm shows the pane as disconnected and offers
Reconnect.

- **`RdpPane`** — a `GridContent`, like `SftpPane`, opened in a new tab by default. It places itself
  first and connects second, so a failure leaves a pane showing Reconnect / Close rather than an
  error over an empty tab. It paints the dirty rectangles the helper sends and turns Swing input
  into RDP input: printable keys without Ctrl/Alt/Meta as Unicode (layout-independent), the rest as
  Windows virtual keys. Each connect attempt gets a fresh helper and a fresh listener, numbered so
  callbacks from a superseded attempt are ignored.
- **`RdpConnection`** — starts the helper and owns its three pipes. The helper is located only in
  the install directory (or an explicit `-Djterm.rdp.helper` in development), **never on `PATH`**,
  because it is handed the password. Its stderr is piped and drained, never inherited: a
  GUI-subsystem helper on Windows has no usable console, and an undrained pipe would block it.
- **`RdpProtocol`** — the Java mirror of `native/rdp-helper/src/protocol.h`: a versioned,
  length-prefixed binary protocol. Change both together; the version in `HELLO` catches a mismatch.
- **`RdpKnownHosts`** — the RDP counterpart of the SSH known-hosts check. Pins are SHA-256
  certificate fingerprints in `rdp_known_hosts.json`. They go to the helper in `CONNECT` and FreeRDP
  accepts a match before anything else; only a certificate that is neither pinned nor CA-valid comes
  back as a prompt. The helper answers FreeRDP's own prompt with "this session only", so FreeRDP's
  store is never written. There is deliberately no "ignore certificate errors".
- **`RdpClipboardBridge`** — off unless the session enables it, and then the channel exists at all
  only for that session. Delayed rendering both ways: jterm offers *kinds*, and content leaves the
  machine only when the server pastes. It tracks who owns the clipboard (an announcement is a claim
  of ownership in this protocol), does not take the server's existing clipboard on connect, and
  treats every byte from the server as hostile (`RdpClipboardCodec` checks sizes and pixel counts
  before allocating).
- **Shared folders (`terminal.rdp.drive`)** — the helper never uses FreeRDP's own drive device
  (lexical confinement only, several advisories). It installs a small forwarding device instead,
  answers every other device service (printers, smart cards, serial ports) with nothing, and sends
  each file request to jterm as `DRIVE_IRP`. `CONNECT` carries share ids and names, never a path.
  `DriveShare.resolve` is the only way from a server's file name to a local path: one component at
  a time, links and reparse points refused, protected folders (jterm's config, `~/.ssh`) compared
  by file identity. Requests for one file run in order (`DriveDispatcher`).
- **Clipboard files (`terminal.rdp.files`)** — both directions, each its own opt-in. Local to
  remote: `FileSnapshot` walks the copy once (no links, protected folders left out, size limits),
  the user consents per copy when the server first asks for the list (`RdpFileClipboard`), and the
  server can then only name an entry by index. Remote to local: `RemoteFileList` parses the
  server's list as hostile input, `RemoteClipboardFiles` holds the server's lock, and
  `RemoteFileTransfer` writes only after the user picks a folder ("Save files…"): no link
  followed, a hidden `.part` name until complete, the downloaded mark (`OriginMark`), a free-space
  floor. The helper relays the cliprdr messages unparsed and never sees a path.
- **`FullScreenWindow`** — full screen moves only the pane's desktop view into its own window on
  the monitor of the jterm window; the `RdpPane` stays in its grid cell (placeholder card), so the
  connection, clipboard and drives don't move. One mechanism per OS: borderless on Windows, EWMH
  full screen on Linux, a native Space on macOS. `FullScreenBar` (timing in `FullScreenReveal`)
  is jterm's own exit and status, drawn over the remote picture. Every dialog of the pane goes
  through `RdpPane.dialogParent()`, which leaves full screen first, so a remote desktop can't pass
  off a drawn prompt as jterm's while jterm's own prompts are on screen. Design:
  `docs/superpowers/specs/2026-11-10-rdp-full-screen-design.md`.
- **`OpenH264`** — Linux only. The packages ship no H.264 codec, because Cisco's patent licence
  covers only the binary Cisco delivers to the user. On opt-in this downloads Cisco's build, checks
  a pinned SHA-256, stamps the install with that pin, and re-downloads when a jterm update moves the
  pin. The helper is given the library's full path and loads it before FreeRDP looks, never through
  `LD_LIBRARY_PATH` (which would also redirect FreeRDP's own libraries). Windows uses Media
  Foundation. The Flatpak downloads the codec like the other Linux packages (its 25.08 runtime has
  no OpenH264 extension).
- **Through an SSH session** — a session can name a saved SSH session on its Gateway tab. The pane
  then asks the application (`Services.sshForwarder`) to connect that SSH session, with its jump
  hosts, keys and vault password, and to open `terminal.ssh.LocalForward`: `127.0.0.1` on an
  ephemeral port, forwarded to the RDP host as seen from the SSH server. The helper dials the
  forward but checks the certificate against the real host name (`serverName` in `CONNECT`, which
  FreeRDP uses for the name check and TLS SNI). The forward belongs to one connect attempt and
  closes with it; pins stay keyed by the session's own host:port.
- **Through an RD Gateway** — the other route on the Gateway tab. The helper connects to the
  gateway over HTTPS (always; no bypass for local addresses) and the gateway forwards the RDP
  connection. The gateway's certificate is pinned and prompted for exactly like the desktop's,
  under the gateway's own host:port. It takes the desktop's credentials unless the session gives it
  its own, whose password then lives in the vault under a separate key.
- **`RdpDropHandler`** — the `PaneGrid` hook for a sidebar drag. `TabPane` builds the pane through
  `TerminalServices.newRdpPane` (so detached windows accept drops too), places it as a split of the
  pane it was dropped on, or opens a new tab when that direction is full — never replacing a pane.

## Threads

- **EDT** — all of `RdpPane`, every prompt (password, certificate), and painting.
- **`rdp-reader-<pid>`** — one per helper: reads protocol messages and hands frames and events to
  the EDT.
- **`rdp-stderr-<pid>`** and **`rdp-reaper-<pid>`** — drain the helper's log and collect its exit.
- **`rdp-clipboard`** — one per session that shares its clipboard: every clipboard read, digest and
  write happens here, never on the EDT.
- **`rdp-clipboard-files`** — file reads for a paste on the remote desktop, so a large range can't
  hold up text and image traffic.
- **`rdp-drive-<n>`** — the shared-folder request workers (`DriveDispatcher`).
- **`rdp-local-network`** — macOS: the pre-connect probe that waits out the Local Network prompt.

Inside the helper, FreeRDP's channel calls run on its RDP thread; the thread reading jterm's stdin
only queues work and signals an event, which the RDP thread drains.

## See also

- [RDP connect](runtime-views.md#rdp-connect) — the sequence of one connect.
- [RDP sessions](../rdp-sessions.md) — the user-facing view.
- Design notes and measurements:
  `docs/superpowers/specs/2026-11-07-rdp-session-design.md`,
  `docs/superpowers/specs/2026-11-09-rdp-drives-and-file-copy-design.md`,
  `docs/superpowers/specs/2026-11-10-rdp-full-screen-design.md`.
- Source: `src/main/java/com/katmoda/jterm/{ui/rdp,terminal/rdp}/`, `native/rdp-helper/`.
