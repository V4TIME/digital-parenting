package com.childguard.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

class MonitorService : Service() {
    companion object {
        private const val TAG = "MonitorService"
        var isMonitoring = false
            private set
        var isKeylogging = false
            private set
        var isTraffic = false
            private set
        var isNotifications = false
            private set

        val eventQueue = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_QUEUE = 500

        fun start() {
            isMonitoring = true
            isKeylogging = true
            isTraffic = true
            isNotifications = true
        }

        fun stop() {
            isMonitoring = false
            isKeylogging = false
            isTraffic = false
            isNotifications = false
        }

        fun enqueue(type: String, pkg: String, data: String) {
            val obj = JSONObject().apply {
                put("type", type)
                put("package", pkg)
                put("data", data)
                put("ts", System.currentTimeMillis())
            }
            while (eventQueue.size > MAX_QUEUE) eventQueue.poll()
            eventQueue.add(obj)
        }

        fun drain(): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            var e: JSONObject?
            while (eventQueue.poll().also { e = it } != null) {
                out.add(e)
            }
            return out
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "childguard.START" -> start()
            "childguard.STOP" -> stop()
        }
        return START_STICKY
    }

    override fun IBinder(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MonitorService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
        Log.i(TAG, "MonitorService destroyed")
    }
}