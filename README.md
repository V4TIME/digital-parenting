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
one small (656 kB) dependency-light binary with constant-time auth and hardened
input handling. The audit behind that recommendation — including measured
throughput, memory, and the honest result that Node actually serves more writes
per second — is in [`AUDIT.md`](AUDIT.md).

## The parent dashboard (Termux)

No web page. Runs in the terminal on your phone via Termux:

```bash
pkg update && pkg install python
./termux/dashboard.sh
```

Headless (scripted) usage:

```bash
python3 termux/dashboard.py --configure --server http://<tailscale-ip>:38080 --token <token>
python3 termux/dashboard.py --sync --overview
python3 termux/dashboard.py --logs ""
python3 termux/dashboard.py --alerts
python3 termux/dashboard.py --screen-time
```

See `termux/README.md` for the full feature list and usage.

## Feature 1 (the core)

Accessibility interception is documented and verified in
[`FEATURE1.md`](FEATURE1.md) — what is captured, the JVM unit tests, and the
end-to-end proof that it renders in the dashboard. Runtime proof (real
`onAccessibilityEvent` on Android 14 + live upload to the server) and the
on-device checklist live in [`RUNTIME_TEST.md`](RUNTIME_TEST.md).

## Quick start (server)

Recommended — Rust collector (single binary):

```bash
cd server-rust
cargo build --release
CHILDGUARD_TOKEN=your-secret ./target/release/childguard-server
# listens on :38080; a token you set is never echoed to the logs
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
