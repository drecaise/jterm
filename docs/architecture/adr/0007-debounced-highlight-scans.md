# ADR 0007: Debounce highlight scans off the emulator thread

**Status:** Accepted
**Date:** 2026-07-06

## Context

`highlight.HighlightingInstaller` colors terminal output by re-running each rule's
regex over the on-screen lines and applying per-character overrides via
`TerminalLine#addCustomHighlighting`. The scan was driven straight off JediTerm's
`TerminalModelListener`, which fires on the emulator's read thread for **every** buffer
mutation — at least twice per output line — and holds `TerminalTextBuffer#lock()` for
the duration.

With the default rule list this meant the full rule set was matched against every
visible line, synchronously, under the buffer lock, many times per line of output. Two
symptoms followed:

- The regex work throttled pty consumption, so fast output (e.g. `cat` of a large file)
  crawled and Ctrl+C appeared to hang while the backlog drained.
- Because the scan held the buffer lock, painting stalled behind it.

Pure scrolling was also pathological: every line got its highlightings disposed and
re-created on each scan even when its text had not changed.

## Decision

Decouple scanning from the change event:

- The model listener only flips an `AtomicBoolean` and schedules a scan
  `SCAN_DELAY_MS` (50 ms) out on a **shared single-thread daemon scheduler**
  (`jterm-highlight-scanner`), coalescing bursts to at most ~20 scans/s. The flag is
  cleared at the start of the scan so changes arriving mid-scan queue a follow-up.
- Each scan caches per line the text it last saw (`LineState`) and **skips lines whose
  text is unchanged**, relying on a `TerminalLine`'s identity travelling with its
  content. Scrolling therefore re-matches almost nothing.
- Pane teardown cancels any pending scan and marks the scanner closed.

## Consequences

- Fast output and Ctrl+C are responsive again: the emulator thread does near-zero work
  per mutation, and the buffer lock is taken briefly at most 20×/s rather than per event.
- Highlights lag real output by up to ~50 ms. Imperceptible for the intended use
  (calling out patterns in scrolling logs).
- One scanner thread is shared across all panes; scans are short and each takes its own
  buffer's lock, so cross-pane contention is negligible. If it ever becomes a
  bottleneck the executor can be sized up without touching callers.
- The `applied` map now stores `LineState` (text + highlightings) instead of bare
  highlighting lists, and is touched only on the scanner thread, keeping the
  `IdentityHashMap` single-threaded.
