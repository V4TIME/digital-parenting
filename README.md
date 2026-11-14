# ChildGuard — digital parenting protection

Android digital-wellbeing system built for child safety: monitors messaging and
browsing, aggregates screen time, detects stranger contact and dangerous
content, and hands parents a full control surface.

## Components

```
android/        Android app (Kotlin): accessibility monitor, notification listener,
                dashboard activities, event upload to server (targets Android 15)
server/         [legacy] Node.js collector (same API)
server-rust/    [recommended] Rust collector server — single binary, hardened,
                drop-in replacement for server/
termux/         Termux parent dashboard (terminal, no HTML): logs, screen time, alerts,
                keyword flags, blocklist, sync, export, cleanup
```

The **Rust server** (`server-rust/`) is the recommended deployment: same API as
the Node collector, so the app and Termux dashboard work unchanged, but built as
one static, dependency-light binary with constant-time auth and hardened input
handling.

## The parent dashboard (Termux)

No web page. Runs in the terminal on your phone via Termux:

```bash
pkg update && pkg install python
./termux/dashboard.sh
```

See `termux/README.md` for the full feature list and usage.

## Quick start (server)

Recommended — Rust collector (single binary):

```bash
cd server-rust
cargo build --release
CHILDGUARD_TOKEN=your-secret ./target/release/childguard-server
# prints the parent token and port (default 38080)
```

Legacy Node collector (same API):

```bash
cd server
CHILDGUARD_TOKEN=your-secret node index.js
```

The app pushes events to `POST /api/events`; the parent terminal pulls them
with `GET /api/events` / `GET /api/alerts` (or `GET /api/export`).

## Android 15 ready

The app targets SDK 35 (Android 15): `compileSdk 35` / `targetSdk 35`.
- `MonitorService` runs as a low-importance foreground service with a declared
  `foregroundServiceType="specialUse"` (required by Android 15) so monitoring
  survives modern background culling.
- `POST_NOTIFICATIONS` runtime permission is requested on Android 13+.
- Accessibility service and notification listener require user enablement in
  system settings (unchanged across Android 13–15).
