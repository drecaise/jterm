# ADR 0005: Native OS keyring CLIs for Linux and macOS master-password storage

**Status:** Accepted
**Date:** 2026-01-04

## Context

The vault master password needs to be stored in the OS keyring so users aren't
prompted every session. Three constraints shape the choice per OS:

- On **Linux** the dbus-java path in `java-keyring` hangs on some setups (see
  [ADR 0003](0003-exclude-java-keyring-linux-dbus.md)), and there is no other
  robust in-JVM Secret Service client.
- On **macOS** Keychain access from Java is possible via JNA, but the `security(1)`
  CLI is a stable, documented interface that already deals with prompt UX and
  Keychain permission dialogs correctly.
- On **Windows** the Credential Store has a stable native ABI and `java-keyring`'s
  JNA backend calls it directly and reliably.

## Decision

`security.MasterPasswordKeyring` picks a backend at startup based on `os.name`:

| OS | Backend | How |
| --- | --- | --- |
| Linux | `secret-tool` CLI | `ProcessBuilder` calling `secret-tool store/lookup` with `service=jterm account=<vault>`. |
| macOS | `security(1)` CLI | `ProcessBuilder` calling `security add-generic-password` and `find-generic-password` with `-s jterm -a <vault>`. |
| Windows | `java-keyring` JNA | `net.east301.keyring.Keyring.create(WINDOWS_CREDENTIAL_STORE)`. |

If the CLI is missing or returns non-zero, the wrapper reports "no keyring" and the
UI prompts for the master password. jterm still runs — the keyring is a convenience,
not a hard requirement.

## Consequences

- No matter the OS, the master password ends up in the same store the user's other
  tools already trust and the same store their system security tooling audits.
- No native library shipped with jterm on Linux or macOS. Reduces packaging surface
  area.
- A missing CLI is a soft failure with a clear diagnostic, not a crash or a hang.
- The subprocess path adds ~50–100 ms per keyring hit, which is invisible next to the
  network cost of the SSH connect that follows.
- If Linux Secret Service ever gains a reliable in-JVM client we don't own, revisit
  and consolidate.
