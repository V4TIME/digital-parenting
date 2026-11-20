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
| F-51 | medium | Both collectors | A malformed timestamp was filed at **epoch 0**. Node used `Number(ev.ts)` so `null`/`""` became `0`; Rust accepted an explicit `ts: 0`. That puts events first in every `ORDER BY ts` and, on the dashboard, first in screen-time ordering. | Node: `Number.isFinite(0)` is true → `ts = 0`; Rust: `as_u64()` returns `Some(0)` | **Fixed 2026-11-18** — both treat a missing, null, blank, or non-positive `ts` as `now()`; Node smoke asserts `ts > 1e12`, Rust test `zero_or_absent_timestamp_falls_back_to_now` |
| F-52 | low | Node collector | At the child cap, `bumpChild` deleted the **first inserted** key, so a long-lived active child could be evicted while a stale one registered later stayed. Rust evicts least-recently-seen. | `state.children.keys().next().value` | **Fixed 2026-11-18** — evicts the smallest `lastSeen`; `CHILDGUARD_MAX_CHILDREN` makes the cap testable; smoke test registers A, B, touches A, registers C and asserts B (stale) is gone and A survives |
| F-53 | low | Rust server | Non-object entries in `events` (strings, numbers, arrays) were stored as empty events (`type`/`package` empty, `data` empty), inflating counts. Node already skipped them. | loop body ran `ev.get(...)` on any JSON value | **Fixed 2026-11-18** — `if !ev.is_object() { continue; }`; the zero-timestamp test sends `"not-an-object"` and `42` and asserts `received == 2` |
| F-54 | medium | Dashboard | A `config.json` that parses to a non-object (`[]`, `null`, `"text"`, or `{"server": 5, "token": null}`) made **every** command crash with `AttributeError: 'list' object has no attribute 'get'`, including `--doctor` and `--sync`. | `return json.load(f)` with no shape check | **Fixed 2026-11-18** — `load_config` accepts only a dict and coerces `server`/`token` to strings; `test_malformed_config_falls_back_to_defaults` runs four shapes and asserts no traceback |
| F-55 | medium | Dashboard | `--doctor` printed the parent-typed server URL raw, and the server-supplied `service` field raw, so an ESC sequence in either could clear the screen or rewrite the parent's terminal. The interactive `configure` prompt echoed the stored URL raw too. | `print(f"    server : {url}")`; `dim(f" (service={health.get('service')})")`; `input(f" ... [{cfg.get('server')}]: ")` | **Fixed 2026-11-18** — `safe_text()` on the URL, service, exception text, and configure prompt; `test_doctor_sanitizes_untrusted_server_url` asserts the clear-screen sequence is gone |
| F-56 | medium | Android | The notification listener read `EXTRA_TITLE`/`EXTRA_TEXT`/`EXTRA_BIG_TEXT` with no length cap and queued them raw. A chat app controls those fields; 200 queued notifications could hold hundreds of MB. Feature 1 already capped at 500 chars. | `extras.getCharSequence(...)?.toString()` passed straight into the queue | **Fixed 2026-11-19** — each field and `combined` capped at 500; `notificationTextIsBounded` posts 200,000 chars and asserts title/text/combined are ≤ 500 |
| F-57 | high | Rust server | Two bugs in request reading. (a) The 30 s socket read timeout equalled the total request budget and a timeout was mapped to `400`, so a slowloris that dribbled a byte just before each expiry could hold one of the 256 slots **indefinitely**, and a merely stalled client saw `400 Bad Request` instead of `408`. (b) `ErrorKind::Interrupted` (EINTR) was also mapped to `400`: an ordinary signal delivery killed a legitimate in-flight request at a random moment (reproduced at 0.6 s, 3.1 s and 24.2 s). | `stream.read(...).map_err(\|_\| 400u16)?` plus a fixed `SO_RCVTIMEO` of 30 s | **Fixed 2026-11-19** — the read timeout is re-armed from the remaining budget before every read, timeout ⇒ `408`, and EINTR is retried; `read_step_retries_eintr_and_classifies_timeouts` plus two socket tests on a 250 ms budget; live probe: trickle 1 B/2 s → `408` at 30.03 s, partial headers → `408` at 30.20 s, partial body → `408` at 30.20 s |
| F-58 | medium | Both collectors | Only the **number** of events was capped, never their size. Each field was copied straight from a 2 MiB request, so 5,000 events (or 2,000 children) of near-maximum strings could pin gigabytes of RSS; `/api/export` then built a second copy, and the next `state.json` flush a third. A child who extracts the plaintext parent token can OOM the collector. | Rust: `ev.get("data")...clone()`, `name` stored whole; Node: `addEvent` claimed "bounded fields" but assigned `ev.data` raw | **Fixed 2026-11-19** — both clip `childId` (128 B), `type` (64 B), `package` (128 B), `data` (8 KiB) and the child name (128 B) before storage/hashing, using UTF-8-safe clipping; Rust `oversized_fields_are_clipped_before_storage`, Node smoke 7d. Live: stored 128/64/128/8192 B, retry `received:0`, `/api/export` 8,803 B |
| F-59 | low | Rust server | SSE streams counted only toward the 256-connection cap, so 256 authenticated streams could occupy every request slot and the collector would answer `503` to the child's uploads. Node already caps streams separately at 64. | `st.sse.push(...)` with no stream limit | **Fixed 2026-11-19** — `MAX_SSE = 64` enforced by `add_sse_client`, which answers `503 {"error":"too many stream clients"}`; `sse_cap_rejects_clients_beyond_the_limit`; live: 64 × `200`, 65th `503` |
| F-60 | medium | Android | Setup saved the typed server URL verbatim. A parent who entered `192.168.1.5:38080` (`URL()` throws *unknown protocol*) got no uploads at all, and the monitor swallowed the exception on every pass, so there was no visible error. Non-http schemes were accepted too. | `prefs.edit().putString(KEY_SERVER_URL, server...)` with no validation | **Fixed 2026-11-19** — `normalizeHttpUrl` fills in `http://` when the scheme is missing, rejects non-http(s)/hostless values, and is used by setup, registration and upload; `SetupActivityTest` proves `192.168.1.5:38080` saves as `http://…` and `ftp://…` shows an error without replacing the saved URL |
| F-61 | medium | Dashboard | `sync_from_server` trusted the response shape. A bare list, `{"events": null}`, or a row that is not an object (`null`, `1`) raised `AttributeError` from `_clean_id(ev, "e")` **outside** the per-row guard, aborting the entire sync before `LAST_SYNC` was written. `--doctor` likewise iterated `children` and called `c.get("online")` on strings/null. | `ev_resp.get("events", [])` then `ev.get("id")`; `sum(1 for c in children if c.get("online"))` | **Fixed 2026-11-19** — `_response_list` validates the envelope, non-dict rows are skipped, and doctor filters non-dict children; three tests drive a fake server with those exact shapes. Live: `Sync complete: 2 new records`, `--logs kept` shows the valid row, `doctor: healthy` (1 child) exit 0 |
| F-62 | high | Rust server | The persistence thread cleared `dirty` **before** writing the snapshot. If the write then failed (disk full, read-only mount, permissions), the flag stayed clear and the collector stopped persisting entirely until the *next* request mutated state; events already accepted were lost on restart with no retry. It also marked a mutation that landed *during* the write as clean, so that event could be dropped forever. | `st.dirty = false; snapshot(&st)` then `write_snapshot(...)` ignoring the result | **Fixed 2026-11-19** — every mutation goes through `mark_dirty()` (increments `generation`); `write_snapshot` returns `bool`; the loop clears `dirty` only after a successful write *and* only if `snapshot_is_current()`; tests `failed_persist_is_reported_and_keeps_state_dirty`, `a_mutation_during_the_write_is_not_marked_clean`. Live: blocked data dir → 2 failed ticks, obstruction removed → state written on the next tick with no new request (dir `700`, file `600`) |
| F-63 | medium | Rust server | Request framing trusted the sender. A repeated `Content-Length` silently used the **last** value, so `Content-Length: 2` followed by `Content-Length: 14` was framed as 14 and the request was applied; a non-numeric value became `0`; `Transfer-Encoding: chunked` was ignored (body dropped); and a body that ended before its declared length was accepted, so a truncated request still mutated state (proved: `Content-Length: 100` + `{}` + EOF registered a child). RFC 7230 §3.3.3 requires rejecting conflicting lengths and unsupported transfer codings. | `content_length = value.parse::<usize>().ok()` (last wins, `None` ⇒ 0) and `body.truncate(cl)` after `break` on EOF | **Fixed 2026-11-19** — lengths must be ASCII digits, repeated lengths must agree, any `Transfer-Encoding` is refused, and `body.len() < cl` is a `400`; tests `conflicting_duplicate_content_length_is_rejected`, `identical_duplicate_content_length_is_accepted`, `non_numeric_content_length_is_rejected`, `truncated_body_is_rejected`, `transfer_encoding_is_rejected`; live probe: four malformed shapes → `400`, identical duplicate + valid → `200`, only the two valid children stored |
| F-64 | medium | Dashboard | Screen time was credited per package from that package's previous event. With WhatsApp 0–100 s and Instagram 100–300 s, WhatsApp got the entire 300 s span **and** Instagram got its 100 s: 400 s of screen time inside a 300 s window, shown to the parent as fact. | `last_active[pkg]`, then `min(ts - prev, max_gap)` per package | **Fixed 2026-11-19** — walk the merged timeline once and attribute each gap to the app that was on screen when it started; `test_foreground_switch_credits_only_the_app_on_screen`; pre-fix probe returned WhatsApp 300,000 ms + Instagram 100,000 ms inside a 300,000 ms window; live `--screen-time` now shows Instagram 3m, WhatsApp 1m |
| F-65 | low | Dashboard | `pkg_name` used the last package segment, so `com.instagram.android` displayed as **Android** and `com.google.android.apps.messaging` as **Messaging** only by luck. | `pkg.split(".")[-1].capitalize()` | **Fixed 2026-11-19** — platform suffixes (`android`, `app`, `apps`, `client`, `mobile`, `beta`, `debug`) are stripped before labeling; `test_package_labels_skip_platform_segments` |
| F-66 | low | Node collector | `limit` disagreed with the Rust collector. `parseInt` accepted `5.9` as 5 and `12abc` as 12, and `limit=0` returned the **default 200** instead of none, because the default check treated 0 as "unset". Rust returns 0 events for `limit=0` and falls back for non-numeric values, so the same request returned different data depending on which collector was running. | `parseInt(raw, 10)` + `n <= 0 ⇒ fallback` | **Fixed 2026-11-20** — only ASCII digits are accepted, `Number.isSafeInteger` rejects overflow, and `tail()` handles 0 explicitly because `slice(-0)` returns the whole list; smoke test covers `0`, `5.9` and `1`; live side-by-side (Rust 38080 / Node 38081) now identical for `0, 2, 5.9, abc, -1` |
| F-67 | medium | All | Registration had no client identity: `/api/register` always minted a fresh `cg_…` id. If the reply was lost (10 s timeout, app killed, flaky tailnet), `ensureRegistered` retried and the collector created **another child**. One phone then appears as N children in `--doctor`/`stats`; at the 2,000 cap the LRU starts evicting real children, and every phantom id inflates `state.json`. | Rust `let id = uid("cg")`, Node `bumpChild(uid(), name)`, Android POSTed only `{name}` | **Fixed 2026-11-20** — the app generates and persists a `pending_child_id` **before** the POST and sends it; both collectors accept a supplied `childId` (clipped to 128 B) and treat a re-register as a touch. Older clients that omit it still get a generated id. Tests: `registering_with_a_client_supplied_id_is_idempotent`, `supplied_child_id_is_clipped_like_an_event_id`, Node smoke 7e. Live same-binary probe: two id-less registers → 2 children; two with `cg_phone_1` → 1 child |
| F-68 | low | Android | Registration read its reply with `bufferedReader().readText()`, with no size limit. A proxy or broken server answering 200 with a large page buffered it all into the app heap. | unbounded `readText()` | **Fixed 2026-11-20** — `readAtMost(input, 8 KiB)`; a malformed 200 now keeps the pending id and retries instead of being trusted; non-200 outcomes are logged with the status; `registrationResponseReadIsBounded` |
| F-69 | medium | Android | Events the server permanently rejected (`400`/`415`/`422`, after the batch was split down to the single poison event) were dropped, and the app incremented `dropped_events_total` — which **nothing ever read**. The dashboard kept saying "Monitoring: ON" while captured evidence disappeared, so a parent had no way to learn that the endpoint or token was wrong. | `recordDropped()` wrote a pref; no reader | **Fixed 2026-11-20** — the dashboard shows a red `tv_dropped` line (`"3 events were rejected by the server (HTTP 400). Check the server URL and token in Setup."`), refreshed on every resume; the ongoing notification is re-posted with the count; changing the server URL or token in Setup clears the historical count. Tests: `rejectedEventsAreVisibleOnTheDashboard`, `droppedCounterAccumulatesAndReadsNaturally`, `notificationSurfacesRejectedEvents`, `changingTheEndpointClearsTheStaleLossCounter` |
| F-70 | medium | Android | The upload loop was a single self-posting `Runnable` that re-armed via the `uploadHandler` **field**. `stopUploadLoop()` used `quitSafely()`, so a pass already running finished after `stop` and then posted itself onto the *new* loop's handler. Every STOP→START added another permanent chain: two, three, … full batches hitting the collector every 15 s, with duplicate uploads and extra radio wake-ups. | single `uploadRunnable` + `uploadHandler?.postDelayed(this, delay)` | **Fixed 2026-11-20** — each loop captures its own `Handler` and a `@Volatile` generation; a pass from a dead generation returns without rescheduling. `restartingTheLoopDoesNotDoubleTheUploadRate` fails on the old shape (`expected:<1> but was:<2>`) and passes on the fix |
| F-71 | low | Dashboard | Sync copied the server's timestamp by type only: `int(float(raw))`. Both collectors treat `0`/negative/missing as "now" and clamp clock skew to +1 h, but the dashboard stored `0` as **1970** (sorted below every real event and ruining screen-time windows) and a JSON `1e400` (Python `inf`) raised `OverflowError`, which the per-row `except` swallowed — the event vanished. | `try: int(float(raw)) except (TypeError, ValueError): now` | **Fixed 2026-11-20** — the same policy as Rust/Node: non-finite or `<= 0` ⇒ `now`, future values clamped to `now + 1 h`; `test_sync_sanitizes_zero_negative_and_future_timestamps` (old code stores 3 of 4 rows) |
| F-72 | low | Dashboard | Export names used `time.strftime("%Y%m%d-%H%M%S")` and `O_TRUNC`. Two exports in the same second — a retry, a double invocation, or two shells — silently overwrote the first bundle, and the staging `.jsonl` names could collide between concurrent runs. | second-resolution name + `O_TRUNC`, fixed `events_{stamp}.jsonl` names | **Fixed 2026-11-20** — the bundle is claimed atomically with `O_EXCL` (`name.tar.gz`, `name-2.tar.gz`, …) and staging files use `tempfile.mkstemp`; a failed export removes the partial bundle and closes its fd. `test_two_exports_in_the_same_second_do_not_overwrite` (old code leaves 1 bundle) |
| F-73 | low | Rust collector | An oversized header region was answered with **413 Payload Too Large**, and the read loop did not enforce the 64 KiB header budget while waiting for `\r\n\r\n`: it buffered up to `MAX_BODY + 64 KiB` (~2.1 MiB) and, with a client that keeps writing, held the connection until the 30 s deadline. Node's HTTP parser answers 431 at its 16 KiB limit, so the collectors also disagreed. | `split > MAX_HEADER_REGION ⇒ 413`, no early cap | **Fixed 2026-11-20** — the loop returns `431` as soon as the unterminated header region exceeds 64 KiB, and a terminated oversized block is `431`; the reason phrase and log text were added. Tests `an_oversized_header_block_is_431_not_413` and `an_unterminated_header_flood_is_431_without_buffering_the_body_cap` (old code: 413 and a 10 s 408). Live: 70 KiB headers → `431` in 1.9 ms / 1.6 ms; valid request → `200` |
| F-74 | medium | Android | A wrong token, a dead tailnet, or an unreachable collector left the dashboard at "Monitoring: ON" with **no uploads and no message**. `ensureRegistered`/upload failures only wrote `Log.w`; events were correctly kept for retry, but the parent had no way to distinguish "nothing happened" from "nothing is arriving". | failures logged only; warning state did not exist | **Fixed 2026-11-20** — the current upload problem is persisted (`last_upload_error` + timestamp) and shown as an amber dashboard line ("Uploads failing since 12:04 — server answered HTTP 401. Retrying automatically."); it is cleared by any 2xx or reachable-but-rejected batch, and the same marker covers registration failures. Tests: `currentUploadProblemIsVisibleAndClearsWhenFixed`; the live E2E dead-port test now asserts the warning appears and clears after recovery |
| F-75 | medium | Android | Both capture queues are capped at 200 events, but the eviction was silent: `while (queue.size >= MAX_QUEUE) queue.poll()` threw the **oldest** captured message away with no counter, no dashboard line, and no notification. A phone that was offline for a few minutes (or a very chatty group chat) lost evidence while the UI still promised capture — the same "silent loss" class as F-69, one layer lower. | `queue.poll()` with no accounting | **Fixed 2026-11-20** — evictions are counted and recorded through the durable loss counter with code `0` (`recordLocalLoss`), so the dashboard says "N events were lost locally: the capture queue was full. Check the server connection."; the counter is written only when something was actually evicted. Tests: `aFullCaptureQueueDropsTheOldestEventLoudly` (old shape: `expected:<1> but was:<0>`), `notificationQueueOverflowIsCountedNotSilent` (`expected:<60> but was:<0>`), `localQueueOverflowIsReportedAsLocalNotAsAServerError` |

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
| Idle RSS | **768 kB** (VmSize 4.3 MB, 1 thread) | 45.3 MB |
| On-disk | **681 kB** binary | 62 MB (`node` + `libnode.so.137`) |
| Cold start to first `/health` (median of 5) | 152 ms | **127 ms** |

### What this means

1. **The premise is false for this workload.** Node serves more writes per
   second and with lower latency. Rust's "fast" reputation did not survive
   contact with this code: it is a thread-per-connection server with a global
   mutex, so it loses to Node's single-threaded event loop on write contention.
2. **Scale makes it moot.** One child produces on the order of one event per
   second. 229 req/s vs 329 req/s is a 200× headroom argument, not a user-visible
   one.
3. **Rust wins where this deployment actually hurts: memory and size.** 768 kB
   idle vs 45 MB, 681 kB vs 62 MB installed. On a phone/Termux box that is the
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
- Keep it for memory footprint (768 kB vs 45 MB), deployment size (681 kB vs
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

## 5g. Daily audit — 2026-11-18, collector-parity pass

The Android app, Rust collector, and Node collector must agree on what a valid
event is. This pass compared the two collectors' handling of malformed input
and found three places where Node and Rust disagreed — and where one of them
filed real data at 1970.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Timestamp zero | `null`/`""` (Node) or `0` (Rust) stored at epoch 0 | Treat missing/null/blank/non-positive as `now()` in both | Node smoke asserts `ts > 1e12`; Rust `zero_or_absent_timestamp_falls_back_to_now` |
| Child eviction | Node evicted first-inserted, Rust least-recently-seen | Node now evicts smallest `lastSeen` | smoke: A touched, B stale, register C → B gone, A stays |
| Non-object events | Rust stored `"x"`/`42` as empty events | Skip entries that are not JSON objects | same Rust test sends both and asserts two stored |

Why these shapes:

- **Two collectors must reject the same input.** A parent can run either one;
  an event accepted by Node but dropped by Rust (or vice versa) makes the
  README's "same behavior" claim false and makes sync results depend on the
  backend.
- **Epoch 0 is never a real device time.** A zero timestamp sorts before every
  genuine event, so it silently rewrites the order a parent reads. Falling
  back to `now()` preserves the event instead of dropping it and keeps order
  honest.
- **Evict by staleness, not by arrival.** The child map is bounded metadata;
  evicting the oldest *insertion* punishes the child who has been monitored
  longest. Both collectors now use `lastSeen`.

Verification for this pass:

```
server:    cargo test --offline -> 20 passed; clippy -D warnings -> exit 0
node:      node server/smoke_test.js ->
           SMOKE OK: auth, token non-disclosure, dedupe, caps, ts coercion,
           LRU eviction, stream limit, and liveness
dashboard: python3 -m unittest termux/test_dashboard.py -> 12 tests, OK
android:   53 tests, 0 failures, 0 skipped (unchanged by this pass)
```

## 5h. Daily audit — 2026-11-18, config-robustness pass

The dashboard is the parent's only window into the data, so it must not be
taken down by its own config file or by a hostile server. This pass drove both
cases directly.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Config shape | `[]`/`null`/string config crashed every command in `cfg.get` | Validate dict, coerce `server`/`token` to strings | `test_malformed_config_falls_back_to_defaults` (4 shapes) |
| Doctor output | URL, server `service`, and exception text printed raw | `safe_text()` at each print | `test_doctor_sanitizes_untrusted_server_url` |
| Configure prompt | Stored URL echoed raw into the prompt | `safe_text()` in the prompt | same sanitizer, covered by the test above |

Why these shapes:

- **A bad config should degrade, not brick.** The token can always be
  re-entered; the command should fall back to defaults and tell the parent,
  not raise into a traceback they cannot act on.
- **The server is not trusted input.** Even a server the parent chose can be
  compromised or misconfigured. Any string it returns that reaches a terminal
  goes through the same sanitizer as captured chat text.

Verification for this pass:

```
dashboard: python3 -m unittest termux/test_dashboard.py -> 14 tests, OK
```

## 5i. Daily audit — 2026-11-19, notification-bound pass

Feature 3 trusted the notification's own text fields. A chat app (or anything
that can post as one) controls title/text/bigText, so this pass bounded that
path the same way Feature 1 already bounds screen text.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Notification text | `EXTRA_TITLE`/`EXTRA_TEXT`/`EXTRA_BIG_TEXT` queued raw; 200 items × unbounded strings | Cap every field and `combined` at 500 chars | `Feature3NotificationTest.notificationTextIsBounded` posts 200,000 chars and asserts ≤ 500 on all three |

Why this shape:

- **Same bound on both capture paths.** Accessibility text was already capped
  at 500; leaving notifications uncapped made the smaller surface the way to
  exhaust the queue. The cap is applied before the dedupe key is built, so the
  key is bounded too.
- **Bound, don't truncate silently at render.** Capping on entry keeps the
  in-memory queue and the upload body predictable; the dashboard still
  sanitizes for terminal safety.

Verification for this pass:

```
android:   54 tests, 0 failures, 0 skipped (live collector; +1 notification bound)
apk:       7,400,089 bytes, sha256 57e69efd...a6253ea
server:    Rust 20, Node smoke, dashboard 14 unchanged and green
```

## 5j. Daily audit — 2026-11-19, request-deadline pass

The collector accepts one thread per connection (up to 256). The per-read
timeout was meant to bound those threads, but two details defeated it.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Total deadline | Socket timeout = total budget, and expiry mapped to `400` | Re-arm `SO_RCVTIMEO` from `budget - elapsed` before each read; expiry ⇒ `408` | two socket tests at a 250 ms budget |
| EINTR | `Interrupted` mapped to `400` | Retry the read (POSIX), re-checking the budget | `read_step_retries_eintr_and_classifies_timeouts` |
| Probe | — | — | live: 408 at 30.03 s (trickle), 30.20 s (headers), 30.20 s (body); complete request 200 in 0.00 s |

Why these shapes:

- **A deadline the peer can extend is not a deadline.** Re-arming from the
  remaining budget means the *last* read can never outlive the budget, no
  matter how the peer paces its bytes.
- **408 is a different fact from 400.** A stalled request is not malformed;
  the status tells the parent (and logs) that the network or client is slow,
  not that the payload was bad.
- **EINTR is not a protocol error.** `read_step` now separates "try again"
  from "timeout" from "fatal", and is unit-tested without sending signals.

Probe against the final release binary:

```
slowloris 1B/2s    -> 30.03s  HTTP/1.1 408 Request Timeout
partial headers    -> 31.77s  HTTP/1.1 408 Request Timeout  {"error":"request timeout"}
partial body       -> 30.20s  HTTP/1.1 408 Request Timeout
oversized body     ->  0.00s  HTTP/1.1 413 Payload Too Large {"error":"payload too large"}
complete request   ->  0.00s  HTTP/1.1 200 OK
health after       -> {"ok":true,...}   (server still serving)
```

## 5k. Daily audit — 2026-11-19, resource-bounds and setup-url pass

Two "bounded" claims did not survive measurement: event *counts* were capped
but field *sizes* were not, and setup accepted an endpoint the app could never
reach. Both are fixed and measured.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Field sizes | 2 MiB per request × 5,000 events (or 2,000 children) unbounded | Clip id/type/package/data/name before storage | Rust test + Node smoke 7d; live: 128/64/128/8192 B |
| SSE | No stream cap of its own; 256 streams could starve uploads | `MAX_SSE = 64`, `503` beyond it | unit test; live 64 × 200, 65th 503 |
| Setup URL | Scheme-less URL saved and failed silently forever | `normalizeHttpUrl` (fill `http://`, require http(s)) everywhere | `SetupActivityTest` (2) |

Why these shapes:

- **Bound bytes, not just rows.** A row cap is only a memory bound if a row is
  bounded. The Android client sends a few hundred bytes per field, so 8 KiB
  for `data` is generous while keeping worst-case state in the tens of MiB.
- **Clip before hashing.** Idempotency hashes the clipped values, so a retry
  of an oversized batch is still recognised as the same event.
- **A URL error must be visible.** Filling in `http://` handles the common
  shorthand; rejecting anything else at setup turns a silent dead monitor into
  an inline field error.

Live probe (release binary):

```
POST oversized  -> {"ok":true,"received":1}
retry           -> {"ok":true,"received":0}
stored sizes    -> childId 128 B, type 64 B, package 128 B, data 8192 B
/api/export     -> 8,803 bytes
SSE             -> 64 × 200 OK, 65th 503 {"error":"too many stream clients"}
health after    -> {"ok":true,...}
```

## 5l. Daily audit — 2026-11-19, dashboard response-shape pass

The dashboard treats the collector as trusted. It is not: it can be an older
build, a misconfigured proxy, or a compromised host. Sync runs unattended from
cron, so a crash there silently stops the parent's view.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Sync envelope | A bare list or `{"events": null}` crashed `.get(...)` | `_response_list` returns `[]` for any non-dict/non-list | `test_sync_survives_a_bare_list_response` |
| Sync rows | `null`/`1` in `events` crashed `ev.get("id")` outside the guard | Skip rows that are not objects | `test_sync_survives_malformed_rows_and_response_shapes` |
| Doctor children | `c.get("online")` on a string/null | Keep only dict entries | `test_doctor_survives_non_dict_children` |

Why these shapes:

- **One bad row must not cost the whole sync.** The per-row `try` only covered
  the SQL; shape errors happened before it. Validate first, then insert.
- **Doctor should report, not traceback.** A wrong `/api/stats` shape is a
  verdict (exit 4) or something to skip — never an exception the parent cannot
  read.

Live probe against a fake server returning `events:[null,1,{…}]`,
`alerts:[null,{…}]`, `children:["x",null,{…}]`:

```
--sync    -> Sync complete: 2 new records   (exit 0)
--logs    -> [..] typing  kept              (valid row survived)
--doctor  -> data: 1 events, 0 alerts, 1 child(ren), 1 online
             doctor: healthy (exit 0)
```

## 5m. Daily audit — 2026-11-19, persistence-durability pass

The collector's persistence is coalesced: handlers only set a flag, and a
background thread writes a snapshot every 2 s. The flag handling was wrong in
two ways, and both lost data silently.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Failed write | `dirty` cleared before writing; a failed write left it clear, so nothing was retried until a new request arrived | Clear only after `write_snapshot` returns `true` | `failed_persist_is_reported_and_keeps_state_dirty` |
| Write race | A request during the write was marked clean by the writer | `generation` counter; clear only if `snapshot_is_current` | `a_mutation_during_the_write_is_not_marked_clean` |
| Observability | `write_snapshot` returned nothing | Returns `bool`; `persist_state_to` too | `persist_roundtrip` asserts success |

Why these shapes:

- **"Dirty" is a promise, not a guess.** It may only be cleared by evidence
  that the exact state it describes is on disk. A generation counter is the
  cheapest way to know the snapshot you wrote is still the current one.
- **Disk failures are normal, not exceptional.** ENOSPC, a full SD card, or a
  read-only remount should make the collector retry every tick, not go quiet.
  The retry loop is the existing one; it just was not allowed to run.

Live probe: data dir pointed at a regular file so `create_dir_all` fails for
every write, one event posted, then the obstruction removed with **no further
requests**:

```
POST /api/events        -> {"ok":true,"received":1}
tick 1 (blocked)        -> persist mkdir failed: File exists
tick 2 (blocked)        -> persist mkdir failed: File exists   (retried, not silent)
obstruction removed
tick 3                  -> state.json written, events = 1, data = "write-during-outage"
                           dir mode 700, file mode 600
```

## 5n. Daily audit — 2026-11-19, request-framing pass

The collector terminates HTTP itself (no hyper/reqwest), so it owns the framing
rules. It was applying requests whose framing was invalid or incomplete.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Duplicate CL | Last value won; conflicting lengths accepted | Digits only, repeats must agree | two unit tests |
| Non-numeric CL | `parse().ok()` ⇒ `None` ⇒ length 0 | Invalid value ⇒ `400` | `non_numeric_content_length_is_rejected` |
| Chunked | `Transfer-Encoding` ignored; body dropped | Any TE ⇒ `400` | `transfer_encoding_is_rejected` |
| Truncated | EOF before declared length ⇒ partial request applied | `body.len() < cl` ⇒ `400` | `truncated_body_is_rejected` |

Why these shapes:

- **Frame first, parse second.** If the server and any intermediary disagree
  about where the body ends, the bytes after it are attacker-chosen. This
  server always answers `Connection: close`, so it cannot be desynced into
  serving a smuggled request on the same socket, but a proxy in front can.
- **A partial body is not a small body.** `{}` is valid JSON; accepting it
  after a truncated `Content-Length` means a dropped connection can still
  perform a write. Rejecting is the only safe reading.
- **Identical duplicates stay legal.** Some clients legitimately repeat the
  same length; refusing them would break compatibility for no gain. Only
  disagreement is an error.

Live probe against the release binary (each request half-closes after sending):

```
dup CL (2 then 14), body 14   -> 400 {"error":"bad request"}
truncated CL 100, body {}     -> 400 {"error":"bad request"}
CL: 12abc                     -> 400 {"error":"bad request"}
TE: chunked                   -> 400 {"error":"bad request"}
identical dup CL (14, 14)     -> 200 {"childId":"cg_21d6…"}
valid register                -> 200 {"childId":"cg_9d58…"}
children after                -> Bob, Dee only (no phantom "Child")
health after                  -> {"ok":true,…}
```

## 5o. Daily audit — 2026-11-19, screen-time attribution pass

The screen-time view inferred duration from event gaps, but it tracked the
previous event **per package**. That double-counts whenever the child switches
apps, which is the normal case.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Attribution | Each package measured against its own last event, so the next app's time was credited to the previous one | One merged timeline; each gap goes to the app active at its start | test with A 0–100 s, B 100–300 s |
| Labels | Last dotted segment is often `android` | Strip platform suffixes | `pkg_name` test |

Why these shapes:

- **A gap belongs to whoever was on screen when it began.** When B's
  `screen_active` arrives, A's session is over; B owns the time until the next
  event. This also makes the invariant obvious: the sum of attributions can
  never exceed the timeline.
- **The idle cap still applies.** A gap longer than 5 minutes is idle, so a
  child who leaves an app open overnight is not credited hours.
- **Labels are a trust signal.** "Android 3m" tells a parent nothing and looks
  like a bug; "Instagram 3m" is the same data stated honestly.

Pre-fix probe on a 300 s timeline (WhatsApp 0–100 s, Instagram 100–300 s):

```
compute_screen_durations -> {'com.whatsapp': 300000, 'com.instagram.android': 100000}
sum = 400000 ms inside a 300000 ms window
```

Live `--screen-time` after the fix (same synthetic rows):

```
Instagram   3m  ####
Whatsapp    1m  ##
```

## 5p. Daily audit — 2026-11-20, collector-parity pass

The README presents the Rust and Node collectors as interchangeable. They were
not interchangeable for `limit`.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| `limit=0` | Node returned the default 200; Rust returned none | Explicit 0 ⇒ empty list (`slice(-0)` trap) | smoke `limit=0` |
| `limit=5.9` | Node truncated to 5; Rust fell back | Digits-only parsing | smoke `5.9` |
| Overflow | `Number()` can be `Infinity` | `Number.isSafeInteger` | code review + fallback test |

Why these shapes:

- **Parity is a promise to the operator.** A script that pages with `limit`
  should not change behavior when the collector is swapped. Matching the
  stricter parser is safer than teaching Rust to truncate decimals.
- **`slice(-0)` is a trap.** `-0` is `0` in JavaScript, so a naive fix for 0
  would return *every* event — the largest possible answer for the smallest
  requested limit. `tail()` makes the intent explicit.
- **Non-numeric means unset.** Falling back to the default keeps a typo from
  turning into an empty dashboard.

Live side-by-side, same three events posted to each collector:

```
limit=0    rust 0 events   node 0 events
limit=2    rust 2 events   node 2 events
limit=5.9  rust 3 events   node 3 events   (fallback)
limit=abc  rust 3 events   node 3 events   (fallback)
limit=-1   rust 3 events   node 3 events   (fallback)
```

## 5q. Daily audit — 2026-11-20, idempotent registration pass

Feature 1's first step is `/api/register`. It had no notion of *which device*
was asking, so retrying it was destructive.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Duplicate children | Lost reply → retry → new `cg_…` id every time | App supplies a persisted stable id; servers honour it | same-binary probe 2 → 1 |
| Response read | Unbounded `readText()` | `readAtMost(…, 8 KiB)` | `registrationResponseReadIsBounded` |
| Visibility | Non-200 was silent | `Log.w` with the status; malformed 200 retried | code + test |

Why these shapes:

- **Generate the id before the request, not after the reply.** Persisting
  `pending_child_id` first means the retry carries the same identity even if
  the first POST reached the server and only its response was lost. Clearing
  it only on success keeps the window closed.
- **The server stays backward compatible.** `childId` is optional; a client
  that omits it (older APK, hand-written curl) still gets a generated id, so
  the change cannot break an existing install.
- **A malformed 200 is not success.** Trusting any 200 would store a
  meaningless id and send every later event to it; retrying is bounded by the
  same backoff as an unreachable server.

Live probe against one Rust binary (port 38082), same body twice:

```
old client shape (no childId):
  cg_0d1bf189… / cg_f3d51e81…   -> 2 children named "Kid"

new client shape (childId=cg_phone_1):
  cg_phone_1 / cg_phone_1       -> 1 child
```

Android keeps the id across a lost response: the pref is written before the
POST and removed only after a 200 that contains a `childId`.

## 5r. Daily audit — 2026-11-20, silent-loss visibility and upload loop

Two Android defects with the same theme: the app kept running while something
important had quietly stopped working.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Rejected events | Counter written, never read | Dashboard line + notification + Setup reset | 6 new tests |
| Stop/start loop | In-flight pass re-armed the new handler | Per-loop handler + generation | test fails pre-fix (`1` vs `2`) |

Why these shapes:

- **A counter nobody reads is the same as no counter.** The 400/415/422 path
  isolates the poison event by halving the batch; when one event remains it is
  discarded. That is the right call for one malformed record, but it must be
  *visible*. The warning is durable (per install) and names the HTTP status,
  because "events were lost" without a reason gives the parent nothing to fix.
- **The notification is the only surface guaranteed to be seen.** A parent
  installs the app once and rarely opens it. Re-posting the ongoing
  notification on each rejection is a handful of bytes and no extra channel.
- **Clearing on endpoint change is deliberate.** After the parent changes the
  URL or token (the action the warning asks for), the old count describes a
  configuration that no longer exists and would nag forever. Re-saving the
  same values keeps it, so a parent cannot dismiss the warning by tapping
  Finish.
- **A loop must own its scheduler.** With `quitSafely()` the old pass cannot
  be cancelled, so the only safe rule is that a pass may reschedule *its own*
  handler and only while its generation is current. Capturing the handler
  removes the field race; the `@Volatile` generation removes the logical race.

Proof of the loop bug, running the new test against the old shape (temporarily
restored):

```
restartingTheLoopDoesNotDoubleTheUploadRate FAILED
java.lang.AssertionError: a stale pass must not schedule a second chain
    expected:<1> but was:<2>
```

and against the fix:

```
MonitorServiceLifecycleTest  tests=6 failures=0
```

Live dashboard surface (Robolectric, real `DashboardActivity`), with
`dropped_events_total=3`, `last_rejection_code=400`:

```
tv_dropped.visibility = VISIBLE
tv_dropped.text = "3 events were rejected by the server (HTTP 400).
                   Check the server URL and token in Setup."
```

## 5s. Daily audit — 2026-11-20, dashboard sync/export parity pass

The dashboard is the parent-facing copy of the data, but it sanitized less than
the collectors that produced it.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Timestamps | `0` stored as 1970; `inf` dropped the row | Same rule as Rust/Node: `<= 0`/non-finite ⇒ now, future ⇒ +1 h | 4-row sync test |
| Exports | Same-second name + `O_TRUNC` overwrote the previous bundle | `O_EXCL` name claim + `mkstemp` staging | 2 exports, 2 bundles |

Why these shapes:

- **The dashboard is allowed to be a cache, not a second policy.** When the
  collector already decided that `ts=0` means "now", storing 1970 in SQLite is
  not extra safety; it is a silent contradiction. A future timestamp is kept
  (clamped), not reset, because a phone one hour ahead is a real clock, not
  corruption.
- **`OverflowError` is not a `ValueError`.** JSON has `Infinity`; Python's
  `json` parses `1e400` to it, and `int(float('inf'))` raises `OverflowError`,
  which the old handler did not catch. The row was skipped by the broad
  per-row `except` — one bad record disappeared with no count.
- **A name is a claim.** `O_TRUNC` says "replace whatever is there"; `O_EXCL`
  says "this name is mine". Exports contain a child's messages, so silently
  replacing yesterday's evidence with a zero-byte or half-written file is the
  worst failure mode. The second bundle gets `-2`, which is obvious in `ls`.
- **Staging files need the same treatment.** Fixed names meant two concurrent
  exports scribbled into the same `.jsonl` before archiving. `mkstemp` is
  already 0600 and unique; the archive members are still forced to 0600.

Proof on the old code (files restored from `HEAD`, new tests kept):

```
FAIL test_sync_sanitizes_zero_negative_and_future_timestamps
     AssertionError: 4 != 3 : every row must still be stored
FAIL test_two_exports_in_the_same_second_do_not_overwrite
     AssertionError: 2 != 1 : same-second export overwrote:
     ['childguard_logs_20260101-000000.tar.gz']
Ran 2 tests ... FAILED (failures=2)
```

After the fix: `Ran 21 tests ... OK`.

## 5t. Daily audit — 2026-11-20, header-budget pass

One more Rust-vs-Node divergence, this time in framing rather than data.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Header overflow | `413` (body too large) | `431` (header fields too large) | unit + live |
| Enforcement | No cap until the body limit; 2.1 MiB buffer, 30 s wait | Reject at 64 KiB while reading | flood test: 10 s → instant |

Why these shapes:

- **The status code is the contract.** `413` tells the client to shrink its
  body. The body was fine; the headers were not. Node already answers `431`
  (its parser has a 16 KiB limit), so matching it means a script sees the same
  answer from either collector.
- **A cap that is only checked after the terminator is not a cap.** A client
  that never sends `\r\n\r\n` made the loop read up to the body budget.
  That is 2.1 MiB of per-connection buffer and a 30 s hold — exactly what a
  slowloris wants. Checking the *unterminated* length every read bounds both.
- **Valid requests are untouched.** The check runs only after the scan fails
  to find the terminator, so a 64 KiB header block followed by a body in the
  same read is still accepted based on `split`, not total buffer size.

Proof, same binary, three socket probes:

```
terminated 70 KiB        HTTP/1.1 431 Request Header Fields Too Large  (1.9 ms)
unterminated 70 KiB      HTTP/1.1 431 Request Header Fields Too Large  (1.6 ms)
valid health             HTTP/1.1 200 OK
```

Old shape, new tests (checks reverted only):

```
an_oversized_header_block_is_431_not_413            FAILED  left: 431  right: 413
an_unterminated_header_flood_is_431_without_...     FAILED  left: 431  right: 408  (10.24 s)
```

## 5u. Daily audit — 2026-11-20, outage visibility pass

Feature 1's promise is that captured events reach the parent. The failure mode
this pass closes is the quiet one: the app is running, the queue is filling,
and the screen still says everything is fine.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Upload/registration errors | Only `Log.w` | Persisted current error + dashboard line | Robolectric UI test + live E2E |
| Recovery | No state to clear | Any 2xx or reachable rejection clears it | E2E: dead port → warning, live server → null |
| Permanent rejections | Already visible (F-69) | Kept separate from transient outages | existing tests |

Why these shapes:

- **Current state, not a log.** The line describes *now* ("Uploads failing…")
  and disappears on the next success, so a parent is not trained to ignore a
  warning that is years old. Permanent losses keep their own durable counter
  (F-69) because those events never come back.
- **Retrying automatically is part of the message.** Without it a red-ish line
  reads as "fix something", when the correct action may be to wait. The line
  names the HTTP status or the failure class so a bug report is actionable.
- **Registration is an upload failure too.** A 401 on `/api/register` means
  zero events will ever be sent; treating only `/api/events` as the failure
  would miss the most common misconfiguration (wrong token).
- **Reachable-but-rejected clears the outage marker.** A 400/413/415/422 is a
  payload problem, not a connectivity one; the drop/split logic reports it
  separately. Keeping "cannot reach the server" while the server is answering
  would be false.

Live E2E (`Feature1ServerE2ETest`, real `MonitorService` and real HTTP):

```
dead port  -> warning = "Uploads failing since 01:xx — cannot reach the server
                          to register (ConnectException). Retrying automatically."
live server-> uploadWarning(prefs) = null
```

## 5v. Daily audit — 2026-11-20, local queue loss visibility

F-69 made *server* rejections visible. This pass closes the same hole one layer
lower: the bounded capture queues.

| Area | What was wrong | Fix | Proof |
|---|---|---|---|
| Accessibility queue | 201st event silently evicted the oldest | Count + durable warning (code 0) | test fails pre-fix (`1` vs `0`) |
| Notification queue | Same cap, same silence | Same accounting | test fails pre-fix (`60` vs `0`) |
| Dashboard wording | Only server rejections had text | Local-loss sentence, no "HTTP" | Robolectric UI test |

Why these shapes:

- **The queue cap stays.** 200 is the right bound for a phone; the bug was not
  the eviction, it was the silence. Counting is a few bytes of prefs only when
  an eviction happens, so a healthy device pays nothing per event.
- **One counter, two causes.** `dropped_events_total` already means "captured
  evidence that will never reach the parent". A local overflow is the same
  outcome; a second counter would double the UI for no benefit. The sentence
  names the most recent cause (`code > 0` = server, `0` = local queue), which
  is what the parent can act on.
- **The message says what to do.** A full queue usually means uploads have not
  drained for a while (offline, wrong token, server down). "Check the server
  connection" points at the cause instead of blaming the child's phone.
- **No prefs write on the hot path.** `recordLocalLoss` returns immediately
  for `count <= 0`; an accessibility callback must never touch disk just to
  confirm nothing was lost.

Proof against the old eviction shape (counting removed, tests kept):

```
Feature1RuntimeTest > aFullCaptureQueueDropsTheOldestEventLoudly FAILED
    one evicted event must be counted  expected:<1> but was:<0>
Feature3NotificationTest > notificationQueueOverflowIsCountedNotSilent FAILED
    all 60 evictions must be counted  expected:<60> but was:<0>
```

On the fix: `Feature1RuntimeTest tests=12 failures=0`,
`Feature3NotificationTest tests=12 failures=0`, `DashboardActivityTest tests=7 failures=0`.

## 6. Verification log (this audit)

```
server-rust:  cargo test            → 37 passed; 0 failed
server-rust:  cargo clippy -D warnings → clean
node server:  node server/smoke_test.js → SMOKE OK (auth, token
              non-disclosure, dedupe, caps, limit parsing, ts coercion,
              LRU eviction, field clipping, stream limit, liveness)
android:      69 tests, 0 failures, 0 skipped (with live server)
              4 E2E tests skip when no server is configured
              test classes: Feature1Extraction 15, Feature1Runtime 11,
              Feature3Notification 11, Feature1ServerE2E 4, UploadBatching 8,
              DashboardActivity 7, MonitorServiceLifecycle 6,
              SetupActivity 4, WebMonitorActivity 1
dashboard:    python3 -m unittest termux/test_dashboard.py → 21 passed
android:      assembleDebug → app-debug.apk 7,400,169 bytes,
              sha256 aa8c214a...f9ad4
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
| Rust vs Node | Node measured **329 writes/s** vs Rust 227 on this host | Rust is kept for a 681 kB binary and 768 kB idle RSS on a phone, not for throughput. The audit states this plainly rather than claiming Rust is faster. | If throughput on a large host matters more than footprint, Node is the faster choice. |

The rule behind these choices: **bound the work, then keep the simplest design
that stays inside the bound.** A full snapshot, a full window, and a full tree
dump are each cheap *because* caps make their size predictable. Removing a cap
to chase speed is how this class of app turns one large screen into an ANR.
