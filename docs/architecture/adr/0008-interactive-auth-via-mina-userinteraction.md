# ADR 0008: Interactive password fallback via MINA's `UserInteraction`

**Status:** Accepted
**Date:** 2026-08-09

## Context

Until 1.6.0 every credential was resolved on the EDT *before* the SSH connect was handed to a
`SwingWorker`. That kept the worker free of UI, but it also meant the connect had exactly one shot
with whatever it was given. A session relying on ssh-agent had **no fallback**: if the agent wasn't
running, held no key for the host, or the server rejected everything it offered, the connect died
with MINA's raw `No more authentication methods available`. A *stale saved password* failed the
same way, with no chance to correct it.

Every other SSH client asks for a password at that point. Three ways to get there:

1. **Reconnect-and-retry.** Catch the auth failure, prompt, dial the host again with the password.
   Simple and keeps all prompting on our own threads — but it costs a second TCP connect and key
   exchange, re-authenticates every jump hop, can't answer keyboard-interactive challenges at all,
   and has no way to know whether the server even *offers* password auth (so it would prompt
   pointlessly against key-only servers).
2. **Lazy `PasswordIdentityProvider`.** Hand MINA an `Iterable<String>` that prompts on `next()`.
   Correct method negotiation, single connection — but it only covers plain `password` auth, and
   it is consulted on a MINA NIO thread just like option 3.
3. **`UserInteraction`.** MINA's designed extension point for exactly this.
   `UserAuthPassword.resolveAttemptedPassword` consults it once the registered password identities
   are exhausted (up to `PASSWORD_PROMPTS`, default 3 — OpenSSH's `NumberOfPasswordPrompts`), and
   `UserAuthKeyboardInteractive.getUserResponses` routes server challenges to it.

## Decision

Implement `terminal.ssh.JtermUserInteraction` and install it on the client in `SshConnect.open`.
`SshConnect.InteractiveAuth` is the headless SPI it delegates to; `security.CredentialResolver`
implements that SPI with Swing prompts marshalled to the EDT.

MINA's default client method order is **publickey → keyboard-interactive → password**, and it only
runs a method the server advertises. Two desirable properties therefore come for free rather than
being coded:

- The fallback fires **only after** agent and key auth are exhausted.
- A key-only server (`PasswordAuthentication no`) produces **no prompt at all** — there is nothing
  to ask for.

A saved password is also still tried first, because MINA registers it as a password identity and
`AUTO_DETECT_PASSWORD_PROMPT` lets keyboard-interactive auto-answer a single masked "…password…"
prompt with it. Only when that is rejected does the user see a dialog, and then with an error line.

## Consequences

- **One connection, no redial.** Jump-host chains keep their upstream sessions; only the hop being
  authenticated is affected.
- **Keyboard-interactive comes along for free** — PAM, one-time passwords and 2FA now work, which
  option 1 could not have delivered.
- **Prompts run on a MINA NIO worker thread** and block it for as long as the dialog is open. This
  is the real cost of the decision. Two mitigations: `SshConnect` raises the client's `NIO_WORKERS`
  floor so a jump host's port-forward still has a worker, and `SshConnect.awaitAuth` replaces
  `AuthFuture.verify(AUTH_TIMEOUT)` with a sliced wait that gives up only on **server silence**,
  never on user think-time. Without the latter, the 30 s auth timeout would kill a connect while
  the user was still typing.
- **The prompt cap had to be re-imposed.** `PASSWORD_PROMPTS` is enforced *per auth method*, so a
  server offering both keyboard-interactive and password would ask six times.
  `JtermUserInteraction` counts prompts per hop and stops at three.
- **Cancel had to be latched.** `UserAuthKeyboardInteractive` reads a null response as "no answers
  *this round*" and re-challenges until its budget is spent, so cancelling re-showed the dialog
  three times on a stock PAM-enabled sshd. (`UserAuthPassword` stops on the first null, which is
  why a password-only server never showed it.) `JtermUserInteraction` now treats an empty answer
  as "the user gave up" for the whole hop, across both methods.
- **The "worker never prompts" invariant is gone**, and the documentation says so. The invariant
  that survives is the one that actually matters: Swing is only ever touched on the EDT. Encrypted
  `~/.ssh` default identities already violated the old wording — their passphrase is requested
  lazily *during* auth — so this decision makes an existing exception explicit rather than
  introducing a new class of behaviour.
- A password is written to the vault only after the hop authenticates, so a typo is never stored.
- `AppSettings.promptPasswordOnAuthFailure` (default on) can disable the whole path.

## See also

- [Components — Terminal](../components-terminal.md#interactive-fallback)
- [Runtime views — SSH connect](../runtime-views.md#ssh-connect)
- [ADR 0002](0002-custom-jdk-agent-factory.md) — the other place jterm substitutes its own
  implementation for a MINA default.
