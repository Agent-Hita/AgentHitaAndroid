package com.agenthita.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HitaAccessibilityService.isWhatsAppGroupSubtitle] — the
 * subtitle-based half of WhatsApp group identification.
 *
 * Regression context (2026-07-18): two group chats were scored and alerted on
 * because the group-header subtitle populates asynchronously and the first
 * processed window after chat-open saw a blank subtitle. A blank subtitle must
 * NOT identify a group (1-1 chats legitimately have blank subtitles — treating
 * blank as group would suppress real 1-1 alerts); the async race is instead
 * covered by the structural sender-name check and the alert-time re-check.
 */
class WhatsAppGroupSubtitleTest {

    // ── Group subtitles ──────────────────────────────────────────────────────

    @Test
    fun `participant list identifies group`() {
        assertTrue(HitaAccessibilityService.isWhatsAppGroupSubtitle("Alice, Bob, You"))
    }

    @Test
    fun `member count identifies group`() {
        assertTrue(HitaAccessibilityService.isWhatsAppGroupSubtitle("Group · 32 members"))
    }

    @Test
    fun `community subtitle identifies group`() {
        assertTrue(HitaAccessibilityService.isWhatsAppGroupSubtitle("Community · 5 groups"))
    }

    @Test
    fun `channel subtitle identifies group`() {
        assertTrue(HitaAccessibilityService.isWhatsAppGroupSubtitle("Channel · 1.2K followers"))
    }

    @Test
    fun `online count identifies group`() {
        assertTrue(HitaAccessibilityService.isWhatsAppGroupSubtitle("12 online"))
    }

    @Test
    fun `tap here for group info placeholder identifies group`() {
        assertTrue(HitaAccessibilityService.isWhatsAppGroupSubtitle("tap here for group info"))
    }

    // ── 1-1 subtitles must NOT identify a group ──────────────────────────────

    @Test
    fun `blank subtitle is not a group`() {
        assertFalse(HitaAccessibilityService.isWhatsAppGroupSubtitle(""))
    }

    @Test
    fun `online presence is not a group`() {
        assertFalse(HitaAccessibilityService.isWhatsAppGroupSubtitle("online"))
    }

    @Test
    fun `last seen presence is not a group`() {
        assertFalse(HitaAccessibilityService.isWhatsAppGroupSubtitle("last seen today at 10:30"))
    }

    @Test
    fun `tap here for contact info placeholder is not a group`() {
        assertFalse(HitaAccessibilityService.isWhatsAppGroupSubtitle("tap here for contact info"))
    }

    @Test
    fun `typing indicator is not a group`() {
        assertFalse(HitaAccessibilityService.isWhatsAppGroupSubtitle("typing…"))
    }
}
