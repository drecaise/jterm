# ADR 0012: RDP through an out-of-process FreeRDP helper

**Status:** Accepted
**Date:** 2026-11-07

## Context

jterm gains an `rdp` session type. No usable pure-Java RDP client exists (none supports
NLA/CredSSP and modern TLS), so the protocol comes from FreeRDP. There were three ways to use it:

- **Embed an external FreeRDP client's window** (the 2026-06 prototype). The foreign window takes
  keyboard focus, so jterm's global shortcut dispatcher never sees a key. On Windows the window is
  re-parented with `SetParent` + `AttachThreadInput`, which ties jterm's UI thread to FreeRDP's. It
  can't be done at all on macOS.
- **Load libfreerdp into the JVM** (via JNA). A crash in a codec would close every SSH session.
  FreeRDP regularly fixes memory-safety bugs a malicious *server* can trigger, and in-process such a
  bug runs next to the unlocked credential vault.
- **Run FreeRDP in a helper process that jterm talks to.**

## Decision

FreeRDP runs in `native/rdp-helper`, a small C client built for each OS, and jterm speaks a
versioned binary protocol with it over stdin/stdout (`protocol.h`, mirrored by
`terminal.rdp.RdpProtocol`). The helper decodes and sends dirty rectangles and cursor shapes; jterm
paints them in `ui.rdp.RdpPane`, a lightweight Swing `GridContent`, and sends keyboard, mouse and
resize input back.

Trust and secrets stay on jterm's side:

- **Certificates** are pinned by SHA-256 in jterm's own store and passed to the helper, which never
  writes FreeRDP's store.
- **Passwords** come from the vault and travel only over the pipe.
- **The helper** is resolved only from the install directory or an explicit setting, never `PATH`.

## Consequences

- A FreeRDP crash or exploit is contained in a process that knows one RDP password. jterm shows
  "disconnected" and offers Reconnect.
- One Java implementation on every OS, including macOS and Wayland (via XWayland). The global
  shortcut dispatcher keeps working, with an RDP-specific rule: only Ctrl+Shift strokes are claimed
  while an RDP pane has focus.
- Frames are copied over a pipe. Measured locally at about 1 GB/s, far more than video needs, so
  shared memory isn't needed.
- jterm ships and maintains native code: FreeRDP is bundled on every target, so a FreeRDP security
  release means a jterm release, and the helper must be built per OS in CI.
- Design detail and measurements:
  [RDP session design](https://github.com/drecaise/jterm/blob/master/docs/superpowers/specs/2026-11-07-rdp-session-design.md).
