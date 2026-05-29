package com.agenthita.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationBufferTest {

    private lateinit var buffer: ConversationBuffer

    @Before
    fun setUp() {
        buffer = ConversationBuffer(maxMessages = 5)
    }

    // ── Empty state ───────────────────────────────────────────────────────────

    @Test
    fun `getContext returns empty list for unknown conversation`() {
        val result = buffer.getContext("com.whatsapp", "Alice")
        assertTrue(result.isEmpty())
    }

    // ── Basic add and retrieve ────────────────────────────────────────────────

    @Test
    fun `single message is retrievable`() {
        buffer.add("com.whatsapp", "Alice", "Hello there")
        val result = buffer.getContext("com.whatsapp", "Alice")
        assertEquals(listOf("Hello there"), result)
    }

    @Test
    fun `multiple messages are returned in insertion order`() {
        buffer.add("com.whatsapp", "Alice", "msg1")
        buffer.add("com.whatsapp", "Alice", "msg2")
        buffer.add("com.whatsapp", "Alice", "msg3")
        assertEquals(listOf("msg1", "msg2", "msg3"), buffer.getContext("com.whatsapp", "Alice"))
    }

    // ── Ring buffer eviction ──────────────────────────────────────────────────

    @Test
    fun `buffer trims to maxMessages when exceeded`() {
        repeat(7) { i -> buffer.add("com.whatsapp", "Alice", "msg$i") }
        val result = buffer.getContext("com.whatsapp", "Alice")
        assertEquals(5, result.size)
    }

    @Test
    fun `oldest messages are evicted first`() {
        repeat(7) { i -> buffer.add("com.whatsapp", "Alice", "msg$i") }
        val result = buffer.getContext("com.whatsapp", "Alice")
        // Should contain msg2..msg6 (the 5 most recent)
        assertEquals(listOf("msg2", "msg3", "msg4", "msg5", "msg6"), result)
    }

    @Test
    fun `buffer at exactly maxMessages keeps all messages`() {
        repeat(5) { i -> buffer.add("com.whatsapp", "Alice", "msg$i") }
        assertEquals(5, buffer.getContext("com.whatsapp", "Alice").size)
    }

    // ── Conversation isolation ────────────────────────────────────────────────

    @Test
    fun `different sender names are stored separately`() {
        buffer.add("com.whatsapp", "Alice", "from alice")
        buffer.add("com.whatsapp", "Bob", "from bob")

        assertEquals(listOf("from alice"), buffer.getContext("com.whatsapp", "Alice"))
        assertEquals(listOf("from bob"), buffer.getContext("com.whatsapp", "Bob"))
    }

    @Test
    fun `same sender name across different packages is stored separately`() {
        buffer.add("com.whatsapp", "Alice", "whatsapp msg")
        buffer.add("com.instagram.android", "Alice", "instagram msg")

        assertEquals(listOf("whatsapp msg"), buffer.getContext("com.whatsapp", "Alice"))
        assertEquals(listOf("instagram msg"), buffer.getContext("com.instagram.android", "Alice"))
    }

    @Test
    fun `unknown conversation returns empty after known one added`() {
        buffer.add("com.whatsapp", "Alice", "hello")
        assertTrue(buffer.getContext("com.whatsapp", "NotAlice").isEmpty())
    }

    // ── Multiple conversations can coexist ────────────────────────────────────

    @Test
    fun `multiple conversations can each hold maxMessages independently`() {
        repeat(5) { i -> buffer.add("com.whatsapp", "Alice", "a$i") }
        repeat(5) { i -> buffer.add("com.whatsapp", "Bob", "b$i") }

        assertEquals(5, buffer.getContext("com.whatsapp", "Alice").size)
        assertEquals(5, buffer.getContext("com.whatsapp", "Bob").size)
    }

    // ── Buffer with maxMessages = 1 ───────────────────────────────────────────

    @Test
    fun `buffer with maxMessages 1 keeps only the latest message`() {
        val tiny = ConversationBuffer(maxMessages = 1)
        tiny.add("pkg", "X", "first")
        tiny.add("pkg", "X", "second")
        assertEquals(listOf("second"), tiny.getContext("pkg", "X"))
    }

    // ── Raw identifiers are not stored ───────────────────────────────────────

    @Test
    fun `context list does not contain package name or sender name`() {
        val pkg = "com.whatsapp"
        val sender = "Alice"
        buffer.add(pkg, sender, "some message")
        val result = buffer.getContext(pkg, sender)
        result.forEach { entry ->
            assertTrue("Raw package name must not appear in buffer: $entry", !entry.contains(pkg))
            assertTrue("Raw sender name must not appear in buffer: $entry", !entry.contains(sender))
        }
    }
}
