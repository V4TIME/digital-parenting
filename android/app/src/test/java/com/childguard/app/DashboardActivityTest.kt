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
}
