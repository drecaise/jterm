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
| `macros.json` | Saved [macros](macros.md). Step contents are **AES-GCM encrypted** when *Encrypt macros on disk* is on; names and hotkeys stay readable either way. |
| `highlights.json` | [Output highlighting](settings.md#highlighting) lists and their rules. Seeded with the built-in *Standard* list on first run. |
| `credentials.json` | SSH passwords and key passphrases, **AES-GCM encrypted** under your master password. No plaintext secrets. |
| `settings.json` | Application preferences and window state (size/position, theme, defaults, [update-check](settings.md#update-checks) state). |
| `colors.json` | Per-theme [terminal palette](settings.md#colors) customizations. Stores only the colours you changed; absent until you customize one. |
| `icons/` | Folder holding imported icon image files. |

### Schema versions

`sessions.json` and `macros.json` carry a `schemaVersion` so jterm can upgrade an older file in
place on startup. `macros.json` was a bare JSON array before schema 1 and is rewritten as
`{ "schemaVersion": 1, "macros": [ ... ] }` the first time a newer jterm opens it.

!!! warning "Upgrading a file is one-way"
    An older jterm cannot read an upgraded file. It does not delete it — it sets it aside as
    `macros.json.unreadable-1` and starts empty — but if you move a config directory between jterm
    versions, expect to have to restore that file by hand.

### Update-check keys in `settings.json`

| Key | Meaning |
|-----|---------|
| `updateCheckEnabled` | Whether the daily [update check](settings.md#update-checks) runs. Same as the Preferences toggle. |
| `lastUpdateCheckEpochSeconds` | When a check was last *attempted*, in Unix epoch seconds (`0` = never). This is what keeps the check to once a day across restarts. |
| `skippedUpdateVersion` | The release tag you chose to skip, e.g. `v1.9.0`; `""` when none. Clear it to be reminded about that release again. |

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
