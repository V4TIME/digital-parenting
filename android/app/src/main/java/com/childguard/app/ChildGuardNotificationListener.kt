package com.childguard.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.support.v4.app.NotificationCompat
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

class ChildGuardNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "ChildGuardNL"
        var instance: ChildGuardNotificationListener? = null

        val queue = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_QUEUE = 200

        private val CHAT_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android",
            "com.snapchat.android", "com.discord", "com.telegram.messenger",
            "com.kik.chat", "com.skype.raider", "com.pinterest",
            "com.facebook.katana", "com.facebook.orca", "com.messenger",
            "com.linecorp.android", "com.viber VoIP", "com.viber VoIP",
            "com.google.android.apps.messenger", "com.android.mms"
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
            Regex("(?i)(\\b\\d{3,}\\s*[-.]?\\s*\\d{3,}\\s*[-.]?\\s*\\d{4,}\\b)")
        )

        fun isStrangerText(text: String): Boolean {
            val t = text.trim()
            if (t.length < 3) return false
            return STRANGER_HINTS.any { it.containsMatchIn(t) }
        }

        fun isChatApp(pkg: String): Boolean = CHAT_PACKAGES.any { pkg.equals(it, true) || pkg.startsWith(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (!MonitorService.isMonitoring) return

        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(NotificationCompat.title)?.toString() ?: ""
        val text = extras.getCharSequence(NotificationCompat.text)?.toString() ?: ""
        val bigText = extras.getCharSequence(NotificationCompat.bigText)?.toString() ?: ""

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
                enqueueEvent("stranger_notification", pkg, combined)
            }
        }

        while (queue.size > MAX_QUEUE) queue.poll()
        queue.add(obj)
    }

    private fun enqueueEvent(type: String, pkg: String, data: String) {
        val obj = JSONObject().apply {
            put("type", type)
            put("package", pkg)
            put("data", data)
            put("ts", System.currentTimeMillis())
        }
        while (queue.size > MAX_QUEUE) queue.poll()
        queue.add(obj)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }
}