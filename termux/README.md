# ChildGuard — Termux Parent Dashboard

A complete terminal dashboard that runs inside **Termux** (Android) or any
Linux terminal. It gives you full visibility and control over the ChildGuard
monitoring data — **no web page, no browser, no HTML**. Just the terminal.

## What it does

| Feature | Description |
|---|---|
| **Overview** | live server status, last sync, total events/alerts, event-type breakdown, top packages |
| **Screen time** | per-app usage/screen-time aggregation (last 7 days), sorted, with ASCII bars |
| **Interception logs** | full log stream with timestamps, package, event type + content; searchable; keyword-flagged |
| **Alerts** | colored severity stream (stranger alerts, dangerous content) |
| **Keyword flags** | manage your own custom keyword list; matches are highlighted in logs |
| **App blocklist** | manage packages blocked on the child device (feeds the app config) |
| **Sync** | pull events + alerts from the ChildGuard server into a local SQLite DB |
| **Configure** | set server URL + token (stored in `~/.childguard/config.json`) |
| **Export** | bundle all logs + alert history + config snapshot into a `.tar.gz` |
| **Cleanup** | retention control: delete logs older than N days |

Everything is stored locally in `~/.childguard/logs.db` (SQLite) so you can
analyze it offline and never depend on the server being up.

## Install (Termux)

```bash
pkg update && pkg install python
mkdir -p ~/childguard && cd ~/childguard
# copy the termux/ folder of this repo here, then:
chmod +x termux/dashboard.sh
./termux/dashboard.sh
```

## Usage

```bash
./termux/dashboard.sh              # interactive menu
./termux/dashboard.sh --sync       # pull from server first, then menu
./termux/dashboard.sh --overview   # one-shot overview
./termux/dashboard.sh --screen-time
./termux/dashboard.sh --logs "whatsapp"
./termux/dashboard.sh --alerts
./termux/dashboard.sh --export
./termux/dashboard.sh --cleanup 30
```

Scheduled sync (optional):

```bash
echo "*/15 * * * * python3 $PWD/termux/sync.py" | crontab -
```

## Configure the connection

In the menu, choose **8. Configure connection**. Enter:

- **server URL** — where the ChildGuard Node server runs
  (default `http://127.0.0.1:38080`)
- **token** — the parent token (printed when the server starts, or set via
  `CHILDGUARD_TOKEN` env)

The token is sent as `Authorization: Bearer <token>`; without it the server
returns 401. You can also pre-write the config file:

```json
{ "server": "http://192.168.1.50:38080", "token": "your_token_here" }
```

## Server requirements

The Node server (`../server/index.js`) exposes:

- `GET /api/events?limit=N` and `GET /api/alerts?limit=N` — used by sync
- `POST /api/events` — used by the Android app to push events
- `GET /health` — status probe

Run it on any machine (including the same Termux via `pkg install nodejs`):

```bash
CHILDGUARD_TOKEN=secret node ../server/index.js
```

## Layout

```
termux/
├── dashboard.py      # the full dashboard (stdlib-only, no pip deps)
├── dashboard.sh      # launcher
├── sync.py           # cron-friendly server → local DB sync
└── README.md
```

Data lives in `~/.childguard/` (`logs.db`, `config.json`, `exports/`).
