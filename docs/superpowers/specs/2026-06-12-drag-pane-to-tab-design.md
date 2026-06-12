# Drag panes and tabs between splits and tabs

**Date:** 2026-06-12
**Status:** Approved (pending spec review)

## Goal

Make panes and (single-pane) tabs freely rearrangeable by dragging their title bars / headers.

**Pane → elsewhere** (grab a pane by its always-visible title bar):

1. **Onto the "+" button** → pull the pane out of its split into a brand-new tab.
2. **Onto another pane in the same tab** → swap the two panes' positions.
3. **Onto an empty cell in the same tab** → move the pane into that cell.

**Tab → into a grid** (grab a tab by its header; single-pane tabs only):

4. **Onto a pane in another tab** → bring the tab's terminal in as a **split** (top 60% → new
   column, bottom 40% → new row, exactly like a session drop). The now-empty source tab closes.
5. **Onto an empty cell in another tab** → move the terminal into that cell; source tab closes.

Out of scope: dropping a *multi-pane* tab into a cell; dropping a pane onto a *different existing*
tab's header.

## Key principle: re-parent, don't recreate

The live `TerminalPane` — its JediTerm widget, `TerminalSession`, and connection — is moved between
grids, never recreated. `Container.add()` reparents the component; the terminal keeps its scrollback,
working directory, and live connection. The grid's own `relayout()` already reparents panes via
`removeAll()` + re-add, so moving a pane across grids is the same proven operation.

A single-pane **tab is just a grid with one pane**, so "drag a tab into a grid" reduces to "drag that
pane into another grid." Both gestures therefore share one transferable and one set of drop targets.

### Couplings to handle when a pane changes grids

- **Array ownership:** the source `PaneGrid` references the pane in `TerminalPane[][]` and its restart
  factory in the parallel `SessionFactory[][]`. The pane must be removed from the source arrays
  *without* `close()`-ing the session, and the factory must travel with it.
- **Per-grid callbacks:** the pane's `onFocus`, `onSessionEnd`, `onBroadcastToggle`, and its drop
  target all call *source*-grid logic; they must be rebound to the destination grid.
- **Broadcast bus:** the pane's input flows through a `BroadcastingTtyConnector` whose `bus` is the
  source grid. Left unchanged, a moved pane would still fan keystrokes into its old tab's broadcast.
  The bus is repointed to the destination grid.

## Components

### New: `dnd/PaneTransferable`

Carries the dragged `TerminalPane` as a local-JVM object `DataFlavor`
(`DataFlavor.javaJVMLocalObjectMimeType`, `representationClass = TerminalPane`), mirroring
`SessionTransferable`. Exposes `PANE_FLAVOR`. Used for **both** the pane-title drag and the tab drag
(the tab drag carries the source grid's sole pane).

### Changed: `broadcast/BroadcastingTtyConnector`

`bus` becomes non-`final`; add `setBus(BroadcastBus)`.

### Changed: `ui/grid/PaneGrid`

Refactor pane creation/placement so an *existing* pane can be adopted:

- `createPane(session)` — construct a `TerminalPane` only (no registration).
- `registerPane(pane)` — bind `onFocus`/`onSessionEnd`/`onBroadcastToggle`, `installDnd(pane)`, and
  repoint the bus to `this` (`if (pane.inputConnector() instanceof BroadcastingTtyConnector b)
  b.setBus(this)`). Then `pane.setBroadcastMode(broadcastActive)`.
- `placeExistingPaneAt(r, c, pane, factory)` — store in arrays, set active, `registerPane`.
- `placeAt(r, c, session, factory)` → `placeExistingPaneAt(r, c, createPane(session), factory)`
  (preserves all current callers: splits, restart, initial open).

New move/placement operations:

- `boolean contains(TerminalPane pane)` / `int paneCount()`.
- `swapPanes(a, b)` — swap positions in `panes[][]` + `factories[][]`, `relayout()`, active follows
  the dropped pane.
- `movePaneToEmptyCell(pane, r, c)` — same-grid move into an empty cell; clear old cell,
  `collapseTrailingEmpty()`, `relayout()`, focus.
- `SessionFactory detachForMove(pane)` — null out the pane's slots **without** `close()`,
  `collapseTrailingEmpty()`, `relayout()`, `moveActiveToExistingPane()`, return the factory.
- `adopt(pane, factory)` — `placeExistingPaneAt(0,0,…)` into a fresh single-cell grid, `relayout()`,
  focus. (Used by pane-out-to-new-tab.)
- `adoptAsSplit(targetPane, region, pane, factory)` — grow a dimension relative to `targetPane`
  (mirrors `splitFromPaneAndOpen`) and `placeExistingPaneAt` the incoming pane at the new cell.
  (Used by tab → occupied pane.)

Drop targets (pane cells **and** empty-cell placeholders) learn `PANE_FLAVOR`:

- `dragOver`: accept and show a move hint (full line-border highlight, distinct from the split hint).
- `drop`: extract dragged pane `P`.
  - If `contains(P)` (same grid): pane target → `swapPanes(P, target)` (no-op if `P == target`);
    empty cell → `movePaneToEmptyCell(P, r, c)`.
  - Else (cross-grid, i.e. a tab drag): `factory = moveCoordinator.detachFromOwner(P)`; if non-null,
    pane target → `adoptAsSplit(target, region, P, factory)`; empty cell →
    `placeExistingPaneAt(r, c, P, factory)` + `relayout()` + focus.

### New: `dnd/PaneMoveCoordinator` (provided by `MainWindow`)

```java
@FunctionalInterface interface PaneMoveCoordinator {
    /** Detach the pane from whichever tab's grid owns it (without closing it), closing that tab if
     *  it becomes empty. Returns the pane's restart factory, or null if not found. */
    SessionFactory detachFromOwner(TerminalPane pane);
}
```

Each grid gets one via `setMoveCoordinator(...)` (alongside the existing `setDropHandler`).

### Changed: `app/MainWindow`

- **Tab dragging converts from the `MouseAdapter` reorder to Swing DnD.** A `DragGestureRecognizer`
  on the tab strip starts a drag carrying `PaneTransferable(grid.solePane())` **only when the
  pressed tab is single-pane**; multi-pane tabs are not draggable into grids (they still reorder).
  Reorder is handled by a drop target on the tab strip that computes the target index and calls the
  existing `moveTab(from, to)`. The visible reorder behavior is unchanged.
- **Drag does not change the active tab.** On a tab press the currently selected index is recorded;
  if a drag begins, the selection is kept on the previously active tab so its grid stays visible as
  the drop target. A plain click (press + release without a drag) selects normally. This is what
  lets a tab be dropped into *another* tab's grid.
- **"+" drop target** accepts `PANE_FLAVOR` → `movePaneToNewTab(pane)`: find the owning grid; if it
  has `paneCount() <= 1` → **no-op** (already its own tab); else `detachForMove` + `newGrid()` +
  `insertGrid` + `grid.adopt(pane, factory)` + select + `decorateTab`.
- **`detachFromOwner` implementation:** scan real tabs for the grid containing the pane,
  `grid.detachForMove(pane)`, and if `grid.paneCount() == 0` close that tab; return the factory.
- Provide `PaneMoveCoordinator` to each grid in `newGrid()`.
- `PaneGrid` gains `TerminalPane solePane()` (the single pane, or null) for the tab drag source.

## Data flow

```
pane title-bar drag ─PaneTransferable(P)─►  "+" button   → MainWindow.movePaneToNewTab
                                            same-grid pane→ swapPanes
                                            same-grid cell→ movePaneToEmptyCell

tab header drag ─PaneTransferable(solePane)► other grid pane→ detachFromOwner + adoptAsSplit
(single-pane only)                          other grid cell→ detachFromOwner + placeExistingPaneAt
                                            tab strip       → moveTab (reorder)
```

## Edge cases

- **Sole pane → "+":** no-op.
- **Pane dropped on itself:** no-op.
- **Multi-pane tab drag:** not a grid drop source; reorder only.
- **Tab dropped on its own grid:** `contains(P)` is true → treated as a same-grid rearrange (drop on
  another pane = swap; usually a no-op since you'd drop on the same pane).
- **Cross-grid detach emptying the source tab:** `detachFromOwner` closes the source tab.
- **Broadcast active in a source tab:** the moved pane's bus is repointed to the destination grid, so
  it leaves the old tab's broadcast and joins the new one.
- **Grid geometry after detach:** `collapseTrailingEmpty()` keeps the source grid rectangular.

## Testing / verification

No automated suite (Swing GUI). Verify by `mvn -q compile` clean, then manually:

- Split a tab into 2–3 panes. Drag a pane's title bar to "+" → opens in a new tab, correct
  title/icon, live shell with preserved scrollback; source grid collapses. Sole-pane → "+" no-op.
- Drag a pane onto another pane → swap. Drag a pane onto an empty cell → move.
- With two tabs (one single-pane, one with an empty cell): drag the single-pane tab's header into
  the other tab's empty cell → terminal moves in, source tab closes. Drag it onto an occupied pane →
  splits (column/row by drop position).
- Confirm tab reorder still works (drag a tab across its neighbours in the strip).
- Confirm a click still switches tabs, and that dragging a tab does **not** switch to it.
- Confirm a pulled-out pane no longer receives broadcast from its old tab.

## Risks

- **Re-parenting a live JediTerm widget** across grids — mitigated: same operation as in-grid
  `relayout()`.
- **Tab selection-during-drag suppression** is the subtlest part; it changes tab-click timing
  slightly (select on click, not on press-then-drag). Behavior is gated by detecting an actual drag.
- **Object-carrying Transferable** works only for intra-JVM DnD (all drags are in-process), like
  `SessionTransferable`.
- **Reorder mechanism swap** (MouseAdapter → DnD) touches working code; mitigated by keeping
  `moveTab` and its semantics, only changing how the drag is recognized and where it's dropped.
