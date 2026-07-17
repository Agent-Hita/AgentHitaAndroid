package com.agenthita.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InstagramConversationHelper.collectMessagesFromList].
 *
 * Covers the structural fallback added for Compose-rendered Instagram messages
 * (MetaComposeView with no-id TextViews). FakeNode stands in for
 * AccessibilityNodeInfo so no Android framework is required.
 */
class InstagramConversationHelperTest {

    // ── FakeNode ─────────────────────────────────────────────────────────────

    private data class FakeNode(
        override val viewIdResourceName: String? = null,
        override val text: CharSequence? = null,
        override val className: CharSequence? = null,
        override val boundsCenterX: Int = 0,
        val children: List<NodeInfo> = emptyList()
    ) : NodeInfo {
        override val childCount get() = children.size
        override fun getChild(i: Int): NodeInfo? = children.getOrNull(i)
    }

    // Helpers to build trees that mirror the real Instagram accessibility hierarchy.

    private fun tv(text: String) = FakeNode(
        text = text,
        className = "android.widget.TextView"
    )

    private fun node(vararg children: NodeInfo) = FakeNode(children = children.toList())

    private fun nodeId(viewId: String, vararg children: NodeInfo) = FakeNode(
        viewIdResourceName = viewId,
        children = children.toList()
    )

    // Filter that matches the production service: length >= 8, no UIChrome, no media.
    private val standardFilter: (String) -> Boolean = { text ->
        text.length >= HitaAccessibilityService.MIN_MESSAGE_LENGTH &&
            !HitaAccessibilityService.isMediaMessage(text) &&
            !HitaAccessibilityService.isUIChrome(text, HitaAccessibilityService.MIN_MESSAGE_LENGTH, emptyList())
    }

    private val IG_MESSAGE_CONTENT = "com.instagram.android:id/message_content"

    // ── Basic extraction ──────────────────────────────────────────────────────

    @Test
    fun `text message in MetaComposeView sibling is extracted`() {
        // MetaComposeView → View → TextView (mirrors real Instagram Compose hierarchy)
        val messageList = node(
            node(node(tv("You send me money now . 3000\$ immediately")))
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertEquals(listOf("You send me money now . 3000\$ immediately"), result)
    }

    @Test
    fun `multiple messages from multiple MetaComposeView siblings are all collected`() {
        // message_list → [MetaComposeView, MetaComposeView, MetaComposeView]
        val messageList = node(
            node(node(tv("Please send \$500 right now"))),
            node(node(tv("What are you talking about"))),
            node(node(tv("I am your bank I need your PIN")))
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertEquals(
            listOf(
                "Please send \$500 right now",
                "What are you talking about",
                "I am your bank I need your PIN"
            ),
            result
        )
    }

    @Test
    fun `deeply nested TextView is found recursively`() {
        val messageList = node(
            node(node(node(tv("Transfer funds immediately"))))  // 3 levels deep
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertEquals(listOf("Transfer funds immediately"), result)
    }

    @Test
    fun `empty list when no TextViews pass the filter`() {
        val messageList = node(node(node(tv("Hi"))))  // "Hi" is too short
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty list when message list has no children`() {
        val result = InstagramConversationHelper.collectMessagesFromList(
            node(), IG_MESSAGE_CONTENT, standardFilter
        )
        assertTrue(result.isEmpty())
    }

    // ── XMA / shared-post container is skipped ────────────────────────────────

    @Test
    fun `message_content XMA container is skipped entirely`() {
        // message_content holds author attribution for shared posts — not a real message.
        val messageList = node(
            nodeId(IG_MESSAGE_CONTENT, tv("anandclemens")),     // skipped
            node(node(tv("Please send \$500 right now")))       // extracted
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertEquals(listOf("Please send \$500 right now"), result)
    }

    @Test
    fun `only message_content is skipped — other containers with view IDs are walked`() {
        val messageList = node(
            nodeId(IG_MESSAGE_CONTENT, tv("anandclemens")),                              // skipped
            nodeId("com.instagram.android:id/some_other_view", tv("I need your PIN"))    // NOT skipped
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertEquals(listOf("I need your PIN"), result)
    }

    // ── Filter edge cases ─────────────────────────────────────────────────────

    @Test
    fun `Edited label is filtered — length 6 is below MIN_MESSAGE_LENGTH`() {
        val messageList = node(
            node(
                tv("Edited"),   // 6 chars — filtered
                tv("Please transfer \$1000 to my account")
            )
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertEquals(listOf("Please transfer \$1000 to my account"), result)
    }

    @Test
    fun `non-TextView nodes with long text are ignored`() {
        val messageList = node(
            FakeNode(
                text = "This is not a TextView so it should be ignored",
                className = "android.widget.FrameLayout"
            )
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `null className node is not collected even if it has long text`() {
        val messageList = node(FakeNode(text = "This has no className at all"))
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, standardFilter
        ).map { it.text }
        assertTrue(result.isEmpty())
    }

    // ── Regression: real hierarchy dumped from Instagram on 2026-07-17 ────────
    // message_list children: profile-context header, timestamp, Compose bubbles
    // (one containing the "Tap and hold to react" hint alongside the message).

    private fun tvAt(text: String, centerX: Int) = FakeNode(
        text = text, className = "android.widget.TextView", boundsCenterX = centerX
    )

    @Test
    fun `dumped Instagram hierarchy extracts messages and skips header, timestamp and react hint`() {
        val igChrome = listOf("tap and hold to react", "view profile", "delivered")
        val filter: (String) -> Boolean = { text ->
            standardFilter(text) && text.lowercase() !in igChrome
        }
        val messageList = node(
            // profile-context header block
            node(
                nodeId("com.instagram.android:id/user_avatar"),
                tv("Femina Polina"),
                tv("174 followers · 346 posts"),
                tv("You follow each other on Instagram"),
                FakeNode(
                    viewIdResourceName = "com.instagram.android:id/view_profile_button",
                    text = "View profile", className = "android.widget.Button"
                )
            ),
            node(),                                   // empty FrameLayout
            tvAt("12:41 PM", 540),                    // timestamp — chrome-filtered
            node(node(tvAt("Hi", 200))),              // too short — filtered
            node(node(tvAt("Hope you are doing well. Do u remember me?", 400))),
            node(
                node(
                    node(),
                    tvAt("Send me money now. Else I will share pictures.", 400),
                    tvAt("Tap and hold to react", 400)  // hint — chrome-filtered
                )
            )
        )
        val result = InstagramConversationHelper.collectMessagesFromList(
            messageList, IG_MESSAGE_CONTENT, filter
        )
        assertEquals(
            listOf(
                "Hope you are doing well. Do u remember me?",
                "Send me money now. Else I will share pictures."
            ),
            result.map { it.text }
        )
        // Direction data flows through for the caller's window-midpoint comparison.
        assertEquals(400, result[0].centerX)
    }
}
