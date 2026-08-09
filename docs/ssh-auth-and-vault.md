# SSH authentication & the credential vault

## Authentication order

When you open an SSH session, jterm attempts authentication in this order:

1. **Public key**
    - **ssh-agent** identities (with agent forwarding, if enabled), then
    - **on-disk keys** — the *Key file* set on the session, or your `~/.ssh` identities.
2. **Password** — a saved one if you enabled *Password auth* for that session, and otherwise
   **whatever you type at the prompt** (see below).

You don't choose a method explicitly; jterm offers them in turn and the server picks the first
that succeeds.

### If key authentication fails

When ssh-agent and key authentication are both rejected, jterm doesn't give up — it **asks you
for a password** and carries on with the *same* connection.

![SSH password prompt](img/ssh-password-prompt.png)

This covers the everyday cases where a connect used to just fail:

- your agent isn't running, or doesn't hold a key for that host;
- the server has no `authorized_keys` entry for you yet;
- a **saved password is out of date** — the prompt reappears with *"Authentication failed — try
  again."* instead of dropping you back to an error dialog.

You get up to **three** attempts per host, matching OpenSSH. **Cancel** gives up immediately — the
connect fails rather than asking again. Tick **Remember this password** to
save it to the vault, and jterm enables *Password auth* + *Save password* on the session for you —
the next connect won't ask. The password is only saved once it has actually worked, so a typo is
never stored.

!!! note "Quick connections can't remember"
    A [Quick Connect](sessions-sidebar.md#quick-connect) target isn't a saved session, so there is
    nothing for a password to belong to — the prompt appears as usual but without *Remember this
    password*. Save the host as an [SSH session](ssh-sessions.md) if you want its password kept.

!!! note "No prompt on key-only servers"
    jterm only offers a method the server advertises. If the server has
    `PasswordAuthentication no`, there is nothing to prompt for and the connect fails as before —
    you won't be asked for a password that could never work.

To turn the fallback off entirely, clear **Preferences → General → Ask for a password if key auth
fails**. Connects then fail immediately when key auth is rejected.

### Two-factor and other challenges

Servers using PAM, one-time passwords or 2FA authenticate with **keyboard-interactive** rather
than a plain password: the server sends one or more questions and jterm shows them as typed.
Replies are masked unless the server asks for them to be echoed.

![Keyboard-interactive challenge](img/ssh-challenge-prompt.png)

Jump hosts are prompted for individually, each named `user@host`, so it's always clear which hop
in the chain is asking.

### ssh-agent

For agent auth you need a running **ssh-agent** holding your keys (`ssh-add -l` to check).

- On **Linux/macOS** jterm uses the agent socket from `$SSH_AUTH_SOCK`.
- On **Windows** both the native **OpenSSH** agent (named pipe) and **PuTTY Pageant** are
  supported (and used together if both are running).

To see which identities the agent is offering, use **SSH → Show Agent Keys…**.

![Show Agent Keys dialog](img/agent-keys.png)

## The credential vault

Saved SSH **passwords** and **key passphrases** are never written in plaintext. They are stored
**AES-GCM encrypted** in `credentials.json`, protected by a **master password**.

- The first time you save a secret, jterm asks you to **create a master password**.
- On later launches the vault is unlocked — transparently if your OS keyring is available
  (see below), otherwise by **prompting** for the master password once per launch.

![Master password prompt](img/master-password-prompt.png)

### Saving passwords and passphrases

In the session dialog, enable **Password auth** and type a password to have it saved to the
vault. For encrypted keys, jterm can remember the **key passphrase** too. In both cases:

- A **blank** secret field keeps whatever is already saved.
- jterm tries a saved passphrase first and only prompts if it fails.

You can also save a password without opening the session dialog at all: tick **Remember this
password** on the connect-time prompt described [above](#if-key-authentication-fails).

You can also set **default** passwords/passphrases at the folder or global level — see
[Preferences → Session Defaults](preferences.md).

### OS keyring (remembering the master password)

To avoid typing the master password every launch, jterm stores it in your operating system's
keyring, using native per-OS tooling:

| OS | Keyring backend |
|----|-----------------|
| Linux | Secret Service (GNOME Keyring / KWallet) via the `secret-tool` CLI (`libsecret` / `libsecret-tools`) |
| macOS | login Keychain via the built-in `security` CLI |
| Windows | Windows Credential Manager |

If no keyring is available (common on minimal Linux setups), nothing breaks — you're simply
prompted for the master password at launch instead. See
[Troubleshooting](troubleshooting.md#vault-and-master-password).

## Host-key verification

jterm checks host keys against `~/.ssh/known_hosts` using **trust-on-first-use (TOFU)**:

- The **first** time you connect to a host, you're asked to confirm its key; accepting records
  it in `known_hosts`.
- If a host's key **later changes**, you get a warning (a possible sign of a man-in-the-middle —
  or just a rebuilt server).

![Host key confirmation prompt](img/host-key-prompt.png)

To trust first-seen hosts without prompting, enable **Preferences → General → Auto-accept new
host keys**. You're still warned about *changed* keys.
