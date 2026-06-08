package com.agenthita.app.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuardianAlertThrottleTest {

    private val storage = mutableMapOf<String, Long>()
    private lateinit var throttle: GuardianAlertThrottle

    private val THROTTLE_MS = 24L * 60 * 60 * 1_000   // 24 h
    private val CONTACT_A   = "aaaaaa"
    private val CONTACT_B   = "bbbbbb"

    @Before
    fun setUp() {
        storage.clear()
        throttle = GuardianAlertThrottle(
            store  = { key -> storage[key] ?: 0L },
            save   = { key, value -> storage[key] = value },
            remove = { key -> storage.remove(key) }
        )
    }

    // ── shouldSendNow ─────────────────────────────────────────────────────────

    @Test
    fun `shouldSendNow returns true when no alert has ever been sent`() {
        assertTrue(throttle.shouldSendNow(CONTACT_A, nowMs = 1_000L, THROTTLE_MS))
    }

    @Test
    fun `shouldSendNow returns false immediately after an alert is sent`() {
        val sentAt = 1_000L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        assertFalse(throttle.shouldSendNow(CONTACT_A, nowMs = sentAt, THROTTLE_MS))
    }

    @Test
    fun `shouldSendNow returns false when within throttle window`() {
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        val withinWindow = THROTTLE_MS - 1
        assertFalse(throttle.shouldSendNow(CONTACT_A, nowMs = withinWindow, THROTTLE_MS))
    }

    @Test
    fun `shouldSendNow returns true exactly at the throttle boundary`() {
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        assertTrue(throttle.shouldSendNow(CONTACT_A, nowMs = THROTTLE_MS, THROTTLE_MS))
    }

    @Test
    fun `shouldSendNow returns true after the throttle window has elapsed`() {
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        assertTrue(throttle.shouldSendNow(CONTACT_A, nowMs = THROTTLE_MS + 1, THROTTLE_MS))
    }

    @Test
    fun `shouldSendNow uses the configured throttle duration not a hardcoded value`() {
        val shortThrottle = 5_000L   // 5 seconds
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        // Still within 5-second window
        assertFalse(throttle.shouldSendNow(CONTACT_A, nowMs = 4_999L, shortThrottle))
        // Past 5-second window
        assertTrue(throttle.shouldSendNow(CONTACT_A, nowMs = 5_000L, shortThrottle))
    }

    // ── remainingMs ──────────────────────────────────────────────────────────

    @Test
    fun `remainingMs returns 0 when no alert has been sent`() {
        assertEquals(0L, throttle.remainingMs(CONTACT_A, nowMs = 1_000L, THROTTLE_MS))
    }

    @Test
    fun `remainingMs returns full throttle period right after alert sent`() {
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        assertEquals(THROTTLE_MS, throttle.remainingMs(CONTACT_A, nowMs = 0L, THROTTLE_MS))
    }

    @Test
    fun `remainingMs decreases as time passes`() {
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        val elapsed = 3_600_000L  // 1 hour
        assertEquals(THROTTLE_MS - elapsed, throttle.remainingMs(CONTACT_A, nowMs = elapsed, THROTTLE_MS))
    }

    @Test
    fun `remainingMs returns 0 when window has fully elapsed`() {
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        assertEquals(0L, throttle.remainingMs(CONTACT_A, nowMs = THROTTLE_MS + 1_000L, THROTTLE_MS))
    }

    @Test
    fun `remainingMs returns 0 exactly at boundary`() {
        val sentAt = 0L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        assertEquals(0L, throttle.remainingMs(CONTACT_A, nowMs = THROTTLE_MS, THROTTLE_MS))
    }

    // ── recordAlertSent ───────────────────────────────────────────────────────

    @Test
    fun `recordAlertSent persists the timestamp so subsequent calls see it`() {
        val sentAt = 999_999L
        throttle.recordAlertSent(CONTACT_A, sentAt)
        // One millisecond later is still within window
        assertFalse(throttle.shouldSendNow(CONTACT_A, nowMs = sentAt + 1, THROTTLE_MS))
    }

    @Test
    fun `recording a second alert updates the timestamp and resets the window`() {
        throttle.recordAlertSent(CONTACT_A, nowMs = 0L)
        // Window expires
        throttle.recordAlertSent(CONTACT_A, nowMs = THROTTLE_MS)
        // 1 ms after the second record — still within the new window
        assertFalse(throttle.shouldSendNow(CONTACT_A, nowMs = THROTTLE_MS + 1, THROTTLE_MS))
        // Full window after second record — allowed again
        assertTrue(throttle.shouldSendNow(CONTACT_A, nowMs = THROTTLE_MS * 2, THROTTLE_MS))
    }

    // ── clear ────────────────────────────────────────────────────────────────

    @Test
    fun `clear removes the stored timestamp so the next send is allowed`() {
        throttle.recordAlertSent(CONTACT_A, nowMs = 0L)
        throttle.clear(CONTACT_A)
        assertTrue(throttle.shouldSendNow(CONTACT_A, nowMs = 1L, THROTTLE_MS))
    }

    @Test
    fun `clear on unknown contact does not throw`() {
        throttle.clear("unknown_contact_hash")
    }

    // ── Multiple contacts tracked independently ───────────────────────────────

    @Test
    fun `throttle state is independent per contact`() {
        throttle.recordAlertSent(CONTACT_A, nowMs = 0L)
        // Contact B has no record — should be allowed
        assertTrue(throttle.shouldSendNow(CONTACT_B, nowMs = 1L, THROTTLE_MS))
        // Contact A is still throttled
        assertFalse(throttle.shouldSendNow(CONTACT_A, nowMs = 1L, THROTTLE_MS))
    }

    @Test
    fun `clearing one contact does not affect another`() {
        throttle.recordAlertSent(CONTACT_A, nowMs = 0L)
        throttle.recordAlertSent(CONTACT_B, nowMs = 0L)
        throttle.clear(CONTACT_A)
        assertTrue(throttle.shouldSendNow(CONTACT_A, nowMs = 1L, THROTTLE_MS))
        assertFalse(throttle.shouldSendNow(CONTACT_B, nowMs = 1L, THROTTLE_MS))
    }

    @Test
    fun `remainingMs for one contact is unaffected by activity on another`() {
        throttle.recordAlertSent(CONTACT_A, nowMs = 0L)
        throttle.recordAlertSent(CONTACT_B, nowMs = 1_000L)
        assertEquals(THROTTLE_MS, throttle.remainingMs(CONTACT_A, nowMs = 0L, THROTTLE_MS))
        assertEquals(THROTTLE_MS, throttle.remainingMs(CONTACT_B, nowMs = 1_000L, THROTTLE_MS))
    }
}
