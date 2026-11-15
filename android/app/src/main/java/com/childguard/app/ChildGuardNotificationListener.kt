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
 * Captures every notification from chat apps and flags potential stranger
 * contact. Only notifications from known chat packages are inspected; all
 * others are ignored (keeps the listener light and battery-friendly).
 */
class ChildGuardNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ChildGuardNL"
        private const val MAX_QUEUE = 200

        val instance: ChildGuardNotificationListener? = null

        val queue = ConcurrentLinkedQueue<JSONObject>()

        private val CHAT_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android",
            "com.snapchat.android", "com.discord", "com.telegram.messenger",
            "com.kik.chat", "com.skype.raider", "com.facebook.katana",
            "com.facebook.orca", "com.messenger", "com.linecorp.android",
            "com.viber.voip", "com.google.android.apps.messenger",
            "com.android.mms", "com.android.providers.downloads",
            "com.android.browser", "com.android.chrome", "com.android.htmlviewer"
        )

        private val STRANGER_HINTS = listOf(
            Regex("(?i)(new\\s+(friend|person|girl|boy|guy))"),
            Regex("(?i)(don'?t\\s+tell\\s+(your|your\\s+parents?|mom|dad))"),
            Regex("(?i)(what'?s\\s+your\\s+(age|school|address|phone|name))"),
            Regex("(?i)(send\\s+(me|us)\\s+(a\\s+)?(photo|picture|pic))"),
            Regex("(?i)(meet\\s+up|come\\s+over|come\\s+to\\s+my\\s+place)"),
            Regex("(?i)(you'?re\\s+(so\\s+)?(cute|hot|pretty|nice|beautiful))"),
            Regex("(?i)(add\\s+me|add\\s+you|follow\\s+me|friend\\s+me)"),
            Regex("(?i)(your\\s+(mom|dad|parents|mother|father))"),
            Regex("(?i)(\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4})"),
            Regex("(?i)(\\d{10,})")
        )

        fun isStrangerText(text: String): Boolean {
            val t = text.trim()
            if (t.length < 3) return false
            return STRANGER_HINTS.any { it.containsMatchIn(t) }
        }

        fun isChatApp(pkg: String): Boolean =
            CHAT_PACKAGES.any { pkg.equals(it, true) || pkg.startsWith(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (!MonitorService.isMonitoring) return

        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString() ?: ""

        val combined = listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" | ")
        if (combined.length < 3) return

        val obj = JSONObject().apply {
            put("type", "notification")
            put("package", pkg)
            put("title", title)
            put("text", text)
            put("combined", combined)
            put("ts", System.currentTimeMillis())
            put("is_chat_app", isChatApp(pkg))
        }

        if (isChatApp(pkg)) {
            obj.put("chat", true)
            if (isStrangerText(combined)) {
                obj.put("stranger_alert", true)
                obj.put("data", combined)
            }
        } else {
            obj.put("chat", false)
        }

        while (queue.size > MAX_QUEUE) queue.poll()
        queue.add(obj)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
    }
}