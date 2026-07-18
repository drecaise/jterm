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
  widget sees it.
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
  by `SessionStore`. Each node is a drag source; the "Local terminal" button is
  a separate drag source using `LocalTransferable`.
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
