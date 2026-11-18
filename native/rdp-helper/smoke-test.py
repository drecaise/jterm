#!/usr/bin/env python3
"""Headless smoke test for a staged jterm-rdp-helper: does it start, load its libraries, speak
the protocol, and end cleanly? No RDP server needed.

It sends HELLO and a CONNECT to a closed port on 127.0.0.1 and expects HELLO_REPLY with the same
protocol version, then DISCONNECTED with a FreeRDP connect-class error, then a clean exit. A
helper that cannot load a library (or, on Apple Silicon, has a broken signature) dies before the
reply, which this reports with its stderr.

With --expect-h264 it also fails unless the helper reports a working H.264 decoder: on macOS that
is VideoToolbox, part of the OS, so its absence means a broken build.

The helper gets the environment jterm gives it (an allow-list, see HelperEnvironment.java), not
this script's. (Windows and Linux depend on
the machine: Media Foundation can be missing, OpenH264 is a download.)

With --expect-aac it likewise fails unless the helper reports an AAC decoder (for builds where one
is part of the OS; on Linux libfaad is the distribution's and may not be installed).

With --expect-sandbox LEVEL (full, partial or none) it fails unless the helper's sandbox reaches at
least that level, and (above none) unless every forbidden action of its self-test was refused. Each
platform tests what its lockdown promises: on Linux reading the home folder, a Unix socket, another
TCP port, execve, signalling jterm, ...; on Windows (step W1, partial at best) writing the profile
and registry, opening jterm's process, starting a program, reading the clipboard; on macOS
(Seatbelt) the home folder and keychains, writing a file, the keychain, audio, pasteboard and
resolver services, other sockets and ports, starting a program, signalling jterm. On macOS
"H.264 available" also requires that decoding a frame after the lockdown worked. The helper
runs the self-test because this script sets JTERM_HELPER_SELFTEST, which jterm never does. CI is
strict where users can't be (sandbox design, decision D1): a package whose helper gives less than
its target should fails here.

usage: smoke-test.py [--expect-h264] [--expect-aac] [--expect-sandbox LEVEL] <path to jterm-rdp-helper>
"""
import os
import re
import socket
import struct
import subprocess
import sys

USAGE = ("usage: smoke-test.py [--expect-h264] [--expect-aac] [--expect-sandbox LEVEL] "
         "<path to jterm-rdp-helper>")
LEVELS = ["none", "partial", "full"]
args = sys.argv[1:]
expect_sandbox = None
if "--expect-sandbox" in args:
    i = args.index("--expect-sandbox")
    if i + 1 >= len(args) or args[i + 1] not in LEVELS:
        sys.exit(USAGE)
    expect_sandbox = args[i + 1]
    del args[i:i + 2]
expect_h264 = "--expect-h264" in args
expect_aac = "--expect-aac" in args
args = [a for a in args if a not in ("--expect-h264", "--expect-aac")]
if len(args) != 1:
    sys.exit(USAGE)
helper = args[0]
here = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(here, "src", "protocol.h")) as f:
    version = int(re.search(r"#define JRDP_PROTOCOL_VERSION (\d+)", f.read()).group(1))

# A port nothing listens on: bind one, then close it.
s = socket.socket()
s.bind(("127.0.0.1", 0))
port = s.getsockname()[1]
s.close()


def frame(kind, payload=b""):
    return struct.pack("<IB", 1 + len(payload), kind) + payload


def text(value):
    b = value.encode()
    return struct.pack("<H", len(b)) + b


# CONNECT, protocol v13: host, port, user, domain, password, pins, w, h, scale, clipboardFlags,
# securityFlags, serverName, gatewayFlags, gatewayHost, gatewayPort, gatewayUser, gatewayDomain,
# gatewayPassword, driveCount (0: no shared folders), audioFlags, audioLatencyMs, printerFlags,
# printerName, printerDriver, smartcardFlags. Sound playback, the microphone, AAC, the printer and the
# smart card are on, so the forwarding devices' add-in paths and the AAC probe run too, and a printer
# or smart-card part the helper refuses fails CONNECT (not the 0x0002xxxx connect error expected below).
JRDP_AUDIO_PLAYBACK = 0x01
JRDP_AUDIO_MIC = 0x02
JRDP_AUDIO_AAC = 0x04
JRDP_PRINTER_ENABLED = 0x01
JRDP_SMARTCARD_ENABLED = 0x01
connect = (text("127.0.0.1") + struct.pack("<H", port) + text("smoke") + text("") + text("x")
           + text("") + struct.pack("<HHH", 1024, 768, 100) + bytes([0, 0]) + text("")
           + bytes([0]) + text("") + struct.pack("<H", 0) + text("") + text("") + text("")
           + bytes([0]) + bytes([JRDP_AUDIO_PLAYBACK | JRDP_AUDIO_MIC | JRDP_AUDIO_AAC]) + struct.pack("<H", 100)
           + bytes([JRDP_PRINTER_ENABLED]) + text("jterm") + text("Microsoft Print To PDF") + bytes([JRDP_SMARTCARD_ENABLED]))
if version != 13:
    sys.exit("smoke-test.py builds a v13 CONNECT but protocol.h says v%d: update it" % version)

# The environment jterm gives the helper: an allow-list, never its own (HelperEnvironment.java; keep
# the two lists in step). Started with the same, a variable the helper turns out to need fails here
# rather than on a user's machine.
KEEP = ["HOME", "TZ", "LANG", "LANGUAGE"] + (
    ["USERPROFILE", "SystemRoot", "SystemDrive", "windir", "TEMP", "TMP"] if os.name == "nt"
    else ["TMPDIR", "XDG_CONFIG_HOME"])
env = {k: os.environ[k] for k in KEEP if k in os.environ}
env.update({k: v for k, v in os.environ.items() if k.upper().startswith("LC_")})
env["WLOG_LEVEL"] = "ERROR"
if expect_sandbox:
    env["JTERM_HELPER_SELFTEST"] = "1"
p = subprocess.Popen([helper], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, env=env)
p.stdin.write(frame(0x01, struct.pack("<H", version)) + frame(0x02, connect))
p.stdin.flush()

seen = []
disconnected = None
while True:
    head = p.stdout.read(5)
    if len(head) < 5:
        break
    length, kind = struct.unpack("<IB", head)
    body = p.stdout.read(length - 1)
    seen.append(kind)
    if kind == 0x81:
        reply_version = struct.unpack_from("<H", body)[0]
        if reply_version != version:
            sys.exit("HELLO_REPLY version %d, expected %d" % (reply_version, version))
    elif kind == 0x8F:
        code = struct.unpack_from("<I", body)[0]
        n = struct.unpack_from("<H", body, 4)[0]
        disconnected = (code, body[6:6 + n].decode(errors="replace"))
        break
p.stdin.close()
try:
    rc = p.wait(timeout=20)
except subprocess.TimeoutExpired:
    p.kill()
    sys.exit("helper did not exit after DISCONNECTED")
err = p.stderr.read().decode(errors="replace").strip()

if 0x81 not in seen:
    sys.exit("no HELLO_REPLY: the helper did not start (exit %s)\n%s" % (rc, err))
if disconnected is None:
    sys.exit("no DISCONNECTED (exit %s)\n%s" % (rc, err))
code, message = disconnected
if (code >> 16) != 0x0002:
    sys.exit("unexpected DISCONNECTED 0x%08X %s" % (code, message))
# Available, and (macOS) not refused by the sandbox when the helper decodes a frame after its
# lockdown: the DMG jobs then check the Seatbelt profile against VideoToolbox on real hardware.
h264 = "H.264 decoder available" in err and "H.264 decoder refused under the sandbox" not in err
if expect_h264 and not h264:
    sys.exit("no working H.264 decoder\n%s" % err)
aac = "AAC decoder available" in err
if expect_aac and not aac:
    sys.exit("no AAC decoder\n%s" % err)
sandbox = re.search(r"jterm-rdp-helper: sandbox (level=(\w+).*)", err)
level = sandbox.group(2) if sandbox else "none"
if expect_sandbox:
    if not sandbox:
        sys.exit("the helper reported no sandbox line\n%s" % err)
    if LEVELS.index(level) < LEVELS.index(expect_sandbox):
        sys.exit("sandbox %s, expected %s\n%s" % (sandbox.group(1), expect_sandbox, err))
    tests = re.findall(r"jterm-rdp-helper: selftest (\S+) (blocked|ALLOWED)", err)
    allowed = [name for name, result in tests if result == "ALLOWED"]
    # Every self-test attempt must be refused: each platform tests only what its lockdown promises.
    if expect_sandbox != "none" and (not tests or allowed):
        sys.exit("self-test: %s\n%s" % ("allowed: " + ", ".join(allowed) if tests else "did not run", err))
print("SMOKE OK: protocol v%d, DISCONNECTED 0x%08X (%s), H.264 %s, AAC %s, sandbox %s, exit %s"
      % (version, code, message, "available" if h264 else "not available",
         "available" if aac else "not available", sandbox.group(1) if sandbox else "none", rc))
