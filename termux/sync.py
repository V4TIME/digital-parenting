#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ChildGuard Termux sync helper.

Pulls events + alerts from the ChildGuard server into the local SQLite
database used by dashboard.py. Safe to run from cron for scheduled sync:

    echo "*/15 * * * * python3 /path/to/sync.py" | crontab -

Arguments:
    --once     run once and exit (default)
    --loop N   run every N seconds until killed
"""
import argparse
import sys
import time
import dashboard as dd  # reuse the same storage + sync logic


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loop", type=int, default=0, help="loop every N sec (0 = once)")
    args = ap.parse_args()

    con = dd.ensure_env()
    cfg = dd.load_config()

    if args.loop > 0:
        print("sync loop every {}s — Ctrl-C to stop".format(args.loop))
        while True:
            dd.sync_from_server(con, cfg, verbose=True)
            time.sleep(args.loop)
    else:
        dd.sync_from_server(con, cfg, verbose=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
