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
}
