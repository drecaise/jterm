# ADR 0009: Track the terminal selection across scrolls by extending JediTerm

**Status:** Accepted
**Date:** 2026-09-08

## Context

Selecting text in a pane and letting the screen scroll lost the selection. JediTerm 3.70's
`TerminalPanel.scrollArea` calls `updateSelection(null)` unconditionally, so a single line of
output — a command printing, ++enter++ at the bottom of the screen, a pager moving its scroll
region — dropped the highlight.

Terminal rows are numbered relative to the top of the screen (`0` is the first screen row, `-1` the
newest scrollback line), so a scroll **renumbers** them. Simply leaving the selection alone is
worse than clearing it: the highlight would stay on fixed row numbers while different text slid
underneath, and the next copy would take content the user never chose. JediTerm already solves this
for resize (`TerminalTextBufferResize` → `selection.shiftY(-screenLinesToMove)`) but not for
scrolling.

Two further discoveries shaped the design, both found by capturing what applications actually emit
under a `pty.fork()` harness rather than by reasoning about them:

- **`less` is asymmetric.** Scrolling forward writes the newly exposed line at the *bottom* of the
  screen; scrolling back emits a reverse index and writes it at the *top*. A "did anything change
  at or above my last row?" heuristic reads the second as a hit, so scrolling up lost the selection
  while scrolling down kept it.
- **`CSI L` / `CSI M` never reach the display.** `JediTerminal.insertLines`/`deleteLines` call the
  text buffer directly, with no `myDisplay.scrollArea(...)` beside them. This is how vim scrolls
  backwards: ++ctrl+e++ sets a scroll region and sends a newline at its bottom (a real scroll),
  ++ctrl+y++ sends `CSI L` at the top.

## Decision

Extend JediTerm at three points, all in `ui.pane`:

- **`JtermTerminalPanel.scrollArea`** captures the selection, calls `super` (which owns the private
  `scrollDy` counter that keeps the scrollbar in step — it cannot be skipped), then puts back a copy
  shifted by the rows the content moved.
- **`JtermJediTerminal`** (installed via `JediTermWidget.createTerminal`) overrides `insertLines`
  and `deleteLines` to report those moves, which JediTerm otherwise applies silently. It must *not*
  touch `scrollDy` — JediTerm never counted that movement — and its rows are discarded rather than
  appended to the scrollback, even at row 1.
- **`SelectionScroll`** holds the geometry as a pure, Swing-free function, so it is unit-tested. It
  shifts only when **both** ends of the selection move by the same amount and clears otherwise; a
  selection straddling a partial `CSI r` region, or pushed out of the buffer, is dropped rather
  than guessed at.

Deciding *when* a selection has genuinely been disturbed is done by **comparing the selected text**
against a snapshot taken when the selection was made, not by reasoning about row numbers — that is
the only thing that answers the question, and it is what makes both directions of a pager behave
alike. A cheap row filter in front of it keeps the common case (output streaming in below the
selection) free.

The restore writes JediTerm's private `mySelection` field directly rather than going through
`updateSelection`, so no selection listener fires.

## Consequences

- A selection survives output scrolling the screen, paging either direction in `less`/`man`, and
  vim's line scrolling — in the alternate screen too, since entering it allocates a fresh full-size
  history storage.
- **Clipboard safety is preserved.** `TerminalPane.installCopyOnSelect` copies on every selection
  event; had the restore notified, every scrolled line would re-push the selection to the clipboard
  and overwrite whatever the user had copied elsewhere. A renumbering is not a selection change.
- An application that **repaints** rows instead of moving them (`htop`, `tmux`, an editor
  redrawing) still clears the selection, which is correct — there is no longer any content to
  track.
- Three cached reflective handles (`mySelection`, `updateSelection`, `myScrollRegionBottom`) pin
  this to JediTerm 3.70. All degrade to a `LOG.debug` and the old clear-on-scroll behaviour rather
  than failing, so a version bump is a regression in polish, not a breakage. The region bottom has
  no public accessor and guessing the screen height instead would shift selections on rows that
  never moved.
- Reading the selection and its snapshot must hold the text-buffer lock. `JediTerminal` holds that
  lock across a whole scroll, so an unsynchronised check can land mid-scroll and discard a good
  selection — intermittently, under load only.

## Links

- [Components — UI](../components-ui.md)
- [Selecting text](../../tabs-and-panes.md#selecting-text) in the user manual
