package com.agenthita.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the hardcoded defaults in ConfigSnapshot. No Android context required —
 * ConfigSnapshot is a plain data class with compile-time default values.
 *
 * These tests ensure that safe, non-blank defaults ship with every build.
 * A blank endpoint or empty package list would cause silent runtime failures.
 */
class RemoteConfigDefaultsTest {

    private val defaults = RemoteConfig.ConfigSnapshot()

    // ── Endpoint URLs are present and use HTTPS ───────────────────────────────

    @Test
    fun `telemetryEndpoint is non-blank and uses https`() {
        assertTrue(defaults.telemetryEndpoint.isNotBlank())
        assertTrue(
            "telemetryEndpoint must start with https://",
            defaults.telemetryEndpoint.startsWith("https://")
        )
    }

    @Test
    fun `alertEndpoint is non-blank and uses https`() {
        assertTrue(defaults.alertEndpoint.isNotBlank())
        assertTrue(
            "alertEndpoint must start with https://",
            defaults.alertEndpoint.startsWith("https://")
        )
    }

    @Test
    fun `feedbackEndpoint is non-blank and uses https`() {
        assertTrue(defaults.feedbackEndpoint.isNotBlank())
        assertTrue(
            "feedbackEndpoint must start with https://",
            defaults.feedbackEndpoint.startsWith("https://")
        )
    }

    @Test
    fun `deviceRegisterEndpoint is non-blank and uses https`() {
        assertTrue(defaults.deviceRegisterEndpoint.isNotBlank())
        assertTrue(defaults.deviceRegisterEndpoint.startsWith("https://"))
    }

    // ── Timeout values are positive ───────────────────────────────────────────

    @Test
    fun `connectTimeoutMs is positive`() {
        assertTrue("connectTimeoutMs must be > 0", defaults.connectTimeoutMs > 0)
    }

    @Test
    fun `readTimeoutMs is positive`() {
        assertTrue("readTimeoutMs must be > 0", defaults.readTimeoutMs > 0)
    }

    // ── Target packages include core messaging apps ───────────────────────────

    @Test
    fun `targetPackages is non-empty`() {
        assertFalse("targetPackages must not be empty", defaults.targetPackages.isEmpty())
    }

    @Test
    fun `targetPackages contains WhatsApp`() {
        assertTrue(
            "targetPackages must contain com.whatsapp",
            defaults.targetPackages.contains("com.whatsapp")
        )
    }

    @Test
    fun `targetPackages contains Instagram`() {
        assertTrue(
            "targetPackages must contain com.instagram.android",
            defaults.targetPackages.contains("com.instagram.android")
        )
    }

    @Test
    fun `targetPackages contains at least one SMS app`() {
        val smsApps = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.messaging",
            "com.android.mms"
        )
        assertTrue(
            "targetPackages must contain at least one SMS app",
            defaults.targetPackages.intersect(smsApps).isNotEmpty()
        )
    }

    // ── Risk thresholds are coherent (high > medium > low) ───────────────────

    @Test
    fun `child risk thresholds are ordered high gt medium gt low`() {
        val t = defaults.riskThresholds.child
        assertTrue("child: high must be > medium", t.high > t.medium)
        assertTrue("child: medium must be > low", t.medium > t.low)
    }

    @Test
    fun `adolescent risk thresholds are ordered high gt medium gt low`() {
        val t = defaults.riskThresholds.adolescent
        assertTrue("adolescent: high must be > medium", t.high > t.medium)
        assertTrue("adolescent: medium must be > low", t.medium > t.low)
    }

    @Test
    fun `adult risk thresholds are ordered high gt medium gt low`() {
        val t = defaults.riskThresholds.adult
        assertTrue("adult: high must be > medium", t.high > t.medium)
        assertTrue("adult: medium must be > low", t.medium > t.low)
    }

    @Test
    fun `child threshold is more sensitive than adult`() {
        val child = defaults.riskThresholds.child
        val adult = defaults.riskThresholds.adult
        assertTrue(
            "Child high threshold must be <= adult high threshold (child protection is more sensitive)",
            child.high <= adult.high
        )
    }

    // ── Safety resources are populated ───────────────────────────────────────

    @Test
    fun `helpResources is non-empty`() {
        assertFalse("helpResources must not be empty", defaults.helpResources.isEmpty())
    }

    @Test
    fun `all helpResources have non-blank name and https url`() {
        defaults.helpResources.forEach { resource ->
            assertTrue("Help resource name must not be blank: $resource", resource.name.isNotBlank())
            assertTrue(
                "Help resource url must start with https://: ${resource.url}",
                resource.url.startsWith("https://")
            )
        }
    }

    @Test
    fun `safetyResources sextortion tips are non-blank`() {
        val s = defaults.safetyResources.sextortion
        assertTrue(s.tip1Url.startsWith("https://"))
        assertTrue(s.tip2Url.startsWith("https://"))
    }

    @Test
    fun `safetyResources grooming tips are non-blank`() {
        val s = defaults.safetyResources.grooming
        assertTrue(s.tip1Url.startsWith("https://"))
        assertTrue(s.tip2Url.startsWith("https://"))
    }

    // ── Detection parameters are sane ────────────────────────────────────────

    @Test
    fun `minMessageLength is positive`() {
        assertTrue("minMessageLength must be > 0", defaults.minMessageLength > 0)
    }

    @Test
    fun `gemmaMaxTokens is positive`() {
        assertTrue("gemmaMaxTokens must be > 0", defaults.gemmaMaxTokens > 0)
    }

    @Test
    fun `gemmaContextMessages is positive`() {
        assertTrue("gemmaContextMessages must be > 0", defaults.gemmaContextMessages > 0)
    }

    @Test
    fun `gemmaMaxFileSizeBytes is at least 1 GB`() {
        assertTrue(
            "gemmaMaxFileSizeBytes must be >= 1 GB",
            defaults.gemmaMaxFileSizeBytes >= 1_000_000_000L
        )
    }

    @Test
    fun `telemetryFlushThreshold is positive`() {
        assertTrue("telemetryFlushThreshold must be > 0", defaults.telemetryFlushThreshold > 0)
    }

    @Test
    fun `telemetryFlushIntervalMs is positive`() {
        assertTrue("telemetryFlushIntervalMs must be > 0", defaults.telemetryFlushIntervalMs > 0)
    }

    @Test
    fun `guardianAlertThrottleMs is at least one hour`() {
        assertTrue(
            "guardianAlertThrottleMs must be >= 1 hour to avoid alert spam",
            defaults.guardianAlertThrottleMs >= 60 * 60 * 1_000L
        )
    }

    @Test
    fun `guardianAlertThrottleMs default is 24 hours`() {
        assertEquals(24L * 60 * 60 * 1_000, defaults.guardianAlertThrottleMs)
    }

    // ── ConfigSnapshot copy preserves unrelated fields ───────────────────────

    @Test
    fun `copy with changed endpoint leaves other fields intact`() {
        val modified = defaults.copy(telemetryEndpoint = "https://custom.example.com/t")
        assertEquals(defaults.alertEndpoint, modified.alertEndpoint)
        assertEquals(defaults.feedbackEndpoint, modified.feedbackEndpoint)
        assertEquals(defaults.targetPackages, modified.targetPackages)
    }

    // ── WhatsApp message-content allowlist (2026-07-30 fix) ──────────────────
    // Regression guard for a shared-contact-card message (vcard_text/msg_contact_btn/
    // action_contact_btn) that used to leak into Gemma as if it were message text.

    @Test
    fun `waMessageContentIds contains message_text`() {
        assertTrue(
            "waMessageContentIds must contain message_text — the real WhatsApp message bubble ID",
            RemoteConfig.UiTags().waMessageContentIds.contains("message_text")
        )
    }

    @Test
    fun `waMessageContentIds does not contain shared-contact-card view IDs`() {
        val contentIds = RemoteConfig.UiTags().waMessageContentIds
        listOf("vcard_text", "msg_contact_btn", "action_contact_btn").forEach { chromeId ->
            assertFalse(
                "waMessageContentIds must not contain $chromeId — it's a shared-contact-card UI element, not message text",
                contentIds.contains(chromeId)
            )
        }
    }

    // ── WhatsApp non-conversation-screen exclusion markers (2026-08-01 fix) ──
    // Regression guard: the media caption editor and forward/recipient picker both
    // have their own compose box + send button, so without these markers
    // isConversationScreen misreads them as a real conversation — flooding
    // parsing-failure telemetry with color_picker_btn/recipients_scroller/etc.

    @Test
    fun `waCaptionEditorMarkerId is non-blank by default`() {
        assertTrue(
            "waCaptionEditorMarkerId must ship with a non-blank default so the exclusion is active out of the box",
            RemoteConfig.UiTags().waCaptionEditorMarkerId.isNotBlank()
        )
    }

    @Test
    fun `waRecipientPickerMarkerId is non-blank by default`() {
        assertTrue(
            "waRecipientPickerMarkerId must ship with a non-blank default so the exclusion is active out of the box",
            RemoteConfig.UiTags().waRecipientPickerMarkerId.isNotBlank()
        )
    }
}
