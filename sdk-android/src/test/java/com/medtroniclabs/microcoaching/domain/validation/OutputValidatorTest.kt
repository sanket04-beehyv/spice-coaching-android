package com.medtroniclabs.microcoaching.domain.validation

import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputValidatorTest {

    private val validator = OutputValidator()

    @Test
    fun `allowFreeText=true skips drug block-list when there are no candidates`() {
        val result = validator.validateChatResponse(
            response = "Take 5 mg of amlodipine once a day. Please confirm with your supervisor for the specific case.",
            candidates = emptyList(),
            allowFreeText = true,
        )
        assertTrue("expected pass in open-scope mode, got: ${result.failureReason}", result.isValid)
    }

    @Test
    fun `allowFreeText=false preserves legacy drug block-list`() {
        val result = validator.validateChatResponse(
            response = "Take 5 mg of amlodipine once a day.",
            candidates = emptyList(),
            allowFreeText = false,
        )
        assertFalse(result.isValid)
        assertEquals("drug_not_in_source:amlodipine", result.failureReason)
    }

    @Test
    fun `allowFreeText=true still blocks diagnostic phrases`() {
        val result = validator.validateChatResponse(
            response = "You have diabetes. Please confirm with your supervisor for the specific case.",
            candidates = emptyList(),
            allowFreeText = true,
        )
        assertFalse(result.isValid)
        assertTrue(
            "expected diagnostic block, got: ${result.failureReason}",
            result.failureReason?.startsWith("diagnostic:") == true,
        )
    }

    @Test
    fun `allowFreeText=true still enforces length cap`() {
        val long = (1..300).joinToString(" ") { "word" }
        val result = validator.validateChatResponse(
            response = long,
            candidates = emptyList(),
            allowFreeText = true,
            maxWords = 250,
        )
        assertFalse(result.isValid)
        assertTrue(
            "expected too_long, got: ${result.failureReason}",
            result.failureReason?.startsWith("too_long:") == true,
        )
    }

    @Test
    fun `isOutOfScopeSentinel detects the sentinel`() {
        assertTrue(validator.isOutOfScopeSentinel("[[REFUSE_OUT_OF_SCOPE]]"))
        assertTrue(validator.isOutOfScopeSentinel("Some prefix [[REFUSE_OUT_OF_SCOPE]] trailing"))
        assertFalse(validator.isOutOfScopeSentinel("normal answer"))
        assertFalse(validator.isOutOfScopeSentinel("[[REFUSE_NO_GROUND]]"))
    }

    @Test
    fun `grounded path still rejects drugs not in the candidate set`() {
        val chunk = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "hypertension-001",
            positionalId = 0,
            titleEn = "Hypertension counselling",
            bodyEn = "Encourage lifestyle changes such as low-salt diet and daily walks.",
            titleBn = null,
            bodyBn = null,
            score = 4.2f,
        )
        val result = validator.validateChatResponse(
            response = "Start metformin 500 mg twice daily.",
            candidates = listOf(chunk),
            allowFreeText = false,
        )
        assertFalse(result.isValid)
        assertEquals("drug_not_in_source:metformin", result.failureReason)
    }

    // ── groundednessScore: catches fluent answers that ignore the references ─

    private fun card(titleEn: String, bodyEn: String) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = "fam-1",
        positionalId = 0,
        titleEn = titleEn,
        bodyEn = bodyEn,
        titleBn = null,
        bodyBn = null,
        score = 5f,
    )

    @Test
    fun `hallucinated answer against adjacent references scores near zero`() {
        // The verified 2026-06-11 failure: breast-engorgement advice generated
        // from pre-training while the references were newborn-warmth cards. The
        // model never emitted [[REFUSE_NO_GROUND]] because the topic felt close.
        val references = listOf(
            card(
                "How to Keep Newborns Warm",
                "To keep newborns warm, they should be dried quickly after birth and wrapped properly. " +
                    "Their heads should be covered. Parents should be educated on these points.",
            ),
            card(
                "How to Give Advice",
                "First praise the mother for how well she is coping with the child and reinforce " +
                    "the good practices she is following.",
            ),
        )
        val hallucinated = "Breast engorgement can be managed by gently massaging and stimulating " +
            "your breasts and by using warm compresses and avoiding tight clothing."
        val score = validator.groundednessScore(hallucinated, references)
        assertTrue("hallucinated answer must score below 0.25, was $score", score < 0.25f)
    }

    @Test
    fun `honest paraphrase of reference facts scores high`() {
        val references = listOf(
            card(
                "Recognizing Danger Signs in Postpartum Women",
                "Danger signs include excessive bleeding of 100 milliliters or more, severe abdominal " +
                    "pain, fever of 100.4 degrees or higher, difficulty breathing, and mental stress.",
            ),
        )
        val paraphrase = "Watch for excessive bleeding, severe abdominal pain, fever above 100.4 " +
            "degrees, difficulty breathing, and signs of mental stress."
        val score = validator.groundednessScore(paraphrase, references)
        assertTrue("faithful paraphrase must score well above the floor, was $score", score > 0.5f)
    }

    @Test
    fun `very short responses are not judged`() {
        val references = listOf(card("Anything", "Unrelated body text entirely."))
        val score = validator.groundednessScore("Yes, refer now.", references)
        assertEquals(1f, score, 0.001f)
    }

    // ── isQuestionEcho: model restating the question instead of answering ────

    @Test
    fun `verified echo from field log is flagged`() {
        // 2026-06-11 15:30 turn: the model handed the question back verbatim-ish.
        assertTrue(
            validator.isQuestionEcho(
                response = "What should you advise a pregnant woman with low BP (90/60 mmHg) " +
                    "regarding her blood pressure management?",
                userQuestion = "What should I advise to a PW with Low BP 90/60?",
            ),
        )
    }

    @Test
    fun `echo rejection routes to card fallback through validateChatResponse`() {
        val result = validator.validateChatResponse(
            response = "What should you advise a pregnant woman with low BP 90/60?",
            candidates = emptyList(),
            allowFreeText = true,
            userQuestion = "What should I advise to a PW with Low BP 90/60?",
        )
        assertFalse(result.isValid)
        assertEquals("question_echo", result.failureReason)
    }

    @Test
    fun `answer reusing question vocabulary is not an echo`() {
        // Heavy vocabulary overlap but declarative — must pass.
        assertFalse(
            validator.isQuestionEcho(
                response = "A BP of 90/60 in a pregnant woman is a high-risk criterion. " +
                    "Advise her to attend the facility and consult your supervisor.",
                userQuestion = "What should I advise to a PW with Low BP 90/60?",
            ),
        )
    }

    @Test
    fun `interrogative response with little question overlap is not an echo`() {
        assertFalse(
            validator.isQuestionEcho(
                response = "Did you also measure her haemoglobin level?",
                userQuestion = "What should I advise to a PW with Low BP 90/60?",
            ),
        )
    }

    // ── L4 dosage: number-adjacent only (no more bare-substring false positives) ─

    @Test
    fun `bare dose and tablet words without a number are not blocked`() {
        val chunk = card("Adherence", "Counsel the patient to take the correct dose daily and never skip a tablet.")
        val result = validator.validateChatResponse(
            response = "Advise the patient to take the correct dose every day and never skip a tablet.",
            candidates = listOf(chunk),
            allowFreeText = false,
        )
        assertTrue("non-numeric dose/tablet words must not trip the gate: ${result.failureReason}", result.isValid)
    }

    @Test
    fun `number-adjacent dose not present in source is blocked`() {
        val chunk = card("Adherence", "Encourage daily medication adherence.")
        val result = validator.validateChatResponse(
            response = "Give 5 mg every morning.",
            candidates = listOf(chunk),
            allowFreeText = false,
        )
        assertFalse(result.isValid)
        assertEquals("dosage_not_in_source", result.failureReason)
    }

    @Test
    fun `number-adjacent dose present in source passes`() {
        val chunk = card("Regimen", "The new patient regimen lasts 6 months with a 5 mg daily dose.")
        val result = validator.validateChatResponse(
            response = "The regimen lasts 6 months at 5 mg daily.",
            candidates = listOf(chunk),
            allowFreeText = false,
        )
        assertTrue("a dose present in the source must pass: ${result.failureReason}", result.isValid)
    }

    @Test
    fun `enableDrugGuard=false lets an out-of-source drug through`() {
        val chunk = card("Hypertension counselling", "Encourage lifestyle changes such as a low-salt diet.")
        val result = validator.validateChatResponse(
            response = "Start metformin twice daily.",
            candidates = listOf(chunk),
            allowFreeText = false,
            enableDrugGuard = false,
        )
        assertTrue("drug guard off must pass: ${result.failureReason}", result.isValid)
    }

    @Test
    fun `enableDosageGuard=false lets an out-of-source dose through`() {
        val chunk = card("Adherence", "Encourage daily medication adherence.")
        val result = validator.validateChatResponse(
            response = "Give 5 mg every morning.",
            candidates = listOf(chunk),
            allowFreeText = false,
            enableDosageGuard = false,
        )
        assertTrue("dosage guard off must pass: ${result.failureReason}", result.isValid)
    }

    @Test
    fun `disabling one guard leaves the other active`() {
        val chunk = card("Hypertension counselling", "Encourage lifestyle changes such as a low-salt diet.")
        // Drug guard off, dosage guard still on → the number-adjacent dose still blocks.
        val result = validator.validateChatResponse(
            response = "Start metformin 5 mg twice daily.",
            candidates = listOf(chunk),
            allowFreeText = false,
            enableDrugGuard = false,
            enableDosageGuard = true,
        )
        assertFalse(result.isValid)
        assertEquals("dosage_not_in_source", result.failureReason)
    }

    // ── groundedness: synonym-aware + explanation-aware ──────────────────────

    @Test
    fun `synonym spellings of reference terms count as grounded`() {
        val chunk = card("Anaemia", "Severe anaemia requires haemoglobin testing and referral.")
        // American spellings "anemia"/"hemoglobin" are synonyms, not literal matches.
        val answer = "For severe anemia, arrange hemoglobin testing and refer the patient."
        val score = validator.groundednessScore(answer, listOf(chunk))
        assertTrue("synonym spellings must count as grounded, was $score", score > 0.5f)
    }

    @Test
    fun `linked quiz explanation counts toward groundedness`() {
        val chunk = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "fam",
            positionalId = 0,
            titleEn = "Suspecting TB",
            bodyEn = "Begin treatment.",
            titleBn = null,
            bodyBn = null,
            score = 5f,
            explanationEn = "If TB is suspected, start treatment immediately and refer the patient quickly to a doctor.",
        )
        val answer = "If TB is suspected, start treatment right away and refer the patient to a doctor quickly."
        val score = validator.groundednessScore(answer, listOf(chunk))
        assertTrue("a paraphrase of the linked explanation must score well, was $score", score > 0.5f)
    }
}
