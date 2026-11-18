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
        var desc: String? = null,
        password: Boolean = false
    ) : NodeLike {
        val children = ArrayList<NodeLike?>()
        override val text: CharSequence? get() = nodeText
        override val contentDescription: CharSequence? get() = desc
        override val isPassword: Boolean = password
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
    fun passwordFieldsAreNeverExtracted() {
        val pin = FakeNode("1234", password = true)
        val button = FakeNode()
        button.children.add(pin)
        assertEquals("", ChildGuardAccessibilityService.extractTextNear(button))

        val out = StringBuilder()
        ChildGuardAccessibilityService.collectNodeText(pin, out, 0, 6, 128)
        assertEquals("password text must never enter a screen dump", "", out.toString())
    }

    @Test
    fun ancestorWalkSkipsSiblingPasswordField() {
        val pin = FakeNode("hunter2", password = true)
        val button = FakeNode()
        val row = FakeNode()
        row.children.add(pin)
        row.children.add(button)
        button.parent = row

        assertEquals("", ChildGuardAccessibilityService.extractTextNear(button))
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
     * Ordinary chat mentions an app, an @handle or a link all the time. Those
     * must be captured as normal text and must NOT raise a HIGH alert: alert
     * fatigue is how a real grooming attempt gets ignored (AUDIT.md F-12).
     */
    @Test
    fun ordinaryMentionsAndLinksDoNotAlert() {
        assertTrue(
            "a platform name alone must not alert",
            !ChildGuardAccessibilityService.isStrangerText("we should switch to telegram")
        )
        assertTrue(
            "social app mention alone must not alert",
            !ChildGuardAccessibilityService.isStrangerText("follow me on instagram")
        )
        assertTrue(
            "an email @mention alone must not alert",
            !ChildGuardAccessibilityService.isStrangerText("my email is kid@example.com")
        )
        assertTrue(
            "a plain URL alone must not alert",
            !ChildGuardAccessibilityService.isStrangerText("look at https://example.com/homework")
        )
    }

    @Test
    fun realGroomingSignalsStillAlert() {
        assertTrue(ChildGuardAccessibilityService.isStrangerText("hey kid, meet up after school alone"))
        assertTrue(ChildGuardAccessibilityService.isStrangerText("don't tell your mom about this"))
        assertTrue(ChildGuardAccessibilityService.isStrangerText("your dad doesn't know about this"))
        assertTrue(ChildGuardAccessibilityService.isStrangerText("what's your school"))
        assertTrue(ChildGuardAccessibilityService.isStrangerText("call me on 123-456-7890"))
    }

    @Test
    fun hugeViewTreeIsTraversedWithinABudget() {
        // Lazy tree: every node claims 60 children, each of which claims 60 more.
        // Without a node budget this is 60^6 nodes of binder traffic.
        var childCalls = 0
        fun lazyNode(level: Int): NodeLike = object : NodeLike {
            override val text: CharSequence? get() = null
            override val contentDescription: CharSequence? get() = null
            override val parent: NodeLike? get() = null
            override val childCount: Int get() = 60
            override fun child(i: Int): NodeLike {
                childCalls++
                return lazyNode(level + 1)
            }
            override fun recycle() {}
        }

        val out = StringBuilder()
        val started = System.nanoTime()
        ChildGuardAccessibilityService.collectNodeText(
            lazyNode(0), out, 0, 6, 128
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("traversal must stay bounded, saw $childCalls child() calls", childCalls < 1000)
        assertTrue("traversal must be fast, took ${elapsedMs}ms", elapsedMs < 2000)
    }

    @Test
    fun identicalScreenDumpsAreNotReuploaded() {
        val pkg = "test.screen.${System.nanoTime()}"
        assertTrue(ChildGuardAccessibilityService.shouldEmitScreenText(pkg, "same feed"))
        assertTrue(
            "the identical dump must be suppressed",
            !ChildGuardAccessibilityService.shouldEmitScreenText(pkg, "same feed")
        )
        assertTrue(
            "changed text must be uploaded",
            ChildGuardAccessibilityService.shouldEmitScreenText(pkg, "same feed + new message")
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
