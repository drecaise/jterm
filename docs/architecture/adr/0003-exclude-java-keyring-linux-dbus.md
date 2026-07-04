# ADR 0003: Exclude java-keyring's dbus-java Linux backend

**Status:** Accepted
**Date:** 2026-01-04

## Context

The `net.east301:keyring` (java-keyring) library is our chosen abstraction for storing
the vault master password in the OS keyring. It ships three backends: JNA (Windows
Credential Store), Apple Keychain (macOS), and dbus-java (Linux Secret Service).

The Linux dbus-java backend has been observed to **hang the JVM on some setups** — the
D-Bus event loop stops making progress and the calling thread never returns. This has
been reproduced with several distros in the "org.freedesktop.secrets" service
implementations and the exact cause depends on the running D-Bus daemon and libsecret
version. It is not a defect we want to shim around; there is no clean deadline / cancel
path.

## Decision

Exclude `com.github.hypfvieh:dbus-java-transport-native-unixsocket` and the surrounding
dbus-java modules from the `net.east301:keyring` dependency in `pom.xml`, so java-keyring
never picks up its Linux backend. Provide our own Linux keyring integration in
`security.MasterPasswordKeyring` by shelling out to the `secret-tool` CLI (see
[ADR 0005](0005-per-os-keyring-clis.md)).

## Consequences

- Linux users need `libsecret-tools` (which provides `secret-tool`) installed. This is
  in-repo for every major distro and is a soft dependency: if missing, jterm falls back
  to prompting for the master password every session.
- We avoid the observed hangs entirely — the failure mode of a missing CLI is a
  fast error rather than an unresponsive UI.
- The macOS path uses the `security(1)` CLI for the same "shell out to something the
  OS ships" reason. Windows keeps the JNA backend because the Credential Store call
  is stable and doesn't have this problem.
- If java-keyring ever adopts an alternative Linux backend (e.g. via libsecret C
  directly, without D-Bus), this decision should be revisited.
