package com.childguard.app

import android.content.Context
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Setup must not save an endpoint that can never work. The monitor used to
 * swallow the MalformedURLException on every pass, so a parent who typed
 * "192.168.1.5:38080" saw no uploads and no explanation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupActivityTest {

    private fun launch(): SetupActivity =
        Robolectric.buildActivity(SetupActivity::class.java).setup().get()

    private fun prefs(activity: SetupActivity) =
        activity.getSharedPreferences(ChildGuardApp.PREFS, Context.MODE_PRIVATE)

    @Test
    fun missingSchemeIsAddedBeforeSaving() {
        val activity = launch()
        activity.findViewById<EditText>(R.id.edit_server_url).setText("192.168.1.5:38080")
        activity.findViewById<Button>(R.id.btn_finish).performClick()

        assertEquals(
            "http://192.168.1.5:38080",
            prefs(activity).getString(ChildGuardApp.KEY_SERVER_URL, null)
        )
    }

    @Test
    fun nonHttpUrlIsRejectedAndNotSaved() {
        val activity = launch()
        val before = prefs(activity).getString(ChildGuardApp.KEY_SERVER_URL, null)
        val edit = activity.findViewById<EditText>(R.id.edit_server_url)
        edit.setText("ftp://evil.example")

        activity.findViewById<Button>(R.id.btn_finish).performClick()

        assertNotNull("the field should show an inline error", edit.error)
        assertEquals(
            "the rejected URL must not replace the saved one",
            before,
            prefs(activity).getString(ChildGuardApp.KEY_SERVER_URL, null)
        )
        assertNotEquals("ftp://evil.example", prefs(activity).getString(ChildGuardApp.KEY_SERVER_URL, null))
    }

    @Test
    fun changingTheEndpointClearsTheStaleLossCounter() {
        val activity = launch()
        prefs(activity).edit()
            .putString(ChildGuardApp.KEY_SERVER_URL, "http://old.example:38080")
            .putInt(MonitorService.KEY_DROPPED_EVENTS, 5)
            .putInt(MonitorService.KEY_LAST_REJECTION, 400)
            .apply()
        activity.findViewById<EditText>(R.id.edit_server_url)
            .setText("http://new.example:38080")

        activity.findViewById<Button>(R.id.btn_finish).performClick()

        assertEquals(
            "a new endpoint deserves a clean slate",
            0,
            prefs(activity).getInt(MonitorService.KEY_DROPPED_EVENTS, 0)
        )
    }

    @Test
    fun resavingTheSameEndpointKeepsTheLossCounter() {
        val activity = launch()
        // Seed the prefs with exactly what the fields already show, so this
        // is a genuine re-save rather than a first-time configuration.
        prefs(activity).edit()
            .putString(
                ChildGuardApp.KEY_SERVER_URL,
                activity.findViewById<EditText>(R.id.edit_server_url).text.toString()
            )
            .putString(
                ChildGuardApp.KEY_PARENT_TOKEN,
                activity.findViewById<EditText>(R.id.edit_token).text.toString()
            )
            .putInt(MonitorService.KEY_DROPPED_EVENTS, 5)
            .putInt(MonitorService.KEY_LAST_REJECTION, 400)
            .apply()

        activity.findViewById<Button>(R.id.btn_finish).performClick()

        assertEquals(
            "re-saving an unchanged endpoint must not hide real losses",
            5,
            prefs(activity).getInt(MonitorService.KEY_DROPPED_EVENTS, 0)
        )
    }
}
