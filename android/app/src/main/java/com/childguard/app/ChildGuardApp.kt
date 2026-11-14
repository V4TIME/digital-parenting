package com.childguard.app

import android.app.Application
import android.util.Log

class ChildGuardApp : Application() {
    companion object {
        const val TAG = "ChildGuard"
        const val PREFS = "childguard_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_CHILD_NAME = "child_name"
        const val KEY_PARENT_TOKEN = "parent_token"
        const val KEY_MONITORING = "monitoring_enabled"
        const val KEY_KEYLOGGER = "keylogger_enabled"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_TRAFFIC = "traffic_enabled"
        const val KEY_SCREEN = "screen_enabled"

        const val DEFAULT_SERVER = "http://192.168.1.1:38080"
    }

    override fun onCreate() {
        super.onCreate()
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!sp.contains(KEY_SERVER_URL)) {
            sp.edit().putString(KEY_SERVER_URL, DEFAULT_SERVER).apply()
        }
        if (!sp.contains(KEY_MONITORING)) {
            sp.edit()
                .putBoolean(KEY_MONITORING, true)
                .putBoolean(KEY_KEYLOGGER, true)
                .putBoolean(KEY_NOTIFICATIONS, true)
                .putBoolean(KEY_TRAFFIC, false)
                .putBoolean(KEY_SCREEN, false)
                .apply()
        }
        Log.i(TAG, "ChildGuardApp initialized")
    }
}