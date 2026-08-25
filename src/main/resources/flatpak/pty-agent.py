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
# We also report the shell's working directory. jterm cannot read it itself: the
# process it spawned is the sandbox-side flatpak-spawn, whose own directory never
# changes, and the sandbox cannot see the host's /proc. We can — the shell is our
# child — so we poll /proc/<pid>/cwd here and splice an OSC 7 sequence into the
# relay, which is the same thing a remote shell would send over SSH.
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
import time
import tty

POLL_SECONDS = 0.1
CWD_POLL_SECONDS = 0.2
DEFAULT_SIZE = (24, 80, 0, 0)

# RFC 3986 unreserved, plus the separator, which stays literal in a file:// path.
UNRESERVED = frozenset(
    bytearray(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~/")
)
HOST_SAFE = frozenset(
    bytearray(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-.")
)

# Byte-stream states, enough to tell whether the shell's output is at a point
# where we may splice our own bytes in. See Boundary.
_GROUND, _ESC, _ESC_INT, _CSI, _STR, _STR_ESC = range(6)


class Boundary(object):
    """Tracks whether the relayed byte stream is at a splice point.

    Injecting an escape sequence into the middle of one the shell is already
    emitting would corrupt both, and injecting between two bytes of a multi-byte
    character would corrupt that character — the far end decodes UTF-8. So we
    follow the stream just closely enough to know when neither is in progress.

    Only the 7-bit (ESC-introduced) forms are recognised. In a UTF-8 stream the
    8-bit C1 introducers cannot be told apart from continuation bytes, and
    treating one as CSI would wedge this permanently 'in a sequence'.
    """

    def __init__(self):
        self.state = _GROUND
        self.utf8_left = 0

    def at_boundary(self):
        return self.state == _GROUND and self.utf8_left == 0

    def feed(self, data):
        state = self.state
        left = self.utf8_left
        for b in bytearray(data):
            if state == _GROUND:
                if left > 0:
                    if 0x80 <= b <= 0xBF:
                        left -= 1
                        continue
                    # Truncated character: drop the expectation and judge this
                    # byte on its own merits.
                    left = 0
                if b == 0x1B:
                    state = _ESC
                elif 0xC2 <= b <= 0xF4:
                    left = 1 if b < 0xE0 else (2 if b < 0xF0 else 3)
            elif state == _ESC:
                if b == 0x5B:                        # [ — CSI
                    state = _CSI
                elif b in (0x5D, 0x50, 0x5E, 0x5F):  # ] P ^ _ — OSC/DCS/PM/APC
                    state = _STR
                elif b == 0x1B:
                    state = _ESC
                elif 0x20 <= b <= 0x2F:              # intermediate byte
                    state = _ESC_INT
                else:
                    state = _GROUND                  # final byte of a short escape
            elif state == _ESC_INT:
                if not 0x20 <= b <= 0x2F:
                    state = _GROUND
            elif state == _CSI:
                if b == 0x1B:
                    state = _ESC
                elif b in (0x18, 0x1A) or 0x40 <= b <= 0x7E:  # CAN/SUB, or final
                    state = _GROUND
            elif state == _STR:
                if b == 0x07 or b in (0x18, 0x1A):   # BEL, or CAN/SUB
                    state = _GROUND
                elif b == 0x1B:
                    state = _STR_ESC
            elif state == _STR_ESC:
                if b == 0x5C:                        # ESC \\ — string terminator
                    state = _GROUND
                elif b != 0x1B:
                    state = _STR
        self.state = state
        self.utf8_left = left


def read_cwd(pid):
    """The shell's working directory, or None once it is gone."""
    try:
        return os.readlink("/proc/%d/cwd" % pid)
    except (OSError, IOError):
        return None


def hostname():
    try:
        return os.uname()[1]
    except (OSError, AttributeError):
        return "localhost"


def osc7(path):
    """ESC ] 7 ; file://host/path BEL — the sequence jterm reads a directory from.

    The path is percent-encoded per byte, not per character: os.readlink hands
    back undecodable bytes as surrogates, and a directory name is not required to
    be valid UTF-8.
    """
    out = bytearray(b"\033]7;file://")
    for b in bytearray(os.fsencode(hostname())):
        if b in HOST_SAFE:
            out.append(b)
    for b in bytearray(os.fsencode(path)):
        if b in UNRESERVED:
            out.append(b)
        else:
            out.extend(("%%%02X" % b).encode("ascii"))
    out.extend(b"\007")
    return bytes(out)


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

    boundary = Boundary()
    reported_cwd = None
    pending_cwd = None
    next_cwd_poll = 0.0

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
            boundary.feed(data)
            if not write_all(1, data):
                break

        now = time.monotonic()
        if now >= next_cwd_poll:
            next_cwd_poll = now + CWD_POLL_SECONDS
            # Cheap, but the loop also spins on every burst of output, so this is
            # throttled rather than run per iteration.
            current_cwd = read_cwd(pid)
            if current_cwd is not None and current_cwd != reported_cwd:
                pending_cwd = current_cwd

        # Held back until the shell's output is between sequences and between
        # characters; a `cd` during a full-screen redraw waits for the next gap.
        if pending_cwd is not None and boundary.at_boundary():
            if not write_all(1, osc7(pending_cwd)):
                break
            reported_cwd = pending_cwd
            pending_cwd = None

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
