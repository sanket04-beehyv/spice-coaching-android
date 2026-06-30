package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * Phase-0 garbage guard for the offline retrieval paths
 * (`ChatViewModel.handleLocalGemmaMessage` and `handleLowEndMessage`).
 *
 * Closes the failure mode where BM25 latches onto a stop-word or generic verb in
 * the query and returns a top hit that has *zero* clinical-token overlap with what
 * the CHW actually asked. The model then dutifully answers about the wrong topic.
 *
 * Intentionally LOOSE: any single shared clinical term is enough to pass. The
 * tuned refusal floor (top1−top2 margin / normalized score, calibrated against the
 * benchmark distributions) is Phase 2. This guard only exists to close the
 * "confident wrong card" hole while we measure.
 *
 * `clinicalTerms` is the same gazetteer used by [ScopeClassifier] — static seed
 * terms plus dynamically-harvested module domain / subdomain / title words. Reusing
 * it keeps the guard's vocabulary in lockstep with what L1 considers in-scope.
 */
object OffTopicGuard {

    /**
     * True when [topHit] is so off-topic relative to [query] that we should refuse
     * rather than feed the chunk to the LLM (or surface it on the low-end path).
     *
     *  - [topHit] absent → refuse (no grounding to compare against).
     *  - query has no clinical tokens → pass (L1 was advisory; don't double-refuse
     *    here on top of a paid LLM call).
     *  - query and chunk share at least one clinical term → pass.
     *  - otherwise → refuse.
     */
    fun isClearlyUnanswerable(
        query: String,
        topHit: GroundingChunk?,
        clinicalTerms: Set<String>,
    ): Boolean {
        if (topHit == null) return true
        val queryClinical = clinicalTermsIn(query, clinicalTerms)
        if (queryClinical.isEmpty()) return false
        val chunkText = listOfNotNull(topHit.titleBn, topHit.bodyBn, topHit.titleEn, topHit.bodyEn)
            .joinToString(" ")
        val chunkClinical = clinicalTermsIn(chunkText, clinicalTerms)
        return queryClinical.intersect(chunkClinical).isEmpty()
    }

    /**
     * Subset of [clinicalTerms] that is genuinely present in [text]. Multi-word terms
     * are matched as phrases (substring on the full lowercased text); single-word
     * terms are matched against the tokenizer's output to avoid the "to" ⊂ "stool"
     * class of false positive that ClinicalSynonymMap's prior implementation had.
     */
    private fun clinicalTermsIn(text: String, clinicalTerms: Set<String>): Set<String> {
        if (text.isBlank() || clinicalTerms.isEmpty()) return emptySet()
        val lowered = text.lowercase()
        val tokens = BanglaTokenizer.tokenize(text).toSet()
        return clinicalTerms.filterTo(mutableSetOf()) { term ->
            when {
                term.isBlank() -> false
                term.contains(' ') -> lowered.contains(term)
                term.all { it.code < 128 } -> term in tokens
                else -> tokens.any { tok -> tok.startsWith(term) }
            }
        }
    }
}
