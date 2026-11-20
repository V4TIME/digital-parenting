package com.childguard.app

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Service lifecycle: an explicit stop must not be undone by START_STICKY, and
 * a system restart (null intent) must either resume monitoring or stop
 * cleanly — never come back half-alive with no foreground notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitorServiceLifecycleTest {

    private lateinit var service: MonitorService
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        MonitorService.stop()
        val app: Application = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences(ChildGuardApp.PREFS, Context.MODE_PRIVATE)
        service = Robolectric.buildService(MonitorService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        MonitorService.stop()
    }

    private fun start(action: String?): Int =
        service.onStartCommand(action?.let { Intent(it) }, 0, 1)

    @Test
    fun startKeepsMonitoringAcrossProcessDeath() {
        assertEquals(Service.START_STICKY, start("childguard.START"))
        assertTrue(MonitorService.isMonitoring)
    }

    @Test
    fun explicitStopIsNotSticky() {
        // Returning START_STICKY here let Android recreate the service after
        // the parent had explicitly turned monitoring off.
        assertEquals(Service.START_NOT_STICKY, start("childguard.STOP"))
        assertFalse(MonitorService.isMonitoring)
    }

    @Test
    fun systemRestartResumesWhenMonitoringIsEnabled() {
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, true).apply()
        // Android recreates a sticky service with a null intent.
        assertEquals(Service.START_STICKY, start(null))
        assertTrue("monitoring must resume after process death", MonitorService.isMonitoring)
    }

    @Test
    fun systemRestartStopsCleanlyWhenMonitoringIsDisabled() {
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, false).apply()
        assertEquals(Service.START_NOT_STICKY, start(null))
        assertFalse(MonitorService.isMonitoring)
    }

    @Test
    fun restartingTheLoopDoesNotDoubleTheUploadRate() {
        // stop() quitSafely()s the thread but a pass already running finishes
        // afterwards. It used to post itself onto whatever handler the field
        // pointed at by then — the NEW loop's handler. Every stop/start added
        // another upload chain in parallel.
        start("childguard.START")
        val stale = privateField("uploadRunnable") as Runnable
        start("childguard.STOP")
        start("childguard.START")

        val handler = privateField("uploadHandler") as android.os.Handler
        val before = pendingMessages(handler)
        assertEquals("the fresh loop schedules exactly one pass", 1, before)

        stale.run() // the old thread finishing its last pass
        assertEquals(
            "a stale pass must not schedule a second chain",
            before,
            pendingMessages(handler)
        )
    }

    /** Messages waiting on a paused looper (PAUSED mode has no Scheduler API). */
    private fun pendingMessages(handler: android.os.Handler): Int {
        val queue = handler.looper.queue
        val head = queue.javaClass.getDeclaredField("mMessages").apply { isAccessible = true }
        val next = android.os.Message::class.java.getDeclaredField("next")
            .apply { isAccessible = true }
        var msg = head.get(queue) as? android.os.Message
        var count = 0
        while (msg != null) {
            count += 1
            msg = next.get(msg) as? android.os.Message
        }
        return count
    }

    private fun privateField(name: String): Any? =
        MonitorService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(service)

    @Test
    fun notificationSurfacesRejectedEvents() {
        // A parent may never open the app. The ongoing notification is the
        // only place a permanent server rejection can be seen in time.
        prefs.edit()
            .putInt(MonitorService.KEY_DROPPED_EVENTS, 4)
            .putInt(MonitorService.KEY_LAST_REJECTION, 400)
            .apply()
        start("childguard.START")

        val nm = RuntimeEnvironment.getApplication()
            .getSystemService(android.app.NotificationManager::class.java)
        val texts = org.robolectric.Shadows.shadowOf(nm).allNotifications.map {
            it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        }
        assertTrue(
            "notification must mention the refused events: $texts",
            texts.any { it.contains("4 event") && it.contains("rejected") }
        )
    }
}
