package com.agenthita.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SeenMessageTrackerTest {

    private val store = mutableMapOf<String, Set<String>>()
    private lateinit var tracker: SeenMessageTracker

    // Identity hash keeps tests readable — no need for real SHA-256 here.
    private val id: (String) -> String = { it }

    private val CONV = "seen_whatsapp_baby2"

    @Before
    fun setUp() {
        store.clear()
        tracker = SeenMessageTracker(
            load = { key -> store[key] ?: emptySet() },
            save = { key, hashes -> store[key] = hashes }
        )
    }

    // ── filterUnseen: baseline ────────────────────────────────────────────────

    @Test
    fun `all messages are unseen when the set is empty`() {
        val msgs = listOf("Hello", "Send me your bank details")
        assertEquals(msgs, tracker.filterUnseen(CONV, msgs, id))
    }

    @Test
    fun `empty message list returns empty`() {
        assertTrue(tracker.filterUnseen(CONV, emptyList(), id).isEmpty())
    }

    // ── markSeen then filterUnseen ────────────────────────────────────────────

    @Test
    fun `messages marked seen are excluded on the next filterUnseen call`() {
        val msgs = listOf("msg1", "msg2")
        tracker.markSeen(CONV, msgs, id)
        assertTrue(tracker.filterUnseen(CONV, msgs, id).isEmpty())
    }

    @Test
    fun `only unseen messages are returned when some are already seen`() {
        tracker.markSeen(CONV, listOf("old1", "old2"), id)
        val result = tracker.filterUnseen(CONV, listOf("old1", "new1"), id)
        assertEquals(listOf("new1"), result)
    }

    @Test
    fun `marking a single message seen does not affect other messages`() {
        tracker.markSeen(CONV, listOf("msg1"), id)
        val result = tracker.filterUnseen(CONV, listOf("msg1", "msg2", "msg3"), id)
        assertEquals(listOf("msg2", "msg3"), result)
    }

    // ── Key regression: seen set survives conversation exit ───────────────────
    //
    // Bug: flushPendingAlert used to delete the seen_* SharedPreferences entry on
    // every conversation exit. This caused every re-entry to re-score the same
    // messages and fire duplicate guardian alerts (verified in logs: "HIGH risk —
    // sending guardian alert" fired three times for the same Baby 2 WhatsApp messages
    // on 2026-06-28 at 10:22:38, 10:22:46, and 10:23:22).
    //
    // Fix: flushPendingAlert no longer clears seen_*. SeenMessageTracker has no
    // clear() method — the set is only reset on service reconnect (onServiceConnected
    // wipes all seen_* keys from dedupPrefs) or trimmed at DEFAULT_MAX_PER_CONV.
    //
    // These tests verify that NOT clearing the set between visits is correct:
    // old messages stay suppressed, new messages are always detected.

    @Test
    fun `seen set persists across conversation exit — same messages are not re-detected on re-entry`() {
        val msgs = listOf("Send me your login credentials", "I am from your bank")

        // First visit: messages scored and marked seen.
        tracker.markSeen(CONV, msgs, id)

        // User leaves conversation (flushPendingAlert). SeenMessageTracker has no
        // clear() — this is the fix. Simulate by doing nothing here.

        // Re-entry: same messages are still visible on screen.
        val unseen = tracker.filterUnseen(CONV, msgs, id)
        assertTrue(
            "Re-entering the same conversation must not re-detect already-scored messages",
            unseen.isEmpty()
        )
    }

    @Test
    fun `new message arriving while away is detected on re-entry`() {
        tracker.markSeen(CONV, listOf("Hi there"), id)

        // User leaves — no clear. New message arrives while away.
        val onReEntry = listOf("Hi there", "Send me your login now")
        val unseen = tracker.filterUnseen(CONV, onReEntry, id)

        assertEquals(listOf("Send me your login now"), unseen)
    }

    @Test
    fun `multiple exits and re-entries accumulate the seen set correctly`() {
        tracker.markSeen(CONV, listOf("msg1"), id)
        // exit (no clear)
        tracker.markSeen(CONV, listOf("msg2"), id)
        // exit (no clear)

        val unseen = tracker.filterUnseen(CONV, listOf("msg1", "msg2", "msg3"), id)
        assertEquals(listOf("msg3"), unseen)
    }

    @Test
    fun `re-entry with all new messages detects them all`() {
        tracker.markSeen(CONV, listOf("old"), id)

        val unseen = tracker.filterUnseen(CONV, listOf("new1", "new2"), id)
        assertEquals(listOf("new1", "new2"), unseen)
    }

    // ── Conversation isolation ────────────────────────────────────────────────

    @Test
    fun `seen sets for different conversations are independent`() {
        val convA = "seen_whatsapp_baby2"
        val convB = "seen_whatsapp_scammer"
        tracker.markSeen(convA, listOf("shared text"), id)

        val unseen = tracker.filterUnseen(convB, listOf("shared text"), id)
        assertEquals(listOf("shared text"), unseen)
    }

    @Test
    fun `marking seen in one conversation does not prevent detection in another`() {
        tracker.markSeen("conv_a", listOf("msg"), id)
        assertEquals(listOf("msg"), tracker.filterUnseen("conv_b", listOf("msg"), id))
    }

    // ── Trim at maxPerConv ────────────────────────────────────────────────────

    @Test
    fun `seen set size stays at or below maxPerConv after trim`() {
        val cap = 10
        val small = SeenMessageTracker(
            load = { key -> store[key] ?: emptySet() },
            save = { key, hashes -> store[key] = hashes },
            maxPerConv = cap
        )
        repeat(cap) { i -> small.markSeen(CONV, listOf("msg$i"), id) }

        // One more entry triggers trim.
        small.markSeen(CONV, listOf("trigger"), id)

        assertTrue(
            "Set size ${store[CONV]!!.size} should not exceed maxPerConv ($cap)",
            store[CONV]!!.size <= cap
        )
    }

    @Test
    fun `trim on one conversation does not affect another`() {
        val cap = 5
        val small = SeenMessageTracker(
            load = { key -> store[key] ?: emptySet() },
            save = { key, hashes -> store[key] = hashes },
            maxPerConv = cap
        )
        repeat(cap) { i -> small.markSeen("convA", listOf("a$i"), id) }
        small.markSeen("convB", listOf("b0"), id)

        small.markSeen("convA", listOf("trigger"), id)

        // convB entry must still be there.
        assertTrue(small.filterUnseen("convB", listOf("b0"), id).isEmpty())
    }

    @Test
    fun `trim removes entries so new messages can be tracked`() {
        val cap = 5
        val small = SeenMessageTracker(
            load = { key -> store[key] ?: emptySet() },
            save = { key, hashes -> store[key] = hashes },
            maxPerConv = cap
        )
        repeat(cap) { i -> small.markSeen(CONV, listOf("old$i"), id) }
        small.markSeen(CONV, listOf("new"), id)

        // "new" must be in the seen set after trim.
        assertTrue(small.filterUnseen(CONV, listOf("new"), id).isEmpty())
    }
}
