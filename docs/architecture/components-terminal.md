# Components — Terminal

**C4 Level 3, terminal grouping.** Everything under `com.katmoda.jterm.terminal.*` plus
the `broadcast` package (which wraps every session's connector).

```mermaid
--8<-- "architecture/model/generated/components-terminal.mmd"
```

## The connector abstraction

`TerminalSession` is the interface a `TerminalPane` drives. It exposes the title, the
per-session profile, and a `TtyConnector` — that's it. Two implementations:

- `terminal.local.LocalSession` — pty4j `PtyProcess`, wrapped by `PtyTtyConnector`
  which extends JediTerm's `ProcessTtyConnector`.
- `terminal.ssh.SshSession` — MINA SSHD `ChannelShell`, wrapped by
  `SshTtyConnector` reading and writing the channel's inverted streams.

The pane never sees the raw connector — `PaneGrid` wraps it in
`broadcast.BroadcastingTtyConnector` first, so writes can be fanned out to sibling
panes when broadcast mode is active. Reads pass through unchanged.

## Creating sessions

Two collaborators keep the EDT free of blocking I/O:

- **`SessionFactory`** — synchronous entry points for local and WSL sessions
  (pty4j is fast), and an async entry point for SSH sessions that delegates to…
- **`ConnectionService`** — resolves credentials on the EDT (see the
  [SSH-connect runtime view](runtime-views.md#ssh-connect)) then hands the actual
  connect off to a `SwingWorker`. When the worker's `done()` fires it returns the
  ready `SshSession` back to the caller on the EDT.

Credential resolution happens **before** the worker starts so that the master-password
prompt and vault-decryption stay on the EDT. The blocking off-EDT step never touches
Swing.

## SSH auth stack

`SshSession` doesn't drive auth itself — MINA does. The two things jterm registers with
the client are the host-key verifier and the agent identities:

- `JtermKnownHostsVerifier` implements TOFU against `~/.ssh/known_hosts` (see
  [ADR 0004](adr/0004-tofu-host-key-verification.md)).
- `AgentSupport` picks the per-OS agent source: on Linux/macOS a JDK Unix-domain-socket
  client (`JdkAgentProxy`, using MINA's `AbstractAgentProxy` protocol layer above a
  `SocketChannel` over `UnixDomainSocketAddress`); on Windows the OpenSSH
  named-pipe agent (`WindowsPipeAgentProxy`) and/or PuTTY Pageant
  (`PageantAgentProxy`), fronted by `CompositeSshAgent` when both are live.
- If MINA's built-in `UnixAgentFactory` were used it would need Apache APR
  (tomcat-native), which jterm doesn't bundle. The custom `JdkAgentFactory` avoids
  that dependency entirely. See [ADR 0002](adr/0002-custom-jdk-agent-factory.md).
- `SshSession.installAgent` also sets `SSH_AUTH_SOCK` as a **client property** (not an
  env var). MINA's `UnixAgentFactory` reads it from client properties, and desktop
  launches often lack the env var, so a login-shell fallback populates it if needed.

Password auth is only tried if the agent has no matching identity — MINA's
`ClientSession.addPasswordIdentity` is called from `SshSession.connect` after the
credential resolver returns something non-empty.

## WSL

`terminal.wsl.WslDistributions` enumerates installed WSL distros and drives
`wsl.exe -d <distro>` as a local shell. From the connector's perspective it's identical
to `LocalSession`.

## See also

- Source: `src/main/java/com/katmoda/jterm/{terminal,broadcast}/`.
- [SSH sessions](../ssh-sessions.md) and
  [SSH auth & vault](../ssh-auth-and-vault.md) — user-facing view.
- [Runtime views](runtime-views.md) for the SSH connect and broadcast flows.
