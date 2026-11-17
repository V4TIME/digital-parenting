# Architecture — from the rival demo to ChildGuard

We reverse-engineered the competitor's logging architecture (used with
permission as a reference in the project brief):

```kotlin
Log.initialise(context)
Log.addLogger(AndroidLogger(LoggerBundle(LogLevel.INFO)))
Log.addLogger(CrashlyticsLogger())
Log.addLogger(FileLogger(applicationContext, DayLogBundle(maxDaysSaved = 4)))
Log.addLogger(FileLogger(applicationContext, CircleLogBundle(numOfFiles = 7)))
Log.addLogger(FileLogger(applicationContext, StrictCircleLogBundle(maxFileSizeMegaBytes = 1)))
Log.addLogger(WebLogger(RestLoggerBundle("http://webserver/api/v1/")))
Log.addLogger(WebLogger(WebSocketLoggerBundle("ws://webserver/ws/v1/")))
```

## What that architecture is

- **`Log`** — a static facade holding a fan-out list of loggers.
- **`LoggerBundle`** — configuration (level, path, rotation policy) carried per logger.
- **Loggers** — `AndroidLogger` (logcat), `FileLogger` (day/circle/strict rotation),
  `WebLogger` (REST/WebSocket push), `CrashlyticsLogger` (crash reporting).
- Pattern: **Strategy + pluggable pipeline** — each logger is independent,
  cheap to add, and failures are isolated.

## How we adapted it (and improved it) — `CgLog.kt` (retired prototype)

> **Status: not used.** `CgLog` was an architecture experiment. It is never
> initialised, is no longer compiled, and lives in `docs/prototype/CgLog.kt`.
> The app deliberately keeps **no on-device message log**; captured events are
> held in bounded in-memory queues and uploaded by `MonitorService`. The real
> transport is described under Feature 6 below.

| Competitor | ChildGuard `CgLog` | Improvement |
|---|---|---|
| `Log.initialise(context)` | `CgLog.initialise(context)` | parity |
| `LoggerBundle(level)` | `LoggerBundle(level, tagPrefix)` | tagged, filterable |
| `AndroidLogger` | `AndroidLogger` | parity |
| `FileLogger + DayLogBundle` | `FileLogger + DayLogBundle(maxDaysSaved=4)` | retains retention |
| `FileLogger + CircleLogBundle` | `FileLogger + CircleLogBundle(numOfFiles=7)` | parity |
| `FileLogger + StrictCircleLogBundle` | `FileLogger + StrictCircleLogBundle(maxFileSizeMegaBytes=1)` | parity |
| `WebLogger + RestLoggerBundle` | `WebLogger + RestLoggerBundle(baseUrl, token)` | **Bearer auth**, reuses our collector |
| `WebLogger + WebSocketLoggerBundle` | `WebSocketLoggerBundle` stub | planned |
| `CrashlyticsLogger` | — | intentionally omitted (external dependency) |
| — | Each logger wrapped in try/catch | a failing logger can never crash the app |

Design differences that make it stronger for our threat model:

1. **No third-party dependencies** — the app and the prototype loggers use only
   Android/JDK APIs.
2. **Authenticated remote push** — `MonitorService` posts captured events to the
   Rust collector with the parent bearer token; the token is never echoed by the
   server or written to its logs.
3. **No local message archive** — events sit in bounded queues (200 / 500 / 1000)
   only until they are uploaded. The durable copy is the collector's
   `state.json` (owner-only, `0600`), so a lost phone does not hand over a
   history of the child's messages.
4. **Bounded everything** — queues are capped, uploads are capped at 250 events
   per batch, and the collector caps events/alerts/children as well.

## Feature mapping (what each "feature number" means)

- **Feature 1** — accessibility interception (`ChildGuardAccessibilityService`)
- **Feature 3** — notification interception (`ChildGuardNotificationListener`)
- **Feature 5** — parent session / reverse control: **prototype only, not
  shipped.** See `docs/prototype/ShellConnectService.kt`. A reverse shell with no
  authentication, no TLS and no per-session consent is a liability in a parenting
  app, so it is not in the manifest or the APK (verified: zero shell strings in
  every dex). Feature 1 already captures the screen; a remote shell adds risk,
  not information.
- **Feature 6** — transport to parent (`MonitorService` upload pipeline →
  Rust server → Termux dashboard)

## Android 14/15 posture

- `targetSdk 35` / `compileSdk 35`, `minSdk 26`
- `MonitorService` is a **foreground service** with `foregroundServiceType="specialUse"`
  (required on Android 14/15 to keep monitoring alive)
- `POST_NOTIFICATIONS` runtime permission requested on Android 13+
- Accessibility + notification listener require user enablement in system
  settings (same on Android 13–15)
- `QUERY_ALL_PACKAGES` declared for app visibility; cleartext permitted for the
  Tailscale HTTP collector
