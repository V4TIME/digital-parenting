package com.childguard.app

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Feature 1 unit tests (plain JVM — no device, emulator, or Android stubs).
 *
 * The extraction code operates on [NodeLike]; these tests drive it with a fake
 * node tree, proving real chat layouts are handled and that recycled/missing
 * children can never hang or crash the service.
 */
class Feature1ExtractionTest {

    /** In-memory stand-in for a view node. */
    private class FakeNode(
        var nodeText: String? = null,
        var desc: String? = null
    ) : NodeLike {
        val children = ArrayList<NodeLike?>()
        override val text: CharSequence? get() = nodeText
        override val contentDescription: CharSequence? get() = desc
        override var parent: NodeLike? = null
        override val childCount: Int get() = children.size
        override fun child(i: Int): NodeLike? = if (i in children.indices) children[i] else null
        override fun recycle() {}
    }

    @Test
    fun buttonClickRecoversChildEditTextText() {
        val edit = FakeNode("hello cutie can i ask ur age")
        val button = FakeNode()
        button.children.add(edit)
        assertEquals(
            "hello cutie can i ask ur age",
            ChildGuardAccessibilityService.extractTextNear(button)
        )
    }

    @Test
    fun buttonClickWalksUpToSiblingEditText() {
        // Real chat layout: row -> [EditText, SendButton]; only the button is
        // clicked, so the ancestor walk must recover the sibling's text.
        val edit = FakeNode("meet me after school")
        val button = FakeNode()
        val row = FakeNode()
        row.children.add(edit)
        row.children.add(button)
        button.parent = row
        assertEquals("meet me after school", ChildGuardAccessibilityService.extractTextNear(button))
    }

    @Test
    fun nodeWithoutTextReturnsEmpty() {
        assertEquals("", ChildGuardAccessibilityService.extractTextNear(FakeNode()))
    }

    @Test
    fun contentDescriptionIsUsedWhenTextMissing() {
        val node = FakeNode(desc = "Send message")
        assertEquals("Send message", ChildGuardAccessibilityService.extractTextNear(node))
    }

    @Test
    fun extractionDoesNotLoopOnNullChildren() {
        val root = FakeNode()
        for (i in 0 until 1000) root.children.add(null)
        val t0 = System.currentTimeMillis()
        val found = ChildGuardAccessibilityService.extractTextNear(root)
        val dt = System.currentTimeMillis() - t0
        assertEquals("", found)
        assertTrue("extraction must be fast even with many null children", dt < 5000)
    }

    @Test
    fun strangerPatternMatches() {
        assertTrue(ChildGuardAccessibilityService.isStrangerText("hey kid what's your name"))
        assertTrue(ChildGuardAccessibilityService.isStrangerText("don't tell your parents"))
        assertTrue(ChildGuardAccessibilityService.isStrangerText("123-456-7890"))
        assertTrue(ChildGuardAccessibilityService.isStrangerText("send me a photo"))
    }

    @Test
    fun strangerPatternDoesNotMatchNormalText() {
        assertTrue(!ChildGuardAccessibilityService.isStrangerText("hi mom how are you"))
        assertTrue(!ChildGuardAccessibilityService.isStrangerText("ok"))
        assertTrue(!ChildGuardAccessibilityService.isStrangerText("the quick brown fox"))
    }

    /**
     * Documents (does not endorse) a known accuracy problem: see AUDIT.md F-12.
     *
     * The pattern list treats any platform name, any @mention, and any URL as
     * a HIGH "stranger" signal, so ordinary messages raise alerts. This test
     * pins the current behaviour so the finding stays measurable; it should be
     * replaced with negative assertions when the patterns are tightened.
     */
    @Test
    fun documentsFalsePositiveProneAlertPatterns() {
        assertTrue(
            "a platform name alone is alerted",
            ChildGuardAccessibilityService.isStrangerText("we should switch to telegram")
        )
        assertTrue(
            "social app mention alone is alerted",
            ChildGuardAccessibilityService.isStrangerText("follow me on instagram")
        )
        assertTrue(
            "an email @mention alone is alerted",
            ChildGuardAccessibilityService.isStrangerText("my email is kid@example.com")
        )
        assertTrue(
            "a plain URL alone is alerted",
            ChildGuardAccessibilityService.isStrangerText("look at https://example.com/homework")
        )
    }

    @Test
    fun strangerNotificationIsDeliveredAsAlertableType() {
        assertEquals(
            "stranger_notification",
            ChildGuardNotificationListener.classify("New friend: hey kid what's your age")
        )
        assertEquals(
            "notification",
            ChildGuardNotificationListener.classify("Mom: dinner at 7")
        )
        assertTrue(ChildGuardNotificationListener.isChatApp("com.whatsapp"))
        assertTrue(!ChildGuardNotificationListener.isChatApp("com.android.settings"))
    }

    @Test
    fun failedUploadKeepsEventsForRetry() {
        // Simulates a dropped connection: drained events are requeued, not lost.
        MonitorService.requeue(listOf(
            org.json.JSONObject().put("type", "keylog").put("package", "com.whatsapp")
                .put("data", "hello").put("ts", 1L),
            org.json.JSONObject().put("type", "stranger_alert").put("package", "com.whatsapp")
                .put("data", "dont tell your parents").put("ts", 2L),
        ))
        val retried = MonitorService.drainRetry()
        assertEquals(2, retried.size)
        assertEquals("hello", retried[0].getString("data"))
        assertEquals("stranger_alert", retried[1].getString("type"))
        assertEquals(0, MonitorService.drainRetry().size)
    }
}
