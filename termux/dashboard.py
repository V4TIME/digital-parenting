#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ChildGuard Termux Dashboard
===========================
A terminal dashboard for parents that runs in Termux (Android) or any Linux
terminal. It reads events pushed by the ChildGuard Android app (either
directly from the local log database or by syncing with the ChildGuard
server), and gives a parent full visibility plus control:

    - live overview / child status
    - screen time per app
    - interception logs (WhatsApp / browser / messaging) with keyword flags
    - alert stream (stranger detection, dangerous content)
    - custom keyword flag management
    - app blocklist management
    - one-command sync from the server
    - export logs (tar.gz) including config snapshot
    - retention / cleanup

Only the Python standard library is used -- no pip dependencies, so it runs
on a fresh Termux with `pkg install python`.

Usage:
    python3 dashboard.py                 # interactive terminal UI
    python3 dashboard.py --sync          # pull latest events from server, then UI
    python3 dashboard.py --doctor        # check server + token (exit code for scripts)
    python3 dashboard.py --overview      # one-shot overview (non-interactive)
    python3 dashboard.py --screen-time   # one-shot per-app screen time
    python3 dashboard.py --logs "query"  # one-shot log search
    python3 dashboard.py --alerts        # one-shot alerts
    python3 dashboard.py --export        # create an export bundle
    python3 dashboard.py --cleanup 30   # delete logs older than 30 days
"""

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
import tarfile
import tempfile
import time
import urllib.request
import urllib.error

# --------------------------------------------------------------------------
# ANSI helpers
# --------------------------------------------------------------------------
class C:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    BLUE = "\033[34m"
    MAGENTA = "\033[35m"
    CYAN = "\033[36m"
    WHITE = "\033[37m"
    BG_RED = "\033[41m"
    BG_GREEN = "\033[42m"


def c(code, text):
    return f"{code}{text}{C.RESET}"


def red(t): return c(C.RED, t)
def green(t): return c(C.GREEN, t)
def yellow(t): return c(C.YELLOW, t)
def blue(t): return c(C.BLUE, t)
def cyan(t): return c(C.CYAN, t)
def magenta(t): return c(C.MAGENTA, t)
def bold(t): return c(C.BOLD, t)
def dim(t): return c(C.DIM, t)

# C0/C1 controls plus bidi overrides. Captured chat text is attacker-controlled:
# an ESC or OSC sequence could clear the screen, spoof a prompt, or set the
# clipboard (OSC 52). Never print it raw to a terminal.
_UNSAFE_TERMINAL = re.compile(r"[\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]")


def safe_text(value, limit=None):
    """Strip terminal control characters from untrusted text."""
    text = _UNSAFE_TERMINAL.sub(" ", str(value if value is not None else ""))
    if limit is not None:
        text = text[:limit]
    return text

# --------------------------------------------------------------------------
# Paths / environment
# --------------------------------------------------------------------------
HOME = os.path.expanduser("~")
BASE = os.environ.get("CHILDGUARD_HOME", os.path.join(HOME, ".childguard"))
DB_PATH = os.path.join(BASE, "logs.db")
CONFIG_PATH = os.path.join(BASE, "config.json")
EXPORT_DIR = os.path.join(BASE, "exports")
DEFAULT_SERVER = "http://127.0.0.1:38080"
DEFAULT_TOKEN = ""

# Matches both collectors: a client clock may be up to an hour ahead. A
# timestamp further in the future is treated as clock skew and clamped.
TS_FUTURE_ALLOWANCE_MS = 60 * 60 * 1000

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id       TEXT PRIMARY KEY,
    ts       INTEGER NOT NULL,
    type     TEXT NOT NULL,
    package  TEXT DEFAULT '',
    data     TEXT DEFAULT '',
    child    TEXT DEFAULT 'default'
);
CREATE TABLE IF NOT EXISTS alerts (
    id       TEXT PRIMARY KEY,
    ts       INTEGER NOT NULL,
    severity TEXT DEFAULT 'info',
    reason   TEXT DEFAULT '',
    detail   TEXT DEFAULT ''
);
CREATE TABLE IF NOT EXISTS config (
    key   TEXT PRIMARY KEY,
    value TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts);
CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts);
"""

KEYWORD_KEY = "keywords"
BLOCKLIST_KEY = "blocklist"
RETENTION_KEY = "retention_days"
LAST_SYNC_KEY = "last_sync"


def ensure_env():
    for d in (BASE, EXPORT_DIR):
        os.makedirs(d, exist_ok=True)
        try:
            os.chmod(d, 0o700)
        except Exception:
            pass
    con = sqlite3.connect(DB_PATH)
    con.executescript(SCHEMA)
    con.commit()
    try:
        os.chmod(DB_PATH, 0o600)
    except Exception:
        pass
    if os.path.exists(CONFIG_PATH):
        try:
            os.chmod(CONFIG_PATH, 0o600)
        except Exception:
            pass
    return con


# --------------------------------------------------------------------------
# Config helpers
# --------------------------------------------------------------------------
def load_config():
    """Return a dict with string `server`/`token`.

    A truncated, hand-edited, or wrong-version config.json can parse to `[]`,
    `null`, or a string. Returning that verbatim made every command crash with
    `AttributeError: 'list' object has no attribute 'get'`. Validate the shape
    and coerce the two fields the rest of the program relies on.
    """
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = None
        if isinstance(data, dict):
            if not isinstance(data.get("server"), str):
                data["server"] = DEFAULT_SERVER
            if not isinstance(data.get("token"), str):
                data["token"] = DEFAULT_TOKEN
            return data
    return {"server": DEFAULT_SERVER, "token": DEFAULT_TOKEN}


def save_config(cfg):
    """Write config.json atomically and owner-only.

    The config holds the parent token. Opening the final path with `open()`
    would briefly create it with the process umask (0644 on a default umask),
    so write to a 0600 temp file, fsync it, then rename it into place. A
    reader never sees a partial file, and a crash cannot leave a token in a
    world-readable inode.
    """
    os.makedirs(BASE, exist_ok=True)
    try:
        os.chmod(BASE, 0o700)
    except OSError:
        pass

    tmp = f"{CONFIG_PATH}.tmp"
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            os.fchmod(f.fileno(), 0o600)  # defeat a permissive umask explicitly
            json.dump(cfg, f, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, CONFIG_PATH)
        os.chmod(CONFIG_PATH, 0o600)
    finally:
        try:
            if os.path.exists(tmp):
                os.unlink(tmp)
        except OSError:
            pass


def get_cfg(con, key, default=""):
    row = con.execute("SELECT value FROM config WHERE key=?", (key,)).fetchone()
    if row is None:
        return default
    try:
        return json.loads(row[0])
    except Exception:
        return row[0]


def set_cfg(con, key, value):
    con.execute(
        "INSERT INTO config(key,value) VALUES(?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, json.dumps(value)),
    )
    con.commit()


# --------------------------------------------------------------------------
# Server sync
# --------------------------------------------------------------------------
# The collector caps its own responses, but the dashboard is also pointed at
# whatever URL the parent typed. A hostile or buggy endpoint could stream a
# multi-gigabyte body and exhaust Termux memory; read at most this much.
MAX_RESPONSE_BYTES = 32 * 1024 * 1024


def http_get_json(url, token, timeout=10):
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        # Read in bounded chunks so a missing/incorrect Content-Length cannot
        # make .read() allocate without limit.
        buf = bytearray()
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            buf.extend(chunk)
            if len(buf) > MAX_RESPONSE_BYTES:
                raise ValueError(
                    f"response from {url} exceeded {MAX_RESPONSE_BYTES} bytes"
                )
        return json.loads(buf.decode("utf-8"))


def run_doctor(cfg, timeout=5):
    """Probe the collector the way the dashboard does and report a verdict.

    Returns a process exit code so scripts can gate on it:
      0 healthy (health + authenticated stats both work)
      2 unreachable / bad /health
      3 token rejected (HTTP 401)
      4 unexpected response body
      5 no token configured
    """
    url = (cfg.get("server") or DEFAULT_SERVER).rstrip("/")
    token = cfg.get("token") or DEFAULT_TOKEN

    print(bold("  Connection doctor"))
    print(f"    server : {safe_text(url)}")
    if token:
        print(f"    token  : set ({len(token)} chars)")
    else:
        print("    token  : " + red("missing (run --configure --token ...)"))
        return 5

    # 1. The collector must answer without credentials: this separates
    #    "network/Tailscale is wrong" from "the token is wrong".
    try:
        health = http_get_json(f"{url}/health", "", timeout=timeout)
    except urllib.error.HTTPError as e:
        print("    health : " + red(f"FAILED (HTTP {e.code})"))
        return 2
    except Exception as e:
        print("    health : " + red(f"FAILED ({safe_text(e)})"))
        print(dim("             check the URL, that the collector is running, and the tailnet"))
        return 2

    if not isinstance(health, dict) or not health.get("ok"):
        print("    health : " + red(f"unexpected response {health!r}"))
        return 4
    print("    health : " + green("OK") + dim(f" (service={safe_text(health.get('service', '?'))})"))

    # 2. Authenticated read: proves the parent token, not just reachability.
    try:
        stats = http_get_json(f"{url}/api/stats", token, timeout=timeout + 3)
    except urllib.error.HTTPError as e:
        if e.code == 401:
            print("    auth   : " + red("FAILED (401 Unauthorized — token does not match)"))
            return 3
        print("    auth   : " + red(f"FAILED (HTTP {e.code})"))
        return 2
    except Exception as e:
        print("    auth   : " + red(f"FAILED ({safe_text(e)})"))
        return 2

    if not isinstance(stats, dict):
        print("    auth   : " + red(f"unexpected response {stats!r}"))
        return 4

    children = stats.get("children")
    if not isinstance(children, list):
        children = []
    # A child entry is server-supplied; a string/null must not crash doctor.
    children = [c for c in children if isinstance(c, dict)]
    online = sum(1 for c in children if c.get("online"))
    print("    auth   : " + green("OK"))
    print(
        "    data   : "
        f"{stats.get('totalEvents', 0)} events, "
        f"{stats.get('totalAlerts', 0)} alerts, "
        f"{len(children)} child(ren), {online} online"
    )
    if stats.get("totalEvents", 0) == 0:
        print(dim("             no events yet — open the app and type in a monitored app"))
    print(green("  doctor: healthy"))
    return 0


def _response_list(resp, key):
    """Return the list under `key`, or [] for any malformed response shape.

    Sync runs from cron against whatever the collector returns. A bare list,
    `{"events": null}`, or a non-dict row must be skipped, not raise: the
    traceback used to abort the whole sync and leave LAST_SYNC untouched.
    """
    if not isinstance(resp, dict):
        return []
    value = resp.get(key)
    return value if isinstance(value, list) else []


def sync_from_server(con, cfg, verbose=True):
    """Pull events + alerts from the server into the local database."""
    url = cfg.get("server", DEFAULT_SERVER).rstrip("/")
    token = cfg.get("token", DEFAULT_TOKEN)
    if not url:
        if verbose:
            print(yellow("No server configured. Run configure first."))
        return 0
    added = 0
    try:
        ev_resp = http_get_json(f"{url}/api/events?limit=5000", token)
        al_resp = http_get_json(f"{url}/api/alerts?limit=500", token)
    except Exception as e:
        if verbose:
            print(red(f"Sync failed: {safe_text(e)}"))
        return 0

    def _clean_id(obj, prefix):
        raw = obj.get("id")
        if isinstance(raw, str) and raw:
            return raw
        return prefix + hashlib.sha1(
            json.dumps(obj, sort_keys=True, default=str).encode("utf-8")
        ).hexdigest()[:16]

    def _clean_ts(raw):
        # INSERT declares ts INTEGER NOT NULL, but a malformed/hostile server
        # response can send a string or null. Coerce to an int instead of
        # letting sqlite raise and silently drop the row.
        #
        # Both collectors treat a missing/null/zero/negative timestamp as
        # "now" and clamp future clock skew to one hour. Copying only the
        # type coercion here meant a legacy or hand-written record with ts=0
        # was stored at 1970 and sorted below everything, and 1e400 (JSON
        # "Infinity") raised OverflowError inside the row loop.
        now = int(time.time() * 1000)
        try:
            ts = int(float(raw))
        except (TypeError, ValueError, OverflowError):
            return now
        if ts <= 0:
            return now
        return min(ts, now + TS_FUTURE_ALLOWANCE_MS)

    for ev in _response_list(ev_resp, "events"):
        if not isinstance(ev, dict):
            continue
        _id = _clean_id(ev, "e")
        try:
            before = con.total_changes
            con.execute(
                "INSERT OR IGNORE INTO events(id,ts,type,package,data,child) "
                "VALUES(?,?,?,?,?,?)",
                (
                    _id,
                    _clean_ts(ev.get("ts")),
                    str(ev.get("type") or "unknown"),
                    str(ev.get("package") or ""),
                    str(ev.get("data") or ""),
                    str(ev.get("childId") or "default"),
                ),
            )
            added += con.total_changes - before
        except Exception:
            pass
    for al in _response_list(al_resp, "alerts"):
        if not isinstance(al, dict):
            continue
        _id = _clean_id(al, "a")
        detail = al.get("detail")
        try:
            before = con.total_changes
            con.execute(
                "INSERT OR IGNORE INTO alerts(id,ts,severity,reason,detail) "
                "VALUES(?,?,?,?,?)",
                (
                    _id,
                    _clean_ts(al.get("ts")),
                    str(al.get("severity") or "info"),
                    str(al.get("reason") or al.get("type") or "alert"),
                    detail if isinstance(detail, str) else json.dumps(al),
                ),
            )
            added += con.total_changes - before
        except Exception:
            pass
    con.commit()
    set_cfg(con, LAST_SYNC_KEY, int(time.time()))
    if verbose:
        print(green(f"Sync complete: {added} new records"))
    return added


# --------------------------------------------------------------------------
# Analysis queries
# --------------------------------------------------------------------------
def event_label(t):
    labels = {
        "screen_on": "screen on",
        "screen_off": "screen off",
        "screen_active": "screen activity",
        "app_usage": "app usage",
        "keylog": "typing",
        "keystroke": "button text",
        "screen_text": "screen text",
        "service_connected": "monitor online",
        "notification": "notification",
        "stranger_alert": "STRANGER ALERT",
        "stranger_notification": "stranger notification",
        "browser": "browser",
        "url": "URL",
        "call": "call",
        "sms": "SMS",
        "clipboard": "clipboard",
        "vpn": "vpn",
    }
    return safe_text(labels.get(t, t))


def alert_reason(r):
    labels = {
        "stranger_alert": "stranger detected",
        "stranger_notification": "stranger notification",
        "dangerous_content": "dangerous content",
    }
    return safe_text(labels.get(r, r))


# Trailing segments that describe the platform rather than the app.
_GENERIC_PKG_SUFFIXES = {"android", "app", "apps", "client", "mobile", "beta", "debug"}


def pkg_name(pkg):
    pkg = safe_text(pkg)
    if not pkg:
        return "(system)"
    parts = [p for p in pkg.split(".") if p]
    # com.instagram.android used to render as "Android"; strip platform
    # segments so the parent sees the app, not the OS.
    while len(parts) > 1 and parts[-1].lower() in _GENERIC_PKG_SUFFIXES:
        parts.pop()
    if len(parts) >= 2:
        return safe_text(parts[-1].capitalize())
    return pkg


def severity_color(sev):
    sev = (sev or "").lower()
    if sev in ("critical", "danger"):
        return red
    if sev in ("high", "warning"):
        return yellow
    if sev in ("medium", "moderate"):
        return cyan
    return dim


def format_ts(ts, fmt="%Y-%m-%d %H:%M:%S"):
    try:
        return dt.datetime.fromtimestamp(ts / 1000).strftime(fmt)
    except Exception:
        return safe_text(ts)


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------
def banner():
    return "\n".join([
        bold(cyan("  ╔════════════════════════════════════════════════════╗")),
        bold(cyan("  ║   ChildGuard · Termux Parent Dashboard             ║")),
        bold(cyan("  ║   child safety, visibility, control               ║")),
        bold(cyan("  ╚════════════════════════════════════════════════════╝")),
    ])


def overview(con, cfg):
    total = con.execute("SELECT COUNT(*) FROM events").fetchone()[0]
    alerts = con.execute("SELECT COUNT(*) FROM alerts").fetchone()[0]
    last_ts = con.execute("SELECT MAX(ts) FROM events").fetchone()[0] or 0
    by_type = con.execute(
        "SELECT type, COUNT(*) FROM events GROUP BY type ORDER BY 2 DESC"
    ).fetchall()
    by_pkg = con.execute(
        "SELECT package, COUNT(*) FROM events WHERE package!='' "
        "GROUP BY package ORDER BY 2 DESC LIMIT 10"
    ).fetchall()

    print(banner())
    print()
    print(bold("  Server") + f"  {safe_text(cfg.get('server', DEFAULT_SERVER))}")
    print(bold("  Last sync") + f"  {format_ts(get_cfg(con, LAST_SYNC_KEY, 0) * 1000) if get_cfg(con, LAST_SYNC_KEY, 0) else dim('never')}")
    print()
    print(bold("  Totals"))
    print(f"    events   : {cyan(str(total))}")
    print(f"    alerts   : {yellow(str(alerts))}")
    print(f"    last log : {format_ts(last_ts)}")
    print()
    print(bold("  By event type"))
    for t, n in by_type:
        print(f"    {event_label(t):18} {green(str(n))}")
    print()
    print(bold("  Top packages"))
    for p, n in by_pkg:
        print(f"    {pkg_name(p):20} {green(str(n))}")


# Longest gap that can still be treated as continuous use of one app. Screen
# events arrive at least every ~800 ms while a screen changes; a gap longer
# than this is idle/off time, not screen time.
MAX_SESSION_GAP_MS = 5 * 60 * 1000


def compute_screen_durations(rows, max_gap_ms=MAX_SESSION_GAP_MS):
    """Return (event_count, attributed_ms) per package.

    `data` is the class name for screen_active, not a duration, so duration is
    inferred from the gap between consecutive events. The old code clamped that
    gap to one day, which turned an overnight idle gap into up to 24 h of
    screen time for whichever app was opened next.

    Gaps are attributed globally, not per package. Tracking `last_active` per
    package credited the time another app spent in the foreground back to the
    app before it: A at 0, B at 100 s, B at 200 s, A at 300 s reported 300 s
    for A *plus* 100 s for B — 400 s inside a 300 s window. Walking the merged
    timeline attributes each gap to the app that was on screen when it started.
    """
    total = {}
    duration = {}
    # Sync stores the server's newest-first response, so rows are not
    # necessarily chronological. Sorting here keeps the helper correct even
    # when a caller forgets ORDER BY (Timsort is linear on sorted input).
    #
    # `ts` is normally an int, but a hand-edited DB or an old schema can hold
    # text/NULL. Skip rows with a non-numeric timestamp instead of raising
    # TypeError in the sort (which killed the whole screen-time view).
    clean = []
    for pkg, _typ, _data, ts in rows:
        try:
            # Keep timestamps integral: durations are rendered with integer
            # arithmetic (`"#" * (ms // 60000)`), and float durations raise
            # TypeError there. Accept "123.0" but store 123.
            clean.append((str(pkg or ""), int(float(ts))))
        except (TypeError, ValueError):
            continue
    ordered = sorted(clean, key=lambda r: r[1])
    for i, (pkg, ts) in enumerate(ordered):
        total[pkg] = total.get(pkg, 0) + 1
        if i + 1 < len(ordered):
            nxt = ordered[i + 1][1]
            if nxt > ts:
                duration[pkg] = duration.get(pkg, 0) + min(nxt - ts, max_gap_ms)
    return total, duration


def screen_time(con, days=7):
    since = int(time.time() * 1000) - days * 86400 * 1000
    rows = con.execute(
        "SELECT package, type, data, ts FROM events "
        "WHERE ts>=? AND type IN ('screen_active','app_usage','screen_text','screen_on') "
        "ORDER BY ts",
        (since,),
    ).fetchall()
    total, duration = compute_screen_durations(rows)

    print(banner())
    print()
    print(bold(f"  Screen / app time  (last {days} days)"))
    print(dim("  ─────────────────────────────────────────────────────"))
    if not total:
        print(yellow("  No usage data yet. Start the app monitor and sync."))
        return
    ranked = sorted(total.items(), key=lambda kv: duration.get(kv[0], 0) or kv[1], reverse=True)
    width = 26
    for pkg, n in ranked[:15]:
        bar = "#" * min(40, (duration.get(pkg, 0) // 60000) + 1)
        mins = duration.get(pkg, 0) // 60000
        print(f"    {pkg_name(pkg):{width}} {cyan(f'{mins:5d}m')} {dim(bar)}")


def show_logs(con, query=None, limit=50, pkg_filter=None):
    sql = "SELECT ts,type,package,data,child FROM events"
    conds = []
    args = []
    if query:
        conds.append("(data LIKE ? OR type LIKE ? OR package LIKE ?)")
        like = f"%{query}%"
        args += [like, like, like]
    if pkg_filter:
        conds.append("package=?")
        args.append(pkg_filter)
    if conds:
        sql += " WHERE " + " AND ".join(conds)
    sql += " ORDER BY ts DESC LIMIT ?"
    args.append(limit)
    rows = con.execute(sql, args).fetchall()

    print(banner())
    print()
    print(bold("  Interception log"))
    print(dim("  ─────────────────────────────────────────────────────"))
    if not rows:
        print(yellow("  No matching entries."))
        return

    keywords = [k.lower() for k in get_cfg(con, KEYWORD_KEY, [])]
    for ts, typ, pkg, data, child in rows:
        # `data` is nullable in the local schema; older rows and hand-edited
        # DBs can contain NULL, which used to raise AttributeError here and
        # kill the whole log view.
        data = data or ""
        flagged = any(k in data.lower() for k in keywords) if keywords else False
        line = f"  [{format_ts(ts)}] {pkg_name(pkg):18} {event_label(typ):16}"
        if flagged:
            line = yellow(line)
        print(line)
        snippet = safe_text(data or "", 120)
        if len(data or "") > 120:
            snippet += "…"
        if snippet:
            flag = yellow(" ⚠ flagged") if flagged else ""
            print(f"      {dim(snippet)}{flag}")

    print()
    print(dim(f"  showing newest {len(rows)} of matching entries"))


def show_alerts(con, limit=50):
    rows = con.execute(
        "SELECT ts,severity,reason,detail FROM alerts ORDER BY ts DESC LIMIT ?",
        (limit,),
    ).fetchall()
    print(banner())
    print()
    print(bold("  Alert stream"))
    print(dim("  ─────────────────────────────────────────────────────"))
    if not rows:
        print(green("  No alerts. All clear."))
        return
    for ts, sev, reason, detail in rows:
        color = severity_color(sev)
        print(f"  {color('●')} [{format_ts(ts)}] {color(safe_text(sev).upper())} — {alert_reason(reason)}")
        try:
            d = json.loads(detail or "{}")
            # Old rows wrapped the whole alert; unwrap the inner detail string.
            if isinstance(d.get("detail"), str):
                try:
                    d = json.loads(d["detail"])
                except Exception:
                    pass
            snippet = d.get("data") or d.get("text") or d.get("combined") or ""
            if snippet:
                print(f"      {dim(safe_text(snippet, 140))}")
        except Exception:
            if detail:
                print(f"      {dim(safe_text(detail, 140))}")


def manage_keywords(con):
    words = get_cfg(con, KEYWORD_KEY, [])
    print(banner())
    print()
    print(bold("  Keywords flagged in logs"))
    if words:
        for i, w in enumerate(words, 1):
            print(f"    {i}. {red(w)}")
    else:
        print(dim("    (none)"))
    print()
    print(bold("  Actions: [a]dd  [r]emove  [d]one"))
    choice = input("  → ").strip().lower()
    if choice == "a":
        w = input("  keyword: ").strip().lower()
        if w and w not in words:
            words.append(w)
            set_cfg(con, KEYWORD_KEY, words)
            print(green(f"  added '{w}'"))
    elif choice == "r":
        try:
            idx = int(input("  number to remove: ").strip()) - 1
            if idx < 0 or idx >= len(words):
                raise ValueError("out of range")
            removed = words.pop(idx)
            set_cfg(con, KEYWORD_KEY, words)
            print(green(f"  removed '{safe_text(removed)}'"))
        except Exception:
            print(red("  bad index"))
    else:
        return
    manage_keywords(con)


def manage_blocklist(con):
    bl = get_cfg(con, BLOCKLIST_KEY, [])
    print(banner())
    print()
    print(bold("  App blocklist (packages blocked on child device)"))
    if bl:
        for i, p in enumerate(bl, 1):
            print(f"    {i}. {p}")
    else:
        print(dim("    (none)"))
    print()
    print(bold("  Actions: [a]dd  [r]emove  [d]one"))
    choice = input("  → ").strip().lower()
    if choice == "a":
        p = input("  package (e.g. com.whatsapp): ").strip()
        if p and p not in bl:
            bl.append(p)
            set_cfg(con, BLOCKLIST_KEY, bl)
            print(green(f"  added '{p}'"))
    elif choice == "r":
        try:
            idx = int(input("  number to remove: ").strip()) - 1
            # Negative indexes silently remove entries from the end (0 -> last
            # item); validate against the list bounds first.
            if idx < 0 or idx >= len(bl):
                raise ValueError("out of range")
            removed = bl.pop(idx)
            set_cfg(con, BLOCKLIST_KEY, bl)
            print(green(f"  removed '{safe_text(removed)}'"))
        except Exception:
            print(red("  bad index"))
    else:
        return
    manage_blocklist(con)


def configure(con):
    cfg = load_config()
    print(banner())
    print()
    print(bold("  Configure connection"))
    cur = input(f"  server URL [{safe_text(cfg.get('server', DEFAULT_SERVER))}]: ").strip()
    if cur:
        cfg["server"] = cur.rstrip("/")
    current_token = cfg.get("token", "")
    # Never echo the secret into the terminal or its scrollback. Enter keeps it.
    hint = f"set, {len(current_token)} chars" if current_token else "unset"
    tok = input(f"  token [{hint}]: ").strip()
    if tok:
        cfg["token"] = tok
    save_config(cfg)
    print(green("  saved. run --sync to pull data."))


def open_private(path, mode="w", encoding="utf-8"):
    """Open a file owner-only from the first byte.

    `open(path, "w")` inherits the umask, so an export temp file or tarball
    is world-readable until a later chmod — and stays that way if the process
    is interrupted. O_CREAT with 0600 plus fchmod closes that window.
    """
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    fd = os.open(path, flags, 0o600)
    try:
        os.fchmod(fd, 0o600)
    except OSError:
        pass
    return os.fdopen(fd, mode, encoding=encoding)


def _owner_only_tarinfo(ti):
    """Archive members must not extract as 0644 for another local user."""
    ti.mode = 0o600
    ti.uid = 0
    ti.gid = 0
    ti.uname = ""
    ti.gname = ""
    return ti


def _claim_export_path(stamp):
    """Atomically reserve a bundle name and return (path, fd).

    The stamp has one-second resolution, so a retry or a double invocation in
    the same second used to truncate the previous bundle. O_EXCL makes the
    claim race-free and keeps the name readable.
    """
    for n in range(1, 1000):
        suffix = "" if n == 1 else f"-{n}"
        path = os.path.join(EXPORT_DIR, f"childguard_logs_{stamp}{suffix}.tar.gz")
        try:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            continue
        try:
            os.fchmod(fd, 0o600)
        except OSError:
            pass
        return path, fd
    raise RuntimeError("could not allocate a unique export name")


def _private_temp(tag, stamp, suffix):
    """Create one owner-only staging file with a collision-free name."""
    fd, path = tempfile.mkstemp(prefix=f"{tag}_{stamp}_", suffix=suffix,
                                dir=EXPORT_DIR)
    try:
        os.fchmod(fd, 0o600)
    except OSError:
        pass
    return path, os.fdopen(fd, "w", encoding="utf-8")


def do_export(con):
    stamp = time.strftime("%Y%m%d-%H%M%S")
    path, bundle_fd = _claim_export_path(stamp)
    tmp_json = tmp_alerts = cfg_snapshot = None

    try:
        tmp_json, f = _private_temp("events", stamp, ".jsonl")
        with f:
            for row in con.execute("SELECT ts,type,package,data,child FROM events ORDER BY ts"):
                f.write(json.dumps({"ts": row[0], "type": row[1], "package": row[2],
                                    "data": row[3], "child": row[4]}) + "\n")
        tmp_alerts, f = _private_temp("alerts", stamp, ".jsonl")
        with f:
            for row in con.execute("SELECT ts,severity,reason,detail FROM alerts ORDER BY ts"):
                f.write(json.dumps({"ts": row[0], "severity": row[1],
                                    "reason": row[2], "detail": row[3]}) + "\n")
        cfg_snapshot, f = _private_temp("config_snapshot", stamp, ".json")
        with f:
            json.dump({
                "keywords": get_cfg(con, KEYWORD_KEY, []),
                "blocklist": get_cfg(con, BLOCKLIST_KEY, []),
                "configured_server": load_config().get("server"),
            }, f, indent=2)

        # The bundle was created 0600 by O_EXCL; chmod-after-write left a
        # readable window and a crashed export left the file readable forever.
        with os.fdopen(bundle_fd, "wb") as raw:
            with tarfile.open(fileobj=raw, mode="w:gz") as tar:
                tar.add(tmp_json, arcname="events.jsonl", filter=_owner_only_tarinfo)
                tar.add(tmp_alerts, arcname="alerts.jsonl", filter=_owner_only_tarinfo)
                tar.add(cfg_snapshot, arcname="config_snapshot.json",
                        filter=_owner_only_tarinfo)
        print(green(f"  exported → {path}"))
    except BaseException:
        # A failed export must not leave a truncated bundle or an open fd.
        try:
            os.close(bundle_fd)
        except OSError:
            pass
        try:
            os.remove(path)
        except OSError:
            pass
        raise
    finally:
        # Interrupted exports must not leave captured messages behind.
        for leftover in (tmp_json, tmp_alerts, cfg_snapshot):
            if not leftover:
                continue
            try:
                os.remove(leftover)
            except OSError:
                pass


def do_cleanup(con, days):
    days = max(1, int(days))
    cutoff = int(time.time() * 1000) - days * 86400 * 1000
    ev = con.execute("DELETE FROM events WHERE ts<?", (cutoff,)).rowcount
    al = con.execute("DELETE FROM alerts WHERE ts<?", (cutoff,)).rowcount
    con.commit()
    print(green(f"  removed {ev} events, {al} alerts older than {days} days"))


# --------------------------------------------------------------------------
# Interactive loop
# --------------------------------------------------------------------------
def interactive(con):
    cfg = load_config()
    while True:
        try:
            os.system("clear")
        except Exception:
            pass
        print(banner())
        print()
        print(bold("  MENU"))
        print("    1.  Overview")
        print("    2.  Screen time / app usage")
        print("    3.  Interception logs (search)")
        print("    4.  Alerts")
        print("    5.  Keyword flags")
        print("    6.  App blocklist")
        print("    7.  Sync from server")
        print("    8.  Configure connection")
        print("    9.  Export logs")
        print("   10.  Cleanup / retention")
        print("    0.  Quit")
        print()
        choice = input("  → ").strip()

        if choice == "1":
            overview(con, cfg)
        elif choice == "2":
            screen_time(con)
        elif choice == "3":
            print()
            q = input("  search (enter = all): ").strip()
            show_logs(con, q or None)
        elif choice == "4":
            show_alerts(con)
        elif choice == "5":
            manage_keywords(con)
        elif choice == "6":
            manage_blocklist(con)
        elif choice == "7":
            sync_from_server(con, cfg)
        elif choice == "8":
            configure(con)
        elif choice == "9":
            do_export(con)
        elif choice == "10":
            d = input("  keep logs newer than N days [30]: ").strip() or "30"
            do_cleanup(con, d)
        elif choice == "0":
            print(dim("  bye, parent. 👋"))
            sys.exit(0)

        print()
        input(dim("  [enter] continue…"))


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(prog="childguard-dashboard")
    ap.add_argument("--sync", action="store_true", help="sync from server before showing")
    ap.add_argument("--doctor", action="store_true",
                    help="check server reachability + token, exit non-zero on failure")
    ap.add_argument("--overview", action="store_true", help="one-shot overview")
    ap.add_argument("--screen-time", action="store_true", help="one-shot screen time")
    ap.add_argument("--alerts", action="store_true", help="one-shot alerts")
    ap.add_argument("--logs", metavar="QUERY", help="one-shot log search")
    ap.add_argument("--export", action="store_true", help="export logs bundle")
    ap.add_argument("--cleanup", metavar="DAYS", type=int,
                    help="delete logs older than DAYS")
    ap.add_argument("--configure", action="store_true",
                    help="save server/token without the interactive menu")
    ap.add_argument("--server", metavar="URL", help="server URL for --configure")
    ap.add_argument("--token", metavar="TOKEN", help="parent token for --configure")
    args = ap.parse_args()

    con = ensure_env()
    cfg = load_config()

    if args.configure:
        if args.server:
            cfg["server"] = args.server.rstrip("/")
        if args.token is not None:
            cfg["token"] = args.token
        save_config(cfg)
        print(green(f"  configured server={safe_text(cfg.get('server'))}"))
        return

    if args.doctor:
        sys.exit(run_doctor(cfg))

    if args.sync:
        sync_from_server(con, cfg)
    if args.overview:
        overview(con, cfg)
        return
    if args.screen_time:
        screen_time(con)
        return
    if args.alerts:
        show_alerts(con)
        return
    if args.logs is not None:
        show_logs(con, args.logs or None)
        return
    if args.export:
        do_export(con)
        return
    if args.cleanup:
        do_cleanup(con, args.cleanup)
        return

    # `--sync` is documented as "sync, then show the UI". When there is no
    # terminal (cron, a pipe, CI), opening the menu only raises EOFError.
    if not sys.stdin.isatty():
        print(dim("  non-interactive terminal: use --overview, --logs, --alerts, "
                  "--screen-time, or --export (sync.py for scheduled pulls)."))
        return

    interactive(con)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
        print(dim("  interrupted."))
        sys.exit(130)
