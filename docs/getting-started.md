# Getting started

## Installing

The easiest way to run jterm is a pre-built binary from the
[GitHub Releases](https://github.com/drecaise/jterm/releases) page:

| Platform | Asset | Install / run |
|----------|-------|---------------|
| Windows | `jterm-<version>.msi` | Run the installer |
| macOS | `jterm-<version>.dmg` | Open the disk image, drag jterm to Applications |
| Linux | `jterm-<version>.flatpak` | `flatpak install jterm-<version>.flatpak` |
| Any OS | `jterm-<version>.jar` | `java -jar jterm-<version>.jar` (needs a JRE 21) |

The MSI, DMG and Flatpak bundle their own Java runtime, so you don't need a separate JDK/JRE.
Only the bare `.jar` requires one.

!!! warning "macOS first launch"
    The macOS `.dmg` is currently **unsigned**, so Gatekeeper blocks the first launch.
    Right-click the app and choose *Open* to run it the first time.

If you prefer to build from source, see the [README](https://github.com/drecaise/jterm#building).

## First launch

When jterm starts it opens a window with:

- a **menu bar** — *File, Terminal, SSH, Macros, Preferences, Help*;
- a **sessions sidebar** on the left — your saved SSH sessions and folders, plus an
  **Open Local Terminal** entry;
- a **tab strip** with a **+** button to add tabs; and
- the **terminal area**, where each tab holds one or more panes.

![The jterm main window](img/main-window.png)

By default jterm opens a local shell on startup. You can turn that off in
**Preferences → General → Open a terminal on startup** (see [Preferences](preferences.md)).

## Opening your first terminal

- **Local shell:** click **Open Local Terminal** in the sidebar, press ++ctrl+shift+t++, or use
  **Terminal → Open Local Shell**. This launches your default login shell in a real PTY.
- **SSH session:** double-click a saved session in the sidebar, or drag it onto a pane. If you
  have no saved sessions yet, see [SSH sessions](ssh-sessions.md) to create one.

## Next steps

- Lay out your workspace with [Tabs & panes](tabs-and-panes.md).
- Organise connections in the [Sessions sidebar](sessions-sidebar.md).
- Learn the [Keyboard shortcuts](shortcuts.md).
