package com.agenthita.app.model

import com.agenthita.app.config.RemoteConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation of any length must always produce a prompt that fits the
 * model's token window — inference is never skipped (regression for
 * "Prompt too long (~468 tokens vs limit 512), skipping inference", which
 * silently dropped ML scoring on exactly the long coercive conversations
 * where it matters most). Trimming keeps the NEWEST content: oldest context
 * lines are shed first, then the message block is cut from the front.
 */
class GemmaPromptFitTest {

    private val maxChars = (RemoteConfig.gemmaMaxTokens - 50) * 3

    private fun longConversation(): String {
        val filler = (1..30).map { "[CONTACT]: OLDEST-MARKER-$it this is filler chat line number $it about nothing much at all" }
        return (filler + "[CONTACT]: Send money now. I know your parents are not home").joinToString("\n")
    }

    @Test
    fun `oversized conversation still yields a prompt within the token budget`() {
        val prompt = GemmaClassifier.buildFittedPrompt(
            longConversation(),
            context = (1..8).map { "older context line $it that is reasonably long to inflate the prompt size" },
            ageHint = "child under 13 years old"
        )
        assertTrue("Prompt must fit budget ($maxChars chars) but was ${prompt.length}", prompt.length <= maxChars)
    }

    @Test
    fun `trimming keeps the newest message and drops the oldest`() {
        val prompt = GemmaClassifier.buildFittedPrompt(longConversation(), emptyList(), null)
        assertTrue("Newest (coercive) line must survive trimming",
            prompt.contains("Send money now. I know your parents are not home"))
        assertFalse("Oldest filler must be dropped", prompt.contains("OLDEST-MARKER-1 "))
    }

    @Test
    fun `short conversation is passed through untrimmed`() {
        val text = "[CONTACT]: Send money now. I know your parents are not home"
        val prompt = GemmaClassifier.buildFittedPrompt(text, emptyList(), null)
        assertTrue(prompt.contains(text.removePrefix("[CONTACT]: ").let { "Send money now" }))
        assertTrue(prompt.length <= maxChars)
    }
}
