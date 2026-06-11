# Session-stopped screen — design

## Goal

When a terminal session ends (SSH disconnect or a local shell `exit`), keep the pane and its
final output visible, draw a horizontal separator, and show:

```
Session stopped
    - Press <Return> to exit tab
    - Press R to restart session
    - Press S to save terminal output to file
```

…and implement those three actions. Modeled on MobaXterm's session-stopped screen.

## Decisions (from brainstorming)

- Applies to **both** SSH and local sessions.
- **Return** removes just this pane (today's collapse behavior), leaving siblings/tab intact;
  closes the tab only if it was the last pane.
- **Save** captures the **full scrollback history + visible screen** as plain text.

## 1. Lifecycle change (detection)

[`PaneGrid.handleSessionEnd`](../../../src/main/java/com/katmoda/jterm/ui/grid/PaneGrid.java)
(fired from the JediTerm widget listener via `TerminalPane.onSessionEnd`) currently closes the
pane and collapses the grid. New behavior: it instead asks the pane to show the *stopped
overlay* and leaves the cell and pane in place. The backend session is already dead; the
widget keeps showing its final scrollback.

The previous teardown logic (close pane, empty cell, `collapseTrailingEmpty`, relayout,
refocus) is extracted into a `removePane(TerminalPane)` method, reused as the **Return/exit**
action.

## 2. Stopped overlay — `ui.pane.TerminalPane`

New method `showSessionStopped(Runnable onExit, Runnable onRestart, Runnable onSave)`:

- Keeps the dead terminal widget in `CENTER` (prior output stays visible) and adds a panel in
  `SOUTH`: a top `JSeparator` (the horizontal line) followed by the text lines.
- Colors approximate the screenshot: "Session stopped" red; `<Return>` cyan; `R` and `S`
  magenta; surrounding text default foreground. Rendered with small HTML-styled `JLabel`s or
  colored labels laid out vertically. Uses the terminal's monospaced font for consistency.
- The panel is focusable. On show it calls `requestFocusInWindow()` and binds keys
  (`WHEN_FOCUSED` input map): `VK_ENTER` → `onExit`, `VK_R` → `onRestart`, `VK_S` → `onSave`.
  `R`/`S`/`Return` are not keymap shortcuts, so `MainWindow`'s global dispatcher ignores them.
- A guard prevents showing the overlay twice. `close()` still tears down the widget + session.

The overlay is removed implicitly: Return removes the whole pane; Restart replaces the whole
pane with a fresh one.

## 3. Restart plumbing — `terminal.SessionFactory`

New functional interface:

```java
@FunctionalInterface
public interface SessionFactory {
    /** Produce a fresh session, delivering it to onReady on the EDT. May be async (SSH). */
    void create(java.util.function.Consumer<TerminalSession> onReady);
}
```

- `PaneGrid` keeps a `SessionFactory[][] factories` array parallel to `panes[][]`. `placeAt`
  records the factory alongside the pane; `disposeAll`/removal clears it.
- `makePane(TerminalSession, SessionFactory)` stores the factory and wires
  `onRestart = () -> restartAt(r, c)`.
- `restartAt(r, c)` closes the stopped pane and calls `factories[r][c].create(newSession ->
  place a new pane in the same cell with the same factory)`. (For SSH the create is async;
  the cell shows the stopped overlay until the new session arrives.)
- The four public open methods gain a `SessionFactory` parameter:
  `placeSessionInActive`, `splitColumnAndOpen`, `splitRowAndOpen`, `splitFromPaneAndOpen`.
- Internal **local** opens build a local factory:
  `onReady -> { TerminalSession s = safeLocalSession(); if (s != null) onReady.accept(s); }`.
  Used by `openInitialLocal`, `openLocalInActive`, `splitColumn`, `splitRow`, and local drops.

`MainWindow` builds the **SSH** factory at each open/drop site as
`onReady -> connectAsync(cfg, onReady::accept)` (an `SshSession` satisfies the
`Consumer<TerminalSession>`), so restart reuses the existing async connect — including
vault/keyring/password re-auth and the error dialog on failure.

Callers updated:
- `addSshTab(cfg)`: `connectAsync(cfg, s -> grid.placeSessionInActive(s, sshFactory(cfg)))`.
- `openSshSession(cfg, mode)`: pass `sshFactory(cfg)` into the ACTIVE / SPLIT_COLUMN /
  SPLIT_ROW calls.
- `newGrid()` drop handler: `connectAsync(cfg, s -> grid.splitFromPaneAndOpen(target, region,
  s, sshFactory(cfg)))`.

A private `MainWindow.sshFactory(SshSessionConfig cfg)` returns the `SessionFactory`.

## 4. Save output — `ui.pane.TerminalBufferText`

Small helper `static String collect(JtermJediTermWidget widget)` that reads the widget's
`TerminalTextBuffer` and returns history + screen as plain text, using
`processHistoryAndScreenLines(-historyLinesCount, historyLinesCount + screenLinesCount, …)`
with a `StyledTextConsumer` that appends each line's text and a newline (trailing blank lines
trimmed).

`onSave` (in `TerminalPane`) shows a `JFileChooser` (default name like
`<title>-output.txt`), and on approval writes the collected text as UTF-8. Errors surface in a
`JOptionPane` message.

## 5. Exit action

`onExit = () -> removePane(thisPane)` — the extracted teardown from the old `handleSessionEnd`:
close pane, null the cell + factory, `collapseTrailingEmpty`, relayout, move active to an
existing pane, refocus. Removes only this pane; an empty grid remains if it was the last.

## Testing / verification

No automated suite (per CLAUDE.md). Verify by:
1. `mvn -q compile` clean; launch via `mvn -q exec:java` and confirm the `pty4j native`
   startup line with no exceptions.
2. Local: in a local pane type `exit`; confirm the separator + "Session stopped" block appears
   below the prior output and the pane is retained.
3. Press `R`; confirm a fresh local shell replaces the pane in the same cell.
4. Press `S`; confirm a save dialog writes a non-empty text file containing the scrollback.
5. Press `Return`; confirm the pane is removed (grid collapses; siblings unaffected).
6. SSH: connect a saved session, end it (`exit` / drop), confirm the same screen and that `R`
   reconnects (re-auth via vault/keyring as needed).
