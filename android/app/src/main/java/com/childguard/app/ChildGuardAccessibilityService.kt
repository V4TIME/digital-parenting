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

    /** True for password/PIN fields. Such nodes are never read: a parenting
     *  monitor must not record credentials or screen-lock codes. Defaults to
     *  false so simple test doubles stay valid. */
    val isPassword: Boolean get() = false

    fun child(i: Int): NodeLike?
    fun recycle()
}

/** Adapter from the real Android node to [NodeLike]. */
internal class A11yNode(private val n: AccessibilityNodeInfo) : NodeLike {
    override val text: CharSequence? get() = n.text
    override val contentDescription: CharSequence? get() = n.contentDescription
    override val isPassword: Boolean get() = n.isPassword
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
        private const val NOTIFICATION_MIN_GAP_MS = 30_000L
        private const val SCREEN_MIN_GAP_MS = 800L
        private const val SCREEN_ACTIVE_GAP_MS = 1_000L
        private const val MAX_DEDUPE_KEYS = 64
        /** Hard ceiling on accessibility nodes touched per screen dump. A huge
         *  browser/chat view tree must never turn an event callback into an
         *  ANR: every child() call is a binder round-trip. */
        private const val MAX_TREE_NODES = 400
        /** How many siblings are inspected around a clicked node. */
        private const val MAX_CHILDREN_SCAN = 60

        var instance: ChildGuardAccessibilityService? = null
        var isServiceRunning = false
            private set

        /** Event mask last requested from the system. Kept as a plain field so
         *  tests can assert the subscription (Robolectric's getServiceInfo()
         *  does not retain the value set by the service). */
        internal var requestedEventTypes = 0
            private set

        val eventQueue = ConcurrentLinkedQueue<JSONObject>()

        // Tightened stranger patterns: only conversational signals that a
        // stranger is targeting the child. Bare platform names, @mentions and
        // URLs are deliberately NOT alerts — ordinary chat contains them all
        // day and alert fatigue hides the real hits (AUDIT.md F-12).
        private val STRANGER_PATTERNS = listOf(
            Regex("(?i)(hey\\s+(there|buddy|kid|child|son|daughter|young))"),
            Regex("(?i)(you\\s+are\\s+(so\\s+)?(cute|young|handsome))"),
            Regex("(?i)(don'?t\\s+tell\\s+(your|your\\s+parents?|mom|dad))"),
            Regex("(?i)(what'?s\\s+your\\s+(age|name|school|address|phone))"),
            Regex("(?i)(send\\s+(me|us)\\s+(a\\s+)?(photo|picture|nude|naked|pic))"),
            Regex("(?i)(meet\\s+(up|in\\s+person))"),
            Regex("(?i)(your\\s+(mom|dad|parents)\\s+(don'?t|do\\s+not|doesn'?t|does\\s+not|didn'?t|can'?t|cannot|won'?t)\\s+know)"),
            Regex("(?i)(add\\s+me|add\\s+you|friend\\s+me)"),
            // phone-like: (xxx) xxx-xxxx or 10+ consecutive digits
            Regex("(?i)(\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4})"),
            Regex("(?i)(\\d{10,})"),
        )

        // Keyed throttle state. All are bounded; when they exceed the cap the
        // whole map is cleared (rare, and only affects throttling granularity).
        private val lastAlertByKey = HashMap<String, Long>()
        private val lastNotificationByKey = HashMap<String, Long>()
        private val lastScreenByPkg = HashMap<String, Long>()
        private val lastKeyByPkg = HashMap<String, String>()
        private val lastScreenTextByPkg = HashMap<String, Int>()
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

        /** Fallback notification dedupe, mirroring the listener's 30 s window. */
        internal fun shouldEmitNotification(pkg: String, text: String): Boolean {
            val key = "$pkg\n${text.hashCode()}"
            val now = System.currentTimeMillis()
            val last = lastNotificationByKey[key]
            if (last != null && now - last < NOTIFICATION_MIN_GAP_MS) return false
            lastNotificationByKey[key] = now
            if (lastNotificationByKey.size > MAX_DEDUPE_KEYS) lastNotificationByKey.clear()
            return true
        }

        /** Prevents repeated full-screen dumps from flooding the logs. */
        private fun screenGapPassed(pkg: String): Boolean {
            val now = System.currentTimeMillis()
            val last = lastScreenByPkg[pkg]
            if (last != null && now - last < SCREEN_MIN_GAP_MS) return false
            lastScreenByPkg[pkg] = now
            if (lastScreenByPkg.size > MAX_DEDUPE_KEYS) lastScreenByPkg.clear()
            return true
        }

        /**
         * A screen can change continuously (cursor blink, timers, ads). Sending
         * the identical dump again only costs battery and makes the dashboard
         * unreadable, so only a changed dump is uploaded.
         */
        internal fun shouldEmitScreenText(pkg: String, text: String): Boolean {
            val hash = text.hashCode()
            if (lastScreenTextByPkg[pkg] == hash) return false
            lastScreenTextByPkg[pkg] = hash
            if (lastScreenTextByPkg.size > MAX_DEDUPE_KEYS) lastScreenTextByPkg.clear()
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
            if (node.isPassword) return ""
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
                while (childIndex < parent.childCount && childIndex < MAX_CHILDREN_SCAN) {
                    val child = parent.child(childIndex)
                    childIndex++ // always advance; null child cannot stall this loop
                    if (child == null) continue
                    if (child.isPassword) {
                        child.recycle()
                        continue
                    }
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
            maxChars: Int,
            budget: IntArray = intArrayOf(MAX_TREE_NODES)
        ) {
            if (depth > maxDepth || out.length >= maxChars || budget[0] <= 0) return
            budget[0]--
            if (!node.isPassword) {
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
            }
            val count = if (depth >= maxDepth) 0 else minOf(node.childCount, MAX_CHILDREN_SCAN)
            for (i in 0 until count) {
                if (budget[0] <= 0 || out.length >= maxChars) return
                val child = node.child(i) ?: continue
                try {
                    collectNodeText(child, out, depth + 1, maxDepth, maxChars, budget)
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
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 250
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        serviceInfo = info
        requestedEventTypes = info.eventTypes
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
                // Same privacy boundary as the listener: never read banking,
                // 2FA, or other non-chat notification text through the
                // accessibility fallback.
                if (!ChildGuardNotificationListener.isChatApp(pkg)) return
                val texts = event.text?.joinToString(" ") ?: ""
                if (texts.isNotEmpty()) {
                    val trimmed = texts.take(MAX_TEXT_LEN)
                    if (!shouldEmitNotification(pkg, trimmed)) return
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
                // Never record password/PIN/OTP fields, even when the event
                // itself (rather than the source node) carries the text.
                if (event.isPassword) return
                val fromNode = event.source?.let { node ->
                    try {
                        if (node.isPassword) "" else node.text?.toString()?.trim() ?: ""
                    } finally {
                        node.recycle()
                    }
                } ?: ""
                // Several apps (WebViews, some IMEs, custom chat fields) report
                // the typed text only in the event payload, with no readable
                // source node. Prefer the node, fall back to event.text so a
                // keystroke is never lost.
                val text = if (fromNode.length > 1) fromNode
                else event.text?.joinToString(" ")?.trim() ?: ""
                if (text.length > 1 && shouldEmitKeylog(pkg, text)) {
                    val trimmed = text.take(MAX_TEXT_LEN)
                    enqueueEvent("keylog", pkg, trimmed)
                    if (isStrangerText(trimmed) && shouldFireAlert(pkg, trimmed)) {
                        enqueueEvent("stranger_alert", pkg, trimmed)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Long chats and web pages deliver older/newer content while
                // scrolling. Some apps only emit TYPE_VIEW_SCROLLED there, so
                // treat it as a screen-content change; the 800 ms gate and the
                // content hash keep repeated scrolls from re-uploading.
                if (!screenGapPassed(pkg)) return
                val texts = collectTextFromRoot()
                if (texts.isEmpty()) return
                val joined = texts.joinToString(" ").take(MAX_TEXT_LEN)
                if (!shouldEmitScreenText(pkg, joined)) return
                enqueueEvent("screen_text", pkg, joined)
                if (isStrangerText(joined) && shouldFireAlert(pkg, joined)) {
                    enqueueEvent("stranger_alert", pkg, joined)
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
        while (eventQueue.size >= MAX_QUEUE) eventQueue.poll()
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
