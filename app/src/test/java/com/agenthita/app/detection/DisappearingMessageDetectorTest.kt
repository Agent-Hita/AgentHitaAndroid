package com.agenthita.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DisappearingMessageDetectorTest {

    private lateinit var detector: DisappearingMessageDetector

    @Before
    fun setUp() {
        detector = DisappearingMessageDetector()
    }

    // --- Confirmed activation → HIGH ---

    @Test
    fun `system notification disappearing messages turned on scores HIGH`() {
        val result = detector.analyze("disappearing messages turned on")
        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.score >= 0.90f)
    }

    @Test
    fun `vanish mode on notification scores HIGH`() {
        val result = detector.analyze("Vanish mode is on")
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `messages set to disappear notification scores HIGH`() {
        val result = detector.analyze("Messages are now set to disappear")
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `self destruct timer notification scores HIGH`() {
        val result = detector.analyze("self-destruct timer has been set to 24 hours")
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `snapchat snaps and chats will delete notification scores HIGH`() {
        val result = detector.analyze("snaps and chats will delete after 24 hours")
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    // --- Activation requests → MEDIUM ---

    @Test
    fun `turn on disappearing messages request scores MEDIUM`() {
        val result = detector.analyze("hey can you turn on disappearing messages?")
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
    }

    @Test
    fun `lets chat on snapchat request scores MEDIUM`() {
        val result = detector.analyze("let's chat on snapchat instead")
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
    }

    @Test
    fun `switch to snap so it deletes request scores MEDIUM`() {
        val result = detector.analyze("switch to snap so it deletes automatically")
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
    }

    @Test
    fun `turn on vanish mode request scores MEDIUM`() {
        val result = detector.analyze("you should turn on vanish mode")
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
    }

    // --- View-once signals → MEDIUM ---

    @Test
    fun `view once photo scores MEDIUM`() {
        val result = detector.analyze("I sent you a view once photo")
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
    }

    @Test
    fun `will disappear after you view scores MEDIUM`() {
        val result = detector.analyze("this will disappear after you view it")
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
    }

    // --- Hiding intent signals → LOW ---

    @Test
    fun `delete this after reading scores LOW`() {
        val result = detector.analyze("delete this after reading ok?")
        assertEquals(RiskLevel.LOW, result.riskLevel)
    }

    @Test
    fun `no record of this scores LOW`() {
        val result = detector.analyze("there should be no record of this")
        assertEquals(RiskLevel.LOW, result.riskLevel)
    }

    @Test
    fun `dont screenshot scores LOW`() {
        val result = detector.analyze("please don't screenshot this")
        assertEquals(RiskLevel.LOW, result.riskLevel)
    }

    // --- Clean text → NONE ---

    @Test
    fun `normal greeting scores NONE`() {
        val result = detector.analyze("Hey! How's your day going?")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `talk about memories scores NONE`() {
        val result = detector.analyze("I love looking back at our old messages")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `homework discussion scores NONE`() {
        val result = detector.analyze("Did you finish the math homework yet?")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    // --- Case insensitivity ---

    @Test
    fun `signal detection is case insensitive`() {
        val result = detector.analyze("DISAPPEARING MESSAGES TURNED ON")
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    // --- Signal metadata ---

    @Test
    fun `confirmed activation populates signal type`() {
        val result = detector.analyze("disappearing messages are on")
        assertTrue(result.signals.any { it.signal == "activation_confirmed" })
    }

    @Test
    fun `activation request populates signal type`() {
        val result = detector.analyze("message me on snap")
        assertTrue(result.signals.any { it.signal == "activation_request" })
    }

    @Test
    fun `category is DISAPPEARING_MESSAGES`() {
        val result = detector.analyze("turn on vanish mode")
        assertEquals(HarmCategory.DISAPPEARING_MESSAGES, result.category)
    }
}
