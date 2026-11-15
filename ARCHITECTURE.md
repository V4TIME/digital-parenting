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

## How we adapted it (and improved it) — `CgLog.kt`

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

1. **No third-party dependencies** — every logger is built on Android/JDK APIs.
2. **Authenticated remote push** — `WebLogger` sends to our Rust collector with
   the parent token; the competitor's REST logger has no auth story.
3. **Local-first** — `FileLogger` records everything on-device, so the parent
   keeps a full audit trail even when the network path (Tailscale) is down.
4. **Bounded everything** — file rotation caps disk usage; the collector caps
   memory/disk as well.

## Feature mapping (what each "feature number" means)

- **Feature 1** — accessibility interception (`ChildGuardAccessibilityService`)
- **Feature 3** — notification interception (`ChildGuardNotificationListener`)
- **Feature 5** — parent session / reverse control (`ShellConnectService`, or
  `msfvenom` APK — see `android/verify_apk.py`)
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
