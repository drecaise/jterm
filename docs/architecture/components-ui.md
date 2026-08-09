# Components — UI

**C4 Level 3, UI grouping.** Everything under `com.katmoda.jterm.ui.*`, plus the `app`
package that hosts the top-level window and the shortcut dispatcher.

```mermaid
--8<-- "architecture/model/generated/components-ui.mmd"
```

## Walkthrough

**Windowing (`app`, `ui.windowing`).**

- `MainWindow` — the top-level `JFrame`. Holds the menu bar, the `TabPane`, and — most
  importantly — installs a single `KeyEventDispatcher` on the global
  `KeyboardFocusManager`. JediTerm consumes key events, so menu accelerators alone
  would never fire while a terminal has focus; the dispatcher matches every
  `KeyStroke` against the current `Keymap` and **consumes** the event before the
  widget sees it. It also claims *bare* ++r++ / ++s++ / ++enter++ for a stopped pane's
  restart/reconnect strip — that branch skips any `JTextComponent` focus owner, because the
  dispatcher sees every window and would otherwise eat those letters out of the Quick Connect
  field or a dialog's inputs whenever some pane happened to be dead.
- `WindowTopology` — registry of open windows; the shortcut dispatcher uses it to
  target the focused window and to move panes between windows.

**Tabs and grid (`ui.tabs`, `ui.grid`).**

- `TabPane` wraps `JTabbedPane` and holds one `PaneGrid` per tab.
- `PaneGrid` is the model of the split layout. It is **not** a binary split tree — it's
  a uniform `TerminalPane[3][3]` with live `rows` and `cols` values from 1 to 3.
  Splitting grows a dimension; closing empties a cell and collapses any fully-empty
  trailing row or column. Rows and columns are sized by **per-axis weights**:
  `WeightedGridLayout` (package-private, next to `PaneGrid`) divides the area purely by
  those weights — children's preferred sizes are deliberately ignored — and leaves a
  gutter between cells that doubles as a draggable divider. Dragging resizes the two
  adjacent rows/columns (clamped to a minimum cell size), double-click resets an axis
  to equal shares, a new split takes an equal share while the survivors keep their
  ratio, and collapsing an axis re-equalises it. The mouse listeners live on the grid
  panel itself: children cover their cells completely, so only gutter pixels ever
  deliver events there. See [ADR 0001](adr/0001-uniform-grid-vs-split-tree.md).

**Panes (`ui.pane`).**

- `TerminalPane` owns a `JediTermWidget`. Critically, the connector handed to the
  widget is not the session's raw `TtyConnector` — `PaneGrid` wraps it in
  `BroadcastingTtyConnector` (in the `broadcast` package) so keystrokes can fan out
  when broadcast mode is on. See the
  [broadcast runtime view](runtime-views.md#broadcast-fan-out) for the sequence.
- The pane also holds the drop target for drag-and-drop launches and reads
  `ThemeColors` from `ThemeManager` on creation.

**Sidebar and DnD (`ui.sidebar`, `dnd`).**

- `SessionSidebar` renders a tree of `FolderNode` and `SshSessionConfig` values loaded
  by `SessionStore`. Each node is a drag source; the "Local Terminal" button is
  a separate drag source using `LocalTransferable`.
- Under the tree sit the two **new-session** entries, both of which open into a *new tab*
  rather than over the focused pane. The `OpenMode` a callback receives is what distinguishes
  them: the Local Terminal button sends `NEW_TAB`, which `MainWindow.openLocalPreferringNewTab`
  resolves to "new tab, unless the focused cell is empty" (`PaneGrid.activeContent() == null`
  — a hole left by a closed pane, or a tab whose async connect never landed); the context menu's
  explicit *Open in Active Pane* still sends `ACTIVE`, the only path that replaces a live session.
- **Quick Connect** parses `[user@]host[:port]` with `session.SshTarget` — a headless record,
  hence the only unit-tested part of the sidebar — and hands the resulting **ephemeral**
  `SshSessionConfig` to the same `onOpenSsh(..., NEW_TAB)` callback a saved session uses, so tab
  title/icon, the async connect, the error dialog and the pane-restart factory all come for free.
  Two properties make the throwaway config behave: it is never added to `SessionStore`, and
  `SessionStore.ancestorsOf` returns an *empty* chain for a node outside the tree, so every blank
  field (user, key path, keep-alive, terminal profile) resolves straight to the global defaults
  with no special-casing. The `ephemeral` flag it carries is a security marker consumed by
  `CredentialResolver` — see
  [components-security](components-security.md#ephemeral-sessions-never-remember).
- On drop, `SessionDropHandler` classifies the cursor position via `DropRegion` (top
  60% opens a new column, bottom 40% opens a new row), calls `PaneGrid.split()`, and
  hands off session creation to `SessionFactory` (see the
  [drop-to-split runtime view](runtime-views.md#drop-to-split)).

**SFTP (`ui.sftp`).**

- `SftpPane` opens the SFTP subsystem on an existing `SshSession` rather than
  opening a second SSH connection. It is a Swing file browser backed by MINA's SFTP
  client — mostly independent of the terminal side of the app.

**Theming (`ui.theme`).**

- `ThemeManager` is a singleton. It installs a FlatLaf LaF and exposes a
  `ThemeColors` record (terminal fg/bg plus 16 ANSI colours).
- `JTermSettingsProvider` extends JediTerm's `DefaultSettingsProvider` and hands the
  theme colours to the terminal engine. Live recolour of existing widgets on toggle is
  **not** implemented — JediTerm bakes default fg/bg into each widget at creation;
  chrome recolours via FlatLaf `updateUI`.

**Cross-cutting stores.**

- `IconLibrary` (bundled SVGs + user imports), `HighlightLibrary` (keyword
  highlight lists), `Keymap` (action → keystroke bindings) and `MacroLibrary` are all
  loaded once at startup and passed by reference where needed. Each has its own JSON
  file under the config dir.

## See also

- Source: `src/main/java/com/katmoda/jterm/{app,ui,dnd}/`.
- [Tabs and panes](../tabs-and-panes.md) — user-facing view of the same subsystem.
- [Runtime views](runtime-views.md) for the drop-to-split and broadcast flows.
