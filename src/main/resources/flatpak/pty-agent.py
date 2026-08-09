# jterm — host-side PTY agent for the Flatpak sandbox.
# Copyright (C) 2026 Mark Moses
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs on the *host* via `flatpak-spawn --host`, launched by
# com.katmoda.jterm.terminal.local.FlatpakHost.
#
# Why this exists: pty4j allocates the PTY inside the sandbox, and handing that
# slave fd to a host process does not carry terminal semantics across the
# boundary. The host shell cannot claim a controlling terminal whose session
# lives in the sandbox (`cannot set terminal process group`, `no job control`),
# ttyname() fails because the sandbox has its own devpts instance
# (`tty: ttyname error: No such device`), and SIGWINCH is delivered to the
# sandbox side and never forwarded.
#
# So we allocate a *second* PTY here on the host — pty.fork() does setsid() +
# TIOCSCTTY properly — run the login shell in it, and relay bytes to and from
# the sandbox PTY on fd 0/1. The sandbox PTY becomes pure transport plus the
# window-size channel: TIOCGWINSZ on fd 0 still reports live values across the
# boundary, so we poll it and mirror changes onto the host PTY.
#
# Stdlib only, and must stay compatible with the oldest python3 a user might
# have on the host (3.6) — notably no os.waitstatus_to_exitcode (3.9+).

import errno
import fcntl
import os
import pty
import select
import signal
import struct
import sys
import termios
import tty

POLL_SECONDS = 0.1
DEFAULT_SIZE = (24, 80, 0, 0)


def get_size(fd):
    try:
        return struct.unpack("HHHH", fcntl.ioctl(fd, termios.TIOCGWINSZ, b"\0" * 8))
    except (OSError, IOError):
        return None


def set_size(fd, size):
    try:
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", *size))
    except (OSError, IOError):
        pass


def read_some(fd):
    """Returns bytes, or None on EOF/hangup. Assumes select() said readable."""
    while True:
        try:
            data = os.read(fd, 65536)
        except (OSError, IOError) as e:
            if e.errno == errno.EINTR:
                continue
            # EIO is how a PTY master reports the far end going away.
            return None
        return data if data else None


def write_all(fd, data):
    """Blocking write of everything. Returns False if the far end is gone.

    fds 0/1/2 share one open file description on the PTY slave, so these must
    stay blocking — making fd 0 non-blocking to poll it would also make writes
    to fd 1 fail with EAGAIN and silently drop terminal output.
    """
    while data:
        try:
            written = os.write(fd, data)
        except (OSError, IOError) as e:
            if e.errno == errno.EINTR:
                continue
            if e.errno in (errno.EIO, errno.EPIPE):
                return False
            raise
        data = data[written:]
    return True


def main():
    argv = sys.argv[1:]
    if not argv:
        sys.stderr.write("jterm pty-agent: no command given\n")
        return 2

    size = get_size(0) or DEFAULT_SIZE

    pid, master = pty.fork()
    if pid == 0:
        try:
            os.execvp(argv[0], argv)
        except Exception as e:  # noqa: BLE001 — last chance before _exit
            sys.stderr.write("jterm pty-agent: cannot exec %s: %s\n" % (argv[0], e))
        os._exit(127)

    set_size(master, size)

    # The sandbox PTY must not apply its own line discipline on top of the host
    # PTY's — raw mode keeps it a transparent pipe (no double echo, no double
    # ONLCR) while leaving TIOCGWINSZ, our size channel, fully usable.
    saved_attrs = None
    try:
        saved_attrs = termios.tcgetattr(0)
        tty.setraw(0)
    except (termios.error, OSError, IOError):
        pass

    def restore():
        if saved_attrs is not None:
            try:
                termios.tcsetattr(0, termios.TCSADRAIN, saved_attrs)
            except (termios.error, OSError, IOError):
                pass

    def terminate(_signum, _frame):
        # pty4j's process.destroy() SIGTERMs flatpak-spawn, which forwards the
        # signal here; take the shell and everything it started down with us so
        # closing a pane can never leak host processes.
        try:
            os.killpg(pid, signal.SIGHUP)
        except OSError:
            pass
        restore()
        os._exit(1)

    for sig in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
        try:
            signal.signal(sig, terminate)
        except (ValueError, OSError):
            pass

    while True:
        current = get_size(0)
        if current is not None and current != size:
            size = current
            set_size(master, size)
            # SIGWINCH from the sandbox PTY goes to the sandbox-side session and
            # is not forwarded across the portal, so raise it ourselves.
            try:
                os.killpg(os.tcgetpgrp(master), signal.SIGWINCH)
            except OSError:
                try:
                    os.kill(pid, signal.SIGWINCH)
                except OSError:
                    pass

        try:
            readable, _, _ = select.select([0, master], [], [], POLL_SECONDS)
        except (select.error, OSError) as e:
            if getattr(e, "errno", None) == errno.EINTR:
                continue
            break

        if 0 in readable:
            data = read_some(0)
            if data is None:
                break
            if not write_all(master, data):
                break

        if master in readable:
            data = read_some(master)
            if data is None:
                break
            if not write_all(1, data):
                break

    try:
        _, status = os.waitpid(pid, 0)
    except OSError:
        status = 0

    restore()

    if os.WIFEXITED(status):
        return os.WEXITSTATUS(status)
    if os.WIFSIGNALED(status):
        return 128 + os.WTERMSIG(status)
    return 0


sys.exit(main())
