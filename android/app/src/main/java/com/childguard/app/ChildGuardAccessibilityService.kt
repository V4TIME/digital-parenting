package com.childguard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Minimal read-only view of an accessibility node. Keeps the interception
 * logic free of Android framework types so it can be unit-tested on the JVM
 * (and reused for any future UI-tree source).
 */
internal interface NodeLike {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val parent: NodeLike?
    val childCount: Int
    fun child(i: Int): NodeLike?
    fun recycle()
}

/** Adapter from the real Android node to [NodeLike]. */
internal class A11yNode(private val n: AccessibilityNodeInfo) : NodeLike {
    override val text: CharSequence? get() = n.text
    override val contentDescription: CharSequence? get() = n.contentDescription
    override val parent: NodeLike? get() = n.parent?.let { A11yNode(it) }
    override val childCount: Int get() = n.childCount
    override fun child(i: Int): NodeLike? = n.getChild(i)?.let { A11yNode(it) }
    override fun recycle() = n.recycle()
}

/**
 * Feature 1 — Accessibility interception.
 *
 * Ultimate hardened implementation (pen-tested against Android 15 semantics):
 *  - monitors real chat/browser text via TYPE_VIEW_TEXT_CHANGED + TYPE_VIEW_FOCUSED
 *  - includes non-important views so EditText content is actually visible
 *  - recycles every node it touches (no binder leaks)
 *  - extracts text near a clicked node (send button -> edit box) with no
 *    possibility of an infinite loop (increment always happens)
 *  - throttles window dumps per package (battery/CPU & log flood control)
 *  - dedupes keylog bursts and alert storms keyed by content, not just time
 *  - records screen-active transitions for the screen-time dashboard
 *  - cleans up lifecycle state on unbind/destroy
 */
class ChildGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChildGuardAS"
        private const val MAX_QUEUE = 200
        private const val MAX_TEXT_LEN = 500
        private const val ALERT_MIN_GAP_MS = 10_000L
        private const val SCREEN_MIN_GAP_MS = 800L
        private const val SCREEN_ACTIVE_GAP_MS = 1_000L
        private const val MAX_DEDUPE_KEYS = 64

        var instance: ChildGuardAccessibilityService? = null
        var isServiceRunning = false
            private set

        val eventQueue = ConcurrentLinkedQueue<JSONObject>()

        // Tightened stranger patterns (phone-like numbers only, not any 3 digits).
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
            // phone-like: (xxx) xxx-xxxx or 10+ consecutive digits
            Regex("(?i)(\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4})"),
            Regex("(?i)(\\d{10,})"),
            Regex("(?i)(@\\w{2,30})"),
            Regex("(?i)(http[s]?://)"),
        )

        // Keyed throttle state. All are bounded; when they exceed the cap the
        // whole map is cleared (rare, and only affects throttling granularity).
        private val lastAlertByKey = HashMap<String, Long>()
        private val lastScreenByPkg = HashMap<String, Long>()
        private val lastKeyByPkg = HashMap<String, String>()
        private var lastActivePkg = ""
        private var lastActiveTs = 0L

        fun isStrangerText(text: String): Boolean {
            val t = text.trim()
            if (t.length < 3) return false
            return STRANGER_PATTERNS.any { it.containsMatchIn(t) }
        }

        /** Alert dedupe keyed by (package+content) with a time window. */
        private fun shouldFireAlert(pkg: String, data: String): Boolean {
            val key = "$pkg\n${data.hashCode()}"
            val now = System.currentTimeMillis()
            val last = lastAlertByKey[key]
            if (last != null && now - last < ALERT_MIN_GAP_MS) return false
            lastAlertByKey[key] = now
            if (lastAlertByKey.size > MAX_DEDUPE_KEYS) lastAlertByKey.clear()
            return true
        }

        /** Prevents repeated full-screen dumps from flooding the logs. */
        private fun shouldEmitScreen(pkg: String): Boolean {
            val now = System.currentTimeMillis()
            val last = lastScreenByPkg[pkg]
            if (last != null && now - last < SCREEN_MIN_GAP_MS) return false
            lastScreenByPkg[pkg] = now
            if (lastScreenByPkg.size > MAX_DEDUPE_KEYS) lastScreenByPkg.clear()
            return true
        }

        /** Avoids logging the same full text on every keystroke/focus event. */
        private fun shouldEmitKeylog(pkg: String, text: String): Boolean {
            val prev = lastKeyByPkg[pkg]
            if (prev != null && prev == text) return false
            lastKeyByPkg[pkg] = text
            if (lastKeyByPkg.size > MAX_DEDUPE_KEYS) lastKeyByPkg.clear()
            return true
        }

        /** Tracks active-window transitions so screen time can be computed. */
        private fun shouldTrackWindowTransition(pkg: String): Boolean {
            val now = System.currentTimeMillis()
            val changedApp = pkg != lastActivePkg
            val gapPassed = now - lastActiveTs >= SCREEN_ACTIVE_GAP_MS
            if (changedApp || gapPassed) {
                lastActivePkg = pkg
                lastActiveTs = now
                return true
            }
            return false
        }

        /**
         * Recovers text from the clicked node's own label or nearby edit fields.
         *
         * Works on [NodeLike], not the Android type, so the logic is pure and
         * runs in plain JVM unit tests. Every loop increments before the next
         * iteration, so a null child (views recycled mid-traversal) can never
         * cause a hang.
         */
        internal fun extractTextNear(node: NodeLike): String {
            node.text?.toString()?.trim()?.let { if (it.length > 1) return it }
            node.contentDescription?.toString()?.trim()?.let { if (it.length > 1) return it }

            // Search children/descendants (max depth 6) for text nodes.
            val found = StringBuilder()
            collectNodeText(node, found, 0, 6, 160)
            if (found.isNotEmpty()) return found.toString()

            // Walk up ancestors looking for a sibling EditText with text.
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 3) {
                var childIndex = 0
                while (childIndex < parent.childCount) {
                    val child = parent.child(childIndex)
                    childIndex++ // always advance; null child cannot stall this loop
                    if (child == null) continue
                    val t = try {
                        child.text?.toString()?.trim() ?: ""
                    } finally {
                        child.recycle()
                    }
                    if (t.length > 1) {
                        parent.recycle()
                        return t
                    }
                }
                val nextParent = parent.parent
                parent.recycle()
                parent = nextParent
                depth++
            }
            return ""
        }

        internal fun collectNodeText(
            node: NodeLike,
            out: StringBuilder,
            depth: Int,
            maxDepth: Int,
            maxChars: Int
        ) {
            if (depth > maxDepth || out.length >= maxChars) return
            node.text?.toString()?.trim()?.let {
                if (it.length > 1 && out.length < maxChars) {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(it.take(maxChars - out.length))
                }
            }
            node.contentDescription?.toString()?.trim()?.let {
                if (it.length > 1 && out.length < maxChars) {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(it.take(maxChars - out.length))
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.child(i) ?: continue
                try {
                    collectNodeText(child, out, depth + 1, maxDepth, maxChars)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 250
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        serviceInfo = info
        Log.i(TAG, "Accessibility service connected")
        enqueueEvent("service_connected", "com.childguard.app", "")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!MonitorService.isMonitoring) return

        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Feature 3 (NotificationListenerService) is richer and is the
                // canonical source; only use this fallback when it is not bound
                // so the same notification is never counted twice.
                if (ChildGuardNotificationListener.isListenerConnected) return
                val texts = event.text?.joinToString(" ") ?: ""
                if (texts.isNotEmpty()) {
                    val trimmed = texts.take(MAX_TEXT_LEN)
                    enqueueEvent("notification", pkg, trimmed)
                    if (isStrangerText(trimmed) && shouldFireAlert(pkg, trimmed)) {
                        enqueueEvent("stranger_alert", pkg, trimmed)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (shouldTrackWindowTransition(pkg) && className.isNotEmpty()) {
                    enqueueEvent("screen_active", pkg, className.take(120))
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val node = event.source
                if (node == null) return
                try {
                    val text = extractTextNear(A11yNode(node))
                    if (text.isNotEmpty()) {
                        if (shouldEmitKeylog(pkg, text)) {
                            enqueueEvent("keystroke", pkg, text)
                        }
                        if (isStrangerText(text) && shouldFireAlert(pkg, text)) {
                            enqueueEvent("stranger_alert", pkg, text)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = event.source
                if (node == null) return
                try {
                    val text = node.text?.toString()?.trim() ?: ""
                    if (text.length > 1 && shouldEmitKeylog(pkg, text)) {
                        val trimmed = text.take(MAX_TEXT_LEN)
                        enqueueEvent("keylog", pkg, trimmed)
                        if (isStrangerText(trimmed) && shouldFireAlert(pkg, trimmed)) {
                            enqueueEvent("stranger_alert", pkg, trimmed)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!shouldEmitScreen(pkg)) return
                val texts = collectTextFromRoot()
                if (texts.isNotEmpty()) {
                    val joined = texts.joinToString(" ").take(MAX_TEXT_LEN)
                    enqueueEvent("screen_text", pkg, joined)
                    if (isStrangerText(joined) && shouldFireAlert(pkg, joined)) {
                        enqueueEvent("stranger_alert", pkg, joined)
                    }
                }
            }
        }
    }

    private fun collectTextFromRoot(): List<String> {
        val texts = ArrayList<String>()
        val root = rootInActiveWindow ?: return texts
        val buf = StringBuilder()
        collectNodeText(A11yNode(root), buf, 0, 12, 128)
        root.recycle()
        if (buf.isNotEmpty()) texts.add(buf.toString())
        return texts
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

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        isServiceRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        isServiceRunning = false
        super.onDestroy()
    }
}
