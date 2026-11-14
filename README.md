# ChildGuard — digital parenting protection

Android digital-wellbeing system built for child safety: monitors messaging and
browsing, aggregates screen time, detects stranger contact and dangerous
content, and hands parents a full control surface.

## Components

```
android/    Android app (Kotlin): accessibility monitor, VPN filter, notification listener,
            dashboard activities, event upload to server
server/     Node.js collector: receives events, stores state, serves stats/export to parents
termux/     Termux parent dashboard (terminal, no HTML): logs, screen time, alerts,
            keyword flags, blocklist, sync, export, cleanup
```

## The parent dashboard (Termux)

No web page. Runs in the terminal on your phone via Termux:

```bash
pkg update && pkg install python
./termux/dashboard.sh
```

See `termux/README.md` for the full feature list and usage.

## Quick start (server)

```bash
cd server
CHILDGUARD_TOKEN=your-secret node index.js
# prints the parent token and port (default 38080)
```

The app pushes events to `POST /api/events`; the parent terminal pulls them
with `GET /api/events` / `GET /api/alerts` (or `GET /api/export`).
