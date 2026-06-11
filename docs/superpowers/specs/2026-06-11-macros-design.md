# Macros — design

## Goal

Add user-defined **macros**: named sequences of typed text and commands that can be
replayed into a terminal. Inspired by MobaXterm's macro feature. A macro can be run from
a new **Macros** top-menu, triggered by a per-macro hotkey, or run automatically right
after a saved SSH session connects.

## Scope

In scope (step types):
- **Text** — a line of literal text, with an optional per-keystroke delay (ms).
- **Key press** — a named key sent as its escape/control sequence (RETURN, TAB, ESC,
  arrows, etc.).
- **Sleep** — pause N milliseconds before the next step.

Out of scope (explicitly deferred):
- **Wait for pattern** — blocking until matching text appears in the terminal output.
  Requires intercepting the live output stream (JediTerm consumes it), which is
  substantially more work. Omitted from the line editor for now.

## Data model — package `com.katmoda.jterm.macro`

### `MacroStep` (sealed interface, Jackson-polymorphic)

Polymorphic via a `type` discriminator using `@JsonTypeInfo` / `@JsonSubTypes`, the same
pattern `session.SessionNode` uses. Each step exposes:

- `void execute(MacroSink sink) throws InterruptedException` — performs the step.
- `String displayLine()` — the one-line rendering shown in the editor list, matching
  MobaXterm (`source ~/.bashrc`, `RETURN`, `SLEEP=200`).

Implementations:

- `TextStep(String text, int keystrokeDelayMs)`
  - `keystrokeDelayMs > 0` → type char-by-char, sleeping that many ms between chars.
  - `keystrokeDelayMs == 0` → write the whole string at once.
  - `displayLine()` → the literal `text`.
- `KeyStep(MacroKey key)`
  - Writes `key.sequence()`.
  - `displayLine()` → `key.name()` (e.g. `RETURN`).
- `SleepStep(int ms)`
  - `Thread.sleep(ms)`.
  - `displayLine()` → `SLEEP=<ms>`.

### `MacroKey` (enum)

Maps named keys to the byte/char sequence written to the terminal. `ESC` denotes the escape
byte 0x1B (Java `\u001b`):

| Key | Sequence |
|-----|----------|
| RETURN | `\r` |
| TAB | `\t` |
| ESC | `ESC` |
| BACKSPACE | DEL (0x7f) |
| DELETE | `ESC [3~` |
| UP / DOWN / RIGHT / LEFT | `ESC [A` / `ESC [B` / `ESC [C` / `ESC [D` |
| HOME / END | `ESC [H` / `ESC [F` |
| PAGE_UP / PAGE_DOWN | `ESC [5~` / `ESC [6~` |
| CTRL_C / CTRL_D / CTRL_Z / CTRL_L | 0x03 / 0x04 / 0x1a / 0x0c |
| F1-F4 | `ESC OP`, `ESC OQ`, `ESC OR`, `ESC OS` |
| F5-F12 | `ESC [15~`, `ESC [17~`, `ESC [18~`, `ESC [19~`, `ESC [20~`, `ESC [21~`, `ESC [23~`, `ESC [24~` |

`sequence()` returns the `String`; `displayLabel()` returns a human label for the dropdown.

### `Macro`

```
id        : String (UUID, stable)
name      : String
hotkey    : String  (nullable; a KeyStroke.toString() value, e.g. "shift ctrl F1")
steps     : List<MacroStep>
```

### `MacroLibrary` (singleton)

- `MacroLibrary.get()` — mirrors `icon.IconLibrary` / `security.VaultManager` singletons.
- Loads/saves `macros.json` in the per-OS config dir via `config.AppPaths.file("macros.json")`,
  using Jackson with `INDENT_OUTPUT`. Malformed/missing file → empty list.
- API: `List<Macro> macros()`, `Macro byId(String)`, `Macro byHotkey(String stroke)`
  (returns the first macro whose `hotkey` matches, or null), `add/remove/replace`, `save()`.

## Execution — `macro.MacroRunner`

- `static void run(Macro macro, TtyConnector connector)`.
- Spawns a **background thread** (daemon) — steps sleep and may type with per-keystroke
  delays, so execution must stay off the EDT.
- The thread wraps the connector in a `MacroSink` that exposes `type(String)` →
  `connector.write(String)`. Each step calls `execute(sink)`.
- `InterruptedException` ends the run quietly.
- Connector `write(String)` handles charset encoding (same path manual typing uses).

## Triggering

### Macros top-menu (`app.MainWindow`)

- A new `Macros` `JMenu`, added to the menu bar after the `SSH` menu.
- Rebuilt from `MacroLibrary.get().macros()`: one `JMenuItem` per macro (label = name) whose
  action runs the macro on the active pane. Then a separator and **Manage Macros…** which
  opens `MacroManagerDialog`.
- Running on the active pane writes to that pane's **broadcasting** connector
  (`TerminalPane.inputConnector()`), so when broadcast mode is on the macro fans out to the
  broadcast panes — consistent with manual typing.
- After the manager dialog closes, the menu is rebuilt (new/renamed/deleted macros) and the
  hotkey lookup picks up changes automatically (it reads the live library).

### Per-macro hotkey

- Hotkeys are chosen from a **fixed dropdown** in the editor: `<none>`, `Ctrl+Shift+F1` …
  `Ctrl+Shift+F12`. A curated `MacroHotkeys` list pairs each label with a `KeyStroke`; the
  stored value is `KeyStroke.toString()`. No key-capture, so it never clashes with the
  shortcut editor's capture mode.
- `MainWindow.installShortcutDispatcher()` is extended: after the existing keymap-action
  match fails, it asks `MacroLibrary.get().byHotkey(strokeString)`. If a macro matches, run
  it on the active pane and return `true` (consume).

### Run-on-connect (saved SSH sessions)

- `session.SshSessionConfig` gains `String macroId` (nullable) with getter/setter, persisted
  in `sessions.json` automatically (Jackson bean).
- In `MainWindow.connectAsync`'s `onConnected`, after the session is placed, if
  `cfg.getMacroId()` is set and `MacroLibrary.get().byId(...)` resolves, run that macro
  against the **session connector** (`session.connector()`). A freshly connected pane has
  broadcast off, so the raw connector is sufficient and simplest.

## UI — package `com.katmoda.jterm.ui.macro`

### `MacroManagerDialog`

- Modal dialog: a `JList<Macro>` of all macros + buttons **New**, **Edit**, **Delete**.
- New → create a `Macro` with a default name, open `MacroEditDialog`; on OK add + save.
- Edit → open `MacroEditDialog` on a copy; on OK replace + save.
- Delete → confirm, remove + save.

### `MacroEditDialog` (matches screenshot 1)

- Fields: **Name** text field; the **step-line list** (`JList<MacroStep>` rendering
  `displayLine()`); buttons **Edit selected line**, **Insert new line above**,
  **Insert new line below**, **Delete line**; a **hotkey** `JComboBox` (`MacroHotkeys`);
  OK / Cancel.
- The four line buttons open `MacroLineDialog`; insert-above/below place the new step
  relative to the selection (append if none selected).
- OK mutates the working `Macro` (name, steps, hotkey) and returns it.

### `MacroLineDialog` (matches screenshot 2, minus Wait-for-pattern)

- Radio group selecting the step type, with the matching input enabled:
  - **Text:** text field + **Delay between keystrokes** spinner (ms).
  - **Key press:** `JComboBox<MacroKey>` (uses `displayLabel()`).
  - **Sleep:** ms spinner.
- OK builds and returns the corresponding `MacroStep`. When editing an existing line, the
  dialog pre-selects the radio and populates fields from that step.

### Session editor — `ui.sidebar.SessionSidebar.showSshDialog`

- In the Basic-settings `JPanel` (around [SessionSidebar.java:824](../../../src/main/java/com/katmoda/jterm/ui/sidebar/SessionSidebar.java#L824)),
  add a row `Run macro on connect:` with a `JComboBox` whose first entry is `(none)` and the
  rest are the macro names from `MacroLibrary.get()`. Preselect by `cfg.getMacroId()`.
- On OK, set `cfg.setMacroId(...)` to the chosen macro's id, or `null` for `(none)`.

## Supporting change — `ui.pane.TerminalPane`

- Store the `TtyConnector` passed to the constructor (the broadcasting wrapper the grid
  builds) in a field and expose `TtyConnector inputConnector()`. This is what the
  Macros menu / hotkey path writes to so broadcast is respected. (`realConnector()` stays as
  the raw session connector used for broadcast source/target identity.)

## Testing / verification

No automated test suite exists. Verify by:
1. `mvn -q compile` clean.
2. Launch the app; create a macro (`source ~/.bashrc`, RETURN, SLEEP=200, RETURN) via
   Macros ▸ Manage Macros…; run it on a local terminal and confirm the text + Enter land.
3. Assign a `Ctrl+Shift+F1` hotkey, confirm it runs on the active pane.
4. Set a macro as "Run macro on connect" on a saved SSH session; connect and confirm it runs.
5. With broadcast on across two local panes, run a macro and confirm both receive it.
6. Confirm `macros.json` round-trips (restart, macro still present).
