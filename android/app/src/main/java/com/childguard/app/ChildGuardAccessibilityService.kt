package com.childguard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

class ChildGuardAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ChildGuardAS"
        var instance: ChildGuardAccessibilityService? = null
        var isServiceRunning = false
            private set

        val eventQueue = ConcurrentLinkedQueue<JSONObject>()
        private const val MAX_QUEUE = 200

        private val STRANGER_PATTERNS = listOf(
            Regex("(?i)(hey\\s+(there|buddy|kid|child|son|daughter|young))"),
            Regex("(?i)(you\\s+are\\s+(so\\s+)?(cute|young|handsome))"),
            Regex("(?i)(don'?t\\s+tell\\s+(your|your\\s+parents?|mom|dad))"),
            Regex("(?i)(what'?s\\s+your\\s+(age|name|school|address|phone))"),
            Regex("(?i)(send\\s+(me|us)\\s+(a\\s+)?(photo|picture|nude|naked|pic))"),
            Regex("(?i)(meet\\s+(up|in\\s+person))"),
            Regex("(?i)(your\\s+(mom|dad|parents)\\s+(don'?t|do\\s+not)\\s+know)"),
            Regex("(?i)(whatsapp|telegram|discord|snapchat|signal|kik|skype|tiktok|instagram)"),
            Regex("(?i)(add\\s+me|add\\s+you|follow\\s+me|friend\\s+me)"),
            Regex("(?i)(\\b\\d{3,}\\s*[-.]?\\s*\\d{3,}\\s*[-.]?\\s*\\d{4,}\\b)"),
            Regex("(?i)(@\\w{2,30})"),
            Regex("(?i)(http[s]?://)"),
        )

        fun isStrangerText(text: String): Boolean {
            val t = text.trim()
            if (t.length < 3) return false
            return STRANGER_PATTERNS.any { it.containsMatchIn(t) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_WINDOW_CONTENT
        }
        accessibilityServiceInfo = info
        Log.i(TAG, "Accessibility service connected")
        enqueueEvent("service_connected", "Accessibility service started", "")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!MonitorService.isMonitoring) return

        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val texts = event.text?.joinToString(" ") ?: ""
                if (texts.isNotEmpty()) {
                    enqueueEvent("notification", pkg, texts)
                    if (isStrangerText(texts)) {
                        enqueueEvent("stranger_alert", pkg, texts)
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                val text = node?.text?.toString() ?: ""
                if (text.isNotEmpty() && text.length > 2) {
                    enqueueEvent("keystroke", pkg, text)
                    if (isStrangerText(text)) {
                        enqueueEvent("stranger_alert", pkg, text)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val texts = collectTextFromRoot()
                if (texts.isNotEmpty()) {
                    val joined = texts.joinToString(" ").take(500)
                    enqueueEvent("screen_text", pkg, joined)
                    if (isStrangerText(joined)) {
                        enqueueEvent("stranger_alert", pkg, joined)
                    }
                }
            }
        }
    }

    private fun collectTextFromRoot(): List<String> {
        val texts = ArrayList<String>()
        val root = rootInActiveWindow ?: return texts
        collectNodeText(root, texts, 0)
        root recycle()
        return texts
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, texts: ArrayList<String>, depth: Int) {
        if (depth > 12) return
        node.text?.toString()?.trim()?.let { if (it.length > 1) texts.add(it) }
        node.contentDescription?.toString()?.trim()?.let { if (it.length > 1) texts.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodeText(it, texts, depth + 1) }
        }
    }

    private fun enqueueEvent(type: String, pkg: String, data: String) {
        val obj = JSONObject().apply {
            put("type", type)
            put("package", pkg)
            put("data", data)
            put("ts", System.currentTimeMillis())
        }
        while (eventQueue.size > MAX_QUEUE) eventQueue.poll()
        eventQueue.add(obj)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        isServiceRunning = false
    }
}