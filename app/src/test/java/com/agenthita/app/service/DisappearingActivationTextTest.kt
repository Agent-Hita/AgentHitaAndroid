package com.agenthita.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banner-text classification for disappearing-messages activation.
 * Input is lowercased by the scanner before matching (mirrored here).
 *
 * Regression (2026-07-17): Instagram's instructional promo "Swipe up to turn on
 * disappearing messages" matched the broad catch-all and fired a HIGH guardian
 * alert. Instructional/promotional texts must never alert; only actual
 * activation confirmations may.
 */
class DisappearingActivationTextTest {

    private fun activates(text: String) =
        HitaAccessibilityService.isDisappearingActivationText(text.lowercase())

    // ── Actual activations must alert ─────────────────────────────────────────

    @Test
    fun `you turned on disappearing messages alerts`() =
        assertTrue(activates("You turned on disappearing messages"))

    @Test
    fun `contact turned on disappearing messages alerts`() =
        assertTrue(activates("Femina turned on disappearing messages. New messages will disappear from this chat 24 hours after they're sent."))

    @Test
    fun `vanish mode alerts`() =
        assertTrue(activates("You're in vanish mode"))

    @Test
    fun `generic disappearing banner alerts via catch-all`() =
        assertTrue(activates("Disappearing messages are active in this chat"))

    // ── Instructional hints must never alert ─────────────────────────────────

    @Test
    fun `instagram swipe up promo does not alert`() =
        assertFalse(activates("Swipe up to turn on disappearing messages"))

    @Test
    fun `tap to turn on hint does not alert`() =
        assertFalse(activates("Tap to turn on disappearing messages"))

    @Test
    fun `learn more hint does not alert`() =
        assertFalse(activates("Learn more about disappearing messages"))

    // ── Turn-off events must never alert ─────────────────────────────────────

    @Test
    fun `turned off disappearing messages does not alert`() =
        assertFalse(activates("You turned off disappearing messages"))

    @Test
    fun `vanish mode turned off does not alert`() =
        assertFalse(activates("Vanish mode has been turned off"))
}
