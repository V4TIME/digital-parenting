# Feature 1 — runtime verification

Two layers of proof, plus the small part that needs a real phone.

## 1. Automated: real Android runtime on the JVM (no device needed)

`android/app/src/test/java/com/childguard/app/`

| Test class | What it proves | Android runtime |
|---|---|---|
| `Feature1ExtractionTest` (15) | extraction/regex logic on `NodeLike`, password-field exclusion, bounded view traversal, screen-dump dedupe, alert precision | none (pure JVM) |
| `UploadBatchingTest` (6) | 600-event backlog → 250+250+100 batches, retry limit, notification `data` field, failure classification, backoff, URL normalization | none (pure JVM) |
| `Feature1RuntimeTest` (11) | **`onAccessibilityEvent` → `eventQueue`** on the real service, scroll subscription, password events ignored, chat-only fallback dedupe | Robolectric API 34 |
| `Feature3NotificationTest` (10) | **`onNotificationPosted` → queue**, alert precision, chat-only allowlist, re-post dedupe, queue cap | Robolectric API 34 |
| `Feature1ServerE2ETest` (4) | **`eventQueue` → `MonitorService` upload → server** | Robolectric API 34 + live server |
| `DashboardActivityTest` (2) | **"Monitoring: ON" actually starts `MonitorService`** (first-run flow), and OFF does not | Robolectric API 34 |
| `MonitorServiceLifecycleTest` (4) | explicit STOP is not sticky; a null-intent system restart resumes or stops cleanly | Robolectric API 34 |
| `WebMonitorActivityTest` (1) | the internet-app scan lists a real `INTERNET` app via one `getInstalledPackages` call, off the UI thread | Robolectric API 34 |

```bash
cd android
export JAVA_HOME=/opt/tools/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:/opt/tools/gradle-8.10.2/bin:$PATH"

# logic + runtime (no server needed)
gradle :app:testDebugUnitTest

# full stack, against a live collector
( cd ../server-rust && CHILDGUARD_DATA_DIR=/tmp/cg_e2e CHILDGUARD_TOKEN=testsecret \
    ./target/debug/childguard-server & )
CHILDGUARD_SERVER=http://127.0.0.1:38080 CHILDGUARD_TOKEN=testsecret \
  gradle :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "*Feature1ServerE2ETest"
```

Last run: **53 tests, 0 failures, 0 skipped** (live collector; the 4 E2E tests
skip when no server is configured).

### What `Feature1RuntimeTest` drives

It builds the real service with `Robolectric.buildService(...)` and calls the real
`onAccessibilityEvent`:

- `TYPE_VIEW_TEXT_CHANGED` "hey kid whats your age" → queued `keylog` with the
  exact text, package, and timestamp, **plus** `stranger_alert`
- harmless text → `keylog` only, no alert (no false accusations)
- `TYPE_VIEW_TEXT_CHANGED` with `isPassword=true` → nothing queued
- `TYPE_WINDOW_STATE_CHANGED` → `screen_active` with the activity class (screen time)
- `TYPE_NOTIFICATION_STATE_CHANGED` → chat-only `notification` fallback when the
  listener isn't bound; non-chat packages and repeated posts are ignored
- `onServiceConnected` → `service_connected` online marker
- monitoring off → nothing captured

### What `Feature1ServerE2ETest` drives

Same real service, then the real `MonitorService.uploadQueuedEvents()` over HTTP:

- **everyCapturedEventReachesTheServer** — server `totalEvents` grows by exactly
  the number of queued events
- **failedUploadKeepsEventsInsteadOfDroppingThem** — pointed at a dead port, all
  events land in the retry queue with payload intact
- **retriesAreIdempotent** — re-sending the identical batch does not change the
  server's counts
- **harmlessChatsAreCapturedButNeverAlert** — captured, no alert

## 2. What the parent sees (dashboard)

Real captured events → server → Termux dashboard (`Sync complete: 8 new records`):

```
Totals
    events   : 7
    alerts   : 1
By event type
    typing            4
    screen activity   2
    STRANGER ALERT    1

Interception log
  [..] Whatsapp     typing          hi mom how was school
  [..] Whatsapp     typing          hey kid, meet up after school alone
  [..] Messenger    typing          mom dinner at 7
  [..] Chrome       screen activity org.chromium.chrome.browser.ChromeTabbedActivity
  [..] Whatsapp     STRANGER ALERT  hey kid, meet up after school alone

Alert stream
  ● [..] HIGH — stranger detected
      hey kid, meet up after school alone
```

## 3. On a real phone (needs the user — not possible in this environment)

This machine has no Android device and no KVM, so the APK can be built and
inspected here but not installed.

```bash
# build (works on aarch64 via the qemu-wrapped aapt2, see android/README notes)
cd android && gradle :app:assembleDebug
# install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Checklist:

1. **Settings → Accessibility → ChildGuard → On.** Grant notification access too.
2. **Server set:** in the app's setup screen, enter `http://<tailscale-ip>:38080` and the parent token.
3. **Start monitoring** from the app (starts the foreground service).
4. Open WhatsApp, type `test message 123`, send it.
5. Expect on the server: `service_connected`, `screen_active` (whatsapp), and a
   `keylog` containing `test message 123`.
6. From Termux: `python3 termux/dashboard.py --sync --overview` shows
   `monitor online`, `typing`, and `Whatsapp` under top packages.
7. Type `hey kid whats your age` → a `HIGH — stranger detected` alert appears.
8. Toggle airplane mode for 30 s, type something, re-enable: the keystroke still
   arrives after the connection returns (the retry queue), and counts do not
   inflate (server-side idempotency).

Steps 1–3 are one-time permission work that only the phone's owner can do; if any
step fails, `adb logcat | grep -E "ChildGuard|ChildGuardNL"` shows the service
state (`Accessibility service connected`).

## Build notes for this host (aarch64)

Android build-tools ship `aapt2` as an x86_64 binary. The APK is built here by
running it through qemu-user:

- `/home/kali/tools/aapt2-bin/aapt2` — wrapper (`qemu-x86_64-static` + sysroot)
- `~/.gradle/gradle.properties` (machine-local, **not** committed — other hosts
  let AGP download its own aapt2):
  `android.aapt2FromMavenOverride=/home/kali/tools/aapt2-bin/aapt2`

Independent of qemu, the real build caught a genuine bug the stub tests missed:
`accessibility_service_config.xml` used `typeNotificationMask`, which is not a
valid flag (it failed resource linking). It is now
`typeNotificationStateChanged`; the packaged APK carries event mask `0x187b`
(click, long-click, text-changed, focused, window-state, window-content,
scrolled, notification).
