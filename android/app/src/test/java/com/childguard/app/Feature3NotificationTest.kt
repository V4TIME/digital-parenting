package com.childguard.app

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature 3 runtime proof on Android 14 (Robolectric, API 34).
 *
 * Drives the real NotificationListenerService with real StatusBarNotification
 * objects, so the path from a chat notification to a queued, alertable upload
 * payload is exercised — not just the regex helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Feature3NotificationTest {

    private lateinit var listener: ChildGuardNotificationListener

    @Before
    fun setUp() {
        MonitorService.start()
        ChildGuardNotificationListener.queue.clear()
        listener = Robolectric.buildService(ChildGuardNotificationListener::class.java)
            .create().get()
    }

    @After
    fun tearDown() {
        ChildGuardNotificationListener.queue.clear()
        listener.onListenerDisconnected()
        MonitorService.stop()
    }

    private fun sbn(pkg: String, title: String, text: String): StatusBarNotification {
        val n = Notification()
        n.extras.putCharSequence(NotificationCompat.EXTRA_TITLE, title)
        n.extras.putCharSequence(NotificationCompat.EXTRA_TEXT, text)
        return StatusBarNotification(
            pkg, pkg, 1, "tag", 10001, 1, 0, n, Process.myUserHandle(),
            System.currentTimeMillis()
        )
    }

    private fun onlyQueued(): JSONObject {
        assertEquals("exactly one event must be queued", 1, ChildGuardNotificationListener.queue.size)
        return ChildGuardNotificationListener.queue.peek()
    }

    @Test
    fun groomingNotificationBecomesAnAlertableEvent() {
        listener.onNotificationPosted(
            sbn("com.whatsapp", "New friend", "hey kid what's your age")
        )

        val ev = onlyQueued()
        assertEquals("stranger_notification", ev.optString("type"))
        assertEquals("com.whatsapp", ev.optString("package"))
        assertTrue("chat flag", ev.optBoolean("chat"))
        assertTrue("alert flag", ev.optBoolean("stranger_alert"))
        assertEquals(
            "the dashboard reads `data`, so it must carry the notification text",
            "New friend | hey kid what's your age",
            ev.optString("data")
        )
    }

    @Test
    fun ordinaryParentMentionStaysQuiet() {
        listener.onNotificationPosted(sbn("com.whatsapp", "Mom", "dinner at 7"))
        val ev = onlyQueued()
        assertEquals("notification", ev.optString("type"))
        assertFalse("no alert for an ordinary family message", ev.has("stranger_alert"))
    }

    @Test
    fun broadMentionsDoNotAlert() {
        // These were false positives before the precision pass (AUDIT F-12).
        assertFalse(ChildGuardNotificationListener.isStrangerText("your mom said hi"))
        assertFalse(ChildGuardNotificationListener.isStrangerText("follow me on instagram"))
        assertFalse(ChildGuardNotificationListener.isStrangerText("look at https://example.com"))
        // Real signals still alert.
        assertTrue(ChildGuardNotificationListener.isStrangerText("don't tell your parents"))
        assertTrue(ChildGuardNotificationListener.isStrangerText("your dad doesn't know about this"))
    }

    @Test
    fun nonChatAppIsCapturedButNotFlagged() {
        listener.onNotificationPosted(sbn("com.example.game", "Level up", "you reached level 3"))

        val ev = onlyQueued()
        assertEquals("notification", ev.optString("type"))
        assertFalse("not a chat app", ev.optBoolean("chat"))
        assertFalse(ev.has("stranger_alert"))
    }

    @Test
    fun listenerConnectionStateTracksBinding() {
        // Feature 1 uses this to avoid double-counting notifications.
        ChildGuardNotificationListener.isListenerConnected = false
        listener.onListenerConnected()
        assertTrue(ChildGuardNotificationListener.isListenerConnected)
        listener.onListenerDisconnected()
        assertFalse(ChildGuardNotificationListener.isListenerConnected)
    }

    @Test
    fun notificationQueueIsBounded() {
        for (i in 1..260) {
            listener.onNotificationPosted(sbn("com.whatsapp", "Mom", "message $i"))
        }
        assertEquals(
            "queue must stay exactly at its cap",
            200,
            ChildGuardNotificationListener.queue.size
        )
        // The newest message survived the eviction.
        assertTrue(
            ChildGuardNotificationListener.queue.any { it.optString("text") == "message 260" }
        )
    }

    @Test
    fun nothingIsCapturedWhenMonitoringStopped() {
        MonitorService.stop()
        listener.onNotificationPosted(sbn("com.whatsapp", "New friend", "hey kid"))
        assertNull("monitoring off must queue nothing", ChildGuardNotificationListener.queue.peek())
    }
}
