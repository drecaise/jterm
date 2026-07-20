# Deployment

Three OS targets, each with its own installer, its own keyring backend, and its own
ssh-agent transport. The application source is identical across all three — only the
integration seams and the packaging change.

## Linux

Distributed as a Flatpak (`packaging/flatpak/`), as a **Rocky Linux 10 RPM** built with
jpackage (the `build-rpm` job in `.github/workflows/release.yml` runs inside a
`rockylinux:10` container so the RPM's dependency metadata is native to that distro), and
as the fat jar built by `mvn package`. The Flatpak is the recommended path on non-RHEL
distros; the RPM bundles a Temurin JRE, so neither needs a system Java.

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

    The **RPM** needs both halves supplied explicitly, because the jpackage launcher
    starts the app in classpath mode (`app.classpath`/`app.mainclass` in `jterm.cfg`),
    where jar-manifest attributes are ignored: the `build-rpm` job passes
    `--java-options "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"` and swaps in
    the shared `packaging/linux/jterm.desktop` (a jpackage resource override with
    `StartupWMClass=jterm`) via `--resource-dir`. Remove either and GNOME shows two
    dock icons — one for the window, one for the launcher.

    Since jpackage takes only one `--resource-dir`, the `build-rpm` job assembles a
    `rpmres/` dir holding both that desktop file and the RPM-specific
    `packaging/rpm/jterm.spec` (based on JDK 21's `template.spec`); the `build-deb` job
    (Ubuntu 26.04, `jpackage --type deb`) points `--resource-dir` straight at
    `packaging/linux`. The spec's `%install` relocates the generated desktop entry
    to an RPM-owned `/usr/share/applications/jterm.desktop`, replacing the stock
    `xdg-desktop-menu` scriptlets that copied `jterm-jterm.desktop` untracked into
    `/usr/local/share/applications`.

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
