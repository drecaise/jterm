/*
 * jterm-rdp-helper — names resolved before the lockdown, and FreeRDP's TCP connect hook (see
 * netroute.h).
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "netroute.h"

#include <stdio.h>
#include <string.h>

#include <freerdp/settings.h>
#include <freerdp/transport_io.h>
#include <winpr/string.h>
#include <winpr/synch.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#endif

#define MAX_NAMES 16

typedef struct
{
	char name[256];
	int count;
	char addrs[NETROUTE_MAX_ADDRS][NETROUTE_ADDR_LEN];
} Name;

static CRITICAL_SECTION g_lock;
static BOOL g_lock_ready = FALSE;
static Name g_names[MAX_NAMES];
static int g_name_count = 0;
static pTCPConnect g_default_connect = NULL;

BOOL netroute_is_literal(const char* host)
{
	if (!host || !*host)
		return FALSE;
	char buf[NETROUTE_ADDR_LEN];
	size_t n = strlen(host);
	if (host[0] == '[' && n >= 2 && host[n - 1] == ']')
	{
		if (n - 2 >= sizeof(buf))
			return FALSE;
		memcpy(buf, host + 1, n - 2);
		buf[n - 2] = '\0';
		host = buf;
	}
	unsigned char addr[16];
	return inet_pton(AF_INET, host, addr) == 1 || inet_pton(AF_INET6, host, addr) == 1;
}

BOOL netroute_is_special(const char* host)
{
	if (!host)
		return FALSE;
	return host[0] == '/' || host[0] == '|' || strncmp(host, "vsock://", 8) == 0;
}

/* Under g_lock. */
static Name* find_name(const char* name)
{
	for (int i = 0; i < g_name_count; i++)
		if (_stricmp(g_names[i].name, name) == 0)
			return &g_names[i];
	return NULL;
}

/* Under g_lock. Replaces an existing entry for the same name. */
static void remember(const char* name, char addrs[][NETROUTE_ADDR_LEN], int count)
{
	if (strlen(name) >= sizeof(g_names[0].name) || count <= 0)
		return;
	Name* e = find_name(name);
	if (!e)
	{
		if (g_name_count >= MAX_NAMES)
			return;
		e = &g_names[g_name_count++];
	}
	memset(e, 0, sizeof(*e));
	memcpy(e->name, name, strlen(name) + 1); /* fits: checked above */
	e->count = count > NETROUTE_MAX_ADDRS ? NETROUTE_MAX_ADDRS : count;
	for (int i = 0; i < e->count; i++)
		memcpy(e->addrs[i], addrs[i], NETROUTE_ADDR_LEN);
}

/* Resolves here, with the system resolver: only before the lockdown. */
static int resolve_locally(const char* name, char addrs[][NETROUTE_ADDR_LEN], int max)
{
	struct addrinfo hints = { 0 };
	hints.ai_family = AF_UNSPEC;
	hints.ai_socktype = SOCK_STREAM;
	struct addrinfo* result = NULL;
	if (getaddrinfo(name, NULL, &hints, &result) != 0 || !result)
		return 0;
	int n = 0;
	/* In the resolver's order (RFC 6724 sorting), as FreeRDP's own lookup would use them. */
	for (struct addrinfo* ai = result; ai && n < max; ai = ai->ai_next)
	{
		if (ai->ai_family != AF_INET && ai->ai_family != AF_INET6)
			continue;
		if (getnameinfo(ai->ai_addr, (socklen_t)ai->ai_addrlen, addrs[n], NETROUTE_ADDR_LEN, NULL, 0,
		                NI_NUMERICHOST) != 0)
			continue;
		int dup = 0;
		for (int i = 0; i < n; i++)
			dup |= strcmp(addrs[i], addrs[n]) == 0;
		if (!dup)
			n++;
	}
	freeaddrinfo(result);
	return n;
}

static void prepare_name(const char* name)
{
	if (!name || !*name || netroute_is_literal(name) || netroute_is_special(name))
		return;
	char addrs[NETROUTE_MAX_ADDRS][NETROUTE_ADDR_LEN];
	const int n = resolve_locally(name, addrs, NETROUTE_MAX_ADDRS);
	if (n == 0)
	{
		fprintf(stderr, "jterm-rdp-helper: could not resolve %s now; jterm will be asked when it is "
		                "needed\n", name);
		return;
	}
	EnterCriticalSection(&g_lock);
	remember(name, addrs, n);
	LeaveCriticalSection(&g_lock);
}

/* FreeRDP's TCPConnect: every TCP connection it makes (the desktop, a redirect target, the RD
 * Gateway's HTTP, WebSocket and ARM routes) arrives here with the name it wants. Numeric addresses
 * go to FreeRDP's own connect unchanged; a name is replaced by its cached addresses, tried in order;
 * a name never seen is resolved by jterm; anything that isn't a TCP host is refused. */
static int j_tcp_connect(rdpContext* context, rdpSettings* settings, const char* hostname, int port,
                         DWORD timeout)
{
	if (netroute_is_special(hostname))
	{
		fprintf(stderr, "jterm-rdp-helper: refused to connect to \"%s\": not a network host\n",
		        hostname);
		freerdp_set_last_error_if_not(context, FREERDP_ERROR_CONNECT_FAILED);
		return -1;
	}
	if (!hostname || !*hostname || netroute_is_literal(hostname))
		return g_default_connect(context, settings, hostname, port, timeout);

	char addrs[NETROUTE_MAX_ADDRS][NETROUTE_ADDR_LEN];
	int n = 0;
	EnterCriticalSection(&g_lock);
	Name* e = find_name(hostname);
	if (e)
	{
		n = e->count;
		memcpy(addrs, e->addrs, sizeof(addrs[0]) * (size_t)n);
	}
	LeaveCriticalSection(&g_lock);
	if (n == 0)
	{
		/* A name first seen now: a redirect to another host (an RDS connection broker sends the
		 * session host's name), or one that didn't resolve before the lockdown. */
		fprintf(stderr, "jterm-rdp-helper: asking jterm to resolve %s\n", hostname);
		n = netroute_ask_jterm(hostname, addrs, NETROUTE_MAX_ADDRS);
		/* Only numeric answers are used: a name here would loop back into FreeRDP's resolver. */
		int kept = 0;
		for (int i = 0; i < n; i++)
			if (netroute_is_literal(addrs[i]))
				memmove(addrs[kept++], addrs[i], NETROUTE_ADDR_LEN);
		n = kept;
		if (n > 0)
		{
			EnterCriticalSection(&g_lock);
			remember(hostname, addrs, n);
			LeaveCriticalSection(&g_lock);
		}
	}
	if (n == 0)
	{
		freerdp_set_last_error_if_not(context, FREERDP_ERROR_DNS_NAME_NOT_FOUND);
		return -1;
	}
	for (int i = 0; i < n; i++)
	{
		const int fd = g_default_connect(context, settings, addrs[i], port, timeout);
		if (fd >= 0)
		{
			/* An earlier address that failed left its error behind; this one worked. */
			if (i > 0)
				freerdp_set_last_error(context, FREERDP_ERROR_SUCCESS);
			return fd;
		}
	}
	return -1;
}

BOOL netroute_prepare(rdpContext* context)
{
	if (!g_lock_ready)
	{
		InitializeCriticalSection(&g_lock);
		g_lock_ready = TRUE;
	}
	rdpSettings* s = context->settings;
	/* Through an RD Gateway the desktop's name goes to the gateway, which resolves it on its side
	 * (often a name only known inside that network); FreeRDP never dials it. */
	if (freerdp_settings_get_bool(s, FreeRDP_GatewayEnabled))
		prepare_name(freerdp_settings_get_string(s, FreeRDP_GatewayHostname));
	else
		prepare_name(freerdp_settings_get_string(s, FreeRDP_ServerHostname));

	/* The callbacks are stored in the rdp object and applied again to every transport FreeRDP
	 * creates later (rdp_reset on a redirect or reconnect), so this is done once. */
	const rdpTransportIo* current = freerdp_get_io_callbacks(context);
	if (!current || !current->TCPConnect)
		return FALSE;
	rdpTransportIo io = *current;
	g_default_connect = io.TCPConnect;
	io.TCPConnect = j_tcp_connect;
	return freerdp_set_io_callbacks(context, &io);
}

size_t netroute_ports(rdpContext* context, uint16_t* ports, size_t max)
{
	rdpSettings* s = context->settings;
	if (max == 0)
		return 0;
	/* Through a gateway only the gateway is dialled (HTTP, WebSocket and the legacy RPC route all
	 * use its port); the desktop is reached inside the gateway's tunnel. A redirect keeps the port
	 * (FreeRDP 3.33 has no redirect port), so it needs no rule of its own. */
	if (freerdp_settings_get_bool(s, FreeRDP_GatewayEnabled))
	{
		const UINT32 p = freerdp_settings_get_uint32(s, FreeRDP_GatewayPort);
		ports[0] = (uint16_t)(p ? p : 443);
	}
	else
	{
		const UINT32 p = freerdp_settings_get_uint32(s, FreeRDP_ServerPort);
		ports[0] = (uint16_t)(p ? p : 3389);
	}
	return 1;
}
