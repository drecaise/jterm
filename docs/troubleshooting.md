# Troubleshooting

## Menus and the sidebar are tiny on a high-resolution display

Raise **UI scale** on the [Appearance tab](settings.md#appearance) of the settings dialog — it enlarges
the sidebar, tabs, menus and dialogs together, and takes effect after you restart jterm. If only
the *terminal* text is too small, that's a separate setting: see
[Terminal Settings](settings.md#terminal-settings), or press ++ctrl+num-plus++ to enlarge the
focused pane straight away.

## SSH agent not found / key auth fails

- Confirm an agent is running and holds your keys: `ssh-add -l`.
- On **Linux/macOS**, jterm reads the agent socket from `$SSH_AUTH_SOCK`. If you launched jterm
  from the desktop (not a shell), that variable may be unset — start it from a terminal, or make
  sure your login shell exports it.
- On **Windows**, both the native **OpenSSH** agent (named pipe) and **PuTTY Pageant** are
  supported; make sure the relevant one is running with your keys loaded.
- Check what the agent is offering via **SSH → Show Agent Keys…**.

If key auth is rejected, jterm falls back to asking for a **password** rather than failing, so a
misconfigured agent is an inconvenience rather than a blocker. If you get that prompt when you
expected the agent to work, the agent is the thing to investigate.

See [SSH auth & vault](ssh-auth-and-vault.md) for the full authentication order.

## "Authentication failed for user@host"

Every method jterm and the server share was tried and rejected, or a password prompt was
cancelled. Things to check, in order:

- Is the **username** right? The message names the user actually offered — a session inheriting a
  folder/global default may not be using the one you expect.
- Does the server allow password auth at all? If `PasswordAuthentication no` and
  `KbdInteractiveAuthentication no` are set in `sshd_config`, jterm never offers you a prompt and
  key auth is the only way in.
- Was the fallback turned off? Check **Settings → General → Ask for a password if key auth
  fails**.
- For a **jump host** chain, the message names the hop that failed — an early hop failing means
  the target was never reached.

## "Host key has changed" warning

This means the key presented by the host differs from the one recorded in `~/.ssh/known_hosts`.
It can be a legitimately rebuilt/re-provisioned server — or a sign of interception. If you're
**sure** the change is expected, remove the host's old entry from `known_hosts` and reconnect to
re-trust it. If you're not sure, **don't connect** until you've verified the new key out-of-band.

## Vault and master password

- **Prompted for the master password every launch?** Your OS keyring isn't available, so jterm
  can't remember it. On **Linux** install and enable a Secret Service keyring (GNOME Keyring or
  KWallet) plus the `secret-tool` CLI (`libsecret` / `libsecret-tools`). On **macOS** the login
  Keychain is used automatically; on **Windows** the Credential Manager is. Everything still
  works without a keyring — you just type the master password once per launch.
- **Forgot the master password?** Saved secrets can't be recovered (that's the point of the
  encryption). Delete `credentials.json` to reset the vault; you'll re-enter your passwords and
  set a new master password.

See [Configuration files](config-files.md) for where these files live.

## A new release is out but jterm hasn't mentioned it

**Help → Check for Updates…** answers straight away: it ignores every throttle below, always
reports a result — including "you're running the latest version" and any network error — and works
even with the automatic check switched off.

A lag on the automatic check is normal. It is deliberately unhurried so that every jterm install
doesn't hit GitHub at once — see [Update checks](settings.md#update-checks):

- Starting jterm checks only if the last attempt was more than **4 hours** ago; left running, it
  looks again every 20–28 hours. A release published shortly after a check isn't announced until
  the next one comes due.
- A due check still waits **30–180 seconds** after startup rather than firing immediately.
- A failed check — no network, GitHub unreachable or rate-limiting — is **silent by design**, and
  still counts as an attempt, so jterm backs off instead of retrying in a loop.

If it stays quiet for longer than that, check the two things that suppress it entirely:

- **Settings → General → Check for updates** is off.
- You chose **Skip This Version** for that release. It silences exactly that version and nothing
  else; clear `skippedUpdateVersion` in [`settings.json`](config-files.md#update-check-keys-in-settingsjson)
  to be reminded about it again.

## Linux: wrong icon / missing from the dash (running the bare jar)

GNOME Shell matches a window to a `.desktop` file by its `WM_CLASS` rather than using the
window's own icon. jterm sets its `WM_CLASS` to `jterm`; you just need a matching desktop entry.
Run the helper script after building:

```bash
bash packaging/install-desktop-integration.sh
```

It installs the app icon and a `jterm.desktop` launcher (with `StartupWMClass=jterm`) into your
user directories. **Re-run it if you move or rebuild the jar to a different path.** (The Flatpak
and the RPM already install matching desktop entries, so this is only needed when running the
bare `.jar`.)

## macOS: "jterm can't be opened" on first launch

The `.dmg` is currently unsigned, so Gatekeeper blocks the first launch. **Right-click** the app
and choose **Open** to run it the first time; subsequent launches work normally.

## Still stuck?

Report issues at <https://github.com/drecaise/jterm/issues>.
