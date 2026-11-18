# ADR 0013: RDP over SSH references a saved SSH session, not a jump-host list

**Status:** Accepted
**Date:** 2026-11-09

## Context

An RDP session can reach its desktop through SSH, for a computer only reachable from a server the
user can SSH into. jterm already has two ways of describing an SSH route:

- **An SSH session's jump hosts** (`JumpHostConfig` list, edited in the session dialog). The hops
  lead to the session's own host, which is the *target*: it has the user, key, password,
  folder-inherited defaults, keep-alive and remembered key passphrase.
- **A tunnel** (`TunnelConfig`, Tunneling…), which names a saved SSH session by id and forwards a
  port through it.

The RDP design spec planned the first: "reuses `JumpHostConfig`". Building it showed the mismatch.
RDP over SSH needs an SSH host to *start a forward on*, and with only a jump-host list that role
would fall silently to the last hop, a different meaning for the same list, on an entry that lacks
everything a target has beyond host, port, user, key and password.

## Decision

An RDP session that connects through SSH names a **saved SSH session**
(`RdpSessionConfig.sshTunnelSessionId`, the Gateway tab's "Through an SSH session" route). jterm
connects that session exactly as a tunnel does (its jump hosts, effective user and key, agent,
vault password, interactive fallback) and opens a loopback forward to the desktop as seen from the
SSH server (`terminal.ssh.LocalForward`).

RDP over SSH *is* a tunnel, so it follows the tunnel model rather than the SSH session dialog.

Alternatives considered:

- **A jump-host list on the RDP dialog**, the last hop forwarding. Consistent with the SSH session
  dialog and self-contained, but redefines what the last entry means, and duplicates a bastion the
  user has usually already saved.
- **Both**: the list, plus "copy from SSH session". The most flexible, and the most UI for a choice
  most users make once.

## Consequences

- A bastion is defined once: fixing its key or address fixes every RDP session that uses it, and
  the SSH session's own jump hosts chain in for free.
- The RDP dialog differs from the SSH session dialog here, deliberately; it matches Tunneling….
- A saved SSH session is required; there is no one-off bastion typed into the RDP dialog.
- Sessions are coupled. Deleting the SSH session leaves the RDP one showing "(missing SSH session)"
  in its dialog and failing with a message that names the setting, not silently connecting
  directly. Import relinks an RDP session to an SSH session imported with it
  (`SessionExportService.reassignIds`); exporting the RDP session alone loses the link.
- Revisit with a new ADR if one-off bastions turn out to be common: the "both" option can be added
  without changing the stored reference.
