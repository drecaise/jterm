# RDP full screen

**Status:** designed, 2026-11-10. Decisions D1–D4 taken the same day (end of this document); D2
revised the same day after the macOS spike. The spike is done on all three OSes. Built the same
day (`fdfceb1`, branch `feature/rdp-session`).

Live tests (numbers refer to the Testing list below):
- **GNOME/Wayland, two monitors, dev jar — done 2026-11-10.** Passed: 1 (all three entry points,
  remote resized to the monitor, opens on the monitor of the jterm window), 2, 3 (Alt+Tab goes to
  the host, as designed: no keyboard hook), 4 (dwell, flick, pin, exit), 5 (server-side sign-out),
  6 (file paste while full screen: full screen ends, then the consent prompt), 9. Not yet tried:
  7 (Alt+F4 on the full-screen window) and 8 (close/detach the tab while full screen, mixed DPI).
- **Windows 11, release candidate MSI (Release run 44356899174, `c65e00a`) — done 2026-11-18.**
  All of tests 1–9 passed on the Win11 VM against Windows Server 2025. That includes the borderless
  frame staying full screen across Alt+Tab (3), and a server-side sign-out (`logoff` over SSH) while
  full screen (5): back in the pane, Reconnect shown.
- **macOS (DMG):** done on the Intel VM 2026-11-16 (see the Cmd+Q item under Open items); Apple
  Silicon untested. On the macOS VM, `~/fsspike/macproxy.command` checks that the
  `FullScreenListener` proxy actually receives callbacks (only its creation was verified).

Two defects found in the Linux test, both fixed before the commit: removing the view from the
pane's `CardLayout` hid it (`removeLayoutComponent` calls `next()` while the view is still a
child), so full screen was black; and a frame mapped at the monitor's size was placed by mutter on
the wrong monitor, so `FullScreenWindow` maps a small frame on the target monitor and switches
500 ms later, the sequence the spike measured.

Confirmed after the test (T3): the file-paste consent prompt *leaving* full screen is the right
behaviour, not just a safe one: the user sees the paste being asked about, in jterm's own window.
Not done, possible later: going back into full screen after the prompt is answered.

**Builds on:** `2026-11-07-rdp-session-design.md` (open item 7: the pane, the RDP shortcut policy,
Display Control resize).

## Summary

One RDP pane's desktop fills one monitor, in a window of its own with no jterm chrome. The jterm
window it came from stays as it was, with its other tabs, and can be reached with Alt+Tab or
Cmd+Tab. The pane's grid cell shows "Shown full screen" with a **Return** button. A fit-to-pane
session is resized to the monitor over Display Control, as a pane resize already does. A
fixed-resolution session is scaled to fit and letterboxed, which `DesktopView.drawRect` already
does.

There are three ways out, and the remote side can block none of them:

- **Ctrl+Shift+F11** (a new `TermAction`, so it can be rebound). jterm's `KeyEventDispatcher` sees
  the key before the desktop view does.
- **A full-screen bar** at the top centre. jterm draws it on top of the desktop, and it appears
  when the pointer rests at the top edge.
- **Closing the full-screen window** through the OS (Alt+F4, the taskbar's Close): this leaves
  full screen and does not disconnect.

No protocol change and no helper change: everything is on the Java side.

## Threat model

The remote desktop is untrusted: it decides every pixel inside the pane. In a pane, the jterm
window around those pixels shows where the remote desktop ends. Full screen removes that border.
The risks, and what answers each one:

| # | Risk | Answer |
| --- | --- | --- |
| T1 | **Spoofed jterm prompts.** The remote desktop draws a perfect "jterm: enter your master password" dialog, and the user types the password into the remote session. | **jterm never shows a dialog while full screen.** Every dialog goes through one choke point that leaves full screen first (below). Secrets are asked for only when connecting, and a disconnect leaves full screen, so a password, master-password or certificate prompt can't appear there. The user manual says so in one sentence: *anything asking for a password in full screen is the remote computer.* |
| T2 | **Trapped user.** The remote desktop or a hung session holds the screen. | The exit shortcut is claimed by the dispatcher before the view. The bar is jterm's own component. No keyboard hook (D4), so Alt+Tab, the Windows key and the local Ctrl+Alt+Del stay with the OS. The window is never always-on-top. Closing it through the OS leaves full screen. |
| T3 | **Server-triggered dialogs.** The file-paste consent prompt is caused by the server (when it requests the file list). | It leaves full screen too. The server can't use that to keep kicking the user out: the existing design asks once per *announced copy*, and only jterm can make a new announcement. |
| T4 | **Hidden state.** In a pane, the bar always shows "Shared: 2 folders". Full screen would hide what the server can reach. | The full-screen bar carries the same indicator, and it shows for 3 s on entry, so the user also learns where the bar is. |
| T5 | **An invisible modal.** A dialog owned by the jterm window opens *behind* the full-screen window and blocks input to both. | The same choke point as T1: the dialog appears only after full screen has ended. |
| T6 | **Stuck keys.** The shortcut's own Ctrl and Shift have already gone down remotely, and the focus moves between windows mid-stroke. | Every transition moves focus off the view, and the view's existing focus-loss path (`releaseAllKeys`) runs. |

## Architecture

### Only the desktop view moves

`RdpPane` stays in its grid cell and remains the `GridContent`: it keeps the connection, the
clipboard bridge, the drives and the bottom bar. Only `DesktopView` is moved into the
full-screen window, and the pane's `CardLayout` gets a third card, `CARD_FULL_SCREEN` (a short
explanation and **Return**). So `PaneGrid`, tab moves, Detach/Attach and closing all work as
before. Moving the pane to another window while its view is full screen is harmless, because the
view isn't in the pane.

`view` is already a field whose input and painting go through `RdpPane`, so moving it is
`center.remove(view)` and `fullScreen.setContent(view)`, then the reverse.

### `ui.rdp.FullScreenWindow` (new)

An undecorated `JFrame`, not a `JWindow`: a `JFrame` gets a taskbar entry, appears in Alt+Tab and
takes focus without an owner. Title `"<session name> — jterm"`, the app icon, black background,
`DO_NOTHING_ON_CLOSE` with a `windowClosing` that asks the pane to leave full screen. Its
`JLayeredPane` holds the view (default layer) and the bar (`PALETTE_LAYER`).

The mechanism differs by OS, behind one small `enter(GraphicsDevice)` / `exit()` pair:

| OS | Mechanism | Why |
| --- | --- | --- |
| Windows | Borderless frame with its bounds set to the device bounds; **not** `GraphicsDevice.setFullScreenWindow` | The JDK's Windows full-screen mode minimizes the window whenever another app takes focus (source and measured, below). A borderless frame covering its monitor hides the taskbar just as well and stays put (measured). |
| Linux | `GraphicsDevice.setFullScreenWindow` | Measured: EWMH `_NET_WM_STATE_FULLSCREEN` (mutter through XWayland). A borderless frame is *not* an option here (measured, below). |
| macOS | Native full screen in its own Space (D2, revised): a *decorated* frame with `apple.awt.fullscreenable`, toggled by `com.apple.eawt.Application.requestToggleFullScreen` | Measured: the JDK's mode hides the menu bar and Dock but **blocks Cmd+Tab**, and a borderless frame leaves the menu bar and Dock showing. Only the native Space behaves like full screen on a Mac. |

**macOS details.** `com.apple.eawt` isn't exported, so it is reached reflectively and needs
`--add-exports java.desktop/com.apple.eawt=ALL-UNNAMED`. That goes in the jar manifest
(`Add-Exports`, beside the existing `Add-Opens` in `pom.xml`) and in the macOS jpackage job's
`--java-options` in `release.yml`, for the same reason as the X11 `--add-opens`: the jpackage launcher
runs in classpath mode and ignores manifest attributes. Without both, the DMG silently falls back.
- **Leaving goes through the same toggle**, and the window is disposed only once the exit animation
  has finished. `com.apple.eawt.FullScreenUtilities.addFullScreenListenerTo` (same package)
  reports `windowExited`, which is also how jterm learns that the user left through the menu bar's
  green button or Ctrl+Cmd+F: that counts as "leave full screen" too. Without the listener, that
  window would be left behind as a plain window holding the desktop.
- **Fallback:** if the reflection fails (the flag is missing, or a future JDK removes the class), the
  failure is logged and macOS uses the JDK's mode (`setFullScreenWindow`). The exit shortcut and the
  bar still work there; Cmd+Tab doesn't.
- The window goes to the target monitor before the toggle, because a Space opens on the monitor
  the window is on.

### Spike results (2026-11-10)

**Windows, from the JDK 21 source** (`Win32GraphicsDevice`, jdk21u): `setFullScreenWindow` installs
a `Win32FSWindowAdapter` on every entry, whether or not the display mode changes. Its
`windowDeactivated` calls `setFSWindowsState(opposite, Frame.ICONIFIED)`, which iconifies every
full-screen `Frame` unless the window taking focus is itself a full-screen window, and that
includes focus going to another application. So Alt+Tab from a full-screen desktop to the jterm
window would minimize the desktop. That rules the JDK's mode out on Windows. `CGraphicsDevice`
(macOS) and `X11GraphicsDevice` have no such handler.

**Linux, measured** (GNOME on Wayland, XWayland, two 2560×1440 monitors at scale 1.0, JDK 21.0.12,
the throwaway `FsSpike`, state read with `xprop`, focus moved with `wmctrl -a`):
- `setFullScreenWindow` on either monitor: the window gets exactly that monitor's bounds and
  `_NET_WM_STATE_FULLSCREEN`. Activating another window leaves it full screen and not iconified
  (`extState=0`). Re-activating it brings focus back. `setFullScreenWindow(null)` restores the
  bounds it had before.
- **A borderless frame lands where mutter puts it, not where it is asked to be.** Retested with a
  numbered window held 7 s each, positions confirmed by eye: asked for the left monitor or the right
  one, with or without setting the bounds again 500 ms after mapping, all four borderless windows
  landed on the **right** monitor, the one *without* focus, and none got the full-screen state.
  The JDK's mode asked for the left monitor landed on the left, full screen. So it isn't
  focus-following: mutter ignores the requested position of a monitor-sized undecorated window.
  Java's reported bounds matched what was on screen (`wmctrl -lG` showed x=5120 for x=2560, its
  doubling quirk for frameless windows). Linux can't use the Windows mechanism, so the table keeps
  two rows.

**Windows 11, live** (the VM, JDK 21.0.13, two 2560×1440 displays at scale 1.0; `FsSpikeWin.java`,
single-file, which offers both mechanisms per monitor and logs to `fsspike-log.txt`):
- **Both mechanisms hide the taskbar**, and Alt+Tab away and back works in both, as far as the
  eye can tell.
- **The log shows the difference the eye misses:** the JDK's mode logged `ICONIFIED` the moment
  focus went to another app, and `deiconified` on the way back. The borderless frame stayed at
  `extState=0` through every Alt+Tab. On one monitor the two look the same. With two monitors,
  the JDK's mode would minimize the remote desktop whenever the user clicked a window on the
  other monitor (confirmed by hand the same day). That confirms the borderless frame for Windows.
- **Win+Shift+Left/Right moves either window to the other display**, at the same size, with a
  `graphicsConfiguration` change for each move. The two displays were the same size, so the
  re-fit listener for a monitor of a different size wasn't exercised.
- **Alt+F4 arrives as `windowClosing`**, several times when held. With `DO_NOTHING_ON_CLOSE` there
  is no visible response, which is right: the design turns it into "leave full screen". In the
  spike nothing consumed the key. jterm's `DesktopView` consumes every key event, and AWT on
  Windows skips the native default handling for a consumed key event. So in jterm, Alt+F4 with
  the desktop focused should go to the remote program (as in mstsc) and not close the window.
  *To confirm when built.* The taskbar's Close still arrives as `windowClosing`.

**macOS 26.7.2, live** (the Intel VM, one 1920×1080 display, Temurin 21.0.13, viewed through
Proxmox's console; `FsSpikeWin.java` with a mode C for native full screen):
- **A, the JDK's mode:** the menu bar and Dock were hidden, but **Cmd+Tab did nothing**, and the log
  has no focus loss at all while full screen. The user is stuck until the app lets go.
- **B, a borderless frame:** Cmd+Tab worked, and the window was never minimized (checked by
  dragging another app's window over it). But the menu bar and Dock stayed visible.
- **C, native full screen:** the window went to 1920×1080 in its own Space. The menu bar appeared
  when the pointer reached the top edge. Cmd+Tab, Spaces (Ctrl+Left/Right) and Mission Control all
  worked, with no minimizing. Both test windows left by their timeout, one of them while it wasn't
  the visible Space, and came back to their old bounds. The Dock didn't appear at the bottom edge,
  most likely because a VNC pointer can't push past the edge, which is what reveals it; to check
  on a real Mac.
- **Cmd+Q reached the quit handler** in all three modes, and `cancelQuit` stopped it. (See the open
  item on Cmd+Q.)
- Side finding, for whoever uses that VM next: connecting to it with **macOS Screen Sharing**
  crash-loops WindowServer (an assertion in Core Animation's software OpenGL renderer; the VMware
  SVGA adapter has no driver), which leaves every console black until a reboot. Proxmox's own
  console doesn't trigger it.

**Monitor (D3):** the monitor of the window holding the pane (`getGraphicsConfiguration().getDevice()`
of that window). If the window moves to another monitor while full screen (Win+Shift+Arrow
on Windows), a `"graphicsConfiguration"` property listener fits it to the new device's bounds.
If the device disappears (a monitor unplugged), the window leaves full screen.

### Entering and leaving (`RdpPane`)

- `enterFullScreen()`: only while `connected` and not already full screen. It creates the window,
  moves the view, shows the placeholder card, calls `enter(device)`, focuses the view, and shows
  the bar for 3 s.
- `exitFullScreen()`: idempotent. It moves the view back, shows the view card, disposes the
  window, and focuses the view if the pane's window is active.
- **Leaves full screen automatically:** `onClosed` (a disconnect, before the message card appears),
  `closeContent` (before teardown), any dialog (below), the device disappearing, and the
  window's `windowClosing`.

**The dialog choke point.** All of `RdpPane`'s dialogs (the certificate prompt, the file-paste
consent and its follow-up, Save files… and its messages, Rename) currently take `this` or
`RdpPane.this` as parent. They switch to `dialogParent()`, which calls `exitFullScreen()` and
returns `this`. `RdpFileDownload` receives that parent, so its own dialogs follow. A grep for
`JOptionPane\.|DialogFocus\.|JFileChooser|new JDialog` in `ui/rdp` that turns up anything other
than `dialogParent()` is a review finding.

**Resize.** `deviceScale()` reads `view.getGraphicsConfiguration()` instead of the pane's: in full
screen the view can be on a monitor with a different scale. The view's existing `componentResized`
listener then requests the monitor size, debounced, and the size of the pane again on the way
back. To save one round trip the transitions call `requestRemoteResize()` directly, after layout
(`invokeLater`), rather than waiting out the 300 ms debounce.

### Keyboard

- **`RdpPane.ownsFocus` must find the pane from a moved view.** Today it walks up to an
  `RdpPane` ancestor, which a view in a `FullScreenWindow` doesn't have. Without the fix the
  Ctrl+Shift-only policy silently stops applying in full screen, and Ctrl+T would open a tab.
  It is replaced by `RdpPane.forFocusOwner(Component)` → `RdpPane` or `null`, which checks for a
  `FullScreenWindow` ancestor (via its owner pane) and then for an `RdpPane` ancestor. The
  dispatcher uses it for the policy check and for the actions aimed at the RDP pane.
- **In full screen the dispatcher claims exactly two actions:** `TOGGLE_FULL_SCREEN` and
  `SEND_CTRL_ALT_DEL`. Every other stroke, Ctrl+Shift included, goes to the remote desktop.
  `WindowManager.focusedTabPane()` is null there, so the other actions would act on a window
  that is out of sight, or act as no-ops that still swallow the key (the defect class described
  under "Global shortcuts" in `CLAUDE.md`). `SEND_CTRL_ALT_DEL` currently finds its pane through
  `grid.activeContent()`, and both actions switch to `forFocusOwner`.
- `TermAction.TOGGLE_FULL_SCREEN("toggle-full-screen", "Full Screen (RDP)", "control shift F11")`.
  It is new, so an existing `keymap.json` lacks it and gets the default; no `KeymapMigrations`
  step is needed. Elsewhere than an RDP pane it does nothing and lets the stroke through, like
  Send Ctrl+Alt+Del. Ctrl+Shift+Enter was rejected: Windows' Start search uses it for "run as
  administrator". `TermActionTest` already checks Send Ctrl+Alt+Del's stroke against
  `RdpKeyMap.jtermMayClaim`, and gains the same assertion for this one.
- **No keyboard hook (D4).** On Windows, Alt+Tab and Win stay local, and the bar keeps the
  **Win** and **Ctrl+Alt+Del** buttons.

### The full-screen bar (`ui.rdp.FullScreenBar`)

A rounded strip at the top centre, from left to right: a **pin**, the session label (the same
`user@host` text as the pane's bar), the shared-folders indicator, **Save files…** (when there
is an offer; it leaves full screen, because it opens a folder chooser), **Ctrl+Alt+Del**,
**Win**, and **Exit full screen** (its tooltip names the current shortcut). The buttons can't
take focus, like the pane's.

**Reveal, without taking pixels from the remote desktop.** While the bar is hidden, nothing is
drawn and every mouse event goes to the remote desktop. The bar appears after the pointer has
stayed **500 ms on the top row within the middle third**. It hides **1 s after the pointer
leaves it**, unless pinned. The dwell keeps a quick flick to a remote window's title bar, or to
the top of a maximized remote browser, from opening it. That state machine (pointer position +
time → hidden / revealing / shown / pinned) is a small pure class, `FullScreenReveal`, so it can
be unit-tested; the bar only feeds it events and a `Timer`.

The pin is remembered globally (`AppSettings.rdpFullScreenBarPinned`, default off). Nothing
per session.

### Entry points

- **Ctrl+Shift+F11** (above).
- **A "Full screen" button** on the pane's bottom bar, visible while connected, next to
  Ctrl+Alt+Del.
- **View → Full Screen** in the menu, enabled when the active content is a connected RDP pane.

## Persistence

`AppSettings.rdpFullScreenBarPinned` (boolean, default false), plus the new keymap entry.
Nothing is added to `RdpSessionConfig`, so the `sessions.json` schema doesn't change. "Open this
session full screen" is an open item.

## Testing

Headless (JUnit):
- `FullScreenReveal`: dwell inside and outside the middle third, a flick under 500 ms, leaving
  and coming back within 1 s, pinned.
- `TermActionTest`: the new default is Ctrl+Shift.
- The full-screen claim policy as a pure function (action, focus in full screen?) → claim.

Live, on **Windows 11 first** (the daily platform), then GNOME on Wayland and the Intel macOS VM:
1. Enter with each of the three entry points; the taskbar, dock or menu bar is hidden; the remote
   desktop resizes to the monitor; a fixed 1280×800 session is letterboxed.
2. Ctrl+T, Ctrl+W and Ctrl+Shift+T reach remote programs; Ctrl+Shift+Del and Ctrl+Shift+F11 don't.
3. Alt+Tab to the jterm window and back: the desktop stays full screen (the case that settles
   the Windows mechanism).
4. The bar: dwell opens it, a flick doesn't, pin survives a restart, the shared indicator matches
   the pane's.
5. Disconnect from the server side while full screen: back in the pane, Reconnect shown, and the
   certificate or password prompt (if any) appears in the jterm window.
6. Copy a file locally, paste remotely while full screen: full screen ends, then the consent
   prompt appears; answering it doesn't bring the prompt back.
7. Alt+F4 / the taskbar's Close on the full-screen window: back in the pane, still connected.
8. Close the pane's tab from the jterm window while full screen; detach that tab while full
   screen; Win+Shift+Arrow to the other monitor; a 100% and a 150% monitor.
9. No key stays held remotely after any transition (type in a remote Notepad straight after).
10. macOS, from the **DMG**, not a dev run (that's where the `--add-exports` has to be): the desktop
    opens in its own Space. Leaving through the green button or Ctrl+Cmd+F returns the desktop to
    the pane, with no stray window and no empty Space. Cmd+Tab and Mission Control work. Run once
    without the flag to see the logged fallback.

## Phasing

1. **Spike** (a throwaway `java` snippet, three OSes): `setFullScreenWindow` versus a borderless
   frame covering the monitor: the taskbar, menu bar or dock hidden, Alt+Tab behaviour, minimizing
   on deactivation. Settles the mechanism table.
2. **Build:** `forFocusOwner` and the dispatcher policy first (useful and testable alone), then
   `FullScreenWindow`, the pane's card and choke point, the bar, the entry points.
3. **Docs:** `docs/rdp-sessions.md` (including the T1 sentence), `docs/shortcuts.md`, a short
   `CLAUDE.md` paragraph under the RDP section (view moves, not the pane; the dialog choke
   point; the two claimed actions), and open item 7 of the session design.

## Open items (after v1)

- **All monitors.** One remote monitor per local screen: a monitor list in CONNECT and RESIZE
  (a protocol bump), the helper's Display Control layout, one window per screen or a spanning
  window, and mixed DPI.
- **Keyboard hook on Windows** (Alt+Tab and Win to the remote desktop, as in mstsc): a
  `WH_KEYBOARD_LL` hook, only while the window is in front, with the exit shortcut exempt. A
  system-wide hook in the process holding the unlocked vault needs its own design.
- **Cmd+Q on macOS (decided 2026-11-10; built 2026-11-11; first seen on a Mac 2026-11-12, File →
  Quit only).** Before, jterm
  installed no quit handler, so Cmd+Q, Dock → Quit and logging out quit at once with every session
  open. The JDK's default quit strategy is `QuitStrategy.NORMAL_EXIT`, which its Javadoc documents as
  `System.exit(0)` ("the default strategy"), so it also skipped `MainWindow`'s `windowClosing`
  teardown (window state, sessions, tunnels). That is confirmed from the public API's
  documentation only: the macOS implementation (`com.apple.eawt._AppEventHandler`) isn't in a
  Linux JDK and wasn't read. The spike showed `Desktop.setQuitHandler` sees Cmd+Q and can cancel
  it. Decided, following Mac convention (Terminal.app, iTerm2, Fusion/Parallels):
  1. **Cmd+Q quits jterm everywhere,** RDP panes and full screen included. It neither closes the
     session nor leaves full screen: macOS has Cmd+W and Ctrl+Cmd+F for those.
  2. **It confirms when any session or tunnel is open** ("Quit jterm? 2 remote desktops and 5 SSH
     sessions will be disconnected."), and quits through the same teardown as closing the window.
  3. **In full screen, full screen ends before the confirmation** (the dialog rule above).
     Cancelling leaves the desktop in its pane; Ctrl+Shift+F11 goes back.
  4. **Cmd+Q never reaches the remote desktop as Ctrl+Q.** Whether `DesktopView` also receives the
     key event alongside the quit handler wasn't measured. Check that when building, and block it
     if so.

  As built:
  - `app.AppQuitHandler` is installed through the public `Desktop.setQuitHandler`, only where
    `APP_QUIT_HANDLER` is supported (macOS); Windows and Linux are unchanged. It moves to the EDT if
    called elsewhere, ignores the repeated requests the JDK may send with the same response while
    the question is open, and answers `performQuit` or `cancelQuit` exactly once: a failure before
    the user's answer cancels, a failed teardown after "Quit" still quits. The strategy stays
    `NORMAL_EXIT`, because the teardown has already run when `performQuit` exits;
    `CLOSE_ALL_WINDOWS` would depend on which window events it sends, which can't be checked here.
  - The teardown is `MainWindow.shutdown()`, now shared by the close button, File → Quit and the
    handler, and run once.
  - File → Quit asks the same question on macOS (`AppQuitHandler.quitFromMenu`, decided
    2026-11-11); the window's red close button still closes without asking.
  - "Any session" is narrowed to **anything remote** (`app.QuitSummary`, unit-tested): remote
    desktops connected or connecting, live SSH sessions, SFTP browsers, running tunnels. Local
    terminals alone don't ask, because every window starts with one and a question that always
    appears stops being read; when it does ask, they are named too ("…, and 2 local terminals will
    be closed."). The default button is Cancel.
  - Point 4 is blocked without having been measured: on macOS `DesktopView` sends neither the
    press nor the release of a Q with Meta down (`RdpKeyMap.isMacQuitStroke`), whatever
    `rdpCmdAsCtrl` says. The Cmd press has already gone as Ctrl (or Win) by then; the question takes
    focus and the existing focus-loss `releaseAllKeys` lets it go.

  **Seen on the macOS VM (x64 DMG, Intel), before 2026-11-12:** File → Quit with remote sessions
  open asked "Quit jterm? … will be disconnected" with Quit / Cancel. jterm asks there only when the
  handler is installed (`quitFromMenu`), so `Desktop.Action.APP_QUIT_HANDLER` is supported and the
  handler is in place on macOS. On Quit, jterm exited.

  **Checked on the macOS VM (x64 DMG of 61fc771, Intel, 2026-11-16), all passed:** Cmd+Q (sent
  as noVNC's Windows key + Q) with nothing remote open quits at once; with an SSH session it asks,
  Cancel keeps everything and Quit exits; with Notepad focused in an RDP desktop it asks (so the
  handler fires while `DesktopView` has focus), and after Cancel typing reaches Notepad as plain
  text (no held Ctrl, no Ctrl+Q); from a full-screen desktop the Space is left first, the question
  appears in front, Cancel leaves the desktop in its pane and Ctrl+Shift+F11 returns; Dock → Quit
  and Log Out ask the same question. After the quits no helper process was left and `settings.json`
  was rewritten. Full screen itself was also seen working on that DMG.

  **Still to check:** the same on Apple Silicon (arm64 DMG).
- **"Open full screen"** as a per-session setting.
- **A Minimize button** on the bar (Alt+Tab covers it for now).

## Decisions

- **D1 (2026-11-10): one RDP pane, not the whole window.** The desktop goes into its own window,
  and the jterm window stays intact. Hiding the window's own chrome would mean disposing and
  rebuilding a visible decorated frame, and it isn't RDP-specific.
- **D2 (2026-11-10, revised the same day): macOS uses native full screen in its own Space.** First
  decided as "the same mechanism as the other OSes", to avoid an internal Apple API. The spike
  showed that the JDK's mode blocks Cmd+Tab and a borderless frame leaves the menu bar and Dock
  showing, so neither behaves like full screen on a Mac. The internal API is contained to one
  reflective call with a logged fallback.
- **D3 (2026-11-10): one monitor in v1**, the one the jterm window is on, using the existing
  single-monitor resize. Spanning all monitors is an open item.
- **D4 (2026-11-10): no keyboard hook in v1.** OS key combinations stay local, and the bar keeps
  the Win and Ctrl+Alt+Del buttons.
