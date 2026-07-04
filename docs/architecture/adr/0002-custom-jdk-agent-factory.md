# ADR 0002: Custom JDK Unix-socket ssh-agent factory instead of MINA's APR-based one

**Status:** Accepted
**Date:** 2026-01-04

## Context

Apache MINA SSHD ships a `UnixAgentFactory` that talks to the OpenSSH agent over
`$SSH_AUTH_SOCK`. It works, but with two problems for a desktop app:

- It's implemented on top of Apache APR / tomcat-native, which jterm doesn't bundle
  and which drags in a per-OS native library.
- It reads `$SSH_AUTH_SOCK` from **client properties**, not from process env. Desktop
  launches (via `.desktop` file, Finder, or MSI shortcut) frequently start jterm with
  an empty env, so the property is never set and identities aren't offered even
  though the agent is running.

Java 16 (JEP 380) added Unix-domain socket support directly to the JDK
(`java.nio.channels.SocketChannel` over `UnixDomainSocketAddress`). We target Java 21,
so we have that unconditionally.

## Decision

Implement `terminal.ssh.agent.JdkAgentFactory` (registered on the MINA `SshClient`) and
`JdkAgentProxy` (extends MINA's `AbstractAgentProxy`, so we reuse the wire-protocol
layer and only replace the transport). `AgentSupport` picks the source per OS:

- **Linux / macOS** — `JdkAgentProxy` over the OpenSSH Unix-domain socket, path taken
  from `$SSH_AUTH_SOCK` if set, otherwise probed via a login-shell fallback.
- **Windows** — `WindowsPipeAgentProxy` (OpenSSH named pipe) and/or
  `PageantAgentProxy`, fronted by `CompositeSshAgent` if both are live.

`SshSession.installAgent` also **writes the resolved socket path back into the MINA
client property** so any code path that consults the property (including MINA's own
internals) sees the same value.

## Consequences

- No APR / tomcat-native dependency. Every OS uses only the JDK and jterm-owned code
  for agent I/O.
- Desktop-launched jterm sees ssh-agent identities as reliably as terminal-launched
  jterm. The login-shell fallback is the reason.
- On Windows we get a real choice between OpenSSH's agent and PuTTY's, and can honour
  users who have both.
- Upstream MINA changes to `UnixAgentFactory` don't affect us. That's a maintenance win
  today; if MINA later ships a JDK-based factory upstream, we can drop the custom
  factory and switch to it.
