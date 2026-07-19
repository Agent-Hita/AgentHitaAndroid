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
    fun `date separators are chrome`() {
        // Regression 2026-07-18: a voice-message-only chat has no message_text
        // nodes; the structural fallback scraped the "December 4, 2025" date
        // separator as a message and Gemma hallucinated an alert on it.
        assertTrue(chrome("December 4, 2025"))
        assertTrue(chrome("4 December 2025"))
        assertTrue(chrome("14 Jul 2026"))
        assertTrue(chrome("Wednesday"))
        assertTrue(chrome("Saturday"))
    }

    @Test
    fun `conversation header chrome is filtered`() {
        assertTrue(chrome("tap here for contact info"))
        assertTrue(chrome("tap here for group info"))
        assertTrue(chrome("last seen today at 10:30"))
    }

    @Test
    fun `call rows and in-call chrome are filtered`() {
        // Regression 2026-07-18: during a WhatsApp voice call the message list
        // transiently renders 0 text nodes; the structural fallback scraped the
        // call UI and "Voice call" rows were scored as messages.
        assertTrue(chrome("Voice call"))
        assertTrue(chrome("Video call"))
        assertTrue(chrome("Missed voice call"))
        assertTrue(chrome("Ongoing voice call"))
        assertTrue(chrome("Video call · 12:34"))
        assertTrue(chrome("Voice call (2 min)"))
        assertTrue(chrome("Tap to return to call"))
        // Regression 2026-07-18 (live alert): the missed-call row's tap label
        // was scraped by the fallback and scored as an incoming message.
        assertTrue(chrome("Tap to call back"))
        assertTrue(chrome("Missed voice call - Tap to call back"))
    }

    // ── isFallbackChromeId ────────────────────────────────────────────────────

    private val excludedPrefixes =
        com.agenthita.app.config.RemoteConfig.UiTags().waFallbackExcludedIdPrefixes

    @Test
    fun `chrome view IDs are excluded from the fallback walk`() {
        // All verified live on-device 2026-07-18 (the out_of_chat banner carried
        // another contact's name and was scored as IDENTITY_PHISHING).
        listOf(
            "com.whatsapp:id/out_of_chat_title",
            "com.whatsapp:id/call_log_title",
            "com.whatsapp:id/call_log_subtitle",
            "com.whatsapp:id/conversation_row_date_divider",
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/info",
            "com.whatsapp:id/date",
            "com.whatsapp:id/entry"
        ).forEach { id ->
            assertTrue("$id must be excluded",
                HitaAccessibilityService.isFallbackChromeId(id, excludedPrefixes))
        }
    }

    @Test
    fun `message view IDs and id-less nodes are not excluded`() {
        assertFalse(HitaAccessibilityService.isFallbackChromeId(
            "com.whatsapp:id/message_text", excludedPrefixes))
        assertFalse(HitaAccessibilityService.isFallbackChromeId(null, excludedPrefixes))
    }

    @Test
    fun `messages mentioning calls are not chrome`() {
        assertFalse(chrome("voice call me tomorrow night"))
        assertFalse(chrome("they did not answer my call last night"))
        assertFalse(chrome("can we do a video call at 5"))
    }

    @Test
    fun `messages mentioning dates or days are not chrome`() {
        assertFalse(chrome("see you in December"))
        assertFalse(chrome("meet me on December 4 near the park"))
        assertFalse(chrome("Wednesday works for me"))
        assertFalse(chrome("my exam is on 14 July, wish me luck"))
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
