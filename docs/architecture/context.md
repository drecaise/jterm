# System context

**C4 Level 1.** jterm is a single-user desktop application. Nothing here is a service, a
daemon, or a shared piece of infrastructure — every interaction happens on one machine,
between one user and their tools.

```mermaid
--8<-- "architecture/model/generated/context.mmd"
```

## Actors and neighbours

- **User** — sits in front of the app, opens tabs, splits panes, and drives sessions.
  Everything jterm does is a response to a user action or a callback from one of the
  systems below.
- **Remote SSH daemon** — the target of every SSH session. jterm speaks the SSH-2
  protocol via Apache MINA SSHD; port forwarding and SFTP ride on the same connection.
- **SSH agent** — the transport differs per OS. On Linux and macOS it's a
  Unix-domain socket (`$SSH_AUTH_SOCK`); on Windows it's the OpenSSH named pipe and/or
  PuTTY Pageant. jterm never touches the private key material — it hands off challenges
  to the agent and receives signatures back.
- **OS keyring** — stores the vault's **master password**, not the SSH passwords
  themselves. The choice of backend is per-OS: `secret-tool` on Linux, `security` on
  macOS, `java-keyring`'s JNA backend on Windows. See
  [ADR 0005](adr/0005-per-os-keyring-clis.md) for the reasoning.
- **Local shell** — spawned by pty4j as a child process for local terminal sessions
  (`bash -l`, `powershell`, or `wsl.exe -d <distro>` on Windows).
- **Local file system** — the OS config directory (see
  [Configuration files](../config-files.md)) holds every JSON persisted by the app.
  jterm never writes outside this directory.

## Trust boundaries

Two boundaries matter for reasoning about security:

- Between the **User** and jterm — everything from the user is trusted, but jterm still
  prompts on the EDT for the master password and for TOFU host-key acceptance before
  proceeding.
- Between jterm and the **network** (remote sshd) — untrusted by default. Host keys are
  pinned on first use in `~/.ssh/known_hosts` and a warning is shown on change; see
  [ADR 0004](adr/0004-tofu-host-key-verification.md).

The SSH agent, OS keyring, and local file system live on the same trust side as jterm
itself (they are all on the local machine, under the same user account).

## What is intentionally out of scope

- **Multi-user coordination.** No shared config, no server-side state, no telemetry.
- **A companion mobile app or web UI.** jterm is Java Swing on the desktop only.
- **Password recovery.** The vault master password is not stored anywhere jterm can
  recover it from — losing it means losing the saved SSH passwords.
