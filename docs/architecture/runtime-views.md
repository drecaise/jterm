# Runtime views

Three dynamic diagrams — the flows that span multiple packages and where the EDT / worker
handoff is load-bearing.

## SSH connect

Saved credentials are resolved on the EDT so any prompt lives there. Only the actual connect and
channel open run off-EDT, in a `SwingWorker` — with one deliberate callback into the EDT when the
server demands a password mid-handshake.

```mermaid
--8<-- "architecture/model/generated/dynamic-ssh-connect.mmd"
```

**Points to notice:**

- `CredentialResolver.resolvePassword` runs on the EDT before the worker is
  submitted (step 3). If the vault is locked, `VaultManager.unlock()` shows the
  master-password dialog — modal, synchronous.
- `SshConnect.installAgent` also sets `SSH_AUTH_SOCK` as a client property with a
  login-shell fallback so desktop launches without a shell environment still see the
  agent (see [`components-terminal.md`](components-terminal.md#ssh-auth-stack)).
- Agent I/O (step 10) is blocking, which is why this whole path is off the EDT.
- **Steps 11–13 are the exception to "resolve everything up front."** Whether a password is
  needed at all is only knowable once the server has rejected publickey auth and named the
  methods it still offers, so `JtermUserInteraction` calls back into `CredentialResolver`, which
  marshals the dialog onto the EDT with `invokeAndWait`. On a key-only server these steps never
  happen. See the
  [interactive fallback](components-terminal.md#interactive-fallback) for the timeout and
  thread-pool consequences.
- On `SwingWorker.done()` the `SshSession` returns to the EDT so the pane can attach
  it to its `JediTermWidget` without touching Swing state off-thread.

## Broadcast fan-out

Broadcast mode makes every write from one pane replay on the others without echoing
back to itself. The wrapper — `BroadcastingTtyConnector` — sits between every pane
and its real connector; the `BroadcastBus` mediates the fan-out.

```mermaid
--8<-- "architecture/model/generated/dynamic-broadcast.mmd"
```

**Points to notice:**

- The wrapper's `write()` **always** writes to its own session (step 3). The fan-out
  is additional; disabling broadcast just skips it.
- `BroadcastBus.fanOut(bytes, self)` passes the sender so the bus can skip it and
  avoid an echo loop (note in step 5).
- Everything runs on the caller thread — the wrapper does not spawn threads for
  broadcast. This keeps ordering trivial: sibling panes see the same byte sequence in
  the same order the source pane emitted it.

## Drop-to-split

Dropping a session onto a pane both creates a new pane cell and starts the SSH connect
asynchronously.

```mermaid
--8<-- "architecture/model/generated/dynamic-drop-to-split.mmd"
```

**Points to notice:**

- `DropRegion` maps the cursor Y-position on the pane to a direction: **top 60%**
  opens a new column, **bottom 40%** opens a new row.
- `PaneGrid.split()` returns synchronously with an empty cell — the layout update is
  immediate so the user sees the split before the connection is ready.
- The connect itself follows the [SSH connect](#ssh-connect) view above; the
  connector is attached to the new pane on the EDT when the worker's `done()` fires.

## See also

- [Containers](containers.md) for the process-level threading model.
- [Broadcast input](../broadcast.md) — user-facing view.
- Source: `src/main/java/com/katmoda/jterm/{terminal,broadcast,dnd,ui/grid}/`.
