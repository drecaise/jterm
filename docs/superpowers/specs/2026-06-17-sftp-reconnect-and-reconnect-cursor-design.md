# Design: SFTP auto-reconnect + reconnected-terminal cursor fix

Date: 2026-06-17

Two related reliability fixes for dropped connections.

## 1. Bug — invisible cursor after an SSH session reconnects

### Symptom
When an SSH terminal session disconnects and is reconnected (R / restart on the
stopped screen), the new session has no visible cursor.

### Root cause
`ui.pane.JtermJediTermWidget.restartWith` reuses the existing JediTerm
`TerminalPanel` (to preserve scrollback). That panel keeps the cursor-visibility
state from the dead session. If the previous session — or a TUI it was running
(vim, htop, etc.) — had hidden the cursor via DECTCEM (`?25l`) and the connection
dropped before the matching `?25h` was sent, the reused panel stays in the
"cursor hidden" state. A fresh shell prompt never re-enables the cursor (it is
on by default and so sends nothing), so it remains invisible.

### Fix
In `restartWith`, after leaving any alternate-screen buffer and before starting
the new connector, force the cursor visible:

```java
getTerminal().useAlternateBuffer(false);
getTerminal().setCursorVisible(true);   // new
setTtyConnector(connector);
start();
```

`getTerminal()` returns the reused `JediTerminal`; `setCursorVisible(true)`
resets the panel's `myShouldDrawCursor`. Any cursor-control sequence the new
session emits still takes effect normally afterward.

Status: already implemented in this session.

## 2. Feature — SFTP auto-reconnect on a dropped connection

### Goal
When an SFTP pane's connection has dropped, the next operation should
transparently rebuild the connection and retry, rather than just failing with an
error dialog.

### Decisions (confirmed with user)
- **Detection: lazy / on operation failure.** No proactive idle polling. A drop
  is noticed only when an operation (navigate, refresh, transfer, mkdir, …)
  fails because the link is dead.
- **Policy: reconnect once, then retry the operation.** Show a brief
  "Reconnecting…" status. Only surface an error dialog if the reconnect itself
  fails (or it is a genuine non-connection error). No multi-retry/backoff, no
  manual Reconnect button.

### Approach (chosen)
`SftpLauncher` hands the `SftpPane` a `reconnector` closure that rebuilds the
connection. When an off-EDT operation fails *and the link is found dead*, the
pane rebuilds once and re-runs the failed operation. This keeps the pane's
`cwd`, scroll position, selection, and drag identity, and reuses the existing
`runAsync` plumbing.

Rejected alternatives:
- **Self-healing `SftpClient` proxy** — hides reconnection from the UI (no
  status) and cannot swap the fresh path's `onClose` connection-teardown.
- **Relaunch a whole new `SftpPane`** — loses `cwd`/selection and pane identity.

### Components

#### `SftpLauncher`
- Promote the private `Built` record to a small carrier
  `SftpConnection(SftpClient client, Runnable onClose)`.
- Both entry points build a `Callable<SftpConnection> reconnector` and pass it to
  the pane constructor:
  - `openFresh`: reconnector re-runs `SshConnect.open(...) + createSftpClient`,
    returning the new client and a fresh `conn::close`. Full reconnect — this
    path owns the credentials/closure.
  - `openOnLiveSession`: reconnector calls `createSftpClient(session)` on the
    terminal's existing `ClientSession`, returning the new client and a no-op
    close. Best-effort: succeeds when the SSH session is still alive (e.g. only
    the SFTP channel dropped); throws when the whole session has died (the
    terminal owns that lifecycle), which surfaces as a normal error.

#### `SftpPane`
- `client` and `onClose` become mutable fields.
- Add `private final Callable<SftpConnection> reconnector`.
- Make the bottom connection-bar `JLabel` a field so its text can flip to
  "Reconnecting…" and back.
- `connectionLost()` helper decides reconnect eligibility from **client state,
  not exception-string parsing**:
  `!client.isOpen() || client.getClientSession() == null
   || !client.getClientSession().isOpen()`.
- `runAsync` gains a single-retry path. In `done()`, on exception:
  1. If `closed`, return (existing behaviour).
  2. If `connectionLost()` is **false**, show the error dialog as today (genuine
     errors — permission denied, etc. — surface immediately).
  3. If `connectionLost()` is **true** and this attempt has not already retried
     and a reconnect is not already in flight:
     a. flip the bar label to "Reconnecting…",
     b. run `reconnector` off the EDT,
     c. on success: swap `client` + `onClose`, restore the bar, re-run the same
        operation once (the retry flag is set so a second failure surfaces the
        error),
     d. on reconnect failure: restore the bar, show the original error dialog.
- A `reconnecting` guard (boolean on the EDT) coalesces concurrent failures so
  two operations do not both spawn reconnects. If an operation fails while a
  reconnect is already in flight, it surfaces its error rather than starting a
  second reconnect.

Because the operations read `this.client` at execution time, re-running the same
`Callable` after swapping the field automatically uses the new client.

### Out of scope
- Proactive idle polling / heartbeat detection.
- Multi-retry with backoff.
- A manual "Disconnected — click to reconnect" button.

### Verification
No automated test suite exists (per CLAUDE.md). Verify by:
- `mvn -q compile` succeeds.
- Manual: open SFTP (both fresh and on-a-live-session paths), kill the
  connection server-side / drop the network, then trigger an operation and
  confirm it shows "Reconnecting…" and recovers; confirm a genuine error (e.g.
  delete a protected file) still shows immediately without a reconnect attempt.
- For the cursor fix: run a cursor-hiding TUI over SSH, drop the connection,
  reconnect, confirm the cursor is visible.
