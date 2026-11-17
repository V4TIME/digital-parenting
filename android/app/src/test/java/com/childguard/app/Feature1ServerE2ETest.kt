package com.childguard.app

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-stack proof of Feature 1: app -> server.
 *
 * Runs the REAL accessibility service on Android 14 (Robolectric API 34), feeds
 * it real AccessibilityEvents, then runs the REAL MonitorService upload path
 * against a live ChildGuard server over HTTP. Passing this means the events a
 * parent sees in the dashboard are exactly the ones the app captured.
 *
 * Requires a running server; skipped otherwise:
 *   CHILDGUARD_SERVER=http://127.0.0.1:38080 CHILDGUARD_TOKEN=testsecret \
 *     gradle :app:testDebugUnitTest --tests "*Feature1ServerE2ETest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Feature1ServerE2ETest {

    private val server: String? =
        System.getProperty("childguard.server") ?: System.getenv("CHILDGUARD_SERVER")
    private val token: String =
        System.getProperty("childguard.token") ?: System.getenv("CHILDGUARD_TOKEN") ?: ""

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences(ChildGuardApp.PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        assumeTrue("no server configured; skipping E2E", !server.isNullOrBlank())
        prefs().edit()
            .putString(ChildGuardApp.KEY_SERVER_URL, server)
            .putString(ChildGuardApp.KEY_PARENT_TOKEN, token)
            .putString(ChildGuardApp.KEY_CHILD_NAME, "Runtime E2E Kid")
            .putString("child_id", "") // register against this server
            .apply()

        // Global queues are shared state: start each test clean.
        MonitorService.start()
        ChildGuardAccessibilityService.eventQueue.clear()
        MonitorService.drainRetry()

        // The service dedupes repeated text/alerts/screens (correct at runtime);
        // reset that state so each test observes fresh capture.
        listOf("lastKeyByPkg", "lastAlertByKey", "lastScreenByPkg",
               "lastScreenTextByPkg", "lastActivePkg", "lastActiveTs")
            .forEach { clearStatic(it) }
    }

    private fun clearStatic(name: String) {
        val classes = listOf(
            ChildGuardAccessibilityService::class.java,
            Class.forName("com.childguard.app.ChildGuardAccessibilityService\$Companion")
        )
        for (cls in classes) {
            val f = try {
                cls.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                continue
            }
            f.isAccessible = true
            when (val v = f.get(null)) {
                is MutableMap<*, *> -> v.clear()
                is Long -> f.setLong(null, 0L)
                is String -> f.set(null, "")
            }
            return
        }
    }

    private fun textEvent(pkg: String, text: String): AccessibilityEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply {
            packageName = pkg
            className = "$pkg.ConversationActivity"
            this.text.add(text)
        }

    /** Runs the real service exactly as Android would deliver events. */
    private fun captureEvents() {
        val a11y = Robolectric
            .buildService(ChildGuardAccessibilityService::class.java)
            .create().get()

        // Plain conversation: captured, no alert.
        a11y.onAccessibilityEvent(textEvent("com.whatsapp", "hi mom how was school"))
        // Grooming phrase ("meet up ..."): captured AND alerted.
        a11y.onAccessibilityEvent(textEvent("com.whatsapp", "hey kid, meet up after school alone"))
        // App switch: drives screen time.
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.android.chrome"
            className = "org.chromium.chrome.browser.ChromeTabbedActivity"
            a11y.onAccessibilityEvent(this)
        }
        // Harmless chat: must be captured, must not alert.
        a11y.onAccessibilityEvent(textEvent("com.telegram.messenger", "mom dinner at 7"))
    }

    private fun types(): List<String> =
        ChildGuardAccessibilityService.eventQueue.map { it.optString("type") }

    /** Creates the service the way the app does and runs its upload pass. */
    private fun uploadLikeTheApp() {
        val monitor = Robolectric.buildService(MonitorService::class.java)
            .create()
            .withIntent(Intent("childguard.START"))
            .get()
        val m = MonitorService::class.java.getDeclaredMethod("uploadQueuedEvents")
        m.isAccessible = true
        m.invoke(monitor)
    }

    private fun totalEvents(): Int {
        val conn = URL("$server/api/stats").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(body).getInt("totalEvents")
    }

    @Test
    fun everyCapturedEventReachesTheServer() {
        captureEvents()

        val queued = types()
        assertTrue("typing must be captured", queued.contains("keylog"))
        assertTrue("grooming phrase must alert", queued.contains("stranger_alert"))
        assertTrue("app switch must be captured", queued.contains("screen_active"))

        val before = totalEvents()
        uploadLikeTheApp()

        assertEquals("upload must drain the queue", 0, ChildGuardAccessibilityService.eventQueue.size)
        assertEquals("nothing may be left for retry", 0, MonitorService.drainRetry().size)
        assertEquals(
            "every captured event must be stored by the server",
            before + queued.size,
            totalEvents()
        )
    }

    @Test
    fun harmlessChatsAreCapturedButNeverAlert() {
        captureEvents()
        val queued = ChildGuardAccessibilityService.eventQueue.toList()

        val harmless = queued.firstOrNull {
            it.optString("package") == "com.telegram.messenger"
        }
        assertNotNull("harmless chat must still be captured", harmless)
        assertEquals(
            "harmless chat must not raise an alert",
            0,
            queued.count { it.optString("type") == "stranger_alert" && it.optString("package") == "com.telegram.messenger" }
        )
    }

    @Test
    fun failedUploadKeepsEventsInsteadOfDroppingThem() {
        captureEvents()
        val before = types().size

        // Dead port: the real uploader fails; events must survive.
        prefs().edit().putString(ChildGuardApp.KEY_SERVER_URL, "http://127.0.0.1:1").apply()
        uploadLikeTheApp()

        val retried = MonitorService.drainRetry()
        assertEquals("all events must survive a failed upload", before, retried.size)
        assertTrue(
            "retried events keep their payload",
            retried.any { it.optString("type") == "stranger_alert" }
        )
        prefs().edit().putString(ChildGuardApp.KEY_SERVER_URL, server).apply()
    }

    @Test
    fun retriesAreIdempotent() {
        val stamp = System.currentTimeMillis()
        val batch = listOf(
            JSONObject().put("type", "keylog").put("package", "com.whatsapp")
                .put("data", "retry me").put("ts", stamp),
            JSONObject().put("type", "screen_active").put("package", "com.android.chrome")
                .put("data", "org.chromium.chrome.browser.ChromeTabbedActivity").put("ts", stamp)
        )

        val before = totalEvents()
        ChildGuardAccessibilityService.eventQueue.addAll(batch)
        uploadLikeTheApp()
        val afterFirst = totalEvents()
        assertEquals("first upload stores the batch", before + batch.size, afterFirst)

        // The app's retry path resends the identical bytes.
        ChildGuardAccessibilityService.eventQueue.addAll(batch)
        uploadLikeTheApp()
        assertEquals(
            "a resent batch must not inflate the server's counts",
            afterFirst,
            totalEvents()
        )
        assertEquals("queue drained", 0, ChildGuardAccessibilityService.eventQueue.size)
    }
}
