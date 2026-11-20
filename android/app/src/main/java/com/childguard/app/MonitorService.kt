package com.childguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
        internal const val KEY_DROPPED_EVENTS = "dropped_events_total"
        internal const val KEY_LAST_REJECTION = "last_rejection_code"

        /**
         * Count a permanent rejection so it is visible to the parent.
         *
         * The server returns 400/415/422 when it will never accept a body.
         * Those events are gone once the batch is split down to one, but the
         * count used to be written and never read: monitoring still showed
         * "ON" while captured evidence disappeared silently.
         */
        internal fun noteDroppedEvents(sp: SharedPreferences, count: Int, code: Int): Int {
            val total = sp.getInt(KEY_DROPPED_EVENTS, 0).coerceAtLeast(0) +
                count.coerceAtLeast(0)
            sp.edit()
                .putInt(KEY_DROPPED_EVENTS, total)
                .putInt(KEY_LAST_REJECTION, code)
                .apply()
            return total
        }

        /** Parent-facing warning, or null when nothing has been lost. */
        internal fun droppedWarning(sp: SharedPreferences): String? {
            val total = sp.getInt(KEY_DROPPED_EVENTS, 0)
            if (total <= 0) return null
            val code = sp.getInt(KEY_LAST_REJECTION, 0)
            val http = if (code > 0) " (HTTP $code)" else ""
            val what = if (total == 1) "1 event was" else "$total events were"
            return "$what rejected by the server$http. Check the server URL and token in Setup."
        }

        internal const val KEY_UPLOAD_ERROR = "last_upload_error"
        internal const val KEY_UPLOAD_ERROR_AT = "last_upload_error_at"

        /** Remember the current upload problem (auth, DNS, timeout, …). */
        internal fun noteUploadError(sp: SharedPreferences, message: String, at: Long) {
            sp.edit()
                .putString(KEY_UPLOAD_ERROR, message)
                .putLong(KEY_UPLOAD_ERROR_AT, at)
                .apply()
        }

        /** The problem is over (any server answer that is not a config error). */
        internal fun clearUploadError(sp: SharedPreferences) {
            if (!sp.contains(KEY_UPLOAD_ERROR) && !sp.contains(KEY_UPLOAD_ERROR_AT)) return
            sp.edit()
                .remove(KEY_UPLOAD_ERROR)
                .remove(KEY_UPLOAD_ERROR_AT)
                .apply()
        }

        /** Parent-facing line for the current upload problem, or null. */
        internal fun uploadWarning(sp: SharedPreferences): String? {
            val message = sp.getString(KEY_UPLOAD_ERROR, "").orEmpty()
            if (message.isEmpty()) return null
            val at = sp.getLong(KEY_UPLOAD_ERROR_AT, 0L)
            val since = if (at > 0) {
                val clock = java.text.SimpleDateFormat(
                    "HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date(at))
                " since $clock"
            } else {
                ""
            }
            return "Uploads failing$since — $message. Retrying automatically."
        }

        /** Foreground-notification text; includes the loss count when known. */
        internal fun notificationText(sp: SharedPreferences): String {
            val total = sp.getInt(KEY_DROPPED_EVENTS, 0)
            if (total <= 0) return "Monitoring chats & notifications"
            return "$total event(s) rejected by server — open the dashboard"
        }

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

        /** A stable id generated before the first registration attempt.
         *
         * The server echoes it back, so a retry after a lost response updates
         * the same child instead of creating a duplicate. */
        internal fun newChildId(): String =
            "cg_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)

        /** Read at most `limit` bytes of a response body.
         *
         * A proxy or misconfigured server can answer 200 with an arbitrarily
         * large page; the previous readText() buffered all of it into the
         * app's heap. The register payload is tiny, so a few KiB is plenty. */
        internal fun readAtMost(input: java.io.InputStream, limit: Int): String {
            val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
            val chunk = ByteArray(4096)
            while (out.size() < limit) {
                val n = input.read(chunk, 0, minOf(chunk.size, limit - out.size()))
                if (n < 0) break
                out.write(chunk, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
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
    private var uploadRunnable: Runnable? = null
    @Volatile private var uploadGeneration = 0

    // Only touched from the single upload HandlerThread.
    private var consecutiveFailures = 0
    private var batchLimit = MAX_BATCH

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

        val notification = buildNotification(
            notificationText(getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE))
        )

        startForeground(NOTIF_ID, notification)
    }

    /** One notification shape for start and for loss updates. */
    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ChildGuard is protecting")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ChildGuard is protecting")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        }
    }

    private fun startUploadLoop() {
        if (uploadThread != null) return
        // Every loop gets a generation. A pass that was still running when the
        // service was stopped and restarted must not schedule itself onto the
        // new handler, or each restart adds another self-perpetuating chain
        // and the phone uploads at 2x, 3x, ... the intended rate.
        val generation = ++uploadGeneration
        uploadThread = HandlerThread("childguard-upload").also { it.start() }
        val handler = Handler(uploadThread!!.looper)
        uploadHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                if (generation != uploadGeneration) return
                val delay = try {
                    uploadQueuedEvents()
                } catch (t: Throwable) {
                    Log.w(TAG, "upload pass failed", t)
                    backoffDelayMs(++consecutiveFailures)
                }
                if (generation == uploadGeneration) handler.postDelayed(this, delay)
            }
        }
        uploadRunnable = runnable
        handler.postDelayed(runnable, UPLOAD_INTERVAL_MS)
    }

    private fun stopUploadLoop() {
        // Invalidate the current pass before removing the queued one.
        uploadGeneration += 1
        val handler = uploadHandler
        val runnable = uploadRunnable
        if (handler != null && runnable != null) handler.removeCallbacks(runnable)
        uploadHandler = null
        uploadRunnable = null
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
            noteUploadError(sp, "invalid server URL in Setup", System.currentTimeMillis())
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
                clearUploadError(sp)
                // Recover batch size after an earlier 413.
                if (batchLimit < MAX_BATCH) batchLimit = minOf(MAX_BATCH, batchLimit * 2)
                return UPLOAD_INTERVAL_MS
            }

            if (code == 413) {
                // Body too large: shrink and resend soon. Evidence is kept.
                // The server is reachable, so the current outage marker (if
                // any) is stale.
                clearUploadError(sp)
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
                clearUploadError(sp)
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
                clearUploadError(sp)
                recordDropped(merged.size, code)
                consecutiveFailures = 0
                return UPLOAD_INTERVAL_MS
            }

            // 401/403/404/408/429/5xx and anything unknown: the same payload
            // may succeed once the token, URL, or server recovers.
            Log.w(TAG, "upload failed code=$code; retrying with backoff")
            noteUploadError(sp, "server answered HTTP $code", System.currentTimeMillis())
            requeue(merged)
            return nextFailureDelay()
        } catch (e: Exception) {
            Log.w(TAG, "upload error: ${e.message}; retrying with backoff")
            noteUploadError(sp, "cannot reach the server (${e.javaClass.simpleName})",
                System.currentTimeMillis())
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
        val total = noteDroppedEvents(sp, count, code)
        Log.w(TAG, "server rejected $count event(s) with $code; dropped, total=$total")
        // Make the loss visible even if the parent never opens the app.
        refreshNotification()
    }

    /** Re-post the ongoing notification with the current loss count. */
    private fun refreshNotification() {
        // A pass can still be in flight when the parent stops monitoring;
        // do not resurrect the notification after stopForeground().
        if (!isMonitoring) return
        // The upload thread owns this call; a notification failure must never
        // be mistaken for an upload failure and trigger a retry/backoff.
        runCatching {
            val sp = getSharedPreferences(ChildGuardApp.PREFS, MODE_PRIVATE)
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(notificationText(sp)))
        }.onFailure { Log.w(TAG, "could not update loss notification: ${it.message}") }
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

        // Persist the id before the POST. If the response is lost, the retry
        // sends the same id and the server updates that child instead of
        // minting another one on every attempt.
        val pending = sp.getString(ChildGuardApp.KEY_PENDING_CHILD_ID, "").orEmpty()
        val requestedId = pending.ifEmpty { newChildId() }
        if (pending.isEmpty()) {
            sp.edit().putString(ChildGuardApp.KEY_PENDING_CHILD_ID, requestedId).apply()
        }

        val body = JSONObject().put("name", name).put("childId", requestedId)
        var conn: HttpURLConnection? = null
        try {
            val opened = URL("$serverUrl/api/register").openConnection() as HttpURLConnection
            conn = opened
            opened.requestMethod = "POST"
            opened.connectTimeout = 10_000
            opened.readTimeout = 10_000
            opened.doOutput = true
            opened.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) {
                opened.setRequestProperty("Authorization", "Bearer $token")
            }
            (opened.outputStream as OutputStream).write(body.toString().toByteArray(Charsets.UTF_8))
            val code = opened.responseCode
            if (code != 200) {
                // The parent sees "Monitoring: ON" with no uploads; at least
                // adb logcat and bug reports now name the reason.
                Log.w(TAG, "registration rejected with $code; events stay queued")
                noteUploadError(sp, "registration rejected (HTTP $code) — check the token",
                    System.currentTimeMillis())
                return null
            }
            val text = readAtMost(opened.inputStream, 8 * 1024)
            val childId = runCatching { JSONObject(text).optString("childId") }
                .getOrDefault("")
            if (childId.isEmpty()) {
                Log.w(TAG, "registration returned no childId; will retry")
                return null
            }
            sp.edit()
                .putString("child_id", childId)
                .remove(ChildGuardApp.KEY_PENDING_CHILD_ID)
                .apply()
            return childId
        } catch (e: Exception) {
            Log.w(TAG, "registration error: ${e.message}")
            noteUploadError(sp, "cannot reach the server to register (${e.javaClass.simpleName})",
                System.currentTimeMillis())
        } finally {
            conn?.disconnect()
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
