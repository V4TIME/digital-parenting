package com.childguard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Feature 1/3 upload pipeline: the app must make progress in bounded chunks on
 * a flaky link, never exceed the batch limit, and never lose a queued event.
 */
class UploadBatchingTest {

    @Before
    fun clearQueues() {
        MonitorService.drainRetry()
        MonitorService.drain()
        ChildGuardAccessibilityService.eventQueue.clear()
        ChildGuardNotificationListener.queue.clear()
    }

    private fun event(n: Int) = JSONObject()
        .put("type", "keylog")
        .put("package", "com.whatsapp")
        .put("data", "event-$n")
        .put("ts", n.toLong())

    @Test
    fun retryCapReportsWhatItEvicts() {
        // Pure accounting: requeue() returns how many events the cap forced
        // it to drop, so the service can count them (F-77).
        assertEquals("a batch below the cap loses nothing",
            0, MonitorService.requeue((1..1000).map { event(it) }))
        assertEquals("the 1001st event evicts one",
            1, MonitorService.requeue(listOf(event(1001))))
        assertEquals("a two-event overflow evicts two",
            2, MonitorService.requeue(listOf(event(1002), event(1003))))
    }

    @Test
    fun batchesAreBoundedAndLoseNothing() {
        val all = (1..600).map { event(it) }
        MonitorService.requeue(all.take(300))
        ChildGuardAccessibilityService.eventQueue.addAll(all.drop(300))

        val b1 = MonitorService.takeUploadBatch(250)
        val b2 = MonitorService.takeUploadBatch(250)
        val b3 = MonitorService.takeUploadBatch(250)
        val b4 = MonitorService.takeUploadBatch(250)

        assertEquals("first batch is capped", 250, b1.size)
        assertEquals("second batch is capped", 250, b2.size)
        assertEquals("remainder is sent next", 100, b3.size)
        assertTrue("nothing is left behind", b4.isEmpty())

        val sent = (b1 + b2 + b3).map { it.optString("data") }.toSet()
        assertEquals("every queued event is uploaded exactly once", 600, sent.size)
    }

    @Test
    fun retryDrainHonoursItsLimit() {
        MonitorService.requeue((1..5).map { event(it) })
        assertEquals(3, MonitorService.drainRetry(3).size)
        assertEquals(2, MonitorService.drainRetry(10).size)
        assertTrue(MonitorService.drainRetry(10).isEmpty())
    }

    @Test
    fun onlyPayloadAndMediaErrorsArePermanent() {
        // Retrying these can never help; the caller must split or drop.
        assertTrue(MonitorService.isPermanentFailure(400))
        assertTrue(MonitorService.isPermanentFailure(415))
        assertTrue(MonitorService.isPermanentFailure(422))

        // Auth, routing, throttling and server faults are recoverable.
        assertFalse(MonitorService.isPermanentFailure(401))
        assertFalse(MonitorService.isPermanentFailure(403))
        assertFalse(MonitorService.isPermanentFailure(404))
        assertFalse(MonitorService.isPermanentFailure(408))
        assertFalse(MonitorService.isPermanentFailure(429))
        assertFalse(MonitorService.isPermanentFailure(500))
        assertFalse(MonitorService.isPermanentFailure(503))
    }

    @Test
    fun transientFailuresBackOffAndCap() {
        assertEquals(15_000L, MonitorService.backoffDelayMs(0))
        assertEquals(30_000L, MonitorService.backoffDelayMs(1))
        assertEquals(60_000L, MonitorService.backoffDelayMs(2))
        assertEquals(120_000L, MonitorService.backoffDelayMs(3))
        assertEquals(240_000L, MonitorService.backoffDelayMs(4))
        assertEquals(MonitorService.MAX_BACKOFF_MS, MonitorService.backoffDelayMs(5))
        assertEquals(
            "backoff must stay capped for a long outage",
            MonitorService.MAX_BACKOFF_MS,
            MonitorService.backoffDelayMs(50)
        )
    }

    @Test
    fun serverUrlIsNormalizedAndMustBeHttp() {
        assertEquals(
            "http://100.64.0.2:38080",
            MonitorService.normalizeHttpUrl("  http://100.64.0.2:38080///  ")
        )
        assertEquals("https://cg.example", MonitorService.normalizeHttpUrl("https://cg.example/"))
        // A scheme is filled in: "host:port" is what parents actually type.
        assertEquals(
            "http://cg.example:38080",
            MonitorService.normalizeHttpUrl("cg.example:38080")
        )
        assertNull(MonitorService.normalizeHttpUrl("   "))
        assertNull(MonitorService.normalizeHttpUrl("ftp://cg.example"))
        assertNull(MonitorService.normalizeHttpUrl("http://"))
    }

    @Test
    fun registrationIdIsStablePerAttemptAndBounded() {
        val a = MonitorService.newChildId()
        val b = MonitorService.newChildId()
        assertTrue("ids must be prefixed", a.startsWith("cg_"))
        assertTrue("ids must fit the server cap", a.length <= 128)
        assertNotEquals("each fresh install gets its own id", a, b)
    }

    @Test
    fun registrationResponseReadIsBounded() {
        val big = ByteArray(200_000) { 'a'.code.toByte() }
        val text = MonitorService.readAtMost(java.io.ByteArrayInputStream(big), 8 * 1024)
        assertEquals("read must stop at the cap", 8 * 1024, text.length)

        val small = "{\"childId\":\"cg_x\"}".toByteArray()
        assertEquals(
            "{\"childId\":\"cg_x\"}",
            MonitorService.readAtMost(java.io.ByteArrayInputStream(small), 8 * 1024)
        )
    }

    @Test
    fun notificationQueueEventsGetTheDashboardDataField() {
        ChildGuardNotificationListener.queue.add(
            JSONObject()
                .put("type", "notification")
                .put("package", "com.whatsapp")
                .put("combined", "Mom: dinner at 7")
                .put("ts", 1L)
        )

        val batch = MonitorService.takeUploadBatch(10)
        assertEquals(1, batch.size)
        assertEquals("Mom: dinner at 7", batch[0].optString("data"))
    }
}
