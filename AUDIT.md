# ChildGuard audit — security, correctness, and the "switch to Rust" question

Date: 2026-11-16 · Repo: `V4TIME/digital-parenting` · Baseline commit: `d1d4646`

Scope: the Android app (`android/`), the Rust collector (`server-rust/`), the
legacy Node collector (`server/`), and the Termux dashboard (`termux/`).
Everything below was reproduced on this machine; commands and raw output are
quoted so each claim can be re-checked.

**Headline:** the app had one genuinely dangerous artifact (a reverse shell in
the source tree), two ways to leak the parent token, a crash bug in the legacy
collector, and a persistence design that made the Rust server slower than the
Node one. All of those are fixed and verified. On the Rust question: the server
is already Rust and should stay Rust, but not because it is faster — measured
throughput is *lower* than Node here. Rust wins memory and footprint by an order
of magnitude, which is what matters on a phone. The Android app cannot "switch
to Rust" (the framework is Java/Kotlin), and the Python dashboard does not have
a performance problem worth a rewrite.

---

## 1. Findings

Severity: **critical** = exploitable/loss of device control, **high** = secret
disclosure or data loss, **medium** = reliability/accuracy with real impact,
**low** = cleanup/misleading behaviour.

| ID | Sev | Component | Finding | Evidence | Status |
|---|---|---|---|---|---|
| F-01 | critical | Android | `ShellConnectService.kt` opened a TCP session to the configured host:4444 and piped `/system/bin/sh` through it — no auth, no TLS. It was dead code (absent from the manifest, zero call sites), so not reachable in the shipped APK, but it must not live in a parenting app's source. | `grep -rn ShellConnectService android/` → only its own file; packaged dex: `/system/bin/sh` = 0, `ShellConnect` = 0 in all 4 dex files | **Fixed** — moved to `docs/prototype/`, APK rebuilt and re-checked |
| F-02 | high | Rust server | Unauthenticated `GET /health` returned the first 8 characters of the parent token. | `curl -s http://127.0.0.1:38080/health` → `{"ok":true,"token":"SuperSec...","uptime":...}` | **Fixed** — now `{"ok":true,"service":"childguard","uptime":...}`; regression test `health_never_discloses_the_token` |
| F-03 | high | Rust server | Same endpoint sliced the token by byte index 8. A non-ASCII token panicked the handler (`end byte index 8 is not a char boundary`); `Cargo.toml` sets `panic = "abort"`, so one unauthenticated request kills the collector. | token `1234567äxyz`: `curl` exit 52; log: `thread '<unnamed>' panicked at src/main.rs:764:85: end byte index 8 is not a char boundary` | **Fixed** — no slicing at all; token re-tested, server **ALIVE**, 0 panics in log |
| F-04 | high | Android | `android:allowBackup="true"` while `SharedPreferences` holds the parent token. Auto Backup/ADB could extract a credential that grants full API access to every child's messages. | manifest had `allowBackup="true"`; `KEY_PARENT_TOKEN` stored in plain prefs | **Fixed** — `allowBackup=false`, `fullBackupContent=false`, `dataExtractionRules` excluding all domains; verified in the packaged manifest |
| F-05 | high | Rust server | `state.json` (the child's captured messages) was created world-readable (`0644`). | `ls -l /tmp/.../state.json` → `-rw-r--r--` | **Fixed** — `OpenOptions::mode(0o600)`; `stat -c %a` → `600`; test `persisted_state_is_owner_only` |
| F-06 | high | Node server | `const child = ...` then `child = {...}` in the unknown-device path: **the legacy collector crashed on the first event from a device that had not registered**. Monitoring would silently stop. | `TypeError: Assignment to constant variable. at server/index.js:115:15` → process exit; the same POST works after the fix | **Fixed** — `const`→`let`; `server/smoke_test.js` posts twice as an unregistered child: `SMOKE OK` |
| F-07 | medium | Rust server | Persistence wrote the entire state snapshot inline on **every** request, making the collector disk-bound. | before: 8.0 req/s, p50 1517 ms, p99 4452 ms; after coalesced 2 s flush: 229.3 req/s, p50 58.9 ms (**28×**) | **Fixed** — background flusher, `dirty` flag, `PERSIST_INTERVAL_MS=2000` |
| F-08 | medium | Rust server | 10 production `state.lock().unwrap()` sites: one panic poisons the mutex and every later request panics. | `grep -c 'state.lock().unwrap()'` → 10 (production) | **Fixed** — `lock_state()` recovers from poisoning; `cargo clippy -- -D warnings` clean |
| F-09 | medium | Rust server | `/api/register` echoed the parent token in its response, needlessly spreading the secret to any client/log that records responses. | response was `{"childId":"cg_...","token":"..."}` | **Fixed** — `{"childId":"..."}`; test `register_does_not_echo_the_token` |
| F-10 | medium | Rust server | Startup printed an operator-supplied token to stderr/journal. | `Parent token: <value>` on every start | **Fixed** — only generated tokens are printed; short user tokens get a warning; verified both cases |
| F-11 | medium | Android | `usesCleartextTraffic="true"` app-wide (token + messages in plain HTTP). | packaged manifest: `usesCleartextTraffic=true` | **Accepted risk** — traffic rides a Tailscale/WireGuard tailnet; Android network security config cannot allow-list bare IPs. Documented in the manifest and here; use TLS or keep the collector tailnet-only |
| F-12 | medium | Android | Alert accuracy: bare platform names, any `@mention`, and any URL are treated as "stranger" signals, so ordinary messages raise HIGH alerts. | test `documentsFalsePositiveProneAlertPatterns` passes for `we should switch to telegram`, `follow me on instagram`, `kid@example.com`, `https://example.com/homework` | **Open** — needs a product decision; alert fatigue defeats the feature. Recommend requiring conversational context (who is asking, request for secrecy/meeting/contact) instead of bare tokens |
| F-13 | low | Android | `QUERY_ALL_PACKAGES` is a restricted Play permission; it is used only to list installed apps in `WebMonitorActivity`. | `getInstalledApplications(...)` in `WebMonitorActivity.kt` only | **Open** — declare the use in Play Console or replace with a `<queries>` element |
| F-14 | low | Android | `WAKE_LOCK` declared, never used. | `grep -rn WakeLock|PowerManager` → none | **Fixed** — removed; new APK permission list has no `WAKE_LOCK` |
| F-15 | low | Android | `CgLog` (198 lines, including `FileLogger`/`WebLogger`) is never initialised, so it is dead code and a future foot-gun for logging messages to disk. | `CgLog.initialise`/`addLogger` have no call sites | **Open** — recommend deleting; no runtime behaviour today |
| F-16 | low | Android | "Web Monitor" showed "Tap an app to view its traffic", but the list is not clickable and there is no WebView or traffic capture. | no `WebView`/`loadUrl` in the file; toast was the only interaction | **Fixed** — misleading toast removed |
| F-17 | info | Dashboard | `config.json` stores the token; it was already owner-only. | `stat -c %a` → `600` | OK — no action |

Controls that held up under review: bearer auth is compared in constant time;
bodies are capped at 2 MiB and headers at 64 KiB; events/alerts/children/dupe
keys are all bounded; timestamps are clamped against future-dated device clocks;
SSE requires auth; both exported Android services are protected by their
signature-level `BIND_*` permissions; the only exported activity is the
launcher.

---

## 2. "Switch to Rust because it's fast" — measured

Same client (`/tmp/bench.py`, Python threads, unique timestamps so server-side
idempotency does not drop payloads), same machine, release Rust vs Node 24.
Each run: 10 s for 16 clients, 5 s for 1 client, 8 s for reads. These are
single-host microbenchmarks: expect ±10–20 % run-to-run noise (the in-memory
Rust row varies the most). The conclusions below rest on the direction repeating
across the 16-client and 1-client write runs, not on a single decimal.

### Writes — `POST /api/events` (5 events per request)

| Implementation | Clients | req/s | p50 | p90 | p99 | RSS after load |
|---|---:|---:|---:|---:|---:|---:|
| Rust, persisting `state.json` (real deployment) | 16 | **229.3** | 58.9 ms | 122.6 ms | 206.4 ms | 11.3 MB |
| Rust, in-memory only | 16 | 193.1 | 52.2 ms | 118.7 ms | 517.5 ms | 4.7 MB |
| Node 24, in-memory | 16 | **329.3** | 46.1 ms | 65.8 ms | 101.1 ms | 90.9 MB |
| Rust, persisting | 1 | 226.7 | 4.25 ms | 5.46 ms | 7.55 ms | — |
| Node 24 | 1 | **407.9** | 2.36 ms | 2.90 ms | 4.41 ms | — |

### Reads — `GET /api/stats`

| Implementation | Clients | req/s | p50 | p99 |
|---|---:|---:|---:|---:|
| Rust | 16 | **496.3** | 28.1 ms | 65.1 ms |
| Node 24 | 16 | 424.1 | 34.2 ms | 91.5 ms |

### Footprint

| Metric | Rust | Node 24 |
|---|---:|---:|
| Idle RSS | **744 kB** (VmSize 4.3 MB, 1 thread) | 45.3 MB |
| On-disk | **656 kB** binary | 62 MB (`node` + `libnode.so.137`) |
| Cold start to first `/health` (median of 5) | 152 ms | **127 ms** |

### What this means

1. **The premise is false for this workload.** Node serves more writes per
   second and with lower latency. Rust's "fast" reputation did not survive
   contact with this code: it is a thread-per-connection server with a global
   mutex, so it loses to Node's single-threaded event loop on write contention.
2. **Scale makes it moot.** One child produces on the order of one event per
   second. 229 req/s vs 329 req/s is a 200× headroom argument, not a user-visible
   one.
3. **Rust wins where this deployment actually hurts: memory and size.** 744 kB
   idle vs 45 MB, 656 kB vs 62 MB installed. On a phone/Termux box that is the
   difference between a background service that coexists with other apps and one
   that gets killed.
4. **Rust also wins the read path** (496 vs 424 req/s, better p99), which is what
   the dashboard and the parent's browser hit.

---

## 3. Per-component verdict

### Android app — do **not** rewrite in Rust

- The UI, services, accessibility and notification callbacks are Android
  framework APIs. They run on ART and are written in Java/Kotlin; Rust can only
  appear as NDK/JNI libraries for narrow CPU-bound work.
- There is no measured hot loop here. The work is: receive an event, extract a
  few strings, append to a bounded queue, POST every 15 s. Rewriting it in Rust
  would add a JNI boundary, a second toolchain, and native crash risk for no
  user-visible gain.
- The real app problems found were not speed: a lost-keystroke bug, a broken
  accessibility config that prevented resource linking, backup of the token, and
  dead code. Speed was never the issue.

### Rust collector — keep it, for the right reason

- It is already Rust, so "switch to Rust" is already done for the backend.
- Keep it for memory footprint (744 kB vs 45 MB), deployment size (656 kB vs
  62 MB), no GC pauses, and the read-path win — **not** for write throughput,
  where Node is ahead. If write throughput ever matters, the fix is the
  concurrency model (sharding/reducing lock hold time), not a language change.

### Termux dashboard — keep Python for now

Measured per invocation on this phone-class host:

| Command | Wall | Peak RSS |
|---|---:|---:|
| `dashboard.py --overview` | 622.8 ms | 23.7 MB |
| `dashboard.py --alerts` | 1170.3 ms | 22.9 MB |

This is a human-interaction tool: ~0.6–1.2 s and ~23 MB is acceptable. A Rust
rewrite would cut startup and RSS, but it would also duplicate the sync logic and
JSON handling for a gain nobody can perceive while reading a dashboard. Revisit
only if it becomes a background service or the dataset grows enough for the
`--alerts` path to be slow for a reason other than interpreter startup.

### Legacy Node collector

Fixed (F-06) and kept as the legacy option. It is not the deployment target; the
Rust collector is. The Node copy is useful for comparison and as a fallback, but
running two collectors against the same data directory is not supported.

---

## 4. The Discord claim

The advice says "Discord changed to Rust". What actually happened, from
Discord's own post ([Why Discord is switching from Go to
Rust](https://discord.com/blog/why-discord-is-switching-from-go-to-rust), Jesse
Howarth, 2020-02-04):

- They ported **one service, "Read States"**, from **Go to Rust** — not the whole
  platform. The post describes it as "a service", "small and self-contained".
- The trigger was **GC latency spikes every ~2 minutes** in that specific
  service, not general slowness.
- Discord already used Rust elsewhere (client video encoding, Elixir NIFs) and
  kept using other languages: the same post calls out being "early adopters of
  Elixir, React, React Native, and Scylla". Discord's platform is not a Rust
  monolith.
- The post's own footnote says: **"To be clear, we don't think you should
  rewrite everything in rust just because."**

So the correct lesson is "measure, then use Rust where a measured bottleneck
justifies it", which is exactly what the numbers above show: Rust is the right
choice for the collector's memory footprint, not for this app's write
throughput, and not a reason to rewrite Kotlin or Python.

---

## 5. Residual risk / next actions

1. **TLS for the collector** (F-11). Tailscale already encrypts the path; if the
   collector is ever exposed beyond the tailnet, plain HTTP is unacceptable.
2. **Tighten alert patterns** (F-12) and add negative tests for ordinary chat.
3. **Play policy** (F-13): declare `QUERY_ALL_PACKAGES` or scope it with
   `<queries>`.
4. **Delete `CgLog`** (F-15) so message logging cannot be enabled by accident.
5. **On-device verification** is still required: no Android device or KVM is
   available here. Checklist in `RUNTIME_TEST.md` §3.

## 6. Verification log (this audit)

```
server-rust:  cargo test            → 15 passed; 0 failed
server-rust:  cargo clippy -D warnings → clean
node server:  node server/smoke_test.js → SMOKE OK
android:      20 tests, 0 failures, 0 skipped (with live server)
              4 E2E tests skip when no server is configured
android:      assembleDebug → app-debug.apk 7,400,089 bytes
              sha256 71047d43...36961e45
APK:          allowBackup=false, fullBackupContent=false,
              dataExtractionRules present, WAKE_LOCK absent,
              /system/bin/sh and ShellConnect absent from all dex files,
              event mask 0x87b, stale WebMonitor toast string absent
state.json:   -rw------- (600), 7 events / 1 alert / 2 children after E2E
server log:   user-supplied token absent (`grep -c testsecret` → 0)
```
