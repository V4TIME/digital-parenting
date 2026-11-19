package com.childguard.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Feature 3 — Notification listener.
 *
 * Captures notifications from chat apps only and flags potential stranger
 * contact. Non-chat packages (banking, 2FA, games, system) are ignored before
 * any text is read: a parenting tool must not harvest one-time passcodes or
 * payment alerts. Repeated identical posts from the same app inside a short
 * window are collapsed, because messaging apps re-post the same notification
 * when an avatar or timestamp changes.
 */
class ChildGuardNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ChildGuardNL"
        private const val MAX_QUEUE = 200
        /** Notification text is app-supplied and can be arbitrarily large
         *  (bigText especially). Bound it before it enters the queue, matching
         *  the accessibility path's 500-char cap. */
        private const val MAX_TEXT_LEN = 500

        /** How long an identical (package, text) notification is treated as a
         * re-post of the previous one. */
        private const val DEDUPE_WINDOW_MS = 30_000L
        private const val DEDUPE_MAX_KEYS = 256

        /** True while the system notification listener is bound. Feature 1 uses
         * this to skip its own notification fallback, preventing the same
         * notification from being counted twice. */
        var isListenerConnected = false
            internal set

        val instance: ChildGuardNotificationListener? = null

        val queue = ConcurrentLinkedQueue<JSONObject>()

        // Messaging apps only. Browsers, download providers, and generic
        // "messenger" package names used to be here, which pulled unrelated
        // notification text into the child's record. Screen text from a
        // browser is Feature 1's job, not the notification listener's.
        private val CHAT_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android",
            "com.snapchat.android", "com.discord", "com.telegram.messenger",
            "org.telegram.plus", "com.kik.chat", "com.skype.raider",
            "com.facebook.katana", "com.facebook.orca", "com.messenger",
            "com.linecorp.android", "com.viber.voip",
            "com.google.android.apps.messaging", "com.google.android.apps.messenger",
            "com.android.mms", "org.thoughtcrime.securesms"
        )

        // Same precision rule as Feature 1: a notification is only a stranger
        // signal when it is conversational targeting, not merely because it
        // mentions a parent, a platform, or a link. "Your mom said dinner at 7"
        // and "follow me on instagram" are ordinary and must stay quiet.
        private val STRANGER_HINTS = listOf(
            Regex("(?i)(new\\s+(friend|person|girl|boy|guy|account))"),
            Regex("(?i)(hey\\s+(there|buddy|kid|child|son|daughter|young))"),
            Regex("(?i)(don'?t\\s+tell\\s+(your|your\\s+parents?|mom|dad))"),
            Regex("(?i)(your\\s+(mom|dad|parents|mother|father)\\s+(don'?t|do\\s+not|doesn'?t|does\\s+not|didn'?t|can'?t|cannot|shouldn'?t|mustn'?t|won'?t)\\s+know)"),
            Regex("(?i)(what'?s\\s+your\\s+(age|school|address|phone|name))"),
            Regex("(?i)(send\\s+(me|us)\\s+(a\\s+)?(photo|picture|nude|naked|pic))"),
            Regex("(?i)(meet\\s+up|come\\s+over|come\\s+to\\s+my\\s+place)"),
            Regex("(?i)(you'?re\\s+(so\\s+)?(cute|hot|pretty|beautiful))"),
            Regex("(?i)(add\\s+me|add\\s+you|friend\\s+me)"),
            Regex("(?i)(\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4})"),
            Regex("(?i)(\\d{10,})")
        )

        fun isStrangerText(text: String): Boolean {
            val t = text.trim()
            if (t.length < 3) return false
            return STRANGER_HINTS.any { it.containsMatchIn(t) }
        }

        /** Exact package or a sub-package, never a prefix spoof such as
         *  `com.whatsapp_clone`. */
        fun isChatApp(pkg: String): Boolean =
            CHAT_PACKAGES.any { pkg.equals(it, true) || pkg.startsWith("$it.", ignoreCase = true) }

        /** Single source of truth for the event type of a notification, so a
         * stranger hit is always delivered as an alertable type. */
        fun classify(combined: String): String =
            if (isStrangerText(combined)) "stranger_notification" else "notification"
    }

    // Instance-scoped so a rebuilt listener starts with a clean window and
    // tests cannot leak dedupe state into each other.
    private val recentlyPosted = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun isDuplicate(pkg: String, combined: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "$pkg\u0000$combined"
        val previous = recentlyPosted.put(key, now)
        if (recentlyPosted.size > DEDUPE_MAX_KEYS) {
            recentlyPosted.entries.removeIf { now - it.value > DEDUPE_WINDOW_MS }
            if (recentlyPosted.size > DEDUPE_MAX_KEYS) recentlyPosted.clear()
        }
        return previous != null && now - previous < DEDUPE_WINDOW_MS
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (!MonitorService.isMonitoring) return

        val pkg = sbn.packageName ?: return
        // Privacy boundary: stop before reading title/text for anything that
        // is not a known chat app.
        if (!isChatApp(pkg)) return

        val extras = sbn.notification?.extras ?: return
        val title = (extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() ?: "")
            .take(MAX_TEXT_LEN)
        val text = (extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() ?: "")
            .take(MAX_TEXT_LEN)
        val bigText = (extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString() ?: "")
            .take(MAX_TEXT_LEN)

        val combined = listOf(title, text, bigText)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .take(MAX_TEXT_LEN)
        if (combined.length < 3) return
        if (isDuplicate(pkg, combined)) return

        val obj = JSONObject().apply {
            put("type", "notification")
            put("package", pkg)
            put("title", title)
            put("text", text)
            put("combined", combined)
            put("ts", System.currentTimeMillis())
            put("is_chat_app", true)
            put("chat", true)
        }

        if (isStrangerText(combined)) {
            obj.put("stranger_alert", true)
            obj.put("data", combined)
            // Emit a distinct type so the collector raises an alert;
            // otherwise the flag would sit invisible in the event stream.
            obj.put("type", classify(combined))
        }

        while (queue.size >= MAX_QUEUE) queue.poll()
        queue.add(obj)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
    }
}