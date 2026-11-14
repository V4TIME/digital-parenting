package com.childguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class UploadReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "UploadReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Boot completed — starting monitor")
                val i = Intent(context, MonitorService::class.java)
                i.action = "childguard.START"
                context.startService(i)
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                val i = Intent(context, MonitorService::class.java)
                i.action = "childguard.START"
                context.startService(i)
            }
        }
    }
}