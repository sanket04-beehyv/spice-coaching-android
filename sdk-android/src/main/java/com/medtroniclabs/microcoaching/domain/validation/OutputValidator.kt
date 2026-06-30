package com.medtroniclabs.microcoaching.domain.validation

import com.medtroniclabs.microcoaching.ai.retrieval.BanglaTokenizer
import com.medtroniclabs.microcoaching.ai.retrieval.ClinicalSynonymMap
import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates AI-generated content before it is displayed to the CHW.
 *
 * Applies to BOTH online (backend Gemini) AND edge (on-device Gemma) responses.
 * On failure, [FallbackSelector] serves the pre-authored Bangla card instead.
 *
 * Block-list rules follow DDD v2 Section 7.9.
 */
class OutputValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val failureReason: String? = null,
    )

    /** Validates a structured JSON coaching card response from the AI. */
    fun validate(jsonResponse: String): ValidationResult {
        if (jsonResponse.isBlank()) return ValidationResult(false, "empty_response")

        val obj = try {
            Json.parseToJsonElement(jsonResponse) as? JsonObject
                ?: return ValidationResult(false, "not_json_object")
        } catch (_: Exception) {
            return ValidationResult(false, "invalid_json")
        }

        val title = obj["title"]?.jsonPrimitive?.content ?: ""
        val body = obj["body"]?.jsonPrimitive?.content ?: ""
        val blocked = containsBlockedPhrase("$title $body".lowercase())
        return if (blocked != null) ValidationResult(false, "blocked:$blocked") else ValidationResult(true)
    }

    /** Validates a plain text fragment (e.g., edge-mode output before JSON wrapping). */
    fun validateText(text: String): ValidationResult {
        if (text.isBlank()) return ValidationResult(false, "empty_text")
        val blocked = containsBlockedPhrase(text.lowercase())
        return if (blocked != null) ValidationResult(false, "blocked:$blocked") else ValidationResult(true)
    }

    /**
     * L3 sentinel that the hardened chat system prompt asks Gemma to emit when it
     * cannot answer from the Reference content. Intercepted client-side so we serve
     * the canned BN refusal instead of leaking the sentinel to the CHW.
     */
    fun isNoGroundSentinel(response: String): Boolean =
        REFUSE_NO_GROUND_SENTINEL in response

    /**
     * Sentinel the open-scope system prompt asks Gemma to emit when the question
     * falls outside SPICE's clinical scope (weather, sports, etc.). Intercepted
     * client-side so the CHW sees the same canned scope-refusal copy used by the
     * L1 keyword classifier in Strict mode.
     */
    fun isOutOfScopeSentinel(response: String): Boolean =
        REFUSE_OUT_OF_SCOPE_SENTINEL in response

    /**
     * L4 validator for chat responses (chat_plan.md §B4).
     *
     * Stricter than [validateText] because the response is produced under a
     * hardened "use only the Reference content" directive. We accept drugs and
     * dosages **only** when the exact term also appears in one of the [candidates]
     * — that's the per-query allow-list. Anything outside the candidate set is
     * treated as fabricated.
     *
     * Length cap is enforced at the word level on the English Gemma output —
     * verbose replies are the strongest hallucination signal at 1B parameters.
     */
    fun validateChatResponse(
        response: String,
        candidates: List<GroundingChunk>,
        maxWords: Int = 250,
        allowFreeText: Boolean = false,
        userQuestion: String? = null,
        enableDrugGuard: Boolean = true,
        enableDosageGuard: Boolean = true,
    ): ValidationResult {
        if (response.isBlank()) return ValidationResult(false, "empty_text")
        val lower = response.lowercase()

        // Reject the sentinel before any other check — it isn't user-facing content.
        if (REFUSE_NO_GROUND_SENTINEL in response) return ValidationResult(false, "no_ground_sentinel")

        // Length cap.
        val wordCount = response.split(WHITESPACE).count { it.isNotBlank() }
        if (wordCount > maxWords) return ValidationResult(false, "too_long:$wordCount")

        // Question echo — a 1B model that cannot synthesize an answer from the
        // references sometimes restates the question instead ("What should you
        // advise a pregnant woman with low BP…?" — verified 2026-06-11). An echo
        // passes the groundedness gate because question words legitimately
        // overlap the references, so it needs its own check. Rejecting routes
        // the turn to the clinician card-body fallback.
        if (userQuestion != null && isQuestionEcho(response, userQuestion)) {
            return ValidationResult(false, "question_echo")
        }

        // Diagnostic phrases are always blocked (mirrors validateText / validate).
        DIAGNOSTIC_PHRASES.firstOrNull { it in lower }?.let {
            return ValidationResult(false, "diagnostic:$it")
        }

        // Drug / dosage block-list only applies to the grounded path, where the
        // hardened prompt told the model to use ONLY the reference text. In the
        // open-scope path there are no candidates, so the rule has no meaning —
        // the safety caveat baked into the system prompt is what carries us.
        if (!allowFreeText) {
            val candidateText = candidates.joinToString(" ") {
                (it.bodyEn.orEmpty() + " " + it.bodyBn.orEmpty() + " " +
                    it.titleEn.orEmpty() + " " + it.titleBn.orEmpty() + " " +
                    it.explanationEn.orEmpty() + " " + it.explanationBn.orEmpty()).lowercase()
            }

            // Drug name (whole word) introduced that the grounding source doesn't carry.
            // Word-boundary match avoids substring false positives. Toggleable via
            // ChatTuning.enableDrugGuard — kept on by default as a clinical-safety net.
            if (enableDrugGuard) {
                DRUG_REGEXES.firstOrNull { rx ->
                    val m = rx.find(lower) ?: return@firstOrNull false
                    m.value !in candidateText
                }?.let {
                    return ValidationResult(false, "drug_not_in_source:${it.find(lower)!!.value}")
                }
            }
            // Number-adjacent dosage ("5 mg", "2 tablets") not present in the source.
            // Bare words like "the correct dose" or "one tablet" no longer trip the gate.
            // Toggleable via ChatTuning.enableDosageGuard — kept on by default.
            if (enableDosageGuard) {
                DOSAGE_REGEXES.firstOrNull { rx ->
                    val m = rx.find(response) ?: return@firstOrNull false
                    m.value.lowercase() !in candidateText
                }?.let {
                    return ValidationResult(false, "dosage_not_in_source")
                }
            }
        }

        return ValidationResult(true)
    }

    /**
     * Fraction of the response's content words that also appear in the grounding
     * chunks (titles + bodies, both languages). Stop-words are excluded on both
     * sides via [BanglaTokenizer.tokenizeQuery].
     *
     * The sentinel-based no-ground check (rule 2 of the hardened prompt) only
     * works when the model *recognises* the references don't cover the question.
     * When the references are thematically adjacent (newborn care vs breast
     * engorgement — the verified failure), a 1B model answers fluently from
     * pre-training instead; that answer scores near zero here, while a genuine
     * rephrasing of reference facts scores high (the reference vocabulary
     * survives paraphrase). Caller decides the floor — [ChatViewModel] refuses
     * below `GROUNDEDNESS_FLOOR` and traces the score on every grounded turn.
     *
     * Returns 1.0 for responses with fewer than [MIN_CONTENT_WORDS] content words
     * — too little signal to judge, and short confirmations shouldn't refuse.
     */
    fun groundednessScore(response: String, candidates: List<GroundingChunk>): Float {
        val responseTokens = BanglaTokenizer.tokenizeQuery(response)
            .filter { it.length >= 3 }
            .distinct()
        if (responseTokens.size < MIN_CONTENT_WORDS) return 1f
        // Include the linked quiz explanations — they are now part of the grounding
        // the model is allowed to answer from, so a faithful rephrasing of an
        // explanation must count as grounded.
        val referenceTokens = candidates.flatMap { chunk ->
            listOfNotNull(
                chunk.titleEn, chunk.bodyEn, chunk.titleBn, chunk.bodyBn,
                chunk.explanationEn, chunk.explanationBn,
            ).flatMap { BanglaTokenizer.tokenize(it) }
        }.toSet()
        if (referenceTokens.isEmpty()) return 1f
        // Synonym/concept-aware: a response token grounds if it appears literally in
        // the references OR shares a clinical concept with them (e.g. "anemia" ↔
        // "anaemia"). Raw literal overlap punished honest paraphrase and the BN↔EN
        // translation round-trip the prompt now actively encourages.
        val referenceConcepts = ClinicalSynonymMap.conceptsFor(referenceTokens)
        val grounded = responseTokens.count { token ->
            token in referenceTokens || ClinicalSynonymMap.sharesConcept(token, referenceConcepts)
        }
        return grounded.toFloat() / responseTokens.size
    }

    /**
     * True when [response] is essentially the user's question handed back: it is
     * interrogative (ends with "?") AND most of the question's content words
     * reappear in it. Both conditions are required — clinical answers that merely
     * reuse question vocabulary don't end with a question mark, and a genuinely
     * different question (low overlap) is not an echo. The comparison runs on the
     * English text that was actually in the prompt, so Bangla-mode turns (which
     * are translated to English before the LLM) are covered identically.
     */
    fun isQuestionEcho(response: String, userQuestion: String): Boolean {
        if (!response.trim().endsWith("?")) return false
        val questionTokens = BanglaTokenizer.tokenizeQuery(userQuestion).distinct()
        if (questionTokens.size < 2) return false
        val responseTokens = BanglaTokenizer.tokenize(response).toSet()
        val shared = questionTokens.count { it in responseTokens }
        return shared.toFloat() / questionTokens.size >= QUESTION_ECHO_OVERLAP
    }

    private fun containsBlockedPhrase(lowercaseText: String): String? {
        DIAGNOSTIC_PHRASES.forEach { if (lowercaseText.contains(it)) return "diagnostic:$it" }
        DRUG_REGEXES.firstOrNull { it.containsMatchIn(lowercaseText) }
            ?.let { return "drug:${it.find(lowercaseText)!!.value}" }
        DOSAGE_REGEXES.firstOrNull { it.containsMatchIn(lowercaseText) }
            ?.let { return "dosage" }
        return null
    }

    companion object {

        /** Sentinel the L3 hardened prompt asks the model to emit when it can't ground its answer. */
        const val REFUSE_NO_GROUND_SENTINEL = "[[REFUSE_NO_GROUND]]"

        /** Sentinel the open-scope prompt asks the model to emit when the question is out of clinical scope. */
        const val REFUSE_OUT_OF_SCOPE_SENTINEL = "[[REFUSE_OUT_OF_SCOPE]]"

        /**
         * Minimum response content words before [groundednessScore] judges at all.
         * Below this the score is a constant 1.0 (pass).
         */
        private const val MIN_CONTENT_WORDS = 4

        /**
         * Fraction of question content-words that must reappear in an
         * interrogative response for [isQuestionEcho] to flag it. The verified
         * echo scored 0.8 (4/5 tokens); answers that merely reuse clinical
         * vocabulary sit far lower and don't end with "?" anyway.
         */
        private const val QUESTION_ECHO_OVERLAP = 0.6f

        private val WHITESPACE = Regex("""\s+""")

        private val DIAGNOSTIC_PHRASES = listOf(
            "you have ", "you are diagnosed", "your diagnosis is", "you suffer from",
            "patient has been diagnosed", "you are suffering from",
            "আপনার আছে", "রোগ নির্ণয়", "আপনার রোগ হয়েছে",
        )
        private val DRUG_NAMES = listOf(
            "metformin", "insulin", "amlodipine", "enalapril", "losartan",
            "aspirin", "nifedipine", "atenolol", "glibenclamide", "lisinopril",
            "hydrochlorothiazide", "furosemide", "methyldopa", "labetalol",
            "glimepiride", "ramipril", "valsartan", "bisoprolol",
        )

        /** Whole-word drug matches — avoids substring false positives. */
        private val DRUG_REGEXES = DRUG_NAMES.map {
            Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE)
        }

        /**
         * Number-adjacent dosage patterns only — "5 mg", "10mg", "2 tablets". The
         * old bare-substring list (" mg", " dose ", " tablet") false-positived on
         * "the correct dose" / "one tablet", forcing correct grounded answers into
         * the verbatim card fallback. A dosage is only suspicious when it carries a
         * specific number not present in the source.
         */
        private val DOSAGE_REGEXES = listOf(
            Regex("""\d+\s?(mg|mcg|ml|units?|tablets?|capsules?|doses?|drops?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\d+\s?(মিগ্রা|মিলি|ট্যাবলেট|ইউনিট)"""),
        )
    }
}
