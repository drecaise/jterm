# Components — Security

**C4 Level 3, security grouping.** Everything under `com.katmoda.jterm.security.*`,
plus its collaborators in `config` and the master-password prompt in `ui.security`.

```mermaid
--8<-- "architecture/model/generated/components-security.mmd"
```

## What is protected, and against what

jterm's threat model is **local, single-user, single-machine**. The credential vault
exists to keep saved SSH passwords off disk in plain text. It is **not** a defence
against an attacker who already has code execution as the user, and it is not a
defence against memory scraping — decrypted passwords necessarily live in the JVM
heap for as long as the app runs.

Concretely:

- Saved SSH passwords in `credentials.json` are AES-GCM ciphertexts.
- The **vault key** is random and lives on disk wrapped by a PBKDF2-derived key.
- The PBKDF2 password is the user's **master password**, which is either prompted at
  first unlock or read from the OS keyring.

## Component roles

- **`CredentialResolver`** — the collaborator that `ConnectionService` calls when it
  needs a password. Handles both the main SSH connection and jump-host credentials.
  Always runs on the EDT.
- **`VaultManager`** — the singleton front door. Ensures the vault is unlocked
  (prompting via `MasterPasswordDialog` if the keyring miss), and passes through
  reads and writes to `CredentialVault`.
- **`CredentialVault`** — the encrypted store. Load/save go through `JsonStore` so
  writes are atomic and corrupt files are preserved rather than overwritten.
- **`VaultKeys`** — PBKDF2 → HKDF-esque key derivation. Isolated for testability.
- **`MasterPasswordKeyring`** — thin wrapper around the platform keyring backend
  (see [ADR 0005](adr/0005-per-os-keyring-clis.md)). Never touches the vault
  contents, only the master password.

## Where the EDT matters

Every unlock step is intentionally synchronous on the EDT because it may need to raise
a prompt. `ConnectionService` therefore resolves credentials **before** dispatching
the SSH connect to a `SwingWorker` — that way the blocking off-EDT step never has to
call back into a modal dialog.

If the master password is already in the OS keyring, no dialog is shown; the whole
resolution completes in a single EDT tick.

## Failure modes

- **Missing keyring backend** (e.g. no `secret-tool` in `$PATH` on Linux) — the
  keyring wrapper reports a soft failure and the user is prompted for the master
  password every unlock. jterm still runs.
- **Corrupt `credentials.json`** — `JsonStore` preserves the original alongside a
  fresh empty store rather than deleting the user's data. See
  `security.CredentialVault` and `config.JsonStore` for the recovery path.
- **Master password lost** — unrecoverable by design. Saved passwords must be
  re-entered; the vault file can be deleted and it will be recreated on next launch.

## See also

- Source: `src/main/java/com/katmoda/jterm/{security,config}/`.
- [SSH auth & vault](../ssh-auth-and-vault.md) — the user-facing view.
- [ADR 0003](adr/0003-exclude-java-keyring-linux-dbus.md) — why the Linux dbus-java
  backend is excluded.
