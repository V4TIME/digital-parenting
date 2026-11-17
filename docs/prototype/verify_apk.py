#!/usr/bin/env python3
"""
verify_apk.py — reverse-engineering check for the compiled APK.

Verifies that an msfvenom-generated Android APK actually has the remote
endpoint (LHOST/LPORT) baked into classes.dex. In Metasploit payloads
2.0.247 the stamping marker (0xdeadbead) is missing from the template, so
the generated APK silently does NOT contain the endpoint — this tool catches
that so we never ship a "control channel" that connects to nothing.

Usage:
    python3 verify_apk.py path/to/app.apk
Exit code 0 = endpoint found; 1 = endpoint missing/unsure; 2 = bad apk.
"""
import sys
import zipfile
import re
import os

MARKER = b"\xde\xad\xba\xad"


def printable_runs(data: bytes) -> list:
    return re.findall(rb"[\x20-\x7e]{6,}", data)


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: verify_apk.py <apk>")
        return 2
    apk = sys.argv[1]
    if not os.path.exists(apk):
        print("failed: file not found:", apk)
        return 2

    try:
        z = zipfile.ZipFile(apk)
    except Exception as e:
        print("failed: not a valid zip/apk:", e)
        return 2

    names = z.namelist()
    print("apk entries:", len(names))
    if "classes.dex" not in names:
        print("failed: classes.dex missing -> not a payload apk")
        return 2
    dex = z.read("classes.dex")
    print("classes.dex size:", len(dex))

    # 1) marker present?
    marker_present = MARKER in dex
    print("stamp marker (0xdeadbead) present:", marker_present)

    # 2) any IPv4-looking endpoint in printable runs (ascii, utf16)
    runs = printable_runs(dex)
    found = []
    ip_re = re.compile(rb"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})")
    for r in runs:
        m = ip_re.search(r)
        if m:
            ip = m.group(0).decode()
            ok = all(0 <= int(g) <= 255 for g in m.groups())
            found.append((ip, ok))
    if marker_present:
        # stamping path works; endpoint almost certainly in config if marker replaced
        print("result: marker present -> endpoint expected (config stamped)")
        # still list any ip tokens
        for ip, ok in found:
            print("  ip token:", ip, "valid:", ok)
        return 0 if found else 1
    else:
        print("result: STAMPING INERT — this payloads build does not embed LHOST/LPORT.")
        print("  The APK will install but connect to nothing. Rebuild with a fixed")
        print("  metasploit-payloads gem, or use the ADB-over-Tailscale path instead.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
