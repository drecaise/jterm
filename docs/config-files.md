# Configuration files

jterm stores all its state as JSON in a per-OS configuration directory.

| OS | Config directory |
|----|------------------|
| Linux | `~/.config/jterm/` (or `$XDG_CONFIG_HOME/jterm`) |
| macOS | `~/Library/Application Support/jterm/` |
| Windows | `%APPDATA%\jterm\` |

## The files

| File | Contents |
|------|----------|
| `sessions.json` | The folder tree and SSH session definitions. |
| `tunnels.json` | Saved [port-forwarding tunnels](tunnels.md) (local / remote / dynamic). |
| `icons.json` | Imported custom icons (the image files are copied into `<config>/icons/`). |
| `keymap.json` | [Keyboard shortcut](shortcuts.md) bindings (written with defaults on first run). |
| `credentials.json` | SSH passwords and key passphrases, **AES-GCM encrypted** under your master password. No plaintext secrets. |
| `settings.json` | Application preferences and window state (size/position, theme, defaults). |
| `colors.json` | Per-theme [terminal palette](preferences.md#colors) customizations. Stores only the colours you changed; absent until you customize one. |
| `icons/` | Folder holding imported icon image files. |

## Editing by hand

The JSON files are human-readable and you *can* edit them, but the in-app dialogs are the safer
route (they keep cross-references — e.g. tunnels referencing sessions — consistent). If you do
edit by hand, **quit jterm first** so your changes aren't overwritten on exit.

!!! warning "Don't hand-edit `credentials.json`"
    `credentials.json` is encrypted and keyed to your master password. Editing it will corrupt
    the vault. Manage saved passwords/passphrases through the session dialogs instead — see
    [SSH auth & vault](ssh-auth-and-vault.md).

!!! note "Master password is not stored here"
    The master password itself never lives in `credentials.json`. It's kept in your OS keyring
    (or prompted at launch). See [SSH auth & vault](ssh-auth-and-vault.md#os-keyring-remembering-the-master-password).

## Backing up & moving settings

To move your sessions to another machine, use **File → Export Sessions… / Import Sessions…**
(see [Sessions sidebar](sessions-sidebar.md#import-and-export)). For a full backup, copy the entire
config directory — but remember `credentials.json` only decrypts with the same master password.
