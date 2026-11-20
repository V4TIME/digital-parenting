package com.childguard.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.junit.After
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * First-run correctness: the dashboard's "Monitoring: ON" label and the
 * service's return mode must match what is actually running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardActivityTest {

    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        MonitorService.stop()
        app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences(ChildGuardApp.PREFS, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        MonitorService.stop()
    }

    @Test
    fun monitoringOnStartsTheForegroundService() {
        // SetupActivity finishes into DashboardActivity. Before this fix the
        // label said ON (the preference default) but nothing was collecting.
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, true).apply()
        Robolectric.buildActivity(DashboardActivity::class.java).create()

        val started = shadowOf(app).nextStartedService
        assertNotNull("Dashboard must start monitoring when the label says ON", started)
        assertEquals(MonitorService::class.java.name, started!!.component?.className)
        assertEquals("childguard.START", started.action)
    }

    @Test
    fun monitoringOffDoesNotStartTheService() {
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, false).apply()
        Robolectric.buildActivity(DashboardActivity::class.java).create()

        assertNull("a stopped monitor must not be restarted", shadowOf(app).nextStartedService)
    }

    @Test
    fun rejectedEventsAreVisibleOnTheDashboard() {
        // Permanent 400/415/422 rejections dropped the evidence while the
        // screen still said "Monitoring: ON". The count was written but no
        // screen ever read it.
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, false).apply()
        MonitorService.noteDroppedEvents(prefs, 3, 400)

        val controller = Robolectric.buildActivity(DashboardActivity::class.java)
            .create().start().resume()
        val activity = controller.get()
        val tv = activity.findViewById<android.widget.TextView>(R.id.tv_dropped)

        assertEquals(android.view.View.VISIBLE, tv.visibility)
        assertTrue("must name the loss count: ${tv.text}", tv.text.contains("3 events"))
        assertTrue("must name the server answer: ${tv.text}", tv.text.contains("HTTP 400"))

        // Another rejection while the screen is already open must show up on
        // the next resume, not only after an app restart.
        MonitorService.noteDroppedEvents(prefs, 2, 422)
        controller.pause().resume()
        assertTrue(
            "dashboard must re-read the counter: ${tv.text}",
            tv.text.contains("5 events") && tv.text.contains("HTTP 422")
        )
    }

    @Test
    fun noWarningWhenNothingWasRejected() {
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, false).apply()
        prefs.edit()
            .putInt(MonitorService.KEY_DROPPED_EVENTS, 0)
            .putInt(MonitorService.KEY_LAST_REJECTION, 0)
            .apply()

        val activity = Robolectric.buildActivity(DashboardActivity::class.java)
            .create().start().resume().get()
        val tv = activity.findViewById<android.widget.TextView>(R.id.tv_dropped)

        assertEquals(android.view.View.GONE, tv.visibility)
        assertNull(MonitorService.droppedWarning(prefs))
        assertEquals(
            "Monitoring chats & notifications",
            MonitorService.notificationText(prefs)
        )
    }

    @Test
    fun currentUploadProblemIsVisibleAndClearsWhenFixed() {
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, false).apply()
        MonitorService.noteUploadError(prefs, "server answered HTTP 401", 1_700_000_000_000L)

        val controller = Robolectric.buildActivity(DashboardActivity::class.java)
            .create().start().resume()
        val tv = controller.get().findViewById<android.widget.TextView>(R.id.tv_upload)
        assertEquals(android.view.View.VISIBLE, tv.visibility)
        assertTrue("must name the problem: ${tv.text}", tv.text.contains("HTTP 401"))
        assertTrue("must say it keeps trying: ${tv.text}", tv.text.contains("Retrying"))

        // The next successful upload clears it; the dashboard must not keep
        // warning about an outage that is over.
        MonitorService.clearUploadError(prefs)
        controller.pause().resume()
        assertEquals(android.view.View.GONE, tv.visibility)
        assertNull(MonitorService.uploadWarning(prefs))
    }

    @Test
    fun localQueueOverflowIsReportedAsLocalNotAsAServerError() {
        prefs.edit().putBoolean(ChildGuardApp.KEY_MONITORING, false).apply()
        MonitorService.noteDroppedEvents(prefs, 2, 0)

        val activity = Robolectric.buildActivity(DashboardActivity::class.java)
            .create().start().resume().get()
        val tv = activity.findViewById<android.widget.TextView>(R.id.tv_dropped)

        assertEquals(android.view.View.VISIBLE, tv.visibility)
        assertTrue("must name the count: ${tv.text}", tv.text.contains("2 events"))
        assertTrue(
            "must explain the local cause: ${tv.text}",
            tv.text.contains("lost locally")
        )
        assertFalse(
            "a full queue is not a server rejection: ${tv.text}",
            tv.text.contains("rejected by the server")
        )
    }

    @Test
    fun droppedCounterAccumulatesAndReadsNaturally() {
        MonitorService.noteDroppedEvents(prefs, 1, 422)
        assertEquals("1 event was rejected by the server (HTTP 422). Check the server URL and token in Setup.",
            MonitorService.droppedWarning(prefs))
        MonitorService.noteDroppedEvents(prefs, 2, 400)
        assertEquals("3 events were rejected by the server (HTTP 400). Check the server URL and token in Setup.",
            MonitorService.droppedWarning(prefs))
        assertEquals(
            "3 event(s) rejected by server — open the dashboard",
            MonitorService.notificationText(prefs)
        )
    }
}
