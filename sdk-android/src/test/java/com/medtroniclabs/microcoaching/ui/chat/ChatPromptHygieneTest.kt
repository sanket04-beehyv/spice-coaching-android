package com.medtroniclabs.microcoaching.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt-hygiene helpers added after the 2026-06-11 field log:
 *
 *  - [dropRefusalExchanges]: refusal turns replayed as history both waste the
 *    token window and prime a 1B model to refuse again.
 *  - [ChatViewModel.trimToCompleteSentence]: when the session token window
 *    closes mid-sentence (`sawEndOfTurn=false`), the dangling fragment must
 *    never reach the CHW.
 */
class ChatPromptHygieneTest {

    private fun user(text: String) = ChatMessage(sessionId = "s", role = ChatRole.USER, text = text)

    private fun assistant(text: String, outcome: String? = null) = ChatMessage(
        sessionId = "s",
        role = ChatRole.ASSISTANT,
        text = text,
        meta = outcome?.let { ChatMessageMeta(outcome = it) },
    )

    // ── dropRefusalExchanges ──────────────────────────────────────────────────

    @Test
    fun `refusal pair is removed from model-facing history`() {
        val history = listOf(
            user("What should I advise to a PW with Low BP 90/60?"),
            assistant("I don't have this in my training material yet.", outcome = "refused_no_ground"),
            user("How can breast engorgement be managed?"),
            assistant("Support the mother with correct techniques.", outcome = "served_grounded"),
        )
        val filtered = dropRefusalExchanges(history)
        assertEquals(2, filtered.size)
        assertEquals("How can breast engorgement be managed?", filtered[0].text)
        assertEquals(ChatRole.ASSISTANT, filtered[1].role)
    }

    @Test
    fun `served and metaless turns pass through untouched`() {
        val history = listOf(
            user("q1"),
            assistant("a1", outcome = "served_grounded"),
            user("q2"),
            assistant("a2"), // restored history — no meta
        )
        assertEquals(history, dropRefusalExchanges(history))
    }

    @Test
    fun `consecutive refusals are all removed with their questions`() {
        val history = listOf(
            user("q1"),
            assistant("refusal", outcome = "refused_no_ground"),
            user("q1 again"),
            assistant("refusal", outcome = "refused_scope"),
        )
        assertTrue(dropRefusalExchanges(history).isEmpty())
    }

    // ── trimToCompleteSentence ────────────────────────────────────────────────

    @Test
    fun `truncated tail after the last sentence is dropped`() {
        val truncated = "Breast engorgement can be managed with warm compresses. " +
            "Pain can be alleviated by"
        assertEquals(
            "Breast engorgement can be managed with warm compresses.",
            ChatViewModel.trimToCompleteSentence(truncated),
        )
    }

    @Test
    fun `fragment with no complete sentence trims to empty for card fallback`() {
        // The verified danger-signs failure: 14 tokens of output, no terminator.
        val fragment = "There are several danger signs that need be recognized when"
        assertEquals("", ChatViewModel.trimToCompleteSentence(fragment))
    }

    @Test
    fun `bangla danda counts as a sentence terminator`() {
        val truncated = "নবজাতককে দ্রুত শুকিয়ে মুড়িয়ে রাখুন। এরপর মাথা ঢেকে"
        assertEquals(
            "নবজাতককে দ্রুত শুকিয়ে মুড়িয়ে রাখুন।",
            ChatViewModel.trimToCompleteSentence(truncated),
        )
    }
}
