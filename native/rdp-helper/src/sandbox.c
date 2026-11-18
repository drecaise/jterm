/*
 * jterm-rdp-helper — restricting what the helper itself can do (see sandbox.h).
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#if defined(__linux__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE /* O_PATH */
#endif
#include "sandbox.h"

#include <stdio.h>
#include <stdlib.h>

#ifdef _WIN32
#include <string.h>
#include <windows.h>
#include <tlhelp32.h>
#include <wincred.h>
#else
#include <errno.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>
#ifdef __linux__
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <netinet/in.h>
#include <signal.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#endif
#ifdef __APPLE__
#include <fcntl.h>
#include <mach-o/dyld.h>
#include <mach/mach.h>
#include <netinet/in.h>
#include <servers/bootstrap.h>
#include <signal.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#endif
#endif

#ifdef __APPLE__
/* The helper's folder (sandbox_early), readable after the lockdown (sandbox_lockdown). */
static char g_helper_dir[1024];
#endif

#ifdef _WIN32

/* A crash ends the process here, before Windows Error Reporting sees it: WER would write a dump of
 * a process that holds the RDP password (to the report queue under %LOCALAPPDATA%, or wherever a
 * LocalDumps policy points, and possibly upload it). jterm notices the exit through the pipe either
 * way. Not covered: __fastfail (the /GS check, CFG violations) goes to WER directly and can't be
 * intercepted from inside the process; only excluding the program in the registry would, and the
 * helper writes nothing. */
static LONG WINAPI die_without_report(EXCEPTION_POINTERS* info)
{
	static const char msg[] = "jterm-rdp-helper: crashed\n";
	DWORD written = 0;
	(void)WriteFile(GetStdHandle(STD_ERROR_HANDLE), msg, sizeof(msg) - 1, &written, NULL);
	TerminateProcess(GetCurrentProcess(),
	                 info && info->ExceptionRecord ? info->ExceptionRecord->ExceptionCode : 3);
	return EXCEPTION_EXECUTE_HANDLER;
}

#ifdef _MSC_VER
/* The CRT's default reaction to an invalid argument is to raise a report to WER ("Watson"). Ending
 * the process instead keeps the outcome (the default handler doesn't return either). */
static void invalid_parameter(const wchar_t* expr, const wchar_t* func, const wchar_t* file,
                              unsigned int line, uintptr_t reserved)
{
	(void)expr;
	(void)func;
	(void)file;
	(void)line;
	(void)reserved;
	TerminateProcess(GetCurrentProcess(), 3);
}
#endif

void sandbox_early(void)
{
	/* No "the program stopped working" box and no "insert a disk" prompt: nobody would see them
	 * (the helper has no window) and the process would hang instead of exiting. */
	SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
	SetUnhandledExceptionFilter(die_without_report);
#ifdef _MSC_VER
	_set_invalid_parameter_handler(invalid_parameter);
	/* abort() reports to WER unless told not to. */
	_set_abort_behavior(0, _WRITE_ABORT_MSG | _CALL_REPORTFAULT);
#endif
	/* Inherited handles: Java starts the helper with bInheritHandles, so any inheritable handle in
	 * the JVM arrives here too, and a process can't enumerate its handles to close them. The JDK
	 * opens its own handles non-inheritable; the launcher of design step W2 fixes the rest with an
	 * explicit handle list. */
}

#endif

#ifdef _WIN32

/* ------------------------------------------------------------------------- Windows: step W1 */

/* W1 restricts what can be restricted from inside a running process (design "Layer 3a", spike
 * 2026-11-13). It does NOT stop reading the user's files, Credential Manager or the microphone, or
 * connecting anywhere: a low-integrity process can do all of that (measured). Those need a
 * restricted token from the start (step W2). Hence level=partial at best. */

int sandbox_needs_early_resolution(void)
{
	/* W1 keeps the resolver working; under W2's restricted token the DNS client service may refuse
	 * the call, so names are resolved before the lockdown, as on Linux (netroute.h). */
	return sandbox_is_restricted_child();
}

/* Kept open for the life of the process: closing it would kill us (KILL_ON_JOB_CLOSE). */
static HANDLE g_job = NULL;
/* jterm's process id, for the self-test: taken before the lockdown, after which the process
 * snapshot no longer lists the parent. */
static DWORD g_parent = 0;

/* One process only (no child process, so no other program), killed with its job, no Windows Error
 * Reporting for an unhandled exception, and no reach into the desktop: clipboard, global atoms,
 * other processes' windows (USER handles), desktops, display and system settings, logoff. */
static DWORD parent_process_id(void);

static int join_job(void)
{
	HANDLE job = CreateJobObjectW(NULL, NULL);
	if (!job)
		return 0;
	JOBOBJECT_EXTENDED_LIMIT_INFORMATION ext = { 0 };
	ext.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_ACTIVE_PROCESS |
	                                       JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE |
	                                       JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION;
	ext.BasicLimitInformation.ActiveProcessLimit = 1;
	JOBOBJECT_BASIC_UI_RESTRICTIONS ui = { 0 };
	ui.UIRestrictionsClass = JOB_OBJECT_UILIMIT_DESKTOP | JOB_OBJECT_UILIMIT_DISPLAYSETTINGS |
	                         JOB_OBJECT_UILIMIT_EXITWINDOWS | JOB_OBJECT_UILIMIT_GLOBALATOMS |
	                         JOB_OBJECT_UILIMIT_HANDLES | JOB_OBJECT_UILIMIT_READCLIPBOARD |
	                         JOB_OBJECT_UILIMIT_SYSTEMPARAMETERS | JOB_OBJECT_UILIMIT_WRITECLIPBOARD;
	if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &ext, sizeof(ext)) ||
	    !SetInformationJobObject(job, JobObjectBasicUIRestrictions, &ui, sizeof(ui)) ||
	    !AssignProcessToJobObject(job, GetCurrentProcess()))
	{
		fprintf(stderr, "jterm-rdp-helper: job object: error %lu\n", GetLastError());
		CloseHandle(job);
		return 0;
	}
	g_job = job;
	return 1;
}

/* Run-time mitigation policies. Win32k lockdown can't be switched on in a running process (the
 * spike: the call fails), so it is not here. No dynamic code: Media Foundation's software H.264
 * and AAC decoders, the only code loaded late, were measured to work with it. Returns how many of
 * the four took. */
static int apply_mitigations(void)
{
	int n = 0;
	PROCESS_MITIGATION_DYNAMIC_CODE_POLICY dynamic = { 0 };
	dynamic.ProhibitDynamicCode = 1;
	n += SetProcessMitigationPolicy(ProcessDynamicCodePolicy, &dynamic, sizeof(dynamic)) != 0;
	/* A bad handle is a crash, not a silent failure an exploit can probe with. */
	PROCESS_MITIGATION_STRICT_HANDLE_CHECK_POLICY handles = { 0 };
	handles.RaiseExceptionOnInvalidHandleReference = 1;
	handles.HandleExceptionsPermanentlyEnabled = 1;
	n += SetProcessMitigationPolicy(ProcessStrictHandleCheckPolicy, &handles, sizeof(handles)) != 0;
	/* No AppInit DLLs, input method editors, window hooks or other code injected by the system. */
	PROCESS_MITIGATION_EXTENSION_POINT_DISABLE_POLICY ext = { 0 };
	ext.DisableExtensionPoints = 1;
	n += SetProcessMitigationPolicy(ProcessExtensionPointDisablePolicy, &ext, sizeof(ext)) != 0;
	/* No DLL from a network share or with a low-integrity label (one the helper itself could have
	 * written after lowering itself); System32 first. */
	PROCESS_MITIGATION_IMAGE_LOAD_POLICY images = { 0 };
	images.NoRemoteImages = 1;
	images.NoLowMandatoryLabelImages = 1;
	images.PreferSystem32Images = 1;
	n += SetProcessMitigationPolicy(ProcessImageLoadPolicy, &images, sizeof(images)) != 0;
	return n;
}

/* Low integrity: no write access to anything labelled medium (the user's files, HKCU, other
 * processes) and no opening jterm or any other medium-integrity process. Handles already open (the
 * protocol pipes) keep their access. Last, since it can stop the process changing itself. */
static int integrity_is_low(HANDLE token)
{
	DWORD len = 0;
	GetTokenInformation(token, TokenIntegrityLevel, NULL, 0, &len);
	TOKEN_MANDATORY_LABEL* label = len ? (TOKEN_MANDATORY_LABEL*)malloc(len) : NULL;
	int low = 0;
	if (label && GetTokenInformation(token, TokenIntegrityLevel, label, len, &len))
	{
		const DWORD rid = *GetSidSubAuthority(label->Label.Sid,
		                                      *GetSidSubAuthorityCount(label->Label.Sid) - 1);
		low = rid <= SECURITY_MANDATORY_LOW_RID;
	}
	free(label);
	return low;
}

static int lower_integrity(void)
{
	HANDLE token;
	if (!OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_DEFAULT | TOKEN_QUERY, &token))
		return 0;
	if (integrity_is_low(token))
	{
		CloseHandle(token); /* W2's launcher set it already (a restricted token can't lower itself) */
		return 1;
	}
	SID_IDENTIFIER_AUTHORITY mandatory = SECURITY_MANDATORY_LABEL_AUTHORITY;
	PSID low = NULL;
	int ok = 0;
	if (AllocateAndInitializeSid(&mandatory, 1, SECURITY_MANDATORY_LOW_RID, 0, 0, 0, 0, 0, 0, 0, &low))
	{
		TOKEN_MANDATORY_LABEL label = { { low, SE_GROUP_INTEGRITY } };
		ok = SetTokenInformation(token, TokenIntegrityLevel, &label,
		                         (DWORD)(sizeof(label) + GetLengthSid(low))) != 0;
		if (!ok)
			fprintf(stderr, "jterm-rdp-helper: low integrity: error %lu\n", GetLastError());
		FreeSid(low);
	}
	CloseHandle(token);
	return ok;
}

void sandbox_lockdown(const uint16_t* ports, size_t nports)
{
	(void)ports;
	(void)nports; /* no network restriction is possible from inside the process */
	g_parent = parent_process_id();
	/* W2: the main thread has been impersonating the start-up token; from here on every thread runs
	 * with the restricted lockdown token. */
	int restricted = 0;
	if (sandbox_is_restricted_child())
	{
		if (RevertToSelf())
			restricted = IsTokenRestricted(GetCurrentProcessToken()) != 0;
		else
			fprintf(stderr, "jterm-rdp-helper: RevertToSelf: error %lu\n", GetLastError());
	}
	/* W2's launcher already holds the child in a job with the same limits (a nested job can't take
	 * UI restrictions: ERROR_NOT_SUPPORTED), so only W1 alone makes its own. */
	BOOL in_job = FALSE;
	const int job = sandbox_is_restricted_child()
	                    ? (IsProcessInJob(GetCurrentProcess(), NULL, &in_job) && in_job)
	                    : join_job();
	const int mitigations = apply_mitigations();
	const int low = lower_integrity();
	const char* level = (job || mitigations || low || restricted) ? "partial" : "none";
	fprintf(stderr,
	        "jterm-rdp-helper: sandbox level=%s integrity=%s job=%d mitigations=%d token=%s\n",
	        level, low ? "low" : "unchanged", job, mitigations, restricted ? "restricted" : "normal");
}

static void report(const char* what, BOOL failed, DWORD err)
{
	if (failed)
		fprintf(stderr, "jterm-rdp-helper: selftest %s blocked (error %lu)\n", what, err);
	else
		fprintf(stderr, "jterm-rdp-helper: selftest %s ALLOWED\n", what);
}

static DWORD parent_process_id(void)
{
	HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
	if (snap == INVALID_HANDLE_VALUE)
		return 0;
	DWORD me = GetCurrentProcessId(), parent = 0;
	PROCESSENTRY32W e = { 0 };
	e.dwSize = sizeof(e);
	for (BOOL ok = Process32FirstW(snap, &e); ok; ok = Process32NextW(snap, &e))
		if (e.th32ProcessID == me)
			parent = e.th32ParentProcessID;
	CloseHandle(snap);
	return parent;
}

/* What W1 and W2 promise. Not the network (neither restricts it) and not the microphone (opening it
 * would light the privacy indicator; measured in the spike instead). */
void sandbox_selftest(void)
{
	if (GetEnvironmentVariableA("JTERM_HELPER_SELFTEST", NULL, 0) == 0)
		return;
	char profile[MAX_PATH];
	char path[MAX_PATH + 64];
	const DWORD n = GetEnvironmentVariableA("USERPROFILE", profile, MAX_PATH);
	_snprintf_s(path, sizeof(path), _TRUNCATE, "%s\\jterm-rdp-selftest.tmp",
	            n > 0 && n < MAX_PATH ? profile : "C:");
	HANDLE f = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_FLAG_DELETE_ON_CLOSE, NULL);
	report("create-profile-file", f == INVALID_HANDLE_VALUE, GetLastError());
	if (f != INVALID_HANDLE_VALUE)
		CloseHandle(f);

	HKEY key;
	const LONG r = RegCreateKeyExA(HKEY_CURRENT_USER, "Software\\jterm-rdp-selftest", 0, NULL,
	                               REG_OPTION_VOLATILE, KEY_WRITE, NULL, &key, NULL);
	report("write-registry", r != ERROR_SUCCESS, (DWORD)r);
	if (r == ERROR_SUCCESS)
	{
		RegCloseKey(key);
		RegDeleteKeyA(HKEY_CURRENT_USER, "Software\\jterm-rdp-selftest");
	}

	const DWORD parent = g_parent;
	HANDLE p = parent ? OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, FALSE, parent) : NULL;
	report("open-parent", p == NULL, parent ? GetLastError() : ERROR_NOT_FOUND);
	if (p)
		CloseHandle(p);

	STARTUPINFOA si = { 0 };
	si.cb = sizeof(si);
	PROCESS_INFORMATION pi;
	char cmd[] = "cmd.exe /c exit 0";
	const BOOL started =
	    CreateProcessA(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi);
	report("start-process", !started, GetLastError());
	if (started)
	{
		WaitForSingleObject(pi.hProcess, 5000);
		CloseHandle(pi.hThread);
		CloseHandle(pi.hProcess);
	}

	/* OpenClipboard can succeed under the job; reading through it is what is refused. */
	const BOOL open = OpenClipboard(NULL);
	DWORD err = GetLastError();
	HANDLE data = NULL;
	if (open)
	{
		data = GetClipboardData(1 /* CF_TEXT */);
		err = GetLastError();
		CloseClipboard();
	}
	/* An empty clipboard also gives no data; only an actual read counts as allowed. */
	report("read-clipboard", data == NULL, err);

	/* W2 only: the user's files and Credential Manager. Listing the profile folder is enough. */
	char pattern[MAX_PATH + 8];
	_snprintf_s(pattern, sizeof(pattern), _TRUNCATE, "%s\\*", n > 0 && n < MAX_PATH ? profile : "C:");
	WIN32_FIND_DATAA fd;
	HANDLE find = FindFirstFileA(pattern, &fd);
	report("list-profile", find == INVALID_HANDLE_VALUE, GetLastError());
	if (find != INVALID_HANDLE_VALUE)
		FindClose(find);
	/* "No credentials" (ERROR_NOT_FOUND) means the store was readable, just empty: only a refusal
	 * counts as blocked. */
	DWORD count = 0;
	PCREDENTIALA* creds = NULL;
	const BOOL listed = CredEnumerateA(NULL, 0, &count, &creds);
	const DWORD cerr = listed ? 0 : GetLastError();
	report("credential-manager", !listed && cerr == ERROR_ACCESS_DENIED, cerr);
	if (creds)
		CredFree(creds);
}

#elif defined(__APPLE__)

/* ------------------------------------------------------------------------- macOS: Seatbelt */

/* Layer 3b (decision D5): a Seatbelt profile, applied by the helper to itself at the lockdown
 * point. sandbox_init_with_parameters with a profile string is not in the SDK headers (it is
 * deprecated API, but what browsers and Apple's own daemons use; there is no supported replacement
 * for a program outside the App Store), so it is declared here. libsystem_sandbox is part of
 * libSystem, which every program links. */
extern int sandbox_init_with_parameters(const char* profile, uint64_t flags,
                                        const char* const parameters[], char** errorbuf);
extern void sandbox_free_error(char* errorbuf);

int sandbox_needs_early_resolution(void)
{
	/* The resolver is a Mach service (mDNSResponder) the profile doesn't allow. */
	return 1;
}

/* Deny by default. Allowed: what any process needs to keep running (system.sb: dyld, the shared
 * cache, sysctl reads, the basic notification and logging services), reading the system's
 * libraries, the CA store and the time zone, the helper's own folder (its libraries), TCP to the
 * session's ports only, and the Mach services the decoders need. Not allowed, among everything
 * else: the user's files (the home folder and the keychains), writing any file, starting a
 * program, signalling or inspecting other processes, the audio server (coreaudiod: jterm records
 * and plays, never the helper, A7), securityd (the keychain holding jterm's master password), the
 * resolver, the pasteboard, and every non-TCP socket. */
static const char PROFILE_HEAD[] =
	"(version 1)\n"
	"(deny default)\n"
	"(import \"system.sb\")\n"
	"(allow file-read* file-test-existence\n"
	"  (subpath (param \"HELPER_DIR\"))\n"
	"  (subpath \"/System/Library\")\n"
	"  (subpath \"/usr/lib\")\n"
	"  (subpath \"/usr/share/zoneinfo\")\n"
	"  (subpath \"/private/var/db/timezone\")\n"
	"  (subpath \"/private/etc/ssl\")\n"
	"  (literal \"/private/etc/localtime\"))\n"
	"(allow file-read-metadata)\n"
	/* VideoToolbox hands decoded frames over in IOSurfaces: without it every H.264 frame fails
	 * (measured on the Intel macOS VM, which decodes in software). The helper checks a decode
	 * after the lockdown (h264_check_after_lockdown) in case hardware decoding needs more. */
	"(allow iokit-open (iokit-user-client-class \"IOSurfaceRootUserClient\"))\n";

void sandbox_lockdown(const uint16_t* ports, size_t nports)
{
	/* The profile text: the fixed part, then one outbound rule per port. */
	char profile[8192];
	size_t used = (size_t)snprintf(profile, sizeof(profile), "%s", PROFILE_HEAD);
	for (size_t i = 0; i < nports && used < sizeof(profile); i++)
		used += (size_t)snprintf(profile + used, sizeof(profile) - used,
		                         "(allow network-outbound (remote tcp \"*:%u\"))\n", ports[i]);
	if (used >= sizeof(profile))
	{
		fprintf(stderr, "jterm-rdp-helper: sandbox level=none seatbelt=0 (profile too long)\n");
		return;
	}
	const char* params[] = { "HELPER_DIR", g_helper_dir[0] ? g_helper_dir : "/nonexistent", NULL };
	char* error = NULL;
	if (sandbox_init_with_parameters(profile, 0, params, &error) != 0)
	{
		fprintf(stderr, "jterm-rdp-helper: sandbox level=none seatbelt=0 (%s)\n",
		        error ? error : "sandbox_init failed");
		if (error)
			sandbox_free_error(error);
		return;
	}
	fprintf(stderr, "jterm-rdp-helper: sandbox level=full seatbelt=1 ports=%zu\n", nports);
}

/* ----------------------------------------------------------------------------------- self-test */

static void report(const char* what, int failed, int err)
{
	if (failed)
		fprintf(stderr, "jterm-rdp-helper: selftest %s blocked (%s)\n", what, strerror(err));
	else
		fprintf(stderr, "jterm-rdp-helper: selftest %s ALLOWED\n", what);
}

static void try_open(const char* what, const char* path, int flags)
{
	const int fd = open(path, flags | O_CLOEXEC, 0600);
	const int err = errno;
	report(what, fd < 0, err);
	if (fd >= 0)
	{
		close(fd);
		if (flags & O_CREAT)
			unlink(path);
	}
}

static void try_mach(const char* what, const char* service)
{
	mach_port_t port = MACH_PORT_NULL;
	const kern_return_t kr = bootstrap_look_up(bootstrap_port, service, &port);
	if (kr == KERN_SUCCESS)
	{
		mach_port_deallocate(mach_task_self(), port);
		fprintf(stderr, "jterm-rdp-helper: selftest %s ALLOWED\n", what);
	}
	else
		fprintf(stderr, "jterm-rdp-helper: selftest %s blocked (bootstrap %d)\n", what, kr);
}

static void try_unix_connect(const char* what, const char* path)
{
	const int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	int err = errno;
	int rc = -1;
	if (fd >= 0)
	{
		struct sockaddr_un sa = { 0 };
		sa.sun_family = AF_UNIX;
		snprintf(sa.sun_path, sizeof(sa.sun_path), "%s", path);
		rc = connect(fd, (struct sockaddr*)&sa, sizeof(sa));
		err = errno;
		close(fd);
	}
	report(what, rc < 0, err);
}

void sandbox_selftest(void)
{
	if (!getenv("JTERM_HELPER_SELFTEST"))
		return;
	char path[2048];
	const char* home = getenv("HOME");
	if (!home || !*home)
		home = "/Users";
	/* Files: the home folder, the keychains, a new file in the temp folder. */
	try_open("read-home", home, O_RDONLY | O_DIRECTORY);
	snprintf(path, sizeof(path), "%s/Library/Keychains", home);
	try_open("read-keychains", path, O_RDONLY | O_DIRECTORY);
	snprintf(path, sizeof(path), "/private/tmp/jterm-rdp-selftest-%ld", (long)getpid());
	try_open("create-tmp-file", path, O_WRONLY | O_CREAT | O_EXCL);
	/* Services: the keychain, the audio server (the microphone), the pasteboard, the resolver. */
	try_mach("securityd", "com.apple.SecurityServer");
	try_mach("audio-server", "com.apple.audio.audiohald");
	try_mach("pasteboard", "com.apple.pasteboard.1");
	try_mach("resolver", "com.apple.dnssd.service");
	try_unix_connect("unix-socket", "/private/var/run/mDNSResponder");
	/* Sockets: UDP out, TCP to another port (refused before it leaves: no listener needed),
	 * listening. */
	{
		struct sockaddr_in sa = { 0 };
		sa.sin_family = AF_INET;
		sa.sin_len = sizeof(sa);
		sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
		sa.sin_port = htons(53);
		const int u = socket(AF_INET, SOCK_DGRAM, 0);
		ssize_t sent = u < 0 ? -1 : sendto(u, "x", 1, 0, (struct sockaddr*)&sa, sizeof(sa));
		int err = errno;
		report("udp-send", sent < 0, err);
		if (u >= 0)
			close(u);
		sa.sin_port = htons(22);
		const int fd = socket(AF_INET, SOCK_STREAM, 0);
		int rc = fd < 0 ? -1 : connect(fd, (struct sockaddr*)&sa, sizeof(sa));
		err = errno;
		if (rc < 0 && err == ECONNREFUSED)
			rc = 0; /* it reached the port: not prevented */
		report("connect-other-port", rc < 0, err);
		if (fd >= 0)
			close(fd);
		sa.sin_port = 0;
		const int fd2 = socket(AF_INET, SOCK_STREAM, 0);
		rc = fd2 < 0 ? -1 : bind(fd2, (struct sockaddr*)&sa, sizeof(sa));
		err = errno;
		if (rc == 0)
		{
			rc = listen(fd2, 1);
			err = errno;
		}
		report("listen-tcp", rc < 0, err);
		if (fd2 >= 0)
			close(fd2);
	}
	/* Another program, in a child so a success can't replace the helper. */
	{
		const pid_t child = fork();
		if (child == 0)
		{
			char* const argv[] = { "/usr/bin/true", NULL };
			char* const envp[] = { NULL };
			execve("/usr/bin/true", argv, envp);
			_exit(errno ? errno : 127);
		}
		int status = 0;
		int err = 0;
		if (child < 0)
			err = errno;
		else if (waitpid(child, &status, 0) != child)
			err = errno;
		else if (WIFEXITED(status))
			err = WEXITSTATUS(status);
		report("execve", err != 0, err);
	}
	/* Other processes: jterm. Signal 0 only checks permission; nothing is sent. */
	report("signal-parent", kill(getppid(), 0) != 0, errno);
}

#endif

#ifndef _WIN32

/* Every descriptor above stderr. The JDK already closes them in the child before exec, so this is
 * for any other way the helper is started (a debugging session, a future launcher). */
static void close_inherited_fds(void)
{
#if defined(__linux__) && defined(SYS_close_range)
	if (syscall(SYS_close_range, 3U, ~0U, 0U) == 0)
		return;
	/* ENOSYS before Linux 5.9 (and in some container seccomp profiles): close them one by one. */
#endif
	struct rlimit lim;
	long max = 1024;
	if (getrlimit(RLIMIT_NOFILE, &lim) == 0 && lim.rlim_cur != RLIM_INFINITY)
		max = (long)lim.rlim_cur;
	if (max > 65536)
		max = 65536;
	for (long fd = 3; fd < max; fd++)
		(void)close((int)fd);
}

#ifdef __APPLE__
/* The folder the helper runs from, its libraries' folder too: readable after the lockdown, for
 * anything loaded late. Resolved now, while the file system can still be asked. */
static void remember_helper_dir(void)
{
	char exe[1024];
	uint32_t size = sizeof(exe);
	char real[1024];
	if (_NSGetExecutablePath(exe, &size) != 0 || !realpath(exe, real))
		return;
	char* slash = strrchr(real, '/');
	if (!slash || slash == real)
		return;
	*slash = '\0';
	snprintf(g_helper_dir, sizeof(g_helper_dir), "%s", real);
}
#endif

void sandbox_early(void)
{
	/* No core file: it would hold the password. Both limits, so nothing in the process can raise
	 * it again. */
	const struct rlimit none = { 0, 0 };
	if (setrlimit(RLIMIT_CORE, &none) != 0)
		fprintf(stderr, "jterm-rdp-helper: could not disable core dumps: %s\n", strerror(errno));
#ifdef __linux__
	/* Not dumpable: no core dump through a core_pattern pipe either, and the user's other processes
	 * can no longer ptrace this one or read /proc/<pid>/mem, environ and fd (they become root's). */
	if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) != 0)
		fprintf(stderr, "jterm-rdp-helper: PR_SET_DUMPABLE failed: %s\n", strerror(errno));
#endif
	close_inherited_fds();
#ifdef __APPLE__
	remember_helper_dir();
#endif
}

#endif

#ifdef __linux__

/* ------------------------------------------------------------------------------------ Landlock */

/* The kernel's UAPI, declared here rather than taken from <linux/landlock.h>: the build hosts'
 * headers range from EL9's to the snap base's, and older ones lack the network rules and scopes.
 * The values are a stable ABI; a kernel that lacks a feature says so through its ABI version. */
#ifndef SYS_landlock_create_ruleset
#define SYS_landlock_create_ruleset 444
#define SYS_landlock_add_rule 445
#define SYS_landlock_restrict_self 446
#endif

struct jl_ruleset_attr
{
	uint64_t handled_access_fs;
	uint64_t handled_access_net; /* ABI 4 */
	uint64_t scoped;             /* ABI 6 */
};

struct jl_path_beneath_attr
{
	uint64_t allowed_access;
	int32_t parent_fd;
} __attribute__((packed));

struct jl_net_port_attr
{
	uint64_t allowed_access;
	uint64_t port;
};

#define JL_CREATE_RULESET_VERSION (1U << 0)
#define JL_RULE_PATH_BENEATH 1
#define JL_RULE_NET_PORT 2
#define JL_FS_READ_FILE (1ULL << 2)
#define JL_FS_READ_DIR (1ULL << 3)
#define JL_FS_ABI1 ((1ULL << 13) - 1) /* execute, write, read, remove, make_*: bits 0..12 */
#define JL_FS_REFER (1ULL << 13)      /* ABI 2 */
#define JL_FS_TRUNCATE (1ULL << 14)   /* ABI 3 */
#define JL_FS_IOCTL_DEV (1ULL << 15)  /* ABI 5 */
#define JL_NET_BIND_TCP (1ULL << 0)
#define JL_NET_CONNECT_TCP (1ULL << 1)
#define JL_SCOPE_ABSTRACT_UNIX_SOCKET (1ULL << 0)
#define JL_SCOPE_SIGNAL (1ULL << 1)

/* Read-only, and nothing else in the file system. What a session needs after the lockdown
 * (measured, design "Phase 2 spike"): the CA store and crypto policy for TLS, the time zone, and
 * glibc's processor count. Distributions keep the CA store in different places; one that doesn't
 * exist here is skipped. Not the home folder, /proc, /tmp, /dev or the helper's own folder (its
 * libraries are already mapped). */
static const char* const k_read_only[] = {
	"/etc/pki",          "/etc/ssl",           "/etc/ca-certificates",     "/usr/share/pki",
	"/usr/share/ca-certificates",              "/var/lib/ca-certificates", "/etc/crypto-policies",
	"/etc/localtime",    "/usr/share/zoneinfo", "/sys/devices/system/cpu/online",
};

static void landlock_read_only(int ruleset, const char* path, uint64_t handled)
{
	const int fd = open(path, O_PATH | O_CLOEXEC);
	if (fd < 0)
		return;
	struct stat st;
	struct jl_path_beneath_attr rule = { 0 };
	rule.parent_fd = fd;
	rule.allowed_access = JL_FS_READ_FILE;
	if (fstat(fd, &st) == 0 && S_ISDIR(st.st_mode))
		rule.allowed_access |= JL_FS_READ_DIR;
	rule.allowed_access &= handled;
	if (syscall(SYS_landlock_add_rule, ruleset, JL_RULE_PATH_BENEATH, &rule, 0) != 0)
		fprintf(stderr, "jterm-rdp-helper: landlock rule for %s: %s\n", path, strerror(errno));
	close(fd);
}

/* Returns the ABI version applied (0: none); *net and *scope say whether those parts are in. */
static int apply_landlock(const uint16_t* ports, size_t nports, int* net, int* scope)
{
	*net = 0;
	*scope = 0;
	const long abi = syscall(SYS_landlock_create_ruleset, NULL, 0, JL_CREATE_RULESET_VERSION);
	if (abi < 1)
	{
		fprintf(stderr, "jterm-rdp-helper: no Landlock (%s)\n",
		        errno == ENOSYS ? "kernel too old" : errno == EOPNOTSUPP ? "disabled" : strerror(errno));
		return 0;
	}
	struct jl_ruleset_attr attr = { 0 };
	size_t size = offsetof(struct jl_ruleset_attr, handled_access_net);
	attr.handled_access_fs = JL_FS_ABI1;
	if (abi >= 2)
		attr.handled_access_fs |= JL_FS_REFER;
	if (abi >= 3)
		attr.handled_access_fs |= JL_FS_TRUNCATE;
	if (abi >= 5)
		attr.handled_access_fs |= JL_FS_IOCTL_DEV;
	if (abi >= 4)
	{
		attr.handled_access_net = JL_NET_BIND_TCP | JL_NET_CONNECT_TCP;
		size = offsetof(struct jl_ruleset_attr, scoped);
	}
	if (abi >= 6)
	{
		attr.scoped = JL_SCOPE_ABSTRACT_UNIX_SOCKET | JL_SCOPE_SIGNAL;
		size = sizeof(attr);
	}
	const int ruleset = (int)syscall(SYS_landlock_create_ruleset, &attr, size, 0);
	if (ruleset < 0)
	{
		fprintf(stderr, "jterm-rdp-helper: landlock_create_ruleset: %s\n", strerror(errno));
		return 0;
	}
	for (size_t i = 0; i < sizeof(k_read_only) / sizeof(k_read_only[0]); i++)
		landlock_read_only(ruleset, k_read_only[i], attr.handled_access_fs);
	if (abi >= 4)
	{
		/* Connect, never bind, and only to this session's ports (any address: a redirect may name
		 * another host, and the SSH route is a loopback port). */
		for (size_t i = 0; i < nports; i++)
		{
			struct jl_net_port_attr rule = { JL_NET_CONNECT_TCP, ports[i] };
			if (syscall(SYS_landlock_add_rule, ruleset, JL_RULE_NET_PORT, &rule, 0) != 0)
				fprintf(stderr, "jterm-rdp-helper: landlock port %u: %s\n", ports[i], strerror(errno));
		}
	}
	if (syscall(SYS_landlock_restrict_self, ruleset, 0) != 0)
	{
		fprintf(stderr, "jterm-rdp-helper: landlock_restrict_self: %s\n", strerror(errno));
		close(ruleset);
		return 0;
	}
	close(ruleset);
	*net = abi >= 4;
	*scope = abi >= 6;
	return (int)abi;
}

/* ------------------------------------------------------------------------------------- seccomp */

#if defined(__x86_64__)
#define JS_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define JS_ARCH AUDIT_ARCH_AARCH64
#endif

#ifdef JS_ARCH

#define JS_MAX 128

typedef struct
{
	struct sock_filter f[JS_MAX];
	unsigned short n;
} Filter;

static void emit(Filter* p, struct sock_filter s)
{
	if (p->n < JS_MAX)
		p->f[p->n++] = s;
}

#define STMT(code, k) ((struct sock_filter)BPF_STMT((code), (k)))
#define JUMP(code, k, jt, jf) ((struct sock_filter)BPF_JUMP((code), (k), (jt), (jf)))
#define DENY_RET STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)
#define ALLOW_RET STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
#define LOAD_NR STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr))
#define LOAD_ARG_LO(i) STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[i]))

static void deny(Filter* p, long nr)
{
	emit(p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)nr, 0, 1));
	emit(p, DENY_RET);
}

/* kill/tgkill: allowed when the first argument is this process, refused otherwise (another process,
 * a process group, or -1 for all). The accumulator holds the call number again afterwards. */
static void only_self(Filter* p, long nr, uint32_t pid)
{
	emit(p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)nr, 0, 4));
	emit(p, LOAD_ARG_LO(0));
	emit(p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, pid, 0, 1));
	emit(p, ALLOW_RET);
	emit(p, DENY_RET);
}

/* A deny-list, on purpose (design, layer 2): the helper runs FreeRDP, OpenSSL and a codec against
 * several distributions' libc, and an allow-list that kills the process on the first unexpected
 * call would trade a security bug for a stream of support cases. Landlock carries the file and
 * port rules; this removes kernel surface and the socket families Landlock can't. Refused calls
 * return EPERM rather than killing, so a library probing for a feature degrades. */
static int apply_seccomp(void)
{
	Filter p = { .n = 0 };
	const uint32_t pid = (uint32_t)getpid();
	emit(&p, STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)));
	emit(&p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, JS_ARCH, 1, 0));
	emit(&p, STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS)); /* 32-bit entry points */
	emit(&p, LOAD_NR);
#ifdef __x86_64__
	/* x32 calls share the x86-64 arch value, with this bit set in the number. */
	emit(&p, JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1));
	emit(&p, DENY_RET);
#endif
	/* No other program, and no reaching into other processes. */
	deny(&p, __NR_execve);
	deny(&p, __NR_execveat);
	deny(&p, __NR_ptrace);
	deny(&p, __NR_process_vm_readv);
	deny(&p, __NR_process_vm_writev);
	/* The kernel keyring (where some desktops keep secrets). */
	deny(&p, __NR_add_key);
	deny(&p, __NR_request_key);
	deny(&p, __NR_keyctl);
	/* Kernel surface nothing in the helper uses. */
	deny(&p, __NR_bpf);
	deny(&p, __NR_perf_event_open);
	deny(&p, __NR_userfaultfd);
	deny(&p, __NR_io_uring_setup);
	deny(&p, __NR_mount);
	deny(&p, __NR_unshare);
	deny(&p, __NR_setns);
	deny(&p, __NR_open_by_handle_at);
	deny(&p, __NR_tkill); /* takes no process id to check; glibc signals threads with tgkill */
	/* socket: IPv4 and IPv6 streams only. No Unix sockets (the SSH agent, D-Bus, the keyring, the
	 * display server, PulseAudio/PipeWire), no netlink, no UDP, no raw sockets. */
	emit(&p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 8));
	emit(&p, LOAD_ARG_LO(0));
	emit(&p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET, 1, 0));
	emit(&p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET6, 0, 3));
	emit(&p, LOAD_ARG_LO(1));
	emit(&p, STMT(BPF_ALU | BPF_AND | BPF_K, 0xF)); /* the type without SOCK_NONBLOCK/CLOEXEC */
	emit(&p, JUMP(BPF_JMP | BPF_JEQ | BPF_K, SOCK_STREAM, 1, 0));
	emit(&p, DENY_RET);
	emit(&p, ALLOW_RET);
	/* socketpair makes a connected pair inside the process only: left alone. */
	only_self(&p, __NR_kill, pid);
	only_self(&p, __NR_tgkill, pid);
	emit(&p, ALLOW_RET);
	if (p.n >= JS_MAX)
	{
		fprintf(stderr, "jterm-rdp-helper: seccomp filter too long\n");
		return 0;
	}
	struct sock_fprog prog = { .len = p.n, .filter = p.f };
	/* TSYNC: every thread of the process, not only this one. */
	if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0)
	{
		fprintf(stderr, "jterm-rdp-helper: seccomp: %s\n", strerror(errno));
		return 0;
	}
	return 1;
}

#else

static int apply_seccomp(void)
{
	fprintf(stderr, "jterm-rdp-helper: no seccomp filter for this architecture\n");
	return 0;
}

#endif

/* ----------------------------------------------------------------------------------- lockdown */

int sandbox_needs_early_resolution(void)
{
	return 1;
}

void sandbox_lockdown(const uint16_t* ports, size_t nports)
{
	/* Required for an unprivileged seccomp filter and Landlock; also: no set-uid gains. */
	const int nnp = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0;

	/* No regular file can be written or grown, whatever the kernel supports (pipes and sockets are
	 * not files). SIGXFSZ ignored, so a write fails with EFBIG instead of killing the process.
	 * Only jterm's pipes are written after this; a helper started by hand with stderr sent to a
	 * file loses its log from here on. */
	signal(SIGXFSZ, SIG_IGN);
	const struct rlimit none = { 0, 0 };
	const int fsize = setrlimit(RLIMIT_FSIZE, &none) == 0;
	const struct rlimit files = { 512, 512 };
	(void)setrlimit(RLIMIT_NOFILE, &files);

	int net = 0;
	int scope = 0;
	const int landlock = nnp ? apply_landlock(ports, nports, &net, &scope) : 0;
	const int seccomp = nnp ? apply_seccomp() : 0;

	const char* level = "none";
	if (landlock && net && seccomp && fsize)
		level = "full";
	else if (landlock || seccomp || fsize)
		level = "partial";
	fprintf(stderr,
	        "jterm-rdp-helper: sandbox level=%s landlock=%d net=%d scope=%d seccomp=%d fsize=%d "
	        "nnp=%d\n",
	        level, landlock, net, scope, seccomp, fsize, nnp);
}

/* ----------------------------------------------------------------------------------- self-test */

static void report(const char* what, int failed, int err)
{
	if (failed)
		fprintf(stderr, "jterm-rdp-helper: selftest %s blocked (%s)\n", what, strerror(err));
	else
		fprintf(stderr, "jterm-rdp-helper: selftest %s ALLOWED\n", what);
}

static void try_open(const char* what, const char* path, int flags)
{
	const int fd = open(path, flags | O_CLOEXEC, 0600);
	const int err = errno;
	report(what, fd < 0, err);
	if (fd >= 0)
	{
		close(fd);
		if (flags & O_CREAT)
			unlink(path);
	}
}

static void try_socket(const char* what, int family, int type)
{
	const int fd = socket(family, type | SOCK_CLOEXEC, 0);
	const int err = errno;
	report(what, fd < 0, err);
	if (fd >= 0)
		close(fd);
}

void sandbox_selftest(void)
{
	if (!getenv("JTERM_HELPER_SELFTEST"))
		return;
	char path[4096];
	const char* home = getenv("HOME");
	/* Files: the user's own (the home folder itself is enough: listing it is refused), the
	 * system's, /proc, a new file in the temp folder, the sound device. */
	snprintf(path, sizeof(path), "%s", home && *home ? home : "/root");
	try_open("read-home", path, O_RDONLY | O_DIRECTORY);
	try_open("read-etc-passwd", "/etc/passwd", O_RDONLY);
	try_open("read-proc-environ", "/proc/self/environ", O_RDONLY);
	snprintf(path, sizeof(path), "/tmp/jterm-rdp-selftest-%ld", (long)getpid());
	try_open("create-tmp-file", path, O_WRONLY | O_CREAT | O_EXCL);
	try_open("open-sound-device", "/dev/snd/controlC0", O_RDONLY);
	/* Sockets other than TCP. */
	try_socket("unix-socket", AF_UNIX, SOCK_STREAM);
	try_socket("netlink-socket", AF_NETLINK, SOCK_RAW);
	try_socket("udp-socket", AF_INET, SOCK_DGRAM);
	/* TCP to a port outside the session's (refused before it leaves: no listener needed), and
	 * listening. */
	{
		const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
		struct sockaddr_in sa = { 0 };
		sa.sin_family = AF_INET;
		sa.sin_port = htons(22);
		sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
		int rc = fd < 0 ? -1 : connect(fd, (struct sockaddr*)&sa, sizeof(sa));
		int err = errno;
		if (rc < 0 && err == ECONNREFUSED)
			rc = 0; /* it reached the port: not prevented */
		report("connect-other-port", rc < 0, err);
		sa.sin_port = 0;
		const int fd2 = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
		rc = fd2 < 0 ? -1 : bind(fd2, (struct sockaddr*)&sa, sizeof(sa));
		err = errno;
		report("bind-tcp", rc < 0, err);
		if (fd >= 0)
			close(fd);
		if (fd2 >= 0)
			close(fd2);
	}
	/* Another program, in a child so a success can't replace the helper. */
	{
		const pid_t child = fork();
		if (child == 0)
		{
			char* const argv[] = { "/bin/true", NULL };
			char* const envp[] = { NULL };
			execve("/bin/true", argv, envp);
			_exit(errno ? errno : 127);
		}
		int status = 0;
		int err = 0;
		if (child < 0 || waitpid(child, &status, 0) != child)
			err = errno;
		else if (WIFEXITED(status))
			err = WEXITSTATUS(status);
		report("execve", err != 0, err);
	}
	/* Other processes: the parent (jterm). Signal 0 only checks permission; nothing is sent. */
	{
		int rc = kill(getppid(), 0);
		report("signal-parent", rc != 0, errno);
		rc = (int)ptrace(PTRACE_ATTACH, getppid(), NULL, NULL);
		const int err = errno;
		if (rc == 0)
			(void)ptrace(PTRACE_DETACH, getppid(), NULL, NULL);
		report("ptrace-parent", rc != 0, err);
	}
}

#endif
