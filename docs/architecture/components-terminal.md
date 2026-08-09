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

*Saved* credentials are resolved **before** the worker starts, so the master-password prompt and
vault decryption stay on the EDT and the common path never blocks. The one deliberate exception is
the interactive auth fallback below, which can only be answered mid-handshake; it marshals its own
prompt to the EDT rather than touching Swing off-thread.

## SSH auth stack

`SshSession` doesn't drive auth itself — `SshConnect` owns connect-and-authenticate for every hop
(the shell, SFTP and tunnels all build on it), and MINA runs the actual method exchange. The three
things jterm registers with the client are the host-key verifier, the agent identities, and the
interactive fallback:

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
- `SshConnect.installAgent` also sets `SSH_AUTH_SOCK` as a **client property** (not an
  env var). MINA's `UnixAgentFactory` reads it from client properties, and desktop
  launches often lack the env var, so a login-shell fallback populates it if needed.
- `JtermUserInteraction` implements MINA's `UserInteraction` to supply a password (or
  keyboard-interactive answers) once publickey auth is exhausted. See below.

Password auth is only tried if the agent has no matching identity — MINA's
`ClientSession.addPasswordIdentity` is called from `SshConnect.connectHop` after the
credential resolver returns something non-empty.

### Interactive fallback

When agent and key auth are both rejected, `SshConnect` prompts rather than failing. This rides
MINA's own extension point instead of a reconnect-and-retry loop of our own:
`UserAuthPassword.resolveAttemptedPassword` consults `UserInteraction` once the registered password
identities are used up, and `UserAuthKeyboardInteractive` routes PAM/2FA challenges to the same
object. Two properties fall out for free — MINA only runs a method the server *offers*, so a
key-only server never produces a prompt; and a stale saved password is tried first, then
re-prompted with an error. `CredentialResolver.interactiveAuth(cfg)` implements the
`SshConnect.InteractiveAuth` SPI with Swing prompts.

Three constraints shape the implementation, and each is commented at its site:

| Constraint | Why | Where |
|---|---|---|
| MINA's `PASSWORD_PROMPTS` cap (3) is enforced **per auth method** | A server offering both keyboard-interactive and password would ask six times | `JtermUserInteraction` imposes its own 3-per-hop total |
| The 30 s `AUTH_TIMEOUT` would fire while the user is still typing | The timeout is there to catch a wedged server, not a slow human | `SshConnect.awaitAuth` waits in slices, abandoning only on server silence |
| The prompt blocks a MINA NIO worker thread | A jump host's port-forward needs another worker to keep the tunnel flowing | `NIO_WORKERS` floor set on the client |

A hop that can't authenticate throws `SshAuthException` (`Authentication failed for user@host`)
rather than surfacing MINA's raw `No more authentication methods available`.
`AppSettings.promptPasswordOnAuthFailure` gates the whole thing; it's read at the call sites
(`ConnectionService.interactiveAuth`) so `CredentialResolver` stays free of settings and remains
headless-testable.

## WSL

`terminal.wsl.WslDistributions` enumerates installed WSL distros and drives
`wsl.exe -d <distro>` as a local shell. From the connector's perspective it's identical
to `LocalSession`.

## See also

- Source: `src/main/java/com/katmoda/jterm/{terminal,broadcast}/`.
- [SSH sessions](../ssh-sessions.md) and
  [SSH auth & vault](../ssh-auth-and-vault.md) — user-facing view.
- [Runtime views](runtime-views.md) for the SSH connect and broadcast flows.
