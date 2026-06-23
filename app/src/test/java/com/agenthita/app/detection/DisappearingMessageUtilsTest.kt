package com.agenthita.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisappearingMessageUtilsTest {

    // ── parseDurationDays ─────────────────────────────────────────────────────

    @Test
    fun `hours under 24 parse to 0 days`() {
        assertEquals(0, parseDurationDays("messages set to disappear after 8 hours"))
        assertEquals(0, parseDurationDays("timer has been set to 1 hour"))
    }

    @Test
    fun `24 hours parses to 1 day`() {
        assertEquals(1, parseDurationDays("messages will disappear after 24 hours"))
    }

    @Test
    fun `days parse directly`() {
        assertEquals(1,  parseDurationDays("chats will delete after 1 day"))
        assertEquals(7,  parseDurationDays("messages set to disappear after 7 days"))
        assertEquals(90, parseDurationDays("disappearing messages turned on — 90 days"))
    }

    @Test
    fun `weeks convert to days`() {
        assertEquals(7,  parseDurationDays("messages set to disappear after 1 week"))
        assertEquals(28, parseDurationDays("new messages will disappear after 4 weeks"))
    }

    @Test
    fun `months convert to days`() {
        assertEquals(30, parseDurationDays("messages set to disappear after 1 month"))
        assertEquals(90, parseDurationDays("disappearing messages — 3 months"))
    }

    @Test
    fun `years convert to days`() {
        assertEquals(365, parseDurationDays("messages set to disappear after 1 year"))
    }

    @Test
    fun `seconds parse to 0 days`() {
        assertEquals(0, parseDurationDays("messages will disappear after 30 seconds"))
    }

    @Test
    fun `minutes parse to 0 days`() {
        assertEquals(0, parseDurationDays("disappearing messages — 5 minutes"))
    }

    @Test
    fun `text with no duration returns null`() {
        assertNull(parseDurationDays("disappearing messages turned on"))
        assertNull(parseDurationDays("vanish mode is on"))
        assertNull(parseDurationDays(""))
    }

    @Test
    fun `parsing is case insensitive`() {
        assertEquals(7, parseDurationDays("Messages Set To Disappear After 7 DAYS"))
    }

    @Test
    fun `singular and plural units both parse`() {
        assertEquals(1,  parseDurationDays("1 day"))
        assertEquals(7,  parseDurationDays("7 days"))
        assertEquals(7,  parseDurationDays("1 week"))
        assertEquals(14, parseDurationDays("2 weeks"))
    }

    // ── disappearingRiskLevel ─────────────────────────────────────────────────

    @Test
    fun `null duration (unknown) with no threat is HIGH`() {
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(null, hasExistingThreat = false))
    }

    @Test
    fun `null duration (unknown) with threat is HIGH`() {
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(null, hasExistingThreat = true))
    }

    @Test
    fun `short timer with no threat is HIGH`() {
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(0, hasExistingThreat = false))
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(1, hasExistingThreat = false))
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(7, hasExistingThreat = false))
    }

    @Test
    fun `short timer with threat is HIGH`() {
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(1, hasExistingThreat = true))
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(7, hasExistingThreat = true))
    }

    @Test
    fun `long timer with no threat is suppressed`() {
        assertNull(disappearingRiskLevel(8,   hasExistingThreat = false))
        assertNull(disappearingRiskLevel(28,  hasExistingThreat = false))
        assertNull(disappearingRiskLevel(90,  hasExistingThreat = false))
        assertNull(disappearingRiskLevel(365, hasExistingThreat = false))
    }

    @Test
    fun `long timer with existing threat is MEDIUM amplifier`() {
        assertEquals(RiskLevel.MEDIUM, disappearingRiskLevel(8,   hasExistingThreat = true))
        assertEquals(RiskLevel.MEDIUM, disappearingRiskLevel(90,  hasExistingThreat = true))
        assertEquals(RiskLevel.MEDIUM, disappearingRiskLevel(365, hasExistingThreat = true))
    }

    @Test
    fun `boundary at threshold is short (HIGH)`() {
        assertEquals(RiskLevel.HIGH, disappearingRiskLevel(DISAPPEARING_SHORT_TIMER_THRESHOLD_DAYS, hasExistingThreat = false))
    }

    @Test
    fun `boundary one above threshold is long`() {
        assertNull(disappearingRiskLevel(DISAPPEARING_SHORT_TIMER_THRESHOLD_DAYS + 1, hasExistingThreat = false))
    }
}
