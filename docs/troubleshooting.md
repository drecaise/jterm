# Troubleshooting

## SSH agent not found / key auth fails

- Confirm an agent is running and holds your keys: `ssh-add -l`.
- On **Linux/macOS**, jterm reads the agent socket from `$SSH_AUTH_SOCK`. If you launched jterm
  from the desktop (not a shell), that variable may be unset — start it from a terminal, or make
  sure your login shell exports it.
- On **Windows**, both the native **OpenSSH** agent (named pipe) and **PuTTY Pageant** are
  supported; make sure the relevant one is running with your keys loaded.
- Check what the agent is offering via **SSH → Show Agent Keys…**.

See [SSH auth & vault](ssh-auth-and-vault.md) for the full authentication order.

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

## Linux: wrong icon / missing from the dash (running the bare jar)

GNOME Shell matches a window to a `.desktop` file by its `WM_CLASS` rather than using the
window's own icon. jterm sets its `WM_CLASS` to `jterm`; you just need a matching desktop entry.
Run the helper script after building:

```bash
bash packaging/install-desktop-integration.sh
```

It installs the app icon and a `jterm.desktop` launcher (with `StartupWMClass=jterm`) into your
user directories. **Re-run it if you move or rebuild the jar to a different path.** (The Flatpak
already integrates with the desktop, so this is only needed when running the bare `.jar`.)

## macOS: "jterm can't be opened" on first launch

The `.dmg` is currently unsigned, so Gatekeeper blocks the first launch. **Right-click** the app
and choose **Open** to run it the first time; subsequent launches work normally.

## Still stuck?

Report issues at <https://github.com/drecaise/jterm/issues>.
