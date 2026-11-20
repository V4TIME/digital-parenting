package com.childguard.app

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Feature 1 runtime proof on Android 14 (Robolectric, API 34).
 *
 * Unlike the pure unit tests, this instantiates the real
 * ChildGuardAccessibilityService, feeds it real AccessibilityEvent objects and
 * checks the exact JSON that MonitorService uploads to the parent server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Feature1RuntimeTest {

    private lateinit var service: ChildGuardAccessibilityService

    @Before
    fun setUp() {
        MonitorService.start()
        service = Robolectric.buildService(ChildGuardAccessibilityService::class.java)
            .create().get()
        ChildGuardAccessibilityService.eventQueue.clear()
        // Make the fallback tests deterministic regardless of test order.
        ChildGuardNotificationListener.isListenerConnected = false
    }

    private fun events(): List<JSONObject> =
        ChildGuardAccessibilityService.eventQueue.toList()

    private fun find(type: String): JSONObject? =
        events().firstOrNull { it.optString("type") == type }

    private fun textEvent(pkg: String, text: String): AccessibilityEvent {
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        ev.packageName = pkg
        ev.className = "$pkg.ConversationActivity"
        // This is how the framework delivers typed text on a text-change event.
        ev.text.add(text)
        return ev
    }

    @Test
    fun typedMessageIsQueuedForUpload() {
        service.onAccessibilityEvent(
            textEvent("com.whatsapp", "hey kid whats your age")
        )

        val q = events()
        assertFalse("onAccessibilityEvent must queue events", q.isEmpty())

        val keylog = find("keylog")
        assertNotNull("typing must be captured", keylog)
        assertEquals("hey kid whats your age", keylog!!.optString("data"))
        assertEquals("com.whatsapp", keylog.optString("package"))
        assertTrue("event must carry a timestamp", keylog.optLong("ts") > 0)

        val alert = find("stranger_alert")
        assertNotNull("stranger phrase must raise an alert", alert)
        assertEquals("hey kid whats your age", alert!!.optString("data"))
    }

    @Test
    fun harmlessMessageDoesNotRaiseAlert() {
        service.onAccessibilityEvent(textEvent("com.whatsapp", "mom dinner at 7"))

        assertNotNull("typing still captured", find("keylog"))
        assertNull("no false alert for normal text", find("stranger_alert"))
    }

    @Test
    fun passwordEventIsNotRecorded() {
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        ev.packageName = "com.whatsapp"
        ev.text.add("PIN 987654")
        ev.isPassword = true
        service.onAccessibilityEvent(ev)

        assertNull("password/PIN text must never be captured", find("keylog"))
        assertNull(find("stranger_alert"))
    }

    @Test
    fun windowChangeIsQueuedForScreenTime() {
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ev.packageName = "com.android.chrome"
        ev.className = "org.chromium.chrome.browser.ChromeTabbedActivity"
        service.onAccessibilityEvent(ev)

        val screen = find("screen_active")
        assertNotNull("window change must be captured", screen)
        assertEquals("com.android.chrome", screen!!.optString("package"))
        assertEquals(
            "org.chromium.chrome.browser.ChromeTabbedActivity",
            screen.optString("data")
        )
    }

    @Test
    fun notificationFallbackQueuesWhenListenerNotBound() {
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
        ev.packageName = "com.whatsapp"
        ev.text.add("New message from unknown number")
        service.onAccessibilityEvent(ev)

        val n = find("notification")
        assertNotNull("notification fallback must queue", n)
        assertEquals("New message from unknown number", n!!.optString("data"))
    }

    @Test
    fun notificationFallbackIgnoresNonChatPackages() {
        // The fallback must enforce the same privacy boundary as Feature 3.
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
        ev.packageName = "com.bank.app"
        ev.text.add("Your one-time code is 654321")
        service.onAccessibilityEvent(ev)

        assertNull("banking/2FA notifications must never be captured", find("notification"))
    }

    @Test
    fun notificationFallbackDedupesRepeatedPost() {
        repeat(3) {
            val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            ev.packageName = "com.whatsapp"
            ev.text.add("Fallback duplicate probe 7842")
            service.onAccessibilityEvent(ev)
        }

        assertEquals(
            "a re-posted notification must produce one event, not three",
            1,
            events().count { it.optString("type") == "notification" }
        )
    }

    @Test
    fun serviceOnlineMarkerIsQueued() {
        // onServiceConnected is protected; invoke it the way the system would.
        val m = ChildGuardAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
        m.isAccessible = true
        m.invoke(service)

        val online = find("service_connected")
        assertNotNull("service must announce it is online", online)
        assertEquals("com.childguard.app", online!!.optString("package"))
    }

    @Test
    fun serviceRequestsScrolledContentEvents() {
        // Long chats/web pages sometimes signal new content only through a
        // scroll event; if the service does not subscribe, that text is lost.
        val m = ChildGuardAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
        m.isAccessible = true
        m.invoke(service)

        val types = ChildGuardAccessibilityService.requestedEventTypes
        assertTrue(
            "TYPE_VIEW_SCROLLED must be requested",
            types and AccessibilityEvent.TYPE_VIEW_SCROLLED != 0
        )
        assertTrue(
            "TYPE_WINDOW_CONTENT_CHANGED must stay requested",
            types and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0
        )
    }

    @Test
    fun scrollEventWithoutAReadableTreeIsIgnoredSafely() {
        // Robolectric has no active window here: the event must be dropped
        // without throwing and without queueing an empty dump.
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        ev.packageName = "com.whatsapp"
        service.onAccessibilityEvent(ev)

        assertNull("no empty screen_text event", find("screen_text"))
    }

    @Test
    fun aFullCaptureQueueDropsTheOldestEventLoudly() {
        // A phone that is offline for a while fills the 200-event queue. The
        // 201st event evicts the oldest, and that eviction used to be
        // completely silent: no counter, no dashboard line, no notification.
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(ChildGuardApp.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(MonitorService.KEY_DROPPED_EVENTS)
            .remove(MonitorService.KEY_LAST_REJECTION)
            .apply()
        repeat(200) { i ->
            ChildGuardAccessibilityService.eventQueue.add(
                JSONObject()
                    .put("type", "keylog")
                    .put("package", "com.whatsapp")
                    .put("data", "backlog-$i")
                    .put("ts", 1_000L + i)
            )
        }

        service.onAccessibilityEvent(textEvent("com.whatsapp", "queue overflow probe 9911"))

        assertEquals(
            "the queue must stay at its cap",
            200,
            ChildGuardAccessibilityService.eventQueue.size
        )
        assertTrue(
            "the newest event must be the one kept",
            events().any { it.optString("data") == "queue overflow probe 9911" }
        )
        assertEquals(
            "one evicted event must be counted",
            1,
            prefs.getInt(MonitorService.KEY_DROPPED_EVENTS, 0)
        )
        val warning = MonitorService.droppedWarning(prefs)
        assertNotNull("local loss must be shown to the parent", warning)
        assertTrue(
            "the warning must blame the local queue: $warning",
            warning!!.contains("lost locally")
        )
    }

    @Test
    fun nothingIsCapturedWhenMonitoringStopped() {
        MonitorService.stop()
        service.onAccessibilityEvent(textEvent("com.whatsapp", "secret text"))
        assertTrue("monitoring off must capture nothing", events().isEmpty())
    }
}
