# Preferences

Open **Preferences → Preferences…** for the main settings dialog. It has five tabs. (The theme
toggle and the keyboard-shortcut editor live in the **Preferences** menu too — see
[Keyboard shortcuts](shortcuts.md).)

## General

![Preferences — General](img/preferences-general.png)

| Setting | Effect |
|---------|--------|
| **Copy to clipboard on select** | Selecting text in the terminal copies it automatically. |
| **Paste on right click** | Right-click pastes. With this on, use ++ctrl++ + right-click for the context menu instead. |
| **Open a terminal on startup** | When off, jterm starts with no open tabs. |
| **Auto-accept new host keys** | Trust first-seen SSH hosts without prompting. You are still warned if a known host's key *changes*. |

## Session Defaults

![Preferences — Session Defaults](img/preferences-session-defaults.png)

Defaults inherited by folders and sessions that don't set their own (folders and individual
sessions can still override them):

- **Default username**
- **Default tab color**
- **Default key file** (blank uses your `~/.ssh` identities)
- **Default key passphrase** and **Default password** — stored **encrypted** in the credential
  vault (see [SSH auth & vault](ssh-auth-and-vault.md)); a blank field keeps any saved value.
- **Keep connection alive** + **interval (s)** — the root of the keep-alive inheritance chain.

Changes apply to **newly opened** sessions.

## Terminal Settings

![Preferences — Terminal Settings](img/preferences-terminal.png)

The application-wide terminal defaults used by the local shell and by sessions that don't
override them:

- **Terminal type** (e.g. `xterm-256color`)
- **Character encoding** (default UTF-8)
- **Font family** and **font size**

These apply to **newly opened** terminals. Individual sessions can override them on their own
[Terminal Settings tab](ssh-sessions.md#terminal-settings).

## Highlighting

![Preferences — Highlighting](img/preferences-highlighting.png)

Define named **highlight lists** — rules that colour matching text as it appears in new output
(for example, flagging `ERROR` red or `WARN` yellow). Pick the **active list (global default)**
at the top; individual sessions can override which list they use. Highlighting applies to
**newly opened** terminals.

## Colors

![Preferences — Colors](img/preferences-colors.png)

Retune the terminal **palette** — what colours the terminal actually draws with. Each theme has
its own palette; pick which one you're editing with the **Scheme** selector (**Dark** / **Light**),
which starts on your active theme.

You can edit:

- **Foreground** / **Background** — the default text and background colours.
- **Selection text** / **Selection background** — colours for selected text.
- **ANSI colors** — the 16-colour palette programs use, laid out as a grid of the eight named
  colours (Black, Red, Green, Yellow, Blue, Magenta, Cyan, White) in a **Normal** and a **Bright**
  row. This is where to fix, say, a *Bright black* that's too dark to read against the background.

Click any swatch to open the colour picker. **Reset to defaults** restores the selected scheme's
whole palette to its built-in preset; only the colours you actually change are saved (stored in
`colors.json` — see [Configuration files](config-files.md)), so untouched colours keep following
the built-in defaults across updates.

Open terminals **recolour immediately** when you click **OK**. (An already-ended *session
stopped* overlay keeps its old colours until a new pane opens in its place.)

## Theme

Switch between **light** and **dark** with **Preferences → Toggle Light/Dark** (++ctrl+shift+l++).
On startup jterm follows your operating system's light/dark preference.

!!! note "Live recolour"
    Toggling the theme recolours the application chrome immediately. Already-open terminal panes
    keep the colours they were created with; new panes use the new theme.
