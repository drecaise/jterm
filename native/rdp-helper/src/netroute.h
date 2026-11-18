/*
 * jterm-rdp-helper — where FreeRDP's TCP connections may go, and how names get resolved.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Design: docs/superpowers/specs/2026-11-11-rdp-helper-sandbox-design.md, "Name resolution before
 * lockdown" and decision D3. The sandbox leaves no resolver (no resolv.conf, no Unix or UDP
 * socket), so the names a session needs are resolved before it, and FreeRDP's TCP connect hook
 * dials the cached numeric addresses. A name first seen later (a server redirect to another host,
 * as an RDS connection broker does) is resolved by jterm on the helper's behalf.
 */
#ifndef JTERM_RDP_NETROUTE_H
#define JTERM_RDP_NETROUTE_H

#include <stddef.h>
#include <stdint.h>

#include <freerdp/freerdp.h>

#define NETROUTE_MAX_ADDRS 16
#define NETROUTE_ADDR_LEN 64 /* a numeric IPv6 address with scope fits */

/* Resolves the desktop's host (and the gateway's, when one is used) and installs the TCP connect
 * hook. Call after CONNECT is applied and before the lockdown. A name that fails to resolve here is
 * not an error yet: the hook asks jterm when FreeRDP gets to it, and reports FreeRDP's usual "name
 * not found" if that fails too. */
BOOL netroute_prepare(rdpContext* context);

/* The TCP ports this session connects to (desktop, gateway), for the sandbox's port rule. Returns
 * how many were written. */
size_t netroute_ports(rdpContext* context, uint16_t* ports, size_t max);

/* Provided by the main program: asks jterm to resolve `name` (RESOLVE / RESOLVE_REPLY) and waits.
 * Fills up to `max` numeric addresses and returns how many (0: not resolved, or jterm is gone). */
int netroute_ask_jterm(const char* name, char addrs[][NETROUTE_ADDR_LEN], int max);

/* Pure helpers. */

/* Whether `host` is a numeric IPv4 or IPv6 address (brackets allowed around IPv6). */
BOOL netroute_is_literal(const char* host);

/* Whether `host` names something other than a TCP host: FreeRDP's default connect treats a leading
 * '/' as a Unix socket path, a leading '|' as "the port number is an open descriptor", and
 * "vsock://" as a VM socket. None of those is ever right for jterm. */
BOOL netroute_is_special(const char* host);

#endif
