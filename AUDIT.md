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
| F-12 | medium | Android | Alert accuracy: bare platform names, any `@mention`, and any URL were treated as "stranger" signals, so ordinary messages raised HIGH alerts. | before: `documentsFalsePositiveProneAlertPatterns` matched `we should switch to telegram`, `kid@example.com`, `https://example.com/homework` | **Fixed 2026-11-17** — those bare tokens no longer alert; text is still captured. Tests: `ordinaryMentionsAndLinksDoNotAlert` + `realGroomingSignalsStillAlert` |
| F-13 | low | Android | `QUERY_ALL_PACKAGES` is a restricted Play permission; it is used only to list installed apps in `WebMonitorActivity`. | `getInstalledApplications(...)` in `WebMonitorActivity.kt` only | **Open** — declare the use in Play Console or replace with a `<queries>` element |
| F-14 | low | Android | `WAKE_LOCK` declared, never used. | `grep -rn WakeLock|PowerManager` → none | **Fixed** — removed; new APK permission list has no `WAKE_LOCK` |
| F-15 | low | Android | `CgLog` (198 lines, including `FileLogger`/`WebLogger`) is never initialised, so it is dead code and a future foot-gun for logging messages to disk. | `CgLog.initialise`/`addLogger` have no call sites | **Fixed 2026-11-17** — moved to `docs/prototype/`; no longer compiled or packaged |
| F-16 | low | Android | "Web Monitor" showed "Tap an app to view its traffic", but the list is not clickable and there is no WebView or traffic capture. | no `WebView`/`loadUrl` in the file; toast was the only interaction | **Fixed** — misleading toast removed |
| F-17 | info | Dashboard | `config.json` stores the token; an existing file was owner-only. | `stat -c %a` → `600` | **Superseded by F-22** — the check only saw a config that a later `ensure_env()` had already chmodded; the *first* creation was `0644` |
| F-18 | low | Docs | `ARCHITECTURE.md` described `CgLog` (`FileLogger`/`WebLogger`) as the live transport, claimed a "local-first" on-device log, and listed the reverse shell as a shipped feature. None of that was true. | `CgLog` had no call sites; `ShellConnectService` was not in the manifest | **Fixed 2026-11-17** — docs now describe the real `MonitorService` transport, no local archive, and Feature 5 as an unshipped prototype |
| F-19 | medium | Android | The notification listener (Feature 3) still alerted on any "your mom/dad/parents", "follow me", or "you're so … nice" — the same alert-fatigue problem as F-12, on the notification path. | before: `isStrangerText("your mom said hi")` → true; `isStrangerText("follow me on instagram")` → true | **Fixed 2026-11-17** — parent mentions require secrecy context; `Feature3NotificationTest` pins both directions |
| F-20 | low | Dashboard | There was no way to tell "network/Tailscale is down" from "the token is wrong" before syncing; both appeared only as a one-line `Sync failed` message. | `--sync` prints one error and returns | **Fixed 2026-11-17** — `--doctor` separates unauthenticated `/health` from authenticated `/api/stats` and documents exit codes 0/2/3/4/5 |
| F-21 | critical | Repo | A tracked `childguard_wellbeing.apk` was a **Metasploit Android payload**, and root `handler.rc` was a matching reverse-TCP handler. | `aapt2 dump badging` → `package com.metasploit.stage`, `minSdkVersion 10`, `SEND_SMS`/location/phone permissions; `strings classes.dex` → `Lcom/metasploit/stage/MainActivity;`; `handler.rc` → `android/meterpreter/reverse_tcp`, `LHOST 0.0.0.0`, `LPORT 4444`; both in `git ls-files` | **Fixed at HEAD 2026-11-18** — `git rm` both, `.gitignore` now blocks root `*.apk` and `handler.rc`; RUNTIME_TEST no longer suggests `msfconsole`. **History still contains the blobs**: rewrite history before making this repo public |
| F-22 | high | Dashboard | First `--configure` created `config.json` (which holds the parent token) with mode `0644`. | `(umask 022; ... --configure ...); stat -c %a config.json` → `644` | **Fixed 2026-11-18** — 0600 temp file + `fchmod` + `fsync` + `rename`; fresh-home test asserts `0600` under umask 022 |
| F-23 | high | Android | The notification listener captured **every** package despite a docstring claiming chat-only, and the Feature 1 fallback did the same. Banking alerts and 2FA codes were uploaded. | old `onNotificationPosted` had no package guard; `Feature3NotificationTest` asserted `com.example.game` was captured | **Fixed 2026-11-18** — chat allowlist checked before reading extras; fallback uses the same allowlist; prefix spoofs (`com.whatsapp_clone`) rejected. Tests: `nonChatAppIsIgnoredEntirely`, `notificationFallbackIgnoresNonChatPackages`, `chatPackageMatchingDoesNotAcceptPrefixSpoofs` |
| F-24 | medium | Android | A re-posted chat notification produced another event, so one message could become several rows/alerts. | old listener queued on every `onNotificationPosted` | **Fixed 2026-11-18** — 30 s `(package, text)` dedupe in both the listener and the fallback; tests `repeatedIdenticalChatPostIsQueuedOnce`, `notificationFallbackDedupesRepeatedPost` |
| F-25 | medium | Android | Uploads retried **permanent** 4xx forever at a fixed 15 s, and a saved trailing slash produced `//api/events`. | old branch: `if (code !in 200..299) requeue`; `URL("$serverUrl/api/events")` | **Fixed 2026-11-18** — classify 400/415/422; split 400/422 batches, shrink on 413, exponential backoff 30 s→5 min for transient failures, normalize base URL. Tests: `onlyPayloadAndMediaErrorsArePermanent`, `transientFailuresBackOffAndCap`, `serverUrlIsTrimmedAndHasNoDoubleSlash` |
| F-26 | high | Android | Feature 1 ignored `AccessibilityNodeInfo.isPassword` / `AccessibilityEvent.isPassword`, so PIN, password, and OTP fields could enter keylogs and screen dumps. | `grep -c isPassword` → 0 before the fix | **Fixed 2026-11-18** — `NodeLike.isPassword`, password nodes skipped in direct reads, ancestor walks, and screen dumps; tests `passwordFieldsAreNeverExtracted`, `ancestorWalkSkipsSiblingPasswordField`, `passwordEventIsNotRecorded` |
| F-27 | medium | Dashboard | Export temp JSONL files and the tarball were created with the umask (`0644`), archive members stored `0644`, and an interrupted export left the child's messages on disk. | `umask 022` export → `tar -tvzf` showed `-rw-r--r--` members; temp files had no explicit cleanup on error | **Fixed 2026-11-18** — `open_private()` (O_CREAT 0600 + fchmod), owner-only `TarInfo`, `finally` cleanup; test checks member modes and no leftovers |
| F-28 | medium | Dashboard | Screen-time attributed an idle gap of up to **24 h** to whichever app was opened next. | old `min(gap, DAY_MS)` with `DAY_MS = 86400*1000` | **Fixed 2026-11-18** — `MAX_SESSION_GAP_MS = 5 min`; `compute_screen_durations()`; test proves an overnight gap adds 5 min, not 24 h |
| F-29 | low | Dashboard | The interactive configure prompt printed the current parent token in clear text. | `input(f"  token [{cfg.get('token','')}]: ")` | **Fixed 2026-11-18** — prompt shows `set, N chars`; Enter keeps the existing secret |
| F-30 | low | Rust server | Persistence used `flush()` without `fsync`, did not force `0600` on a pre-existing `state.tmp`, and created the data directory with the umask. A power loss could leave a truncated state file. | `write_snapshot` had `flush()` then `rename`; no `sync_all`; dir mode not set | **Fixed 2026-11-18** — `sync_all()` + directory fsync, `fchmod` the temp file, new dirs `0700`; test asserts `0600` file and `0700` directory |
| F-31 | info | Rust server | `POST /api/events` deep-cloned the whole event array before processing; a 5,000-event batch allocated a second full copy. | `incoming = arr.clone()` / `obj.clone()` | **Improved 2026-11-18** — borrows the parsed slice; `events_accepts_a_single_object` covers the object form |
| F-32 | high | Node collector | The legacy collector still had the token leaks and DoS gaps fixed only in Rust: `/health` returned the first 8 characters, `/api/register` echoed the full token, startup printed a user-supplied token, auth used `!==`, and bodies/children were unbounded. Retries also inflated counts. | old `/health` → `token: AUTH_TOKEN.slice(0,8)+'...'`; old register → `{childId, token: AUTH_TOKEN}`; old startup → `Parent token: ${AUTH_TOKEN}`; no `MAX_BODY` | **Fixed 2026-11-18** — token never returned or logged, constant-time compare, 2 MiB body cap, 2,000-child cap, bounded limits, dedupe by `(child,type,package,data,ts)`, device timestamps preserved; `node server/smoke_test.js` asserts each property |
| F-33 | medium | Dashboard | Screen time was **always 0 for synced data**. `sync_from_server` inserts the server's newest-first response, but `screen_time` had no `ORDER BY`; every gap was negative, so no duration was accumulated. | DB `rowid` order `[newer, older]`; `--screen-time` showed `0m` for two events 24 h apart; adding `ORDER BY ts` showed `5m` | **Fixed 2026-11-18** — `ORDER BY ts` in SQL and a defensive sort in `compute_screen_durations()`; test feeds rows newest-first and expects the clamped 5 min |
| F-34 | medium | Dashboard | Captured chat text was printed raw, so a message containing ANSI/OSC sequences could clear the screen, spoof output, or write the clipboard (OSC 52). Bidi controls could also spoof text direction. | a queued event containing `\x1b[2J`, `\x1b]52;…\x07`, and `\u202e` rendered those sequences to the terminal | **Fixed 2026-11-18** — `safe_text()` strips C0/C1 controls and bidi overrides on messages, reasons, package names, severities, and timestamps; test covers ESC/OSC/BEL/bidi; live CLI output contains no `ESC[2J`, OSC 52, or BEL |
| F-35 | low | Dashboard | `--sync` without a terminal fell through to the interactive menu and raised `EOFError` (traceback) in cron/pipes/CI. | `--sync </dev/null` → `EOFError` at `input("  → ")` | **Fixed 2026-11-18** — non-TTY runs print a hint and exit 0; test `test_sync_without_a_tty_exits_cleanly` |
| F-36 | high | Android | The first-run flow (`SetupActivity` → `DashboardActivity`) showed **"Monitoring: ON"** but never started `MonitorService`; only `MainActivity` did. A freshly configured phone collected nothing while the UI claimed it was protecting. | `grep -n startForegroundService` showed only `MainActivity` and the toggle branch; `DashboardActivity` read the preference and rendered it without acting | **Fixed 2026-11-18** — `DashboardActivity.onCreate` starts the service when the preference is true (START is idempotent); `DashboardActivityTest` asserts the start intent and that OFF does not start it |
| F-37 | high | Android | `onStartCommand` returned `START_STICKY` for every path, including an explicit STOP. After process death Android recreated the service with a null intent, which matched no branch: the service came back with `isMonitoring=false`, no upload loop, and no foreground notification — monitoring silently died while the UI still said ON. | old `when (intent?.action)` had only START/STOP, followed by an unconditional `return START_STICKY` | **Fixed 2026-11-18** — STOP returns `START_NOT_STICKY`; a null intent resumes from the `KEY_MONITORING` preference or stops cleanly; `MonitorServiceLifecycleTest` covers all four paths |
| F-38 | low | Dashboard | `--cleanup abc` reached `int(days)` and raised an uncaught `ValueError` traceback. | `--cleanup soon` → `ValueError: invalid literal for int()` | **Fixed 2026-11-18** — argparse `type=int`; test `test_cleanup_rejects_non_numeric_days` asserts a clean usage error and no traceback |
| F-39 | medium | Node collector | `/api/stream` had no listener cap and no heartbeat, unlike the Rust collector. A few stuck dashboard tabs could pin unbounded memory, and an idle proxy could silently drop the stream. | old stream branch: `state.clients.add(res)` with no bound; no `setInterval` keepalive | **Fixed 2026-11-18** — `CHILDGUARD_MAX_STREAMS` (default 64), 25 s heartbeat, and `close`/`error` cleanup; smoke test opens a stream, asserts the next is `503`, then proves the slot is released |
| F-40 | low | Dashboard | Removing keyword `0` from the menu silently removed the **last** keyword (Python negative indexing). | `int(input)-1` with no range check; `words.pop(-1)` succeeds | **Fixed 2026-11-18** — index range validated before `pop` |
| F-41 | low | Dashboard | `--configure` printed the server URL without sanitizing terminal controls. | `print(f"  configured server={cfg.get('server')}")` | **Fixed 2026-11-18** — `safe_text()` on the printed value |
| F-42 | medium | Both collectors | At 20,000 duplicate keys the set was **cleared**, so a retried upload of an event still inside the retained 5,000-event window could be stored again and re-raise its alert. | old code: `if st.seen.len() > MAX_DUPE_KEYS { st.seen.clear(); }` (Node: `state.seen.clear()`) | **Fixed 2026-11-18** — Rust `remember_seen()` keeps an insertion-order `VecDeque` and evicts only the oldest key; Node uses Set insertion order to delete one key. Test `dedupe_evicts_only_the_oldest_key` inserts 20,005 keys and proves `k0..k4` are gone while `k5` and the newest remain |
| F-43 | low | Rust tests | `future_timestamp_is_sanitized` was **flaky**: it compared the clamped timestamp with a `now` captured before `handle_events()` took its own `now_ms()`. A one-millisecond difference made it fail intermittently. | `assert!(ev.ts <= now + TS_FUTURE_ALLOWANCE_MS)` using a pre-call `now` | **Fixed 2026-11-18** — bracket the call (`before`/`after`), assert against `after`, and assert `ev.ts < future`; 3 consecutive runs pass |
| F-44 | medium | Android | `WebMonitorActivity` called `getPackageInfo()` once per installed app **on the UI thread**, then read `requestedPermissions`. On a phone with hundreds of apps that is hundreds of binder round-trips, can jank/ANR, and throws `NameNotFoundException` if an app disappears mid-scan. | `for (app in apps) { pm.getPackageInfo(app.packageName, 0) ... }` inside `onCreate` | **Fixed 2026-11-18** — one `getInstalledPackages(GET_PERMISSIONS)` call, scan moved to a daemon thread, labels loaded with exceptions contained; `WebMonitorActivityTest` proves an `INTERNET` app is listed and the view renders |
| F-45 | low | Dashboard | Removing blocklist entry `0` popped the **last** entry (Python negative indexing), same class as F-40 but the blocklist copy was missed. | `bl.pop(int(input)-1)` with no bounds check | **Fixed 2026-11-18** — range validated; `test_blocklist_rejects_out_of_range_index` feeds `0` and asserts both entries survive |
| F-46 | medium | Dashboard | `http_get_json` read the entire response body with `resp.read()` before parsing. Pointed at a hostile/large endpoint, the dashboard could allocate unbounded memory in Termux. | `return json.loads(resp.read().decode())` | **Fixed 2026-11-18** — streams in 64 KiB chunks and aborts above 32 MiB; `test_http_get_json_rejects_an_oversized_response` drives a fake response past the cap |
| F-47 | low | Dashboard | `show_logs` called `data.lower()` on a nullable column, so one NULL row raised `AttributeError` and killed the whole log view. | `if any(k in data.lower() ...)` without a null guard | **Fixed 2026-11-18** — `data = data or ""`; `test_show_logs_survives_null_data` inserts an explicit NULL and renders |
| F-48 | low | Dashboard | Server sync inserted `ts`/`type`/`package`/`data`/`severity` as received. A malformed response (string/null timestamp) tripped the `INTEGER NOT NULL` constraint and the row was silently dropped. | `ev.get("ts", ...)` passed straight to sqlite | **Fixed 2026-11-18** — `_clean_ts` coerces to int, every text field is stringified, ids fall back to a hash; `test_sync_coerces_bad_types_from_server` asserts all stored columns are the right type |
| F-49 | low | Dashboard | `compute_screen_durations` sorted rows by raw `ts`; a text/NULL timestamp in an old or hand-edited DB raised `TypeError` and blanked the screen-time view. | `sorted(rows, key=lambda r: r[3])` | **Fixed 2026-11-18** — rows are coerced and non-numeric ones skipped; `test_screen_time_skips_non_numeric_timestamps` |
| F-50 | medium | Rust server | `load_state_from` trusted `state.json` completely: an oversized file was read whole, and arrays were loaded with no `MAX_EVENTS`/`MAX_ALERTS`/`MAX_CHILDREN` bound, defeating the caps enforced on the request path. | `fs::read(path)` then `for ev in arr` with no length check | **Fixed 2026-11-18** — 64 MiB file gate and newest-N slicing per array; `load_state_ignores_an_oversized_file` and `load_state_caps_arrays_to_the_live_limits` |

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

## 4. Provenance and offline compliance

**Disclosure, added 2026-11-17 after review.** During the first audit pass I
fetched Discord's public blog post about its Go-to-Rust migration without the
user's permission, and an earlier version of this section repeated its content.
That fetch violated the project's explicit no-download rule, and the first
summary's claim that "nothing was downloaded from the internet" was false. The
externally sourced material has been removed. The earlier commit message
(`98587bb`) still mentions a "Discord claim"; it refers to this removed section,
not to anything in the code or the local measurements.

What this audit actually relies on:

- source code in this repository, read locally;
- local builds and tests only: `cargo --offline`, `gradle --offline`, the
  collector bound to `127.0.0.1:38080`, and the dashboard syncing from it;
- measurements taken on this host with one local client against the Rust and
  Node collectors. These are microbenchmarks of this code, not published
  figures.

No dependency, binary, dataset, or model was downloaded for the results below.
The only network operations in the session were the unauthorized fetch above
(removed) and the `git push` to the project's own remote, which the task
requires. The local numbers still support the narrow verdict in section 3: keep
the collector in Rust for memory/size, not for write throughput.

---

## 5. Residual risk / next actions

1. **TLS for the collector** (F-11). Tailscale already encrypts the path; if the
   collector is ever exposed beyond the tailnet, plain HTTP is unacceptable.
2. **Play policy** (F-13): declare `QUERY_ALL_PACKAGES` or scope it with
   `<queries>`.
3. **On-device verification** is still required: no Android device or KVM is
   available here. Checklist in `RUNTIME_TEST.md` §3.

## 5b. Daily audit — 2026-11-17: Feature 1 hardening

Feature 1 already passed its correctness tests; this pass made it smoother and
less noisy in normal use.

| Change | Why it matters | Evidence |
|---|---|---|
| View-tree traversal capped at **400 nodes / 60 siblings per level** | every `child()` is a binder call; a big browser DOM could stall the accessibility callback into an ANR | `hugeViewTreeIsTraversedWithinABudget` (a lazy 60^6 tree returns bounded and <2 s) |
| **Identical screen dumps suppressed** | cursor blink/timers/ads were re-uploading the same text, burning mobile data and flooding the dashboard | `identicalScreenDumpsAreNotReuploaded` |
| **Upload batches capped at 250** across retries + both queues | leftovers stay queued, so a flaky link makes progress instead of re-sending one growing body | `batchesAreBoundedAndLoseNothing` (600 → 250+250+100, no duplicates) |
| **Bare platform names / @mentions / URLs no longer alert** | ordinary chat contains them all day; HIGH alerts must mean something (F-12) | `ordinaryMentionsAndLinksDoNotAlert`, `realGroomingSignalsStillAlert` |
| Queue caps fixed from `>` to `>=` | queues now stay exactly at their limit instead of limit+1 | code + tests |
| `CgLog` and the Feature 5 prototype moved out of the build | F-15/F-18; the APK no longer carries dead logging code or shell code | source tree + dex checks |

Feature inventory after this pass:

- **Feature 1 (accessibility)** — present and verified end-to-end.
- **Feature 3 (notifications)** — present (`ChildGuardNotificationListener`),
  unit/runtime paths covered; queue now exactly capped.
- **Feature 5 (reverse session)** — intentionally **not shipped**; prototype in
  `docs/prototype/`.
- **Feature 6 (transport/dashboard)** — present; real app events render in the
  dashboard (below).

Daily checks (first pass, 2026-11-17):

```
server:    15/15 tests, clippy clean, node smoke OK
android:   26 tests, 0 failures, 0 skipped (live server)
app→server keylog 4, screen_active 2, stranger_alert 1
dashboard: Sync complete: 8 new records; 7 events / 1 alert / HIGH alert +
           interception log + screen time all render
server restart from state.json: 7 events / 1 alert preserved
dashboard failure modes: wrong token → "Sync failed: HTTP Error 401:
           Unauthorized"; dead server → "Sync failed: <urlopen error [Errno 111]
           Connection refused>" (no crash, menu still usable)
dashboard config.json: 600
```

## 5c. Daily audit — 2026-11-17, second pass (repeat of the whole cycle)

Re-ran the full audit, then made a second round of improvements and re-verified
from a clean build.

| Change | Why it matters | Evidence |
|---|---|---|
| **Scroll-aware capture** — `TYPE_VIEW_SCROLLED` added to the XML mask and the runtime `serviceInfo`, handled as a content change | long chats/web pages sometimes emit only a scroll event for newly loaded text; without subscribing, that text is invisible | packaged mask `0x187b`; `serviceRequestsScrolledContentEvents`, `scrollEventWithoutAReadableTreeIsIgnoredSafely` |
| **Feature 3 pattern alignment** (F-19) — parent mentions require secrecy context; `follow me` and "so nice" removed | notification alerts had the same alert-fatigue flaw F-12 fixed in Feature 1 | `Feature3NotificationTest` (7) drives real `StatusBarNotification` objects: grooming → `stranger_notification` + `data`; "Mom: dinner at 7" → plain `notification`; non-chat app not flagged; 260 posts → queue 200 |
| **`dashboard.py --doctor`** (F-20) | splits "tailnet/network down" from "token wrong" and returns exit codes usable by scripts | healthy → exit 0 with counts; wrong token → exit 3; dead server → exit 2; missing token → exit 5 |
| **Secrecy wording broadened** — `doesn't / didn't / won't know` now alert in both Feature 1 and Feature 3 | "your dad doesn't know about this" was missed | `realGroomingSignalsStillAlert`, `broadMentionsDoNotAlert` |

Features re-checked this pass:

- **Feature 1** — real service → queue → upload → live collector: 7 events,
  1 HIGH alert; dashboard renders the interception log, alert stream, and screen
  time. Scroll subscription verified.
- **Feature 3** — real notification objects on API 34; alert precision, chat
  filtering, queue cap, connection flag.
- **Feature 5** — still intentionally absent (0 shell strings in all four dex
  files).
- **Feature 6 / dashboard** — sync, failure modes, config permissions, doctor.

Daily checks (second pass):

```
server:    15/15 tests (--offline), clippy -D warnings exit 0, node smoke OK
android:   35 tests, 0 failures, 0 skipped (live server)
           Extraction 13, Runtime 8, Notification 7, E2E 4, Batching 3
APK:       7,400,089 bytes, sha256 a3b622df...ec219578
           mask 0x187b, allowBackup=false, no WAKE_LOCK,
           0 shell/ShellConnect/CgLog strings across all dex files
dashboard: doctor healthy exit 0 (7 events, 1 alert, 2 children);
           sync 8 records; HIGH alert + logs + screen time render;
           wrong token exit 3; dead server exit 2; config 600
server restart: 7 events / 1 alert preserved from state.json
server log: 0 occurrences of the supplied token
```


## 5d. Daily audit — 2026-11-18, adversarial pass

This pass deliberately tried to break the previous claims rather than confirm
them. It found a payload artifact, two privacy leaks, a durability gap, two
dashboard file-permission windows, and an upload loop that could never drain a
permanent failure. Each fix below was reproduced first and re-tested after.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Repository | Tracked Metasploit APK + `handler.rc` | Removed at HEAD; ignore rules; docs cleaned | `aapt2` package `com.metasploit.stage`; `strings` `Lcom/metasploit/stage/MainActivity;`; `git ls-files` no longer lists either path |
| Dashboard config | First write was `0644` | Atomic 0600 write | `stat -c %a` → `600` under `umask 022`; `test_fresh_config_is_owner_only_even_with_permissive_umask` |
| Notifications | All packages captured, re-posts duplicated | Chat allowlist + 30 s dedupe, same in fallback | `nonChatAppIsIgnoredEntirely`, `repeatedIdenticalChatPostIsQueuedOnce`, `notificationFallbackIgnoresNonChatPackages` |
| Password fields | `isPassword` ignored | Skip password nodes/events | `passwordFieldsAreNeverExtracted`, `passwordEventIsNotRecorded` |
| Upload | Permanent 4xx retried forever at 15 s; `//api/events` | Classification, batch split/shrink, backoff, URL trim | `onlyPayloadAndMediaErrorsArePermanent`, `transientFailuresBackOffAndCap`, `serverUrlIsTrimmedAndHasNoDoubleSlash` |
| Export | Temp files/tarball/members `0644`; crash leftovers | `open_private`, owner-only `TarInfo`, `finally` cleanup | `tar -tvzf` → `-rw-------` for all members; no temp files remain |
| Screen time | 24 h idle gap counted as use; synced rows were newest-first so usage was always `0m` | 5 min session cap + `ORDER BY ts` + defensive sort | `test_idle_gap_is_not_counted_as_screen_time`; live DB now renders `Whatsapp 5m` |
| Persistence | No `fsync`; temp mode not forced; dir `0755` | `sync_all`, dir fsync, `fchmod`, `0700` | `persisted_state_is_owner_only` asserts file `0600` and dir `0700` |
| Configure prompt | Echoed the token | Masked hint | `grep` shows `set, N chars`, no token interpolation |
| Legacy Node | Token prefix/echo/startup log; no caps; retries duplicated | Rust-parity auth, caps, dedupe, timestamps | `node server/smoke_test.js` → `SMOKE OK: auth, token non-disclosure, dedupe, caps, and liveness` |
| Terminal output | Captured text could inject ANSI/OSC/bidi | `safe_text()` on all untrusted render paths | live CLI has no `ESC[2J`, OSC 52, or BEL; `test_terminal_escape_sequences_are_neutralized` |
| Non-TTY | `--sync` raised `EOFError` | Clean hint + exit 0 | `test_sync_without_a_tty_exits_cleanly` |

Why the fixes take these shapes:

- **Split first, drop last.** A malformed event should not discard the 249
  healthy events beside it. `400`/`422` halve the batch until one event is
  isolated; only then is it dropped and counted. `413` shrinks the batch to
  `max(25, n/2)` and keeps every event.
- **Backoff is for recoverable failures only.** 401/403/404/408/429/5xx may
  succeed after the token, URL, or server recovers; retrying a 415 content
  type forever cannot. The old single branch treated both the same.
- **Privacy is checked before allocation/read, not after.** The listener now
  returns on a non-chat package before touching notification extras. The
  accessibility fallback uses the same predicate, so binding state cannot
  silently widen collection.
- **Owner-only from creation, not after.** Both dashboard bugs came from
  `open(path, "w")` / `tarfile.open(path, "w:gz")`: there is always a window
  where the file has the umask mode, and a crash makes it permanent. O_CREAT
  with `0600` plus `fchmod` removes the window; a `finally` removes leftovers.

Verification for this pass:

```
payload:   aapt2 badging -> package com.metasploit.stage; minSdk 10
           strings classes.dex -> Lcom/metasploit/stage/MainActivity;
           handler.rc -> android/meterpreter/reverse_tcp / LPORT 4444
           git ls-files | grep -E '\.apk$|handler\.rc' -> (none)
server:    16 tests passed, 0 failed (cargo test --offline)
           cargo clippy --offline -- -D warnings -> exit 0
           node server/smoke_test.js -> SMOKE OK
android:   46 tests, 0 failures, 0 skipped (live collector)
           Extraction 15, Runtime 11, Notification 10, E2E 4, Batching 6
dashboard: python3 -m unittest termux/test_dashboard.py -> 6 tests, OK
           config.json 600; export tar 600; members -rw-------; no temp files
screen:    synced newest-first rows -> ORDER BY ts; 24 h gap -> 5 min, not 0 or 24 h
```

## 5e. Daily audit — 2026-11-18, lifecycle and bounds pass

The previous pass fixed data handling; this pass followed the *control* path and
the remaining unbounded surfaces. It found that the app could look healthy while
collecting nothing, that a system restart could silently disable monitoring, and
that the Node stream endpoint still lacked the bounds already added in Rust.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| First-run start | Dashboard showed ON without starting the service | Start from `DashboardActivity` when enabled | `DashboardActivityTest` (2): start intent present, OFF does not start |
| Service lifecycle | STOP was sticky; null-intent restart left a half-alive service | Branch-specific return codes; resume from preference | `MonitorServiceLifecycleTest` (4): sticky/not-sticky and resume/stop |
| Dashboard CLI | `--cleanup abc` traceback; keyword `0` removed the last entry; configure printed raw URL | argparse `type=int`, index bounds, `safe_text()` | `test_cleanup_rejects_non_numeric_days`; range check; sanitized print |
| Node SSE | Unbounded listeners, no heartbeat | Cap (default 64, env-overridable), 25 s ping, cleanup on close/error | smoke test: first stream 200, second 503, slot released after disconnect |
| Duplicate keys | Full clear at the cap re-admitted in-flight retries | Oldest-only eviction in both collectors | `dedupe_evicts_only_the_oldest_key` (20,005 keys) |
| Test flake | Timestamp test compared against a pre-call clock | Bracket the call | 3 consecutive runs pass |

Why these shapes:

- **The label must follow the service, not the preference.** A monitoring app
  that says "ON" while idle is worse than one that says "OFF": the parent stops
  looking. Starting from the screen that renders the state removes the split.
- **Sticky is a promise to restart.** It is correct for START and wrong for
  STOP. Handling the null intent explicitly is the only way to keep that
  promise without resurrecting a service the parent turned off.
- **Bounds belong on every long-lived collection.** Events, alerts, children,
  duplicate keys, bodies, connections, and SSE listeners are now all capped in
  both collectors; an authenticated but buggy client cannot exhaust the parent.

Verification for this pass:

```
server:    cargo test --offline -> 17 passed; clippy -D warnings -> exit 0
node:      node server/smoke_test.js ->
           SMOKE OK: auth, token non-disclosure, dedupe, caps, stream limit, and liveness
android:   52 tests, 0 failures, 0 skipped (live collector)
           DashboardActivity 2, MonitorServiceLifecycle 4,
           Extraction 15, Runtime 11, Notification 10, E2E 4, Batching 6
dashboard: python3 -m unittest termux/test_dashboard.py -> 7 tests, OK
```

**Evidence correction.** The first dashboard end-to-end run in this pass used a
`target/release/childguard-server` binary built on 2026-11-16, before the
`fsync`/directory-permission changes. It reported the data directory as `770`.
After `cargo build --release --offline`, the same test reports `700` for the
directory and `600` for `state.json`. All numbers in section 6 come from the
rebuilt binary; the stale-binary result is recorded here rather than hidden.

## 5f. Daily audit — 2026-11-18, input-trust pass

This pass followed untrusted input across the three runtimes: what the Android
UI trusts from `PackageManager`, what the dashboard trusts from a server
response or its own SQLite file, and what the Rust collector trusts from
`state.json`. Every finding below is a real crash, a silent data loss, or an
unbounded allocation — not style.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Web monitor | Per-app `getPackageInfo()` on the UI thread (binder storm, `NameNotFoundException`) | One `getInstalledPackages(GET_PERMISSIONS)` call, off-main-thread scan | `WebMonitorActivityTest` lists a real `INTERNET` app |
| Blocklist menu | `0` removed the last entry (negative index) | Bounds-check before `pop` | `test_blocklist_rejects_out_of_range_index` |
| Dashboard HTTP | Whole body read before parse (unbounded memory) | 64 KiB chunks, 32 MiB cap | `test_http_get_json_rejects_an_oversized_response` |
| Log view | NULL `data` raised `AttributeError` and killed the view | Coerce to `""` | `test_show_logs_survives_null_data` |
| Sync | Bad `ts`/text types tripped `INTEGER NOT NULL` and dropped rows silently | Coerce ts to int, stringify text fields | `test_sync_coerces_bad_types_from_server` |
| Screen time | Non-numeric `ts` raised `TypeError` in the sort | Skip unparseable rows | `test_screen_time_skips_non_numeric_timestamps` |
| State load | `state.json` read whole; arrays loaded with no caps | 64 MiB gate + newest-N slicing | `load_state_ignores_an_oversized_file`, `load_state_caps_arrays_to_the_live_limits` |

Why these shapes:

- **Enumerate once, not per item.** `getInstalledPackages` returns permissions
  in the same parcel as the app list. The per-app version was 2 binder calls
  per app and an exception surface that could abort the whole scan.
- **The dashboard is a client too.** It talks to a URL the parent types, so it
  needs the same body cap the collector applies to requests. A dashboard that
  OOMs on a bad response is a denial of service on the parent's own phone.
- **Validate at the trust boundary, not at render time.** Coercing server data
  and `state.json` on entry keeps every downstream query simple and means a
  single bad row cannot blank a whole view.
- **A file cap is not redundant with an array cap.** The array cap bounds live
  state; the file cap bounds the cost of reading a corrupt file *before* any
  JSON parsing. Both are needed because they fail at different stages.

Verification for this pass:

```
server:    cargo test --offline -> 19 passed; clippy -D warnings -> exit 0
node:      node server/smoke_test.js -> SMOKE OK (all assertions)
android:   53 tests, 0 failures, 0 skipped (live collector)
           + WebMonitorActivity 1
dashboard: python3 -m unittest termux/test_dashboard.py -> 12 tests, OK
```

## 6. Verification log (this audit)

```
server-rust:  cargo test            → 19 passed; 0 failed
server-rust:  cargo clippy -D warnings → clean
node server:  node server/smoke_test.js → SMOKE OK (auth, token
              non-disclosure, dedupe, caps, stream limit, liveness)
android:      53 tests, 0 failures, 0 skipped (with live server)
              4 E2E tests skip when no server is configured
              test classes: Feature1Extraction 15, Feature1Runtime 11,
              Feature3Notification 10, Feature1ServerE2E 4, UploadBatching 6,
              DashboardActivity 2, MonitorServiceLifecycle 4,
              WebMonitorActivity 1
dashboard:    python3 -m unittest termux/test_dashboard.py → 12 passed
android:      assembleDebug → app-debug.apk 7,400,089 bytes,
              sha256 7dabac98...e4eec7ba
dashboard:    --doctor → healthy exit 0 (7 events / 1 alert / 2 children);
              wrong token exit 3; dead server exit 2; missing token exit 5
              --sync → "Sync complete: 8 new records";
              7 events / 1 alert / HIGH alert / screen time rendered
              wrong token → 401 message; dead server → connection-refused
              message (no crash); config.json mode 600
APK:          minSdk 26 / targetSdk 35, allowBackup=false,
              fullBackupContent=false, dataExtractionRules present,
              /system/bin/sh, ShellConnect, CgLog, metasploit, meterpreter,
              and reverse_tcp all absent from every dex; isPassword present;
              event mask 0x187b; stale WebMonitor toast absent
payload scan: root *.apk and handler.rc absent from the tree and git index;
              .gitignore blocks both
state.json:   -rw------- (600), data dir drwx------ (700),
              7 events / 1 alert / 2 children after E2E
server log:   user-supplied token absent (`grep -c testsecret` → 0)
network:      one unauthorized fetch (discord.com blog) happened in the first
              pass; its content has been removed from this document and no
              result depends on it. All other HTTP was to 127.0.0.1:38080;
              cargo/gradle ran with --offline.
```

## 7. Efficiency: what could be faster, and why the current shape

The brief asks not only for bugs but for *better ways to do it*. These are the
places where a different design would be faster or smaller, and the reason the
current one was kept. None of them is a correctness bug; they are conscious
trade-offs, and each names the condition that would justify switching.

| Area | Faster/smaller alternative | Why the current shape | When to switch |
|---|---|---|---|
| Collector persistence | Append-only event log + periodic compaction instead of serializing all state every 2 s | State is already bounded (5,000 events / 500 alerts), so one atomic snapshot is small, restores in one read, and cannot replay a half-written record. An append-only log needs truncation, crash recovery, and duplicate-on-replay handling. | If the event cap grows past ~50k or the host is SD-card-class. |
| Dashboard sync | `GET /api/events?since=<last_ts>` delta instead of re-fetching the full 5,000-event window and `INSERT OR IGNORE` | The server has no `since` parameter, and a clock-skewed phone could hide rows from a naive delta. Re-inserting 5,000 rows is milliseconds in SQLite and is idempotent by `id`. | If the accepted event window grows or sync runs every few seconds. The safe version is server-side `ts > since` plus a small overlap. |
| Dedupe set | Persist the keys (Bloom filter or SQLite table) so a collector restart cannot re-ingest a retried batch | Persisting 20k keys costs disk I/O on the same phone that is running the collector, while the retained event window is only 5,000; after a restart the overlap is small. Keys are now `u64` (~160 kB) instead of strings (~2 MB). | If restarts become frequent or the event window grows. |
| Upload cadence | Server-sent events / push from the app | Mobile radio wake-ups are the dominant battery cost. A 15 s poll with exponential backoff (30 s → 5 min) keeps alerts timely and an outage cheap. | If sub-second parent alerts are required, use push (FCM) with an explicit privacy decision. |
| Accessibility capture | Read only the focused node instead of a budgeted full-window dump | WebViews, custom chat lists, and IMEs often expose text only in siblings or the root tree. The dump is bounded by depth, node count, character count, an 800 ms gate, and a content hash, so cost is predictable. | If profiling on a low-end phone shows the dump dominates CPU. Target the focused subtree first. |
| Screen time | Android `UsageStatsManager` | It needs the separate `PACKAGE_USAGE_STATS` special permission and gives OS-level totals, but the event-gap method needs no extra consent and is already available. The 5-minute session cap bounds the approximation error. | If minute-accurate totals matter more than the extra permission. |
| Two collectors | Delete the Node collector and keep Rust only | Existing Node users and the legacy API are still exercised by `server/smoke_test.js`; the cost is duplicate maintenance, which is why both are brought to the same security behavior and tested. | If the Node path has no users; then remove it and its docs. |
| Rust vs Node | Node measured **329 writes/s** vs Rust 227 on this host | Rust is kept for a 656 kB binary and 744 kB idle RSS on a phone, not for throughput. The audit states this plainly rather than claiming Rust is faster. | If throughput on a large host matters more than footprint, Node is the faster choice. |

The rule behind these choices: **bound the work, then keep the simplest design
that stays inside the bound.** A full snapshot, a full window, and a full tree
dump are each cheap *because* caps make their size predictable. Removing a cap
to chase speed is how this class of app turns one large screen into an ANR.
