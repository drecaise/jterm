# ADR 0011: Open SFTP moves to Ctrl+Shift+F, with a `keymap.json` migration

**Status:** Accepted
**Date:** 2026-10-05

## Context

[ADR 0010](0010-shortcut-fall-through-on-no-op.md) kept **Open SFTP browser** on ++ctrl+f++ and
made the shortcut fall through wherever it could not act. It considered moving the default to
++ctrl+shift+f++ and rejected that, because `keymap.json` is written with every default on first
run and so each existing install would need a one-shot migration.

The fall-through did not cover the remaining conflict. ++ctrl+f++ is also JediTerm's **Find**
action, and on an SSH pane — the one place Open SFTP *does* apply — the dispatcher consumes the
key first. The terminal's search was therefore unreachable on exactly the panes where scrollback
tends to matter most. That is a missing feature rather than an inconvenience, which changes the
trade-off 0010 made.

## Decision

The default for `OPEN_SFTP` is ++ctrl+shift+f++, and `keymap.KeymapMigrations` carries existing
installs over.

- The file stays a flat `action id → stroke` map. The version is a `schemaVersion` entry *inside*
  it, not a wrapper object, so an older build reading a newer file still works: it looks entries
  up by action id and ignores the rest.
- v0 → v1 moves Open SFTP only when it is still on the old default. Strokes are compared as parsed
  `KeyStroke`s — the first-run file spells it `control F`, a save from the shortcut editor
  `ctrl pressed F`.
- It does not move when another action already holds ++ctrl+shift+f++. Two actions on one stroke
  leave one unreachable, and that binding was the user's own.
- The stamp is written even when nothing moved, so the migration runs once. A user who rebinds
  Open SFTP back to ++ctrl+f++ afterwards keeps it.
- A file that could not be read is not migrated or rewritten.

## Consequences

- ++ctrl+f++ opens the search bar in every pane.
- Changing any other default in `TermAction` now has a place to go: bump `CURRENT_VERSION` and add
  a step. Without one, the change reaches fresh installs only.
- The fall-through rule of ADR 0010 is unchanged and still applies to Open SFTP on its new key.
- Users with a customised Open SFTP binding, or with ++ctrl+shift+f++ already in use, see no
  change and may still have search hidden on SSH panes; the manual's troubleshooting page says so.
