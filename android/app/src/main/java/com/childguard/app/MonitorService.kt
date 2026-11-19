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
        private const val MIN_BATCH = 25
        internal const val MAX_BACKOFF_MS = 300_000L
        private const val KEY_DROPPED_EVENTS = "dropped_events_total"

        /**
         * Statuses where resending the same body cannot succeed. 400/422 are
         * handled by splitting the batch first, so one malformed event cannot
         * discard its healthy neighbours; 415 means the server will never
         * accept this content type.
         */
        internal fun isPermanentFailure(code: Int): Boolean =
            code == 400 || code == 415 || code == 422

        /**
         * Exponential backoff for transient failures: 30 s, 60 s, 120 s,
         * 240 s, then capped at 5 min. A dead tailnet must not make the phone
         * wake and retry every 15 s forever.
         */
        internal fun backoffDelayMs(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return UPLOAD_INTERVAL_MS
            val shift = consecutiveFailures.coerceAtMost(5)
            return (UPLOAD_INTERVAL_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        }

        /** Normalize a parent-typed URL and require an http(s) endpoint.
         *
         * `URL("192.168.1.5:38080")` throws "unknown protocol", and the
         * monitor swallowed that on every pass, so an address entered without
         * a scheme produced no uploads and no visible error. Fill in http://
         * when the scheme is missing and reject anything not http(s). */
        internal fun normalizeHttpUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val url = try {
                java.net.URL(withScheme)
            } catch (e: Exception) {
                return null
            }
            val scheme = url.protocol?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (url.host.isNullOrBlank()) return null
            return withScheme.trimEnd('/')
        }

        var isMonitoring = false
            private set
        var isKeylogging = false
            private set
        var isNotifications = false
            private set

        val eventQueue = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_QUEUE = 500

        // Events that were drained for upload but could not be delivered.
        // Server-side idempotency makes re-sending safe, so a dropped
        // connection (mobile data / Tailscale blip) never loses evidence.
        private val retryQueue = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_RETRY = 1000

        fun drainRetry(limit: Int = Int.MAX_VALUE): List<JSONObject> {
            if (limit <= 0) return emptyList()
            val out = ArrayList<JSONObject>(minOf(limit, 64))
            while (out.size < limit) {
                val e = retryQueue.poll() ?: break
                out.add(e)
            }
            return out
        }

        /**
         * Builds one bounded upload batch: retries first (so evidence is never
         * starved), then the accessibility queue, then notifications, then this
         * service's own queue. Leftovers stay queued for the next cycle, so a
         * slow/flaky link still makes progress in small chunks instead of
         * re-sending a huge body forever.
         */
        internal fun takeUploadBatch(limit: Int = MAX_BATCH): List<JSONObject> {
            if (limit <= 0) return emptyList()
            val merged = ArrayList<JSONObject>(minOf(limit, 64))
            merged.addAll(drainRetry(limit))

            while (merged.size < limit) {
                val obj = ChildGuardAccessibilityService.eventQueue.poll() ?: break
                merged.add(obj)
            }
            while (merged.size < limit) {
                val obj = ChildGuardNotificationListener.queue.poll() ?: break
                if (!obj.has("data")) {
                    obj.put("data", obj.optString("combined"))
                }
                merged.add(obj)
            }
            while (merged.size < limit) {
                val obj = eventQueue.poll() ?: break
                merged.add(obj)
            }
            return merged
        }

        fun requeue(items: List<JSONObject>) {
            for (obj in items) {
                while (retryQueue.size >= MAX_RETRY) retryQueue.poll()
                retryQueue.add(obj)
            }
        }

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
            while (eventQueue.size >= MAX_QUEUE) eventQueue.poll()
            eventQueue.add(obj)
        }

        fun drain(): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            while (true) {
                val e = eventQueue.poll() ?: break
                out.add(e)
            }
            return out
        }
    }

    private var uploadThread: HandlerThread? = null
    private var uploadHandler: Handler? = null

    // Only touched from the single upload HandlerThread.
    private var consecutiveFailures = 0
    private var batchLimit = MAX_BATCH

    private val uploadRunnable = object : Runnable {
        override fun run() {
            val delay = try {
                uploadQueuedEvents()
            } catch (t: Throwable) {
                Log.w(TAG, "upload pass failed", t)
                backoffDelayMs(++consecutiveFailures)
            }
            uploadHandler?.postDelayed(this, delay)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "childguard.START" -> {
                start()
                startAsForeground()
                startUploadLoop()
                return START_STICKY
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
                // The parent explicitly stopped monitoring. Do not ask the
                // system to resurrect the service later.
                return START_NOT_STICKY
            }
            else -> {
                // A null intent means Android recreated the process after
                // killing it. The old code returned START_STICKY without
                // restoring any state, so the service came back "running"
                // but with isMonitoring=false and no foreground notification:
                // monitoring silently stopped while the UI still said ON.
                // Resume only if the parent still wants it.
                val enabled = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
                    .getBoolean(ChildGuardApp.KEY_MONITORING, false)
                if (!enabled) {
                    stop()
                    stopSelf()
                    return START_NOT_STICKY
                }
                start()
                startAsForeground()
                startUploadLoop()
                return START_STICKY
            }
        }
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

    /**
     * One upload pass. Returns how long to wait before the next pass, so a
     * transient outage backs off instead of hammering the collector.
     */
    private fun uploadQueuedEvents(): Long {
        // One bounded batch per cycle. Retries first, leftovers remain queued.
        val merged = takeUploadBatch(batchLimit)
        if (merged.isEmpty()) return UPLOAD_INTERVAL_MS

        val childId = ensureRegistered() ?: run {
            requeue(merged)
            return nextFailureDelay()
        }

        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val serverUrl = normalizeHttpUrl(
            sp.getString(ChildGuardApp.KEY_SERVER_URL, ChildGuardApp.DEFAULT_SERVER) ?: ""
        ) ?: run {
            Log.w(TAG, "invalid server URL configured; keeping events for retry")
            requeue(merged)
            return nextFailureDelay()
        }
        val token = sp.getString(ChildGuardApp.KEY_PARENT_TOKEN, "") ?: ""

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

            if (code in 200..299) {
                consecutiveFailures = 0
                // Recover batch size after an earlier 413.
                if (batchLimit < MAX_BATCH) batchLimit = minOf(MAX_BATCH, batchLimit * 2)
                return UPLOAD_INTERVAL_MS
            }

            if (code == 413) {
                // Body too large: shrink and resend soon. Evidence is kept.
                if (merged.size > MIN_BATCH) {
                    batchLimit = maxOf(MIN_BATCH, merged.size / 2)
                    requeue(merged)
                    consecutiveFailures = 0
                    Log.w(TAG, "upload 413; shrinking batch to $batchLimit")
                    return UPLOAD_INTERVAL_MS
                }
                Log.w(TAG, "upload 413 even at minimum batch; keeping events")
                requeue(merged)
                return nextFailureDelay()
            }

            if (code == 400 || code == 422) {
                // Isolate the poison event: halve until one event remains,
                // so good events on either side still reach the collector.
                if (merged.size > 1) {
                    val half = merged.size / 2
                    requeue(merged.subList(0, half))
                    requeue(merged.subList(half, merged.size))
                    consecutiveFailures = 0
                    Log.w(TAG, "upload $code; splitting ${merged.size} events into halves")
                    return UPLOAD_INTERVAL_MS
                }
                recordDropped(1, code)
                consecutiveFailures = 0
                return UPLOAD_INTERVAL_MS
            }

            if (isPermanentFailure(code)) {
                recordDropped(merged.size, code)
                consecutiveFailures = 0
                return UPLOAD_INTERVAL_MS
            }

            // 401/403/404/408/429/5xx and anything unknown: the same payload
            // may succeed once the token, URL, or server recovers.
            Log.w(TAG, "upload failed code=$code; retrying with backoff")
            requeue(merged)
            return nextFailureDelay()
        } catch (e: Exception) {
            Log.w(TAG, "upload error: ${e.message}; retrying with backoff")
            requeue(merged)
            return nextFailureDelay()
        }
    }

    private fun nextFailureDelay(): Long {
        consecutiveFailures += 1
        return backoffDelayMs(consecutiveFailures)
    }

    private fun recordDropped(count: Int, code: Int) {
        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val total = sp.getInt(KEY_DROPPED_EVENTS, 0) + count
        sp.edit().putInt(KEY_DROPPED_EVENTS, total).apply()
        Log.w(TAG, "server rejected $count event(s) with $code; dropped, total=$total")
    }

    /**
     * Ensure this device is registered on the parent server.
     * Runs once; if the server is unreachable it returns null and the next
     * upload cycle retries. Returns the persisted child id when registered.
     */
    private fun ensureRegistered(): String? {
        val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
        val existing = sp.getString("child_id", "")
        if (!existing.isNullOrEmpty()) return existing

        val serverUrl = normalizeHttpUrl(
            sp.getString(ChildGuardApp.KEY_SERVER_URL, ChildGuardApp.DEFAULT_SERVER) ?: ""
        ) ?: return null
        val token = sp.getString(ChildGuardApp.KEY_PARENT_TOKEN, "") ?: ""
        val name = sp.getString(ChildGuardApp.KEY_CHILD_NAME, "Child") ?: "Child"

        val body = JSONObject().put("name", name)
        try {
            val conn = URL("$serverUrl/api/register").openConnection() as HttpURLConnection
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
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val childId = JSONObject(text).optString("childId")
                if (childId.isNotEmpty()) {
                    sp.edit().putString("child_id", childId).apply()
                    return childId
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "registration error: ${e.message}")
        }
        return null
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
