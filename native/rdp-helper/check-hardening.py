#!/usr/bin/env python3
"""Checks that a staged jterm-rdp-helper and the FreeRDP libraries built with it carry the exploit
mitigations of the sandbox design's layer 1 (docs/superpowers/specs/2026-11-11-rdp-helper-sandbox-
design.md). The helper parses a possibly hostile server's bytes; these make a memory-safety bug in
it harder to exploit, and nothing else notices when a toolchain or build-script change drops one.

ELF (Linux), through readelf:
  - the executable is position-independent (ET_DYN)
  - full RELRO: a GNU_RELRO segment and immediate binding (BIND_NOW)
  - no executable stack (GNU_STACK without E)
  - stack protector (__stack_chk_fail) and _FORTIFY_SOURCE (some __*_chk) in the imports
  - on x86-64, CET: IBT and SHSTK in the GNU property note

PE (Windows), parsed here, so no Visual Studio prompt is needed:
  - ASLR with high entropy, DEP (DYNAMIC_BASE, HIGH_ENTROPY_VA, NX_COMPAT)
  - Control Flow Guard (GUARD_CF) and CET shadow-stack compatibility (/CETCOMPAT)

Only files built by jterm's scripts (the helper, FreeRDP, WinPR) must pass everything. Libraries
taken from elsewhere (the distribution's cJSON, vcpkg's OpenSSL and zlib, the MSVC runtime) are
checked for the baseline only — ASLR and DEP on Windows, no executable stack on Linux — and their
other results are printed as information.

usage: check-hardening.py <stage directory>
"""
import os
import platform
import re
import struct
import subprocess
import sys

def is_ours(name):
    return name.lower().startswith(("jterm-rdp-helper", "libfreerdp", "libwinpr", "freerdp", "winpr"))


# ---------------------------------------------------------------------------------------------- ELF

def readelf(*args):
    return subprocess.run(["readelf", "-W", *args], check=True, capture_output=True,
                          text=True).stdout


def elf_checks(path):
    """(name, passed) pairs, and whether the file is the executable."""
    header = readelf("-h", path)
    executable = os.path.basename(path).startswith("jterm-rdp-helper")
    segments = readelf("-l", path)
    dynamic = readelf("-d", path)
    symbols = readelf("--dyn-syms", path)
    imported = set()
    for line in symbols.splitlines():
        parts = line.split()
        # Num: Value Size Type Bind Vis Ndx Name — undefined (imported) symbols have Ndx UND.
        if len(parts) >= 8 and parts[6] == "UND":
            imported.add(parts[7].split("@")[0])
    checks = []
    if executable:
        checks.append(("PIE", re.search(r"Type:\s+DYN", header) is not None))
    checks.append(("RELRO", "GNU_RELRO" in segments))
    checks.append(("BIND_NOW", re.search(r"\(BIND_NOW\)|\(FLAGS\).*\bNOW\b|\(FLAGS_1\).*\bNOW\b",
                                         dynamic) is not None))
    stack = re.search(r"GNU_STACK\s+(\S+\s+){5}(\S+)", segments)
    checks.append(("NX stack", stack is not None and "E" not in stack.group(2)))
    checks.append(("stack protector", "__stack_chk_fail" in imported))
    checks.append(("FORTIFY", any(re.fullmatch(r"__\w+_chk", s) for s in imported)))
    if re.search(r"Machine:\s+Advanced Micro Devices X86-64", header):
        notes = readelf("-n", path)
        feature = re.search(r"x86 feature: (.*)", notes)
        flags = feature.group(1) if feature else ""
        checks.append(("CET (IBT, SHSTK)", "IBT" in flags and "SHSTK" in flags))
    return checks


ELF_BASELINE = {"NX stack"}

# ----------------------------------------------------------------------------------------------- PE

DYNAMIC_BASE = 0x0040
HIGH_ENTROPY_VA = 0x0020
NX_COMPAT = 0x0100
GUARD_CF = 0x4000
DEBUG_TYPE_EX_DLLCHARACTERISTICS = 20
EX_CET_COMPAT = 0x01


def pe_checks(path):
    with open(path, "rb") as f:
        data = f.read()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("not a PE file")
    coff = pe + 4
    sections_count = struct.unpack_from("<H", data, coff + 2)[0]
    optional_size = struct.unpack_from("<H", data, coff + 16)[0]
    opt = coff + 20
    magic = struct.unpack_from("<H", data, opt)[0]
    pe32plus = magic == 0x20B
    dll_chars = struct.unpack_from("<H", data, opt + 70)[0]
    data_dirs = opt + (112 if pe32plus else 96)
    debug_rva, debug_size = struct.unpack_from("<II", data, data_dirs + 6 * 8)

    sections = []
    table = opt + optional_size
    for i in range(sections_count):
        s = table + 40 * i
        vsize, vaddr, raw_size, raw_ptr = struct.unpack_from("<IIII", data, s + 8)
        sections.append((vaddr, max(vsize, raw_size), raw_ptr))

    def file_offset(rva):
        for vaddr, size, raw_ptr in sections:
            if vaddr <= rva < vaddr + size:
                return raw_ptr + rva - vaddr
        return None

    cet = False
    start = file_offset(debug_rva) if debug_rva else None
    if start is not None:
        for i in range(debug_size // 28):
            entry = start + 28 * i
            kind, size, _, raw = struct.unpack_from("<IIII", data, entry + 12)
            if kind == DEBUG_TYPE_EX_DLLCHARACTERISTICS and size >= 4:
                cet = bool(struct.unpack_from("<I", data, raw)[0] & EX_CET_COMPAT)
    return [
        ("ASLR", bool(dll_chars & DYNAMIC_BASE)),
        ("high-entropy ASLR", not pe32plus or bool(dll_chars & HIGH_ENTROPY_VA)),
        ("DEP", bool(dll_chars & NX_COMPAT)),
        ("CFG", bool(dll_chars & GUARD_CF)),
        ("CET compatible", cet),
    ]


PE_BASELINE = {"ASLR", "high-entropy ASLR", "DEP"}

# --------------------------------------------------------------------------------------------- main


def kind_of(path):
    if os.path.islink(path) or not os.path.isfile(path):
        return None
    with open(path, "rb") as f:
        head = f.read(4)
    if head == b"\x7fELF":
        return "elf"
    if head[:2] == b"MZ":
        return "pe"
    return None


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: check-hardening.py <stage directory>")
    stage = sys.argv[1]
    failed = []
    checked = 0
    for name in sorted(os.listdir(stage)):
        path = os.path.join(stage, name)
        kind = kind_of(path)
        if kind is None:
            continue
        checked += 1
        checks = elf_checks(path) if kind == "elf" else pe_checks(path)
        baseline = ELF_BASELINE if kind == "elf" else PE_BASELINE
        ours = is_ours(name)
        parts = []
        for check, passed in checks:
            required = ours or check in baseline
            parts.append(("" if passed else ("MISSING " if required else "no ")) + check)
            if required and not passed:
                failed.append("%s: %s" % (name, check))
        print("%-32s %s%s" % (name, ", ".join(parts), "" if ours else "  (not built by jterm)"))
    if checked == 0:
        sys.exit("no ELF or PE files in %s" % stage)
    if failed:
        sys.exit("HARDENING CHECK FAILED:\n  " + "\n  ".join(failed))
    print("HARDENING OK: %d files (%s)" % (checked, platform.system() or "?"))


if __name__ == "__main__":
    main()
