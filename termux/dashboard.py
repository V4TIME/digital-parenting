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
    python3 dashboard.py --overview      # one-shot overview (non-interactive)
    python3 dashboard.py --screen-time   # one-shot per-app screen time
    python3 dashboard.py --logs "query"  # one-shot log search
    python3 dashboard.py --alerts        # one-shot alerts
    python3 dashboard.py --export        # create an export bundle
    python3 dashboard.py --cleanup 30   # delete logs older than 30 days
"""

import argparse
import datetime as dt
import json
import os
import shutil
import sqlite3
import sys
import tarfile
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
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {"server": DEFAULT_SERVER, "token": DEFAULT_TOKEN}


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)


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
def http_get_json(url, token, timeout=10):
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


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
            print(red(f"Sync failed: {e}"))
        return 0

    for ev in ev_resp.get("events", []):
        _id = ev.get("id") or f"e{ev.get('ts')}-{hash(json.dumps(ev, sort_keys=True))}"
        try:
            con.execute(
                "INSERT OR IGNORE INTO events(id,ts,type,package,data,child) "
                "VALUES(?,?,?,?,?,?)",
                (
                    _id,
                    ev.get("ts", time.time() * 1000),
                    ev.get("type", "unknown"),
                    ev.get("package", ""),
                    ev.get("data", ""),
                    ev.get("childId", "default"),
                ),
            )
            added += con.total_changes
        except Exception:
            pass
    for al in al_resp.get("alerts", []):
        _id = al.get("id") or f"a{al.get('ts')}-{hash(json.dumps(al, sort_keys=True))}"
        try:
            con.execute(
                "INSERT OR IGNORE INTO alerts(id,ts,severity,reason,detail) "
                "VALUES(?,?,?,?,?)",
                (
                    _id,
                    al.get("ts", time.time() * 1000),
                    al.get("severity", "info"),
                    al.get("reason") or al.get("type", "alert"),
                    json.dumps(al),
                ),
            )
            added += con.total_changes
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
    return labels.get(t, t)


def pkg_name(pkg):
    if not pkg:
        return "(system)"
    short = pkg.split(".")
    if len(short) >= 2:
        return short[-1].capitalize()
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
        return str(ts)


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
    print(bold("  Server") + f"  {cfg.get('server', DEFAULT_SERVER)}")
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


def screen_time(con, days=7):
    since = int(time.time() * 1000) - days * 86400 * 1000
    rows = con.execute(
        "SELECT package, type, data, ts FROM events "
        "WHERE ts>=? AND (type IN ('screen_active','app_usage') OR data LIKE '%duration%')",
        (since,),
    ).fetchall()
    total = {}
    duration = {}
    for pkg, typ, data, ts in rows:
        total[pkg] = total.get(pkg, 0) + 1
        try:
            d = json.loads(data or "{}")
            ms = d.get("duration") or d.get("dur") or 0
            if ms:
                duration[pkg] = duration.get(pkg, 0) + int(ms)
        except Exception:
            pass

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
        flagged = any(k in data.lower() for k in keywords) if keywords else False
        line = f"  [{format_ts(ts)}] {pkg_name(pkg):18} {event_label(typ):16}"
        if flagged:
            line = yellow(line)
        print(line)
        snippet = (data or "").replace("\n", " ")
        if len(snippet) > 120:
            snippet = snippet[:120] + "…"
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
        print(f"  {color('●')} [{format_ts(ts)}] {color(sev.upper())} — {reason}")
        try:
            d = json.loads(detail or "{}")
            snippet = d.get("data") or d.get("text") or ""
            if snippet:
                print(f"      {dim(snippet[:140])}")
        except Exception:
            if detail:
                print(f"      {dim(detail[:140])}")


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
            removed = words.pop(idx)
            set_cfg(con, KEYWORD_KEY, words)
            print(green(f"  removed '{removed}'"))
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
            removed = bl.pop(idx)
            set_cfg(con, BLOCKLIST_KEY, bl)
            print(green(f"  removed '{removed}'"))
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
    cur = input(f"  server URL [{cfg.get('server', DEFAULT_SERVER)}]: ").strip()
    if cur:
        cfg["server"] = cur
    tok = input(f"  token [{cfg.get('token', '')}]: ").strip()
    if tok:
        cfg["token"] = tok
    save_config(cfg)
    print(green("  saved. run --sync to pull data."))


def do_export(con):
    stamp = time.strftime("%Y%m%d-%H%M%S")
    path = os.path.join(EXPORT_DIR, f"childguard_logs_{stamp}.tar.gz")
    tmp_json = os.path.join(EXPORT_DIR, f"events_{stamp}.jsonl")
    tmp_alerts = os.path.join(EXPORT_DIR, f"alerts_{stamp}.jsonl")

    with open(tmp_json, "w", encoding="utf-8") as f:
        for row in con.execute("SELECT ts,type,package,data,child FROM events ORDER BY ts"):
            f.write(json.dumps({"ts": row[0], "type": row[1], "package": row[2],
                                "data": row[3], "child": row[4]}) + "\n")
    with open(tmp_alerts, "w", encoding="utf-8") as f:
        for row in con.execute("SELECT ts,severity,reason,detail FROM alerts ORDER BY ts"):
            f.write(json.dumps({"ts": row[0], "severity": row[1],
                                "reason": row[2], "detail": row[3]}) + "\n")

    with tarfile.open(path, "w:gz") as tar:
        tar.add(tmp_json, arcname="events.jsonl")
        tar.add(tmp_alerts, arcname="alerts.jsonl")
        cfg_snapshot = os.path.join(EXPORT_DIR, "config_snapshot.json")
        with open(cfg_snapshot, "w", encoding="utf-8") as f:
            json.dump({
                "keywords": get_cfg(con, KEYWORD_KEY, []),
                "blocklist": get_cfg(con, BLOCKLIST_KEY, []),
                "configured_server": load_config().get("server"),
            }, f, indent=2)
        try:
            os.chmod(cfg_snapshot, 0o600)
        except Exception:
            pass
        tar.add(cfg_snapshot, arcname="config_snapshot.json")

    os.remove(tmp_json)
    os.remove(tmp_alerts)
    os.remove(cfg_snapshot)
    try:
        os.chmod(path, 0o600)
    except Exception:
        pass
    print(green(f"  exported → {path}"))


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
    ap.add_argument("--overview", action="store_true", help="one-shot overview")
    ap.add_argument("--screen-time", action="store_true", help="one-shot screen time")
    ap.add_argument("--alerts", action="store_true", help="one-shot alerts")
    ap.add_argument("--logs", metavar="QUERY", help="one-shot log search")
    ap.add_argument("--export", action="store_true", help="export logs bundle")
    ap.add_argument("--cleanup", metavar="DAYS", help="delete logs older than DAYS")
    args = ap.parse_args()

    con = ensure_env()
    cfg = load_config()

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

    interactive(con)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
        print(dim("  interrupted."))
        sys.exit(130)
