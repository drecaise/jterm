# ADR 0001: Uniform R x C pane grid instead of a binary split tree

**Status:** Accepted (amended 2026-07-18: per-axis weights)
**Date:** 2026-01-04

## Context

Every tabbed terminal that supports splits picks one of two data models:

1. A **binary split tree** — each internal node has a direction and two children. This
   is what tmux does and what most Electron-based terminals inherit.
2. A **uniform grid** — a fixed 2-D array of cells with `rows` and `cols`, and each
   cell either holds a pane or is empty.

The binary tree is more expressive (arbitrary nesting, uneven splits) but also carries
a lot of edge-case code: rebalancing after close, computing pixel sizes recursively,
serialising the tree, and driving keyboard focus movement across a tree.

For jterm the design brief was **fast to build, easy to reason about, no weird
layouts** — a user opening a session with a drag should never end up with a pane
squeezed into a 40 px sliver.

## Decision

`ui.grid.PaneGrid` holds a `TerminalPane[3][3]` plus live `rows` and `cols` values from
1 to 3. Splits grow one dimension; closing empties a cell and **collapses any fully
empty trailing row or column** so the grid stays rectangular. Layout uses
`GridBagLayout` with equal weights on every cell — no user-adjustable sash. The whole
grid is re-laid-out on every structural change.

## Consequences

- Layouts are always neat: every visible pane is the same size (within `GridBagLayout`
  rounding). No 40 px slivers.
- The maximum is 3 rows × 3 cols = 9 panes per tab. Users who want more open a new
  tab. This has not been a practical limit for the app's target audience.
- Focus movement is trivial: it's an `(r, c)` update with a bounds check.
- The serialised state per tab is tiny — two ints plus a cell-to-session mapping — so
  session restore is straightforward.
- Uneven splits (e.g. a 30/70 vertical divide) are not possible. This is an explicit
  non-goal; if it ever becomes one, the grid can be extended with per-column weights
  without a full rewrite.

## Amendment (2026-07-18): per-axis weights

The extension anticipated above landed, exactly as predicted: `WeightedGridLayout` now
sizes rows and columns by per-axis weights that the user adjusts by dragging the gutters
between cells (double-click resets an axis to equal shares; a minimum cell size keeps
slivers impossible). The core decision is unchanged — the layout is still a uniform R×C
grid, not a split tree: weights apply to whole rows and columns, never to individual
cells, and splitting/collapsing still works purely on the two `rows`/`cols` values. The
"no user-adjustable sash" consequence no longer holds; "no weird layouts" still does.
