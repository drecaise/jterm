# Pageant ssh-agent support (Windows) — design

## Problem

On Windows, jterm reaches the local ssh-agent through a single endpoint resolved by
`AgentSupport.resolveEndpoint()`:

1. `$SSH_AUTH_SOCK` if set, else
2. the Windows OpenSSH named pipe `\\.\pipe\openssh-ssh-agent` if it can be opened, else
3. `null`.

KeeAgent (KeePass2) works because it *emulates* that OpenSSH named pipe, so jterm's existing
`WindowsPipeAgentProxy` talks to it transparently.

**Pageant (PuTTY) does not use that pipe.** Classic Pageant exposes keys over a Win32
`WM_COPYDATA` message to a hidden window of class/title `Pageant`, with the request/reply body
passed through a named shared-memory file mapping. jterm has no transport for that protocol, so
it cannot see Pageant's keys.

On the affected machine the Windows OpenSSH `ssh-agent` service is *also* running but empty.
jterm therefore connects to that pipe successfully and reports **zero identities**, while the
user's key sits in Pageant — the observed symptom ("connects but no keys").

## Goals

- jterm can use SSH keys held in Pageant on Windows.
- jterm keeps working with the OpenSSH agent / KeeAgent.
- When both an (empty) OpenSSH agent and Pageant are present, jterm uses whatever key is loaded,
  in whichever agent — no manual configuration, no fragile global "prefer X" ordering.
- No new third-party dependencies (JNA + `jna-platform` are already on the classpath via
  java-keyring).

## Non-goals

- Adding identities to / removing identities from agents (jterm is a read/sign-only client).
- A user-facing setting to choose the agent source (rejected as overkill — aggregation removes
  the need to choose).
- Pageant's newer named-pipe / Unix-socket transports (PuTTY ≥ 0.76). The `WM_COPYDATA`
  protocol is the universal one supported by every Pageant version and by Pageant emulators.
- Non-Windows behavior is unchanged.

## Approach (chosen: "A — Pageant transport + aggregate all agents")

Two pieces:

1. A new `PageantAgentProxy` that implements the Pageant `WM_COPYDATA` transport, reusing
   MINA's `AbstractAgentProxy` for the protocol layer — exactly the pattern already used by
   `JdkAgentProxy` (Unix socket) and `WindowsPipeAgentProxy` (OpenSSH pipe).
2. A `CompositeSshAgent` that fronts every available agent source on Windows, merging their key
   lists and routing each `sign()` to the agent that owns the key.

### Component 1: `PageantAgentProxy extends AbstractAgentProxy`

Package `com.katmoda.jterm.terminal.ssh.agent`. Same shape as the sibling proxies: it only
implements `request(Buffer)`; `AbstractAgentProxy` does list/sign/etc.

Transport per `request(Buffer)` round-trip (the buffer is already framed as
`[uint32 length][payload]`, identical to the other proxies):

1. `FindWindow("Pageant", "Pageant")` (JNA `User32`). If `null`, the constructor / first request
   fails with an `IOException` ("Pageant is not running") — caller treats it as "no agent".
2. Create a uniquely named page-file-backed file mapping
   (`CreateFileMapping(INVALID_HANDLE_VALUE, sa, PAGE_READWRITE, 0, AGENT_MAX, mapName)`),
   `mapName = "PageantRequest%08x"` using the current thread id. `AGENT_MAX = 8192`
   (PuTTY's `AGENT_MAX_MSGLEN`); requests/replies larger than this are rejected with an
   `IOException` (in practice list+sign for normal keys fit comfortably).
3. **Security descriptor (the sharp edge):** the mapping must be created with a
   `SECURITY_ATTRIBUTES` whose security descriptor sets the **owner to the current user's SID**.
   Pageant checks that the mapping's owner SID equals its own process user's SID and silently
   refuses otherwise. Built via `Advapi32`:
   `OpenProcessToken` → `GetTokenInformation(TokenUser)` → user SID →
   `InitializeSecurityDescriptor` + `SetSecurityDescriptorOwner(sd, userSid)`. This mirrors
   jsch-agent-proxy's BSD-licensed `PageantConnector`, which is the reference implementation.
4. `MapViewOfFile`, copy `buffer.array()[rpos .. rpos+available]` to offset 0.
5. Build a `COPYDATASTRUCT { dwData = AGENT_COPYDATA_ID (0x804e50ba), cbData = mapName bytes + 1,
   lpData = pointer to NUL-terminated ASCII mapName }` and
   `SendMessage(hwnd, WM_COPYDATA, 0, &cds)`. A zero return means Pageant rejected the request
   → `IOException`.
6. Read the reply from offset 0: `uint32 length` then `length` bytes; wrap in `ByteArrayBuffer`
   (matches `WindowsPipeAgentProxy.request`).
7. `finally`: `UnmapViewOfFile`, `CloseHandle(mapping)`, free the SID/token handles.

A single shared mapping is created lazily and reused across requests, guarded by a lock (the
proxy uses MINA's single-thread executor, so requests are already serialized); it is released on
`close()`. `isOpen()` tracks an `open` flag like `WindowsPipeAgentProxy`.

JNA usage: prefer the interfaces in `jna-platform` (`com.sun.jna.platform.win32.User32`,
`Kernel32`, `Advapi32`, `WinNT`, `WinUser.COPYDATASTRUCT`). Custom mapping declarations only
where `jna-platform` lacks a binding.

### Component 2: `CompositeSshAgent implements SshAgent`

Package `com.katmoda.jterm.terminal.ssh.agent`. Aggregates an ordered list of delegate
`SshAgent`s.

- `getIdentities()` — concatenates each delegate's identities, **de-duplicating by encoded
  public key** (first occurrence wins). While building the list it records a
  `Map<PublicKey, SshAgent>` so signing can be routed. Delegates that throw during enumeration
  are skipped (logged), so one dead agent doesn't blind the others.
- `sign(session, key, algo, data)` — looks up the owning delegate from the routing map (falling
  back to trying each delegate) and forwards.
- `addIdentity` / `removeIdentity` / `removeAllIdentities` — throw
  `UnsupportedOperationException` (jterm never calls these; consistent with read/sign-only use).
- `isOpen()` — true if any delegate is open. `close()` — closes all delegates, suppressing and
  collecting individual failures.

### Wiring: `AgentSupport`

Replace the single-endpoint model on Windows with a source list, leaving Unix untouched.

- Keep `resolveEndpoint()` for Unix (socket path) and for the `$SSH_AUTH_SOCK` override.
- New `AgentSupport.open()` behavior:
  - **Unix:** unchanged — `new JdkAgentProxy(resolveEndpoint())`.
  - **Windows:** build delegates for every available source and wrap them:
    - OpenSSH pipe: `new WindowsPipeAgentProxy(WINDOWS_PIPE)` if `canOpen(WINDOWS_PIPE)`
      (or if `$SSH_AUTH_SOCK` points at it).
    - Pageant: `new PageantAgentProxy()` if `FindWindow("Pageant","Pageant") != null`
      (a cheap `pageantAvailable()` probe).
    - If exactly one source: return it directly. If two or more: wrap in `CompositeSshAgent`.
    - If none: throw `IOException` (as today).
- New `AgentSupport.isAgentAvailable()` returning whether *any* source exists (Unix endpoint
  resolvable, or on Windows the OpenSSH pipe openable or Pageant window present). Used by
  `SshConnect.installAgent` to decide whether to install the factory.
- `JdkAgentFactory.createClient` calls `AgentSupport.open(...)` and so transparently returns the
  composite on Windows. On Unix it still honors the MINA `SSH_AUTH_SOCK` client property.
- The "Agent Keys" dialog uses `listIdentities()`, which calls `open()` — it will now list the
  merged set, including Pageant keys.

### Wiring: `SshConnect.installAgent`

Currently installs the factory only when `resolveEndpoint() != null`. Change the guard to
`AgentSupport.isAgentAvailable()` so the factory is installed when Pageant is present even if no
socket/pipe endpoint string exists. The MINA `SSH_AUTH_SOCK` property is still set when an
endpoint string is available (needed on Unix); on Windows the factory builds the composite
itself, so the property is informational only.

## Data flow

```
SshClient auth
  └─ JdkAgentFactory.createClient
       └─ AgentSupport.open()
            ├─ Unix:    JdkAgentProxy(socket)
            └─ Windows: CompositeSshAgent[
                          WindowsPipeAgentProxy(openssh-ssh-agent)?,   // empty here
                          PageantAgentProxy()                          // holds the key
                        ]
       getIdentities() → merged list (Pageant's key included)
       sign(key)       → routed to PageantAgentProxy
```

## Error handling

- Pageant not running → `PageantAgentProxy` construction/probe fails; it is simply omitted from
  the source list (no error to the user unless *no* agent at all is available).
- `WM_COPYDATA` rejected / SID mismatch / oversized message → `IOException` from `request()`,
  surfaced as an auth-source failure; other agents in the composite still apply, and
  key/password auth still follow.
- One delegate throwing during `getIdentities()`/`close()` must not break the others (caught,
  logged).

## Testing / verification

No automated suite exists (per CLAUDE.md); verification is build + targeted manual checks. This
code is **Windows-only at runtime**, and the dev box is Linux, so:

- **Linux dev box:** `mvn -q compile` must pass (JNA classes resolve at compile time). Confirm
  `AgentSupport.open()` on Linux is byte-for-byte equivalent to today (still `JdkAgentProxy`),
  e.g. with a small throwaway `java` snippet listing identities against the running agent.
- **`CompositeSshAgent`** is OS-independent — exercise it on Linux with a throwaway driver that
  wraps two `JdkAgentProxy` instances (or one real + one stub) and verify merge/dedupe/sign
  routing.
- **Windows (user-run):** on the Pageant box, confirm the "Agent Keys" dialog now lists the
  Pageant key and that an SSH session authenticates via Pageant while the OpenSSH agent service
  is running but empty. Also re-confirm the KeeAgent box still works (regression check).

## Files

- **New:** `terminal/ssh/agent/PageantAgentProxy.java`
- **New:** `terminal/ssh/agent/CompositeSshAgent.java`
- **Modify:** `terminal/ssh/agent/AgentSupport.java` (source-list `open()`, `isAgentAvailable()`,
  `pageantAvailable()` probe)
- **Modify:** `terminal/ssh/SshConnect.java` (`installAgent` guard)

## References

- jsch-agent-proxy `PageantConnector` / `PageantLibrary` (BSD) — reference for the `WM_COPYDATA`
  + shared-memory + owner-SID `SECURITY_ATTRIBUTES` sequence.
- PuTTY `pageant.h`: `AGENT_COPYDATA_ID = 0x804e50ba`, `AGENT_MAX_MSGLEN = 8192`.
