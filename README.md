# jterm

A cross-platform desktop **terminal emulator** built in Java 21 (Swing + FlatLaf +
JediTerm + pty4j). It supports tabbed windows, a splittable pane grid, a saved-sessions
sidebar with SSH connections, drag-and-drop session launching, input broadcast, and
light/dark theming that follows the host OS style.

## Features

- **Tabs** and a **uniform pane grid** — split any tab into up to 3 columns × 3 rows
  (max 9 panes), all equally sized.
- **Local shells** (your default shell via a real PTY) and **SSH sessions**.
- **Saved-sessions sidebar** — recursive folders and SSH sessions, each with a custom icon
  (built-in library + import your own PNG/JPG/GIF/SVG).
- **Drag-and-drop** a session (or the Local Terminal entry) onto a pane to split-and-open:
  drop on the top 60% adds a column, the bottom 40% adds a row.
- **SFTP file browser** — open an SFTP pane on any SSH session to browse the remote
  filesystem, edit the path directly, and upload/download multiple files at once.
- **SSH tunnels** — named local, remote, and dynamic (SOCKS) port forwards per session.
- **Detach & re-attach tabs** — pop a tab out into its own window, and attach it back.
- **Reconnect** a disconnected session (retaining its scrollback) and **save session output**
  to a file from the pane title-bar menu.
- **Broadcast mode** — type once, send to multiple panes; per-pane checkboxes to opt out.
- **SSH auth**: ssh-agent (with forwarding) + on-disk keys, plus optional **password auth**.
  Saved passwords are stored in an **encrypted vault** unlocked by a master password that is
  transparently remembered in the OS keyring.
- **Host-key checking** via `~/.ssh/known_hosts` (trust-on-first-use, warns on changes).
- **Light/dark** themes.

## Requirements

- **JDK 21** (build and run).
- **Maven 3.9+** (build).
- Per-OS, for transparently remembering the vault master password (optional — without it you
  are simply prompted for the master password each launch):
  - **Linux**: a running Secret Service (gnome-keyring / KWallet) and the `secret-tool` CLI
    (package `libsecret` / `libsecret-tools`).
  - **macOS**: the built-in `security` CLI (login Keychain).
  - **Windows**: Windows Credential Manager (used automatically).
- For SSH agent auth, a running **ssh-agent** with your keys (`ssh-add -l`). On Linux/macOS
  the agent socket (`$SSH_AUTH_SOCK`) is used; on Windows both the native OpenSSH agent
  (named pipe) and **PuTTY Pageant** are supported.

## Building

```bash
mvn package
```

This produces a self-contained runnable jar at `target/jterm.jar` (dependencies shaded in).

## Running

```bash
java -jar target/jterm.jar
```

Or, during development, run straight from compiled classes:

```bash
mvn exec:java
```

## Installing

### Pre-built binaries (recommended)

Each [GitHub Release](https://github.com/drecaise/jterm/releases) ships downloadable binaries:

| Platform | Asset | Install / run |
|----------|-------|---------------|
| Windows | `jterm-<version>.msi` | Run the installer |
| macOS | `jterm-<version>.dmg` | Open the disk image, drag to Applications |
| Linux | `jterm-<version>.flatpak` | `flatpak install jterm-<version>.flatpak` |
| Any OS | `jterm-<version>.jar` | `java -jar jterm-<version>.jar` (needs a JRE 21) |

The MSI, DMG and Flatpak bundle their own Java runtime, so end users don't need a separate
JDK/JRE; only the bare `.jar` requires one.

> The macOS `.dmg` is currently **unsigned**, so Gatekeeper will block the first launch —
> right-click the app and choose *Open* to run it.

These assets are built automatically by the `Release` GitHub Actions workflow
([`.github/workflows/release.yml`](.github/workflows/release.yml)) whenever a `v*.*.*` tag is
pushed; a manual workflow run builds the same artifacts for testing without publishing a release.

### Linux / GNOME desktop integration (running the bare jar)

If you run the bare `.jar` directly (rather than installing the Flatpak, which already
integrates with the desktop), you can install a desktop entry and icon yourself.

GNOME Shell (on Wayland via XWayland, and on Xorg) does **not** use a window's X11 icon for
the dash / overview / Alt-Tab. Instead it matches a window to a `.desktop` file by its
`WM_CLASS` and uses that file's `Icon=`. jterm already sets its `WM_CLASS` to `jterm`, so you
just need a matching desktop entry and a themed icon installed.

Run the helper script after building (it installs into your user directories — no root needed):

```bash
bash packaging/install-desktop-integration.sh
```

It installs:

- the app icon into the hicolor icon theme (`~/.local/share/icons/hicolor/...`), and
- a `jterm.desktop` launcher with `StartupWMClass=jterm` into
  `~/.local/share/applications/`, then refreshes the icon/desktop caches.

After this, jterm appears in the Activities overview and shows its icon in the dash and
Alt-Tab. The desktop entry's `Exec=` points at `target/jterm.jar`; **re-run the script if you
move or rebuild the jar to a different path.**

If no Secret Service keyring is available on your Linux session, the master password is simply
prompted at launch instead of being remembered — everything else still works.

## Configuration & data

State is stored as JSON in the per-OS config directory:

- Linux: `~/.config/jterm/` (or `$XDG_CONFIG_HOME/jterm`)
- macOS: `~/Library/Application Support/jterm/`
- Windows: `%APPDATA%\jterm\`

| File | Contents |
|------|----------|
| `sessions.json` | Saved folders and SSH sessions |
| `tunnels.json` | Saved SSH port-forwarding tunnels (local / remote / dynamic) |
| `icons.json` | Imported custom icons (files copied into `<config>/icons/`) |
| `keymap.json` | Keyboard shortcuts (created with defaults on first run; edit to customize) |
| `credentials.json` | SSH passwords, **AES-GCM encrypted** under your master password (no plaintext) |

The master password itself is never stored in `credentials.json`; it is kept in the OS keyring
(see Requirements).

## Keyboard shortcuts

Defaults (editable in `keymap.json`):

| Action | Shortcut |
|--------|----------|
| New tab | `Ctrl+T` |
| Close tab | `Ctrl+W` |
| Split into a new column | `Ctrl+→` |
| Split into a new row | `Ctrl+↓` |
| Close the focused pane | `Ctrl+↑` |
| Open a local shell in the active pane | `Ctrl+Shift+T` |
| Open SFTP browser | `Ctrl+F` |
| Tunneling… | `Ctrl+Shift+P` |
| Toggle broadcast input | `Ctrl+Shift+B` |
| Toggle light/dark theme | `Ctrl+Shift+L` |
| Duplicate session | `Ctrl+Shift+D` |
| Move session up / down | `Ctrl+Shift+↑` / `Ctrl+Shift+↓` |
| Move tab left / right | `Ctrl+Shift+←` / `Ctrl+Shift+→` |
| Duplicate tab | `Ctrl+Shift+K` |
| Detach tab to new window | `Ctrl+Shift+O` |
| Attach tab to main window | `Ctrl+Shift+I` |

## SSH authentication

When you open a saved SSH session, authentication is attempted in order: **public key**
(ssh-agent, then on-disk keys in `~/.ssh`), then **password** if you enabled it for that
session. Enable password auth and optionally "save password" in the session's edit dialog;
saved passwords go into the encrypted vault. The first time you save a password you'll be asked
to create a master password; on later launches it's unlocked transparently from the OS keyring.

On first connection to a host you'll be asked to confirm its key (trust-on-first-use); it is
recorded in `~/.ssh/known_hosts`. If a host's key later changes, you'll get a warning.
