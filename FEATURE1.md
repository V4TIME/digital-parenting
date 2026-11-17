# Feature 1 — accessibility interception (verified)

This is the core feature: what the child types/reads in chat and browser apps is
captured, delivered to the parent server, and rendered in the Termux dashboard.

## What runs in the app

`ChildGuardAccessibilityService.kt`

| Event type | Trigger | Data |
|---|---|---|
| `keylog` | `TYPE_VIEW_TEXT_CHANGED` / `TYPE_VIEW_FOCUSED` | full text of the focused field |
| `keystroke` | `TYPE_VIEW_CLICKED` / `TYPE_VIEW_LONG_CLICKED` | text recovered from the clicked node or its siblings |
| `screen_text` | `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_VIEW_SCROLLED` | visible text of the active window, uploaded only when it changed (and at most every 800 ms per package) |
| `screen_active` | `TYPE_WINDOW_STATE_CHANGED` | activity class (powers screen time) |
| `stranger_alert` | pattern hit (age/photo/meet/phone/…) | the matched text |
| `service_connected` | `onServiceConnected` | online marker |
| `notification` | fallback only, when the notification listener is NOT bound | notification text |

Delivery: `MonitorService` drains the queue every 15 s and POSTs
`{"childId": ..., "events": [...]}` to the collector in **bounded batches**
(250 events max). On any failure (no network, non-2xx) the batch is **requeued**
(bounded at 1000) and retried; the server dedupes identical events, so retries
never inflate counts. Un-batched leftovers stay queued for the next cycle, so a
slow link still makes progress instead of re-sending one huge body forever.

Typed text is read from the source node when available and falls back to the
event payload (`AccessibilityEvent.text`), which is how WebViews and some chat
fields report it — so a keystroke is not lost when no readable node exists.

## Smoothness / robustness hardening

- **Bounded view traversal.** A screen dump visits at most 400 nodes and 60
  siblings per level. Every `child()` call is a binder round-trip; an
  unbounded tree (browser DOM, recycled chat list) could otherwise stall the
  accessibility callback into an ANR. A regression test builds a lazy tree that
  would be 60^6 nodes and proves it returns in bounded time.
- **Scroll-aware capture.** Long chats and web pages often signal new content
  only with `TYPE_VIEW_SCROLLED`; the service subscribes and treats it as a
  content change, so messages loaded by scrolling are not missed. The 800 ms
  gate and the content hash stop scroll spam from re-uploading.
- **Content-based screen dedupe.** Identical `screen_text` dumps (cursor blink,
  timers, ads) are no longer uploaded over and over: only a changed dump is
  sent. This cuts mobile-data use and makes the dashboard readable.
- **Bounded upload batches.** 250 events per request, retries first, leftovers
  retained. Verified with 600 queued events: 250 + 250 + 100, no duplicates and
  nothing lost.
- **High-precision alerts.** Bare platform names, `@mentions` and URLs no longer
  raise HIGH alerts — ordinary chat contains them all day. Real signals (age /
  school / photo / secrecy / meeting / phone number) still do; tests pin both
  sides. Text is still captured either way, it just is not flagged.
- **Notification precision (Feature 3).** The listener had its own broader
  pattern list; it now applies the same rule (parent mentions need secrecy
  context, `follow me` and "nice" are not signals). Tested with real
  `StatusBarNotification` objects on API 34.
- **Connection doctor.** `dashboard.py --doctor` reports reachability, token
  validity, and live counts with scriptable exit codes, so a blank dashboard is
  never ambiguous.


## Proof in the app (JVM unit tests)

`android/app/src/test/java/com/childguard/app/Feature1ExtractionTest.kt` (13)
drives the real extraction logic through a fake node tree (`NodeLike`):

- send-button click recovers the child EditText's text
- click on a sibling button walks up and finds the EditText's text
- 1000 null children return `""` quickly (no infinite loop)
- a 60^6 lazy view tree returns within a node/time budget
- identical screen dumps are suppressed; changed text is uploaded
- ordinary app mentions/@mentions/URLs do not alert; grooming signals do
- stranger patterns match/ignore correct text
- notification classification emits `stranger_notification`
- failed uploads keep events for retry

`UploadBatchingTest.kt` (3) proves the upload pipeline: 600 queued events are
sent as 250 + 250 + 100 with no duplicates, retry draining honours its limit,
and notification events get the `data` field the dashboard reads.

Run on any build machine:

```bash
cd android && ./gradlew :app:testDebugUnitTest
# report: app/build/reports/tests/testDebugUnitTest/index.html
```

## Proof in the app (real Android runtime + live server)

The logic tests above don't exercise `onAccessibilityEvent`. Two Robolectric
tests do, on **Android 14 (API 34)**:

- `Feature1RuntimeTest` (6 tests) builds the real service and feeds it real
  `AccessibilityEvent`s: typing → `keylog` + `stranger_alert`, window change →
  `screen_active`, notification fallback, `service_connected`, and
  "nothing captured when monitoring is off".
- `Feature1ServerE2ETest` (4 tests) then runs the real
  `MonitorService.uploadQueuedEvents()` over HTTP against a live server:
  every queued event reaches it, a dead network requeues instead of dropping,
  and a resent batch doesn't inflate counts.

Full details and the on-device checklist: [`RUNTIME_TEST.md`](RUNTIME_TEST.md).

## Proof in the dashboard

Verified end-to-end with the collector:

```
push#1: {'ok': True, 'received': 10}
push#2 (retry): {'ok': True, 'received': 0}     # deduped, no false counts
Sync complete: 12 new records
Sync complete: 0 new records                    # idempotent sync
events: 10   alerts: 2
```

Dashboard rendering:

```
By event type
  screen activity    4
  typing             1
  button text        1
  screen text        1
  monitor online     1
  STRANGER ALERT     1
  stranger notification 1

Alert stream
  ● [..] HIGH — stranger detected
      dont tell your parents
  ● [..] MEDIUM — stranger notification
      New friend | meet up after school
```

Screen time computed from `screen_active` events also renders
(`Whatsapp 4m`).

## Parent quickstart (Termux)

```bash
python3 termux/dashboard.py --configure --server http://<tailscale-ip>:38080 --token <token>
python3 termux/dashboard.py --sync --overview
python3 termux/dashboard.py --logs ""
python3 termux/dashboard.py --alerts
python3 termux/dashboard.py --screen-time
```
