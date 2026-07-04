package com.agenthita.app.detection

import com.agenthita.app.consent.UserCategory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the address-disclosure boost in [RiskScorer]:
 * a child or adolescent sharing their location in a [USER] message should
 * produce at least a MEDIUM GROOMING or LURING result. Adults, phone-number-only
 * shares, and [CONTACT]-side disclosures must not trigger the boost.
 */
class AddressDisclosureTest {

    private class FakeClassifier : Classifier {
        override val isLoaded = false
        override fun classify(text: String, context: List<String>, ageHint: String?) = null
    }

    private fun scorer(userCategory: UserCategory) =
        RiskScorer(FakeClassifier(), userCategory)

    private fun childScorer()      = scorer(UserCategory.CHILD)
    private fun adolescentScorer() = scorer(UserCategory.ADOLESCENT)
    private fun adultScorer()      = scorer(UserCategory.SELF_PROTECTING_ADULT)

    private fun List<DetectionResult>.disclosureResult() =
        firstOrNull {
            (it.category == HarmCategory.GROOMING || it.category == HarmCategory.LURING) &&
            it.signals.any { s -> s.signal == "location_disclosure" }
        }

    // ── Should trigger ────────────────────────────────────────────────────────

    @Test
    fun `child 'i live at' phrase triggers MEDIUM grooming or luring`() {
        val results = childScorer().score("[USER]: i live at 45 Maple Street")
        val result = results.disclosureResult()
        assertNotNull("Expected location_disclosure for child 'i live at'", result)
        assertTrue(result!!.riskLevel >= RiskLevel.MEDIUM)
    }

    @Test
    fun `child 'my address is' phrase triggers MEDIUM`() {
        val results = childScorer().score("[USER]: my address is 12 Pine Avenue")
        assertNotNull(results.disclosureResult())
    }

    @Test
    fun `adolescent 'come to my house' triggers MEDIUM`() {
        val results = adolescentScorer().score("[USER]: come to my house after school ok")
        val result = results.disclosureResult()
        assertNotNull("Expected location_disclosure for adolescent 'come to my house'", result)
        assertTrue(result!!.riskLevel >= RiskLevel.MEDIUM)
    }

    @Test
    fun `child 'come to my place' triggers MEDIUM`() {
        val results = childScorer().score("[USER]: come to my place i'm alone")
        assertNotNull(results.disclosureResult())
    }

    @Test
    fun `child structural street address triggers MEDIUM`() {
        val results = childScorer().score("[USER]: 23 Oak Road is where i am")
        assertNotNull("Structural address pattern should trigger for child", results.disclosureResult())
    }

    @Test
    fun `child 'here is my address' triggers MEDIUM`() {
        val results = childScorer().score("[USER]: here is my address so you can find me")
        assertNotNull(results.disclosureResult())
    }

    @Test
    fun `child 'here's my address' contraction triggers MEDIUM`() {
        val results = childScorer().score("[USER]: here's my address 8 Birch Court")
        assertNotNull(results.disclosureResult())
    }

    @Test
    fun `score is at least 0_70 for child address disclosure`() {
        val results = childScorer().score("[USER]: my address is 5 Elm Drive")
        val result = results.disclosureResult()
        assertNotNull(result)
        assertTrue("Score must be at least 0.70 to clear MEDIUM threshold", result!!.score >= 0.70f)
    }

    @Test
    fun `disclosure fires in mixed conversation with contact messages`() {
        val text = "[CONTACT]: where do you live?\n[USER]: i live at 7 Cedar Lane"
        val results = childScorer().score(text)
        assertNotNull("Disclosure should fire even alongside [CONTACT] messages", results.disclosureResult())
    }

    @Test
    fun `adolescent 'my location is' triggers MEDIUM`() {
        val results = adolescentScorer().score("[USER]: my location is 99 Willow Boulevard")
        assertNotNull(results.disclosureResult())
    }

    @Test
    fun `child multiple avenue address pattern triggers`() {
        val results = childScorer().score("[USER]: 100 Sunset Avenue, third floor")
        assertNotNull(results.disclosureResult())
    }

    // ── Should NOT trigger ────────────────────────────────────────────────────

    @Test
    fun `child sharing phone number only does not trigger`() {
        val results = childScorer().score("[USER]: my number is 9618122225 call me")
        assertNull("Phone-number-only must not trigger disclosure boost", results.disclosureResult())
    }

    @Test
    fun `child contact card name and phone does not trigger`() {
        val results = childScorer().score("[USER]: Name: Sarada Bikkina Phone: 9618122225")
        assertNull("Name and phone contact card must not trigger", results.disclosureResult())
    }

    @Test
    fun `adult sharing address does not trigger disclosure boost`() {
        val results = adultScorer().score("[USER]: my address is 10 Birch Street")
        assertNull("Disclosure boost must not apply to adults", results.disclosureResult())
    }

    @Test
    fun `contact sharing address does not trigger boost`() {
        val results = childScorer().score("[CONTACT]: i live at 99 Elm Street come visit")
        assertNull("Address in [CONTACT] message must not trigger boost", results.disclosureResult())
    }

    @Test
    fun `child vague 'i am at home' does not trigger`() {
        val results = childScorer().score("[USER]: i am at home right now")
        assertNull("Vague location without street address or phrase must not trigger", results.disclosureResult())
    }

    @Test
    fun `child 'i live in the city' does not trigger`() {
        // "i live in" is not in the phrase list — only "i live at"
        val results = childScorer().score("[USER]: i live in the city centre")
        assertNull("'i live in' without street address must not trigger", results.disclosureResult())
    }

    @Test
    fun `no user messages in text does not trigger`() {
        val results = childScorer().score("[CONTACT]: my address is 5 Oak Street, where are you?")
        assertNull("No [USER] lines means no disclosure", results.disclosureResult())
    }
}
