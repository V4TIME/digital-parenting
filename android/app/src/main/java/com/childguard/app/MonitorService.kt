package com.childguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Core monitoring service.
 *
 * Runs as a low-importance foreground service so it survives modern Android
 * background limits (Android 12+ aggressively culls background services;
 * Android 15 targets must declare a foregroundServiceType — "specialUse").
 *
 * Also owns the upload pipeline (Feature 6): every N seconds it drains the
 * event queues from the accessibility service and the notification listener,
 * merges them, and POSTs them to the parent server using the configured URL
 * and token. This is what actually gets data onto the Termux dashboard.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "childguard_monitor"
        private const val NOTIF_ID = 1
        private const val UPLOAD_INTERVAL_MS = 15_000L
        private const val MAX_BATCH = 250

        var isMonitoring = false
            private set
        var isKeylogging = false
            private set
        var isNotifications = false
            private set

        val eventQueue = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_QUEUE = 500

        fun start() {
            isMonitoring = true
            isKeylogging = true
            isNotifications = true
        }

        fun stop() {
            isMonitoring = false
            isKeylogging = false
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

    private var uploadThread: HandlerThread? = null
    private var uploadHandler: Handler? = null
    private val uploadRunnable = object : Runnable {
        override fun run() {
            try {
                uploadQueuedEvents()
            } catch (t: Throwable) {
                Log.w(TAG, "upload pass failed", t)
            }
            uploadHandler?.postDelayed(this, UPLOAD_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "childguard.START" -> {
                start()
                startAsForeground()
                startUploadLoop()
            }
            "childguard.STOP" -> {
                stop()
                stopUploadLoop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ChildGuard Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps child protection monitoring active"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ChildGuard is protecting")
                .setContentText("Monitoring chats & notifications")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ChildGuard is protecting")
                .setContentText("Monitoring chats & notifications")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        }

        startForeground(NOTIF_ID, notification)
    }

    private fun startUploadLoop() {
        if (uploadThread != null) return
        uploadThread = HandlerThread("childguard-upload").also { it.start() }
        uploadHandler = Handler(uploadThread!!.looper)
        uploadHandler?.postDelayed(uploadRunnable, UPLOAD_INTERVAL_MS)
    }

    private fun stopUploadLoop() {
        uploadHandler?.removeCallbacks(uploadRunnable)
        uploadHandler = null
        uploadThread?.quitSafely()
        uploadThread = null
    }

    private fun uploadQueuedEvents() {
        val merged = ArrayList<JSONObject>()

        // drain accessibility service queue
        ChildGuardAccessibilityService.eventQueue.poll()?.let { merged.add(it) }
        while (ChildGuardAccessibilityService.eventQueue.isNotEmpty() && merged.size < MAX_BATCH) {
            ChildGuardAccessibilityService.eventQueue.poll()?.let { merged.add(it) }
        }
        // drain notification listener queue
        while (ChildGuardNotificationListener.queue.isNotEmpty() && merged.size < MAX_BATCH) {
            ChildGuardNotificationListener.queue.poll()?.let { obj ->
                if (!obj.has("data")) {
                    obj.put("data", obj.optString("combined"))
                }
                merged.add(obj)
            }
        }
        // drain own queue
        merged.addAll(drain())

        if (merged.isEmpty()) return

        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val serverUrl = sp.getString(ChildGuardApp.KEY_SERVER_URL, ChildGuardApp.DEFAULT_SERVER) ?: return
        val token = sp.getString(ChildGuardApp.KEY_PARENT_TOKEN, "") ?: ""
        var childId = sp.getString("child_id", "")
        if (childId.isNullOrEmpty()) {
            childId = UUID.randomUUID().toString()
            sp.edit().putString("child_id", childId).apply()
        }

        val body = JSONObject().apply {
            put("childId", childId)
            put("events", JSONArray(merged))
        }

        try {
            val conn = URL("$serverUrl/api/events").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            (conn.outputStream as OutputStream).write(body.toString().toByteArray(Charsets.UTF_8))
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "upload rejected code=$code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload error: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MonitorService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
        stopUploadLoop()
        Log.i(TAG, "MonitorService destroyed")
    }
}
