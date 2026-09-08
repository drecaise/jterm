# ADR 0010: Unapplied shortcuts fall through to the terminal

**Status:** Accepted
**Date:** 2026-09-08

## Context

`MainWindow.installShortcutDispatcher()` matches every key press against the `Keymap` and consumes
the event so JediTerm and the menu accelerators can't also act on it (see
[Components — UI](../components-ui.md)). Consuming was unconditional, including for an action that
then did nothing.

That is unrecoverable for the program in the pane. JediTerm encodes control keys in `keyPressed`
only, and its `keyTyped` ignores ISO control characters, so a swallowed ++ctrl++ + letter never
arrives as `^X`. **Open SFTP browser** is bound to ++ctrl+f++ and requires a live SSH pane; on a
local shell it was a silent no-op that still ate the key. ++ctrl+f++ is page-down in `vim` and
`less`, and their page-up (++ctrl+b++) is unbound — so the user-visible symptom was a pager that
would only scroll one way, which reads as a terminal-emulator defect rather than a shortcut
conflict.

The obvious alternative was to move the default binding to ++ctrl+shift+f++. Rejected: `keymap.json`
is written with the defaults on first run, so every existing install would need a schema-versioned
one-shot migration (as `sessions.json` and `macros.json` have), and the conflict class would remain
for anyone who binds a terminal control key deliberately.

## Decision

`handle(TermAction)` returns whether the action applied to the current focus, and the dispatcher
returns that value instead of an unconditional `true`. Only `OPEN_SFTP` on a non-SSH pane reports
`false` today, via `openSftpForActivePane()`.

The other silent no-ops (no focused grid, sidebar closed) keep reporting `true`: there is no
terminal under focus for the key to be useful to, so passing it on would gain nothing.

## Consequences

- ++ctrl+f++ reaches `vim`, `less` and anything else running in a local, WSL or `cmd` pane. On an
  SSH pane the shortcut still wins, because there the browser can actually open.
- Defaults and `keymap.json` are untouched — no migration, and no relearning for existing users.
- The rule generalises: a binding that can't act must let the key through. ++ctrl+t++ (New Tab) and
  ++ctrl+w++ (Close Tab) are the same class of conflict with `vim`'s window prefix and readline's
  delete-word, but they always apply, so they legitimately keep the key.
- Any new action whose handler can decline must return `false` rather than falling off the end of
  the switch, or it reintroduces the swallow.
