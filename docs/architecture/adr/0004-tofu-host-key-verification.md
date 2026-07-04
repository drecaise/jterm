# ADR 0004: Trust-on-first-use host-key verification

**Status:** Accepted
**Date:** 2026-01-04

## Context

Every SSH client has to answer: "we just received a host key we've never seen — now
what?" The three positions in the spectrum are:

1. **Accept anything.** Trivially wrong for anything internet-facing.
2. **Strict.** Only trust keys already in `~/.ssh/known_hosts`; a first-time
   connection to any new host fails. Correct in the abstract, but jterm is a
   desktop tool that people use to hop onto ephemeral cloud VMs, and every new box
   would need an out-of-band pinning step. Nobody does that; strict mode makes the
   feature unusable.
3. **Trust on first use (TOFU).** Accept the key the first time and record it in
   `~/.ssh/known_hosts`; on subsequent connections, verify strictly and warn
   loudly if the key has changed. This is what OpenSSH's default behaviour looks
   like from the user's perspective.

## Decision

`terminal.ssh.JtermKnownHostsVerifier` implements TOFU against `~/.ssh/known_hosts`:

- **First connection to a host** — record the key, log an INFO line, and continue.
- **Subsequent connection with a matching key** — proceed silently.
- **Subsequent connection with a *changed* key** — abort the connect and surface an
  `ErrorDialog` explaining the change, showing both fingerprints, and pointing the
  user at `~/.ssh/known_hosts` for manual reconciliation.

The behaviour is controlled by a `Preferences` toggle (`TOFU host keys`) that defaults
to on. Users who want strict mode disable it, at which point unknown keys fail
immediately with a link to the same manual reconciliation flow.

## Consequences

- Interop with `openssh(1)` — jterm and OpenSSH share the same `known_hosts` file, so
  hosts pinned in one are pinned in the other.
- A changed key is never silently accepted. The dialog interrupts the connect flow
  before any credentials are sent.
- First-use is friction-free, which is what makes SSH clients usable on desktops.
- The trade-off is honest: TOFU protects against later MITM but not against a
  first-time MITM. Users on hostile networks can pin keys ahead of time by editing
  `known_hosts` directly.
