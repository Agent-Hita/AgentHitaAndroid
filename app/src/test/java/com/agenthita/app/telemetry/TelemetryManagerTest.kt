package com.agenthita.app.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryManagerTest {

    // ── Allowlist format ──────────────────────────────────────────────────────

    @Test
    fun `every allowed event name is snake_case and within length bounds`() {
        val pattern = Regex("[a-z][a-z0-9_]*")
        for (name in TelemetryManager.ALLOWED_EVENT_NAMES) {
            assertTrue(
                "'$name' must start with a lowercase letter and contain only [a-z0-9_]",
                pattern.matches(name)
            )
            assertTrue(
                "'$name' is ${name.length} chars — suspiciously long for an event name",
                name.length <= 64
            )
        }
    }

    // ── Allowlist membership ──────────────────────────────────────────────────

    @Test
    fun `track accepts all registered event names`() {
        for (name in TelemetryManager.ALLOWED_EVENT_NAMES) {
            assertTrue("'$name' should be in ALLOWED_EVENT_NAMES", name in TelemetryManager.ALLOWED_EVENT_NAMES)
        }
    }

    @Test
    fun `track rejects message text containing spaces`() {
        assertFalse("hey how are you" in TelemetryManager.ALLOWED_EVENT_NAMES)
        assertFalse("send me $500 now" in TelemetryManager.ALLOWED_EVENT_NAMES)
        assertFalse("I found your photos online" in TelemetryManager.ALLOWED_EVENT_NAMES)
    }

    @Test
    fun `track rejects unregistered snake_case names`() {
        // Catches a developer who forgot to add the event to the allowlist
        assertFalse("some_new_event" in TelemetryManager.ALLOWED_EVENT_NAMES)
        assertFalse("user_message_text" in TelemetryManager.ALLOWED_EVENT_NAMES)
        assertFalse("contact_name" in TelemetryManager.ALLOWED_EVENT_NAMES)
        assertFalse("raw_message" in TelemetryManager.ALLOWED_EVENT_NAMES)
    }

    @Test
    fun `track rejects empty string`() {
        assertFalse("" in TelemetryManager.ALLOWED_EVENT_NAMES)
    }

    @Test
    fun `track rejects strings that look like message content`() {
        val messageLike = listOf(
            "Hey! Are you free tonight?",
            "Send gift cards urgently",
            "I have your photos",
            "analysis_completed but also hey",
            "a".repeat(65)
        )
        for (text in messageLike) {
            assertFalse("'$text' must not be in the allowlist", text in TelemetryManager.ALLOWED_EVENT_NAMES)
        }
    }
}
