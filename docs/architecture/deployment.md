# Deployment

Three OS targets, each with its own installer, its own keyring backend, and its own
ssh-agent transport. The application source is identical across all three — only the
integration seams and the packaging change.

## Linux

Distributed as a Flatpak (`packaging/flatpak/`) and as the fat jar built by
`mvn package`. The Flatpak sandbox is the recommended path for end users.

```mermaid
--8<-- "architecture/model/generated/deployment-linux.mmd"
```

- **Keyring** — `secret-tool` from libsecret. See
  [ADR 0005](adr/0005-per-os-keyring-clis.md) for why the CLI is used instead of
  a direct D-Bus client.
- **ssh-agent** — Unix-domain socket via `$SSH_AUTH_SOCK`. jterm reads it directly with
  a JDK `SocketChannel` over `UnixDomainSocketAddress` (see
  [ADR 0002](adr/0002-custom-jdk-agent-factory.md)).
- **Desktop integration** — `app.Main` sets X11 `WM_CLASS` to `jterm` via
  reflection on `sun.awt.X11.XToolkit`. That reflection needs
  `Add-Opens: java.desktop/sun.awt.X11` in the jar manifest, which the shaded jar
  already sets. `packaging/install-desktop-integration.sh` installs the icon and a
  `StartupWMClass=jterm` `.desktop` file so GNOME associates the running window
  with the installer.

## Windows

Distributed as an MSI built by `jpackage` (`.github/workflows/release.yml`).

```mermaid
--8<-- "architecture/model/generated/deployment-windows.mmd"
```

- **Keyring** — `java-keyring`'s JNA backend against the Windows Credential Store.
  This is the one OS where the `java-keyring` library is used directly; on Linux and
  macOS the CLI wrappers replace it.
- **ssh-agent** — two transports supported: OpenSSH's named pipe
  (`\\.\pipe\openssh-ssh-agent`) via `WindowsPipeAgentProxy`, and PuTTY Pageant via
  `PageantAgentProxy` (Pageant listens on a hidden window; the proxy talks to it via
  `WM_COPYDATA`). When both are live, `CompositeSshAgent` fronts them so identities
  from both sources are offered to MINA.

## macOS

Distributed as a DMG built by `jpackage`.

```mermaid
--8<-- "architecture/model/generated/deployment-macos.mmd"
```

- **Keyring** — the `security(1)` CLI wrapping Keychain. See
  [ADR 0005](adr/0005-per-os-keyring-clis.md).
- **ssh-agent** — Unix-domain socket, path published by launchd
  (`/private/tmp/com.apple.launchd.*/Listeners`). Same `JdkAgentProxy` code as Linux.
- Notarisation is not currently automated — the DMG is unsigned and users see the
  Gatekeeper prompt on first launch.

## Anti-patterns explicitly rejected

- **A single "universal" installer** — every keyring backend behaves differently
  enough that packaging separately is cheaper than papering over the differences.
- **Bundling ssh-agent** — jterm always uses the OS-native agent so private keys stay
  where the user's other tools (openssh, git, IDEs) already find them.
- **A separate config app** — configuration is JSON in the config dir, editable in a
  regular editor. See [Configuration files](../config-files.md).

## See also

- Build workflow: `.github/workflows/release.yml`.
- Flatpak manifest: `packaging/flatpak/`.
- [Configuration files](../config-files.md) — the per-OS config dir paths.
