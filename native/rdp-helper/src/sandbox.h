/*
 * jterm-rdp-helper — restricting what the helper itself can do.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Design: docs/superpowers/specs/2026-11-11-rdp-helper-sandbox-design.md. The helper parses bytes
 * from a server that may be hostile; these functions limit what code running inside it after a
 * memory-safety bug could reach.
 */
#ifndef JTERM_RDP_SANDBOX_H
#define JTERM_RDP_SANDBOX_H

#include <stddef.h>
#include <stdint.h>

/* Layer 1, the first thing main does, before any descriptor of its own exists and before the
 * password arrives: no core dumps or crash reports (they would hold the password), not dumpable or
 * traceable by the user's other processes (Linux), and no descriptor inherited from the parent
 * beyond stdin, stdout and stderr. Each step is best effort: a failure is logged, never fatal. */
void sandbox_early(void);

#ifdef _WIN32
/* Windows step W2 (launcher_win.c), first thing in main after sandbox_early: unless this process
 * is already the restricted child, start the helper again with a restricted token, hand it the
 * standard handles, wait, and return its exit code, which main returns. Returns -1 when this
 * process should carry on as the helper itself: it is the child, or the relaunch couldn't be set
 * up (logged; the helper then runs with W1 alone). */
int sandbox_relaunch_restricted(void);

/* Whether this process is that restricted child (its main thread impersonates the start-up token
 * until sandbox_lockdown reverts it). */
int sandbox_is_restricted_child(void);

/* In the restricted child, runs fn with the start-up impersonation dropped (the lockdown token
 * applies) and resumes it afterwards; anywhere else, or if the start-up token can't be reopened,
 * simply runs fn. For the calls that work under the lockdown token but not the start-up one, as
 * measured on GitHub's Windows Server runner: seeding OpenSSL's random generator through
 * CryptoAPI (NTE_KEYSET_NOT_DEF; FreeRDP's context creation fails with it) and Media Foundation
 * (no H.264 or AAC decoder found). */
void sandbox_without_startup_token(void (*fn)(void* arg), void* arg);
#endif

/* Whether this platform's lockdown can't resolve names afterwards, so the helper must resolve them
 * before (netroute.h). True where sandbox_lockdown does something. */
int sandbox_needs_early_resolution(void);

/* Layer 2, the lockdown point: after CONNECT is applied and every library is loaded, before
 * freerdp_connect parses the first byte from the server, and before any other thread exists (the
 * restrictions are inherited by threads started later). Irreversible. On Linux: no new privileges,
 * no file can be written (RLIMIT_FSIZE), Landlock (read-only access to the CA stores, the crypto
 * policy, the time zone and the processor count, nothing else; TCP connect only to `ports`; no
 * abstract Unix sockets or signals outside the process), and a seccomp deny-list (no exec, ptrace,
 * keyring, non-TCP sockets, ...). Each part is best effort, as the kernel allows (decision D1).
 *
 * Logs one line that jterm parses, e.g.
 *   jterm-rdp-helper: sandbox level=full landlock=6 net=1 scope=1 seccomp=1 fsize=1 nnp=1
 * level is full (files, network and system calls all restricted), partial, or none (also on the
 * platforms without a lockdown yet). */
void sandbox_lockdown(const uint16_t* ports, size_t nports);

/* With JTERM_HELPER_SELFTEST=1 in the environment (jterm never sets it; smoke-test.py does): after
 * the lockdown, attempt each thing it should prevent and log one line per attempt,
 *   jterm-rdp-helper: selftest <what> blocked|ALLOWED (<errno text>)
 * Harmless when allowed: nothing is written, sent or kept. */
void sandbox_selftest(void);

#endif
