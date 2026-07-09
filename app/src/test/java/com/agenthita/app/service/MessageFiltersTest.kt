package com.agenthita.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the companion-object filter functions in [HitaAccessibilityService]:
 * [HitaAccessibilityService.isUIChrome] and [HitaAccessibilityService.isMediaMessage].
 *
 * These are pure string functions — no Android framework required.
 */
class MessageFiltersTest {

    private val MIN = HitaAccessibilityService.MIN_MESSAGE_LENGTH  // 8

    // ── isUIChrome ────────────────────────────────────────────────────────────

    private fun chrome(text: String, prefixes: List<String> = emptyList()) =
        HitaAccessibilityService.isUIChrome(text, MIN, prefixes)

    @Test
    fun `short strings below minLength are chrome`() {
        assertTrue(chrome("Edited"))   // 6 chars
        assertTrue(chrome("Hi"))       // 2 chars
        assertTrue(chrome(""))
    }

    @Test
    fun `exact length boundary — string of exactly minLength chars is not chrome by length alone`() {
        val exactly8 = "abcdefgh"
        assertFalse(chrome(exactly8))
    }

    @Test
    fun `timestamps are chrome`() {
        assertTrue(chrome("9:23 AM"))
        assertTrue(chrome("10:30 PM"))
        assertTrue(chrome("3:05 am"))
        assertTrue(chrome("11:59"))   // no AM/PM
    }

    @Test
    fun `today and yesterday prefixes are chrome`() {
        assertTrue(chrome("Today, 3:45 PM"))
        assertTrue(chrome("Yesterday, 11:00 AM"))
        assertTrue(chrome("today"))   // short but also starts with "today"
    }

    @Test
    fun `exact UI label matches are chrome`() {
        assertTrue(chrome("send"))
        assertTrue(chrome("message"))
        assertTrue(chrome("type a message"))
        assertTrue(chrome("SEND"))     // case-insensitive
        assertTrue(chrome("Message"))
    }

    @Test
    fun `gmUiChromePrefixes filter applies`() {
        val prefixes = listOf("texting with")
        assertTrue(chrome("texting with John", prefixes))
        assertTrue(chrome("Texting with Alice", prefixes))  // case-insensitive
        assertFalse(chrome("I am texting with someone", prefixes)) // prefix mismatch
    }

    @Test
    fun `real message content is not chrome`() {
        assertFalse(chrome("You send me money now . 3000\$ immediately"))
        assertFalse(chrome("Please transfer funds to my account"))
        assertFalse(chrome("I am from your bank and I need your PIN"))
        assertFalse(chrome("What are you talking about"))
    }

    // ── isMediaMessage ────────────────────────────────────────────────────────

    private fun media(text: String) = HitaAccessibilityService.isMediaMessage(text)

    @Test
    fun `bare media labels are filtered`() {
        assertTrue(media("photo"))
        assertTrue(media("video"))
        assertTrue(media("image"))
        assertTrue(media("gif"))
        assertTrue(media("sticker"))
        assertTrue(media("audio"))
        assertTrue(media("document"))
        assertTrue(media("location"))
        assertTrue(media("file"))
    }

    @Test
    fun `media labels are case-insensitive`() {
        assertTrue(media("Photo"))
        assertTrue(media("VIDEO"))
        assertTrue(media("Voice Message"))
    }

    @Test
    fun `media labels with metadata suffixes are filtered`() {
        assertTrue(media("Video · 0:21"))
        assertTrue(media("voice message (5.4 MB)"))
        assertTrue(media("audio (1.2 MB)"))
        assertTrue(media("photo 3"))
    }

    @Test
    fun `leading emoji before media label is filtered`() {
        assertTrue(media("🎥 Video"))
        assertTrue(media("📷 photo"))
    }

    @Test
    fun `natural language captions are NOT filtered`() {
        assertFalse(media("Video of us at the party"))
        assertFalse(media("I sent you a photo yesterday"))
        assertFalse(media("Check out this audio recording I made"))
    }

    @Test
    fun `common image file extensions are filtered`() {
        assertTrue(media("image.jpg"))
        assertTrue(media("screenshot.png"))
        assertTrue(media("photo.heic"))
        assertTrue(media("clip.mp4"))
        assertTrue(media("recording.aac"))
    }

    @Test
    fun `real message content is not media`() {
        assertFalse(media("You send me money now . 3000\$ immediately"))
        assertFalse(media("Please transfer funds to my account"))
        assertFalse(media("I am your bank"))
    }

    // ── Edit detection pattern ────────────────────────────────────────────────
    // isConversationScreen returns false when igEditBarId node is present in the
    // tree (presence = edit mode active). Verified via logcat: edit_bar_header
    // only appears in the tree during active editing.

    @Test
    fun `igEditBarId default is edit_bar_header`() {
        val defaults = com.agenthita.app.config.RemoteConfig.ConfigSnapshot()
        assertTrue(
            "igEditBarId must default to edit_bar_header",
            defaults.uiTags.igEditBarId == "edit_bar_header"
        )
    }

    @Test
    fun `igEditBarId is non-empty so edit check is enabled by default`() {
        val defaults = com.agenthita.app.config.RemoteConfig.ConfigSnapshot()
        assertTrue(
            "igEditBarId must be non-empty for the edit detection check to run",
            defaults.uiTags.igEditBarId.isNotEmpty()
        )
    }

    @Test
    fun `waEditBarId defaults to empty — check disabled until confirmed`() {
        val defaults = com.agenthita.app.config.RemoteConfig.ConfigSnapshot()
        assertTrue(
            "waEditBarId must be empty until its view ID is confirmed via device logs",
            defaults.uiTags.waEditBarId.isEmpty()
        )
    }

    @Test
    fun `gmEditBarId defaults to empty — check disabled until confirmed`() {
        val defaults = com.agenthita.app.config.RemoteConfig.ConfigSnapshot()
        assertTrue(
            "gmEditBarId must be empty until its view ID is confirmed via device logs",
            defaults.uiTags.gmEditBarId.isEmpty()
        )
    }
}
