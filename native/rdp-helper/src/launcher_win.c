/*
 * jterm-rdp-helper — Windows step W2: the helper relaunches itself with a restricted token.
 * Copyright (C) 2026 Mark Moses
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Design: docs/superpowers/specs/2026-11-11-rdp-helper-sandbox-design.md, "W2" and the W1 spike.
 * A low-integrity process (W1) can still read the user's files, Credential Manager (where jterm's
 * master password is kept) and the microphone; a restricted token can't, but Java can't start a
 * process with one. So the helper jterm starts is a small launcher: it builds the token, starts
 * itself again with it, passes on the protocol pipes and waits. The child does all the work.
 *
 *  - Lockdown token (the child's primary token): every privilege removed, the user's SID and
 *    Administrators deny-only, restricting SIDs Everyone, Users, RESTRICTED and the logon SID, so
 *    an object is reachable only if it is open to one of those as well (Program Files and System32
 *    are; the profile, HKCU's writable parts and Credential Manager are not). Low integrity.
 *  - Start-up token: the child's main thread starts impersonating a token with the user's normal
 *    access, so the loader and the initialisation before the lockdown point can read what they
 *    need (a per-user install folder, the user's settings). It is itself a restricted token whose
 *    restricting SIDs are all of the user's: Windows refuses to let a restricted process
 *    impersonate an unrestricted token. Also low integrity. At the lockdown point the child calls
 *    RevertToSelf (sandbox_lockdown) and the lockdown token applies to every thread.
 *  - A job object holding the child: one process, killed when the job's last handle closes (the
 *    launcher's: if jterm kills the launcher, the child goes too), no Windows Error Reporting, no
 *    clipboard or other desktop access.
 *  - Only the three standard handles are inherited (an explicit handle list), so nothing else the
 *    JVM left inheritable reaches the child. Working directory System32: always readable, and no
 *    DLL planted in jterm's working directory is searched.
 */
#ifdef _WIN32

#include "sandbox.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <sddl.h>

#define CHILD_FLAG L"--jterm-restricted-child"

static int g_is_child = 0;

int sandbox_is_restricted_child(void)
{
	return g_is_child;
}

static void fail(const char* what)
{
	fprintf(stderr, "jterm-rdp-helper: restricted token: %s failed (error %lu); continuing "
	                "without it\n", what, GetLastError());
}

static PSID sid_from_string(const wchar_t* text)
{
	PSID sid = NULL;
	return ConvertStringSidToSidW(text, &sid) ? sid : NULL;
}

static void* token_info(HANDLE token, TOKEN_INFORMATION_CLASS cls)
{
	DWORD len = 0;
	GetTokenInformation(token, cls, NULL, 0, &len);
	void* buf = len ? malloc(len) : NULL;
	if (buf && !GetTokenInformation(token, cls, buf, len, &len))
	{
		free(buf);
		buf = NULL;
	}
	return buf;
}

static BOOL set_low_integrity(HANDLE token)
{
	PSID low = sid_from_string(L"S-1-16-4096");
	if (!low)
		return FALSE;
	TOKEN_MANDATORY_LABEL label = { { low, SE_GROUP_INTEGRITY } };
	const BOOL ok = SetTokenInformation(token, TokenIntegrityLevel, &label,
	                                    (DWORD)(sizeof(label) + GetLengthSid(low)));
	LocalFree(low);
	return ok;
}

/* Objects the child creates get this DACL. The token's own default names the user's SID, which is
 * deny-only in the lockdown token, so the child could not open its own objects again; Chromium
 * adds the same entries for the same reason. */
static BOOL set_default_dacl(HANDLE token, PSID logon)
{
	PSID restricted = sid_from_string(L"S-1-5-12");
	PSID system = sid_from_string(L"S-1-5-18");
	BOOL ok = FALSE;
	if (restricted && system)
	{
		const DWORD size = sizeof(ACL) + 3 * (sizeof(ACCESS_ALLOWED_ACE) + SECURITY_MAX_SID_SIZE);
		ACL* acl = (ACL*)calloc(1, size);
		if (acl && InitializeAcl(acl, size, ACL_REVISION) &&
		    AddAccessAllowedAce(acl, ACL_REVISION, GENERIC_ALL, system) &&
		    AddAccessAllowedAce(acl, ACL_REVISION, GENERIC_ALL, restricted) &&
		    (!logon || AddAccessAllowedAce(acl, ACL_REVISION, GENERIC_ALL, logon)))
		{
			TOKEN_DEFAULT_DACL dacl = { acl };
			ok = SetTokenInformation(token, TokenDefaultDacl, &dacl, sizeof(dacl));
		}
		free(acl);
	}
	LocalFree(restricted);
	LocalFree(system);
	return ok;
}

static BOOL make_tokens(HANDLE* lockdown, HANDLE* startup)
{
	*lockdown = *startup = NULL;
	HANDLE self = NULL;
	if (!OpenProcessToken(GetCurrentProcess(),
	                      TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ASSIGN_PRIMARY | TOKEN_ADJUST_DEFAULT,
	                      &self))
	{
		fail("OpenProcessToken");
		return FALSE;
	}
	TOKEN_USER* user = (TOKEN_USER*)token_info(self, TokenUser);
	TOKEN_GROUPS* groups = (TOKEN_GROUPS*)token_info(self, TokenGroups);
	PSID everyone = sid_from_string(L"S-1-1-0");
	PSID users = sid_from_string(L"S-1-5-32-545");
	PSID restricted = sid_from_string(L"S-1-5-12");
	PSID admins = sid_from_string(L"S-1-5-32-544");
	PSID local_admin = sid_from_string(L"S-1-5-114"); /* local account and member of Administrators */
	BOOL ok = user && groups && everyone && users && restricted && admins && local_admin;
	PSID logon = NULL;
	SID_AND_ATTRIBUTES* all = NULL;
	DWORD nall = 0;
	if (ok)
	{
		all = (SID_AND_ATTRIBUTES*)calloc(groups->GroupCount + 1, sizeof(*all));
		ok = all != NULL;
	}
	if (ok)
	{
		all[nall++].Sid = user->User.Sid;
		for (DWORD i = 0; i < groups->GroupCount; i++)
		{
			const DWORD a = groups->Groups[i].Attributes;
			if (a & SE_GROUP_LOGON_ID)
				logon = groups->Groups[i].Sid;
			if ((a & SE_GROUP_ENABLED) && !(a & SE_GROUP_INTEGRITY))
				all[nall++].Sid = groups->Groups[i].Sid;
		}
	}

	/* The lockdown token. */
	HANDLE primary = NULL;
	if (ok)
	{
		SID_AND_ATTRIBUTES deny[3] = { { user->User.Sid, 0 }, { admins, 0 }, { local_admin, 0 } };
		SID_AND_ATTRIBUTES restrict_to[4] = { { everyone, 0 }, { users, 0 }, { restricted, 0 }, { logon, 0 } };
		ok = CreateRestrictedToken(self, DISABLE_MAX_PRIVILEGE, 3, deny, 0, NULL, logon ? 4 : 3,
		                           restrict_to, &primary);
		if (!ok)
			fail("CreateRestrictedToken (lockdown)");
		else if (!set_low_integrity(primary))
			ok = (fail("low integrity (lockdown)"), FALSE);
		else if (!set_default_dacl(primary, logon))
			ok = (fail("default DACL"), FALSE);
	}

	/* The start-up token: the user's access, as a restricted token, low integrity, impersonation. */
	HANDLE same = NULL, imp = NULL;
	if (ok)
	{
		ok = CreateRestrictedToken(self, 0, 0, NULL, 0, NULL, nall, all, &same);
		if (!ok)
			fail("CreateRestrictedToken (start-up)");
		else if (!DuplicateTokenEx(same, TOKEN_IMPERSONATE | TOKEN_QUERY | TOKEN_ADJUST_DEFAULT, NULL,
		                           SecurityImpersonation, TokenImpersonation, &imp))
			ok = (fail("DuplicateTokenEx"), FALSE);
		else if (!set_low_integrity(imp))
			ok = (fail("low integrity (start-up)"), FALSE);
	}

	if (same)
		CloseHandle(same);
	free(all);
	free(user);
	free(groups);
	LocalFree(everyone);
	LocalFree(users);
	LocalFree(restricted);
	LocalFree(admins);
	LocalFree(local_admin);
	CloseHandle(self);
	if (!ok)
	{
		if (primary)
			CloseHandle(primary);
		if (imp)
			CloseHandle(imp);
		return FALSE;
	}
	*lockdown = primary;
	*startup = imp;
	return TRUE;
}

static HANDLE make_job(void)
{
	HANDLE job = CreateJobObjectW(NULL, NULL);
	if (!job)
		return NULL;
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
	    !SetInformationJobObject(job, JobObjectBasicUIRestrictions, &ui, sizeof(ui)))
	{
		CloseHandle(job);
		return NULL;
	}
	return job;
}

static BOOL usable(HANDLE h)
{
	return h != NULL && h != INVALID_HANDLE_VALUE;
}

void sandbox_without_startup_token(void (*fn)(void* arg), void* arg)
{
	/* OpenAsSelf FALSE: checked against the start-up token itself. Checked against the process's
	 * lockdown token, opening it is refused (error 5). */
	HANDLE startup = NULL;
	if (!g_is_child ||
	    !OpenThreadToken(GetCurrentThread(), TOKEN_IMPERSONATE | TOKEN_QUERY, FALSE, &startup))
	{
		if (g_is_child)
			fprintf(stderr, "jterm-rdp-helper: restricted token: reopening the start-up token "
			                "failed (error %lu)\n", GetLastError());
		fn(arg);
		return;
	}
	if (!RevertToSelf())
	{
		CloseHandle(startup);
		fn(arg);
		return;
	}
	fn(arg);
	if (!SetThreadToken(NULL, startup))
		fprintf(stderr, "jterm-rdp-helper: restricted token: resuming the start-up token failed "
		                "(error %lu)\n", GetLastError());
	CloseHandle(startup);
}

int sandbox_relaunch_restricted(void)
{
	/* The child: recognised by its flag, which only the launcher adds. Run by hand with the flag,
	 * the helper simply runs unrestricted, as a debugging session would. */
	const wchar_t* cmdline = GetCommandLineW();
	if (wcsstr(cmdline, CHILD_FLAG))
	{
		g_is_child = 1;
		/* NLA through WinPR's own NTLM, not Windows' SSPI: LSA refuses AcquireCredentialsHandle to
		 * the restricted token (SEC_E_NO_CREDENTIALS, measured), and NLA runs after the lockdown, and
		 * again on every reconnect and redirect. WinPR's NTLM runs in-process, as on Linux and
		 * macOS. The cost: no Kerberos (WinPR has none in these builds). Read by WinPR when the
		 * SSPI module is first initialised, which is inside freerdp_connect. */
		SetEnvironmentVariableW(L"WINPR_NATIVE_SSPI", L"0");
		return -1;
	}
	if (GetEnvironmentVariableW(L"JTERM_HELPER_NO_RELAUNCH", NULL, 0) > 0)
		return -1; /* for comparing with W1 alone; jterm never sets it */
	/* The session asked for Windows' own sign-in (Kerberos; RdpSessionConfig.useKerberos, passed by
	 * HelperEnvironment). SSPI refuses a restricted token, so this helper keeps the user's token and
	 * only W1 applies; WINPR_NATIVE_SSPI stays unset, so WinPR uses Windows' SSPI. */
	if (GetEnvironmentVariableW(L"JTERM_RDP_KERBEROS", NULL, 0) > 0)
	{
		fprintf(stderr, "jterm-rdp-helper: Windows sign-in (Kerberos) requested: running without the "
		                "restricted token\n");
		return -1;
	}

	HANDLE lockdown, startup;
	if (!make_tokens(&lockdown, &startup))
		return -1;
	HANDLE job = make_job();
	if (!job)
	{
		fail("job object");
		CloseHandle(lockdown);
		CloseHandle(startup);
		return -1;
	}

	/* The standard handles, made inheritable and listed explicitly (no duplicates allowed). */
	HANDLE std[3] = { GetStdHandle(STD_INPUT_HANDLE), GetStdHandle(STD_OUTPUT_HANDLE),
		              GetStdHandle(STD_ERROR_HANDLE) };
	HANDLE list[3];
	DWORD nlist = 0;
	for (int i = 0; i < 3; i++)
	{
		if (!usable(std[i]) || !SetHandleInformation(std[i], HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT))
		{
			std[i] = NULL;
			continue;
		}
		BOOL dup = FALSE;
		for (DWORD j = 0; j < nlist; j++)
			dup |= list[j] == std[i];
		if (!dup)
			list[nlist++] = std[i];
	}
	SIZE_T attr_size = 0;
	InitializeProcThreadAttributeList(NULL, 1, 0, &attr_size);
	LPPROC_THREAD_ATTRIBUTE_LIST attrs = (LPPROC_THREAD_ATTRIBUTE_LIST)malloc(attr_size);
	BOOL ok = attrs && InitializeProcThreadAttributeList(attrs, 1, 0, &attr_size) &&
	          (nlist == 0 || UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, list,
	                                                   nlist * sizeof(HANDLE), NULL, NULL));
	if (!ok)
		fail("handle list");

	wchar_t exe[MAX_PATH], sysdir[MAX_PATH];
	const DWORD n = GetModuleFileNameW(NULL, exe, MAX_PATH);
	ok = ok && n > 0 && n < MAX_PATH && GetSystemDirectoryW(sysdir, MAX_PATH) > 0;
	const size_t cmdlen = wcslen(cmdline) + wcslen(CHILD_FLAG) + 2;
	wchar_t* childcmd = (wchar_t*)malloc(cmdlen * sizeof(wchar_t));
	ok = ok && childcmd && swprintf(childcmd, cmdlen, L"%ls %ls", cmdline, CHILD_FLAG) > 0;

	STARTUPINFOEXW si = { 0 };
	si.StartupInfo.cb = sizeof(si);
	si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
	si.StartupInfo.hStdInput = std[0];
	si.StartupInfo.hStdOutput = std[1];
	si.StartupInfo.hStdError = std[2];
	si.lpAttributeList = attrs;
	PROCESS_INFORMATION pi = { 0 };
	if (ok)
	{
		ok = CreateProcessAsUserW(lockdown, exe, childcmd, NULL, NULL, nlist > 0,
		                          CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT, NULL, sysdir,
		                          &si.StartupInfo, &pi);
		if (!ok)
			fail("CreateProcessAsUser");
	}
	if (ok && !AssignProcessToJobObject(job, pi.hProcess))
		ok = (fail("AssignProcessToJobObject"), FALSE);
	if (ok && !SetThreadToken(&pi.hThread, startup))
		ok = (fail("SetThreadToken"), FALSE);
	if (attrs)
	{
		DeleteProcThreadAttributeList(attrs);
		free(attrs);
	}
	free(childcmd);
	CloseHandle(lockdown);
	CloseHandle(startup);
	if (!ok)
	{
		if (pi.hProcess)
		{
			TerminateProcess(pi.hProcess, 1);
			CloseHandle(pi.hThread);
			CloseHandle(pi.hProcess);
		}
		CloseHandle(job);
		return -1; /* nothing was read from stdin yet: the helper carries on in this process */
	}
	ResumeThread(pi.hThread);
	CloseHandle(pi.hThread);
	/* The launcher never reads or writes the pipes again; jterm sees EOF when the child is gone. */
	if (std[0])
		CloseHandle(std[0]);
	if (std[1] && std[1] != std[0])
		CloseHandle(std[1]);

	WaitForSingleObject(pi.hProcess, INFINITE);
	DWORD code = 1;
	GetExitCodeProcess(pi.hProcess, &code);
	CloseHandle(pi.hProcess);
	CloseHandle(job);
	return (int)code;
}

#else
typedef int launcher_win_is_windows_only; /* ISO C: no empty translation unit */
#endif
