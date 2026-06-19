# Screenshot shot list

This file tracks the screenshots the manual expects. It is **excluded from the published site**
(see `mkdocs.yml`) — it's a checklist for whoever captures the images.

## How to capture

1. Build and run jterm: `mvn package -DskipTests && java -jar target/jterm.jar`
   (Maven lives at `/home/mark/apache-maven-3.9.11/bin` here — prefix `PATH` if needed).
2. Take each screenshot below, crop to the relevant window/dialog.
3. Save it as a **PNG** into `docs/img/` using the exact filename in the table.
4. Re-run `mkdocs build --strict` (or `mkdocs serve`) — the placeholder image references will
   resolve automatically; no page edits are needed.

Prefer the **dark** theme for window shots (it's the typical terminal look) and a reasonable
window size (~1200×800) so text stays legible.

## Required images

| Filename | What to capture | Used on page |
|----------|-----------------|--------------|
| `main-window.png` | The full main window: menu bar, a tab, the sidebar with a few sessions, an open terminal | `index.md`, `getting-started.md` |
| `sidebar-tree.png` | The sessions sidebar with folders expanded, a couple of SSH sessions with icons, and the "Open Local Terminal" entry | `sessions-sidebar.md` |
| `sidebar-context-menu.png` | Right-click context menu on a saved session (Edit / Delete / Duplicate / Open … ) | `sessions-sidebar.md` |
| `icon-picker.png` | The icon picker dialog showing the built-in library | `sessions-sidebar.md` |
| `pane-grid-3x3.png` | A tab split into several panes (e.g. 2×2 or 3×2), active pane highlighted | `tabs-and-panes.md` |
| `dnd-drop-regions.png` | A drag in progress over a pane, showing the top (column) / bottom (row) drop highlight | `tabs-and-panes.md` |
| `session-dialog-basic.png` | The SSH session edit dialog, **Basic settings** tab | `ssh-sessions.md` |
| `session-dialog-jumphosts.png` | The SSH session edit dialog, **Jump Hosts** tab | `ssh-sessions.md` |
| `session-dialog-terminal.png` | The SSH session edit dialog, **Terminal Settings** tab | `ssh-sessions.md` |
| `master-password-prompt.png` | The "create / enter master password" dialog | `ssh-auth-and-vault.md` |
| `host-key-prompt.png` | The trust-on-first-use host key confirmation dialog | `ssh-auth-and-vault.md` |
| `agent-keys.png` | The "Show Agent Keys" dialog listing ssh-agent identities | `ssh-auth-and-vault.md` |
| `sftp-browser.png` | An SFTP browser pane showing a remote directory listing and the path field | `sftp.md` |
| `tunnels-dialog.png` | The Tunneling… manager dialog with one or more tunnels listed | `tunnels.md` |
| `tunnel-edit.png` | The tunnel New/Edit dialog showing Type (Local/Remote/SOCKS) and fields | `tunnels.md` |
| `broadcast-panes.png` | Several panes with broadcast on, showing per-pane opt-out checkboxes | `broadcast.md` |
| `macro-manager.png` | The Manage Macros… dialog listing macros | `macros.md` |
| `macro-edit.png` | The macro edit dialog showing Name, Hotkey, and steps | `macros.md` |
| `shortcuts-dialog.png` | The Keyboard Shortcuts editor | `shortcuts.md` |
| `preferences-general.png` | Preferences dialog, **General** tab | `preferences.md` |
| `preferences-session-defaults.png` | Preferences dialog, **Session Defaults** tab | `preferences.md` |
| `preferences-terminal.png` | Preferences dialog, **Terminal Settings** tab | `preferences.md` |
| `session-stopped-screen.png` | A pane showing the "session stopped" reconnect/restart screen | `reconnect-and-output.md` |
