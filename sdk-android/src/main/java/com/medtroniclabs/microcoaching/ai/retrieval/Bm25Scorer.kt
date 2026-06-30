package com.medtroniclabs.microcoaching.ai.retrieval

import kotlin.math.ln

/**
 * Okapi BM25 scorer for a fixed corpus of tokenised documents.
 *
 * Builds an inverted index once at construction, then exposes O(|query|) scoring
 * per document and an O(N log K) top-K helper. Pure-Kotlin, no Lucene dependency.
 *
 * Tuning knobs match the chat_plan.md B2 spec: `k1 = 1.5`, `b = 0.75`. These are
 * Okapi defaults and sane for the ≤ 200-chunk pilot corpus.
 */
class Bm25Scorer(
    private val documents: List<List<String>>,
    private val k1: Float = 1.5f,
    private val b: Float = 0.75f,
) {

    private val docLengths: IntArray = IntArray(documents.size) { documents[it].size }
    private val avgDocLen: Float = if (documents.isEmpty()) 0f else docLengths.average().toFloat()

    /** term → list of (docId, termFrequency). */
    private val invertedIndex: Map<String, List<Posting>> = buildIndex()

    /** term → idf (precomputed). */
    private val idf: Map<String, Float> = buildIdf()

    private data class Posting(val docId: Int, val tf: Int)

    private fun buildIndex(): Map<String, List<Posting>> {
        val tmp = HashMap<String, MutableList<Posting>>()
        documents.forEachIndexed { docId, tokens ->
            val tfMap = HashMap<String, Int>()
            for (t in tokens) tfMap[t] = (tfMap[t] ?: 0) + 1
            for ((term, tf) in tfMap) {
                tmp.getOrPut(term) { mutableListOf() }.add(Posting(docId, tf))
            }
        }
        return tmp
    }

    private fun buildIdf(): Map<String, Float> {
        val n = documents.size.toFloat()
        val out = HashMap<String, Float>(invertedIndex.size)
        for ((term, postings) in invertedIndex) {
            val df = postings.size.toFloat()
            // Classic Okapi BM25 idf with the `+0.5` smoothing on both sides.
            // ln((N - df + 0.5) / (df + 0.5) + 1) is the BM25Plus variant that
            // stays positive even when df > N/2 — safer for tiny corpora.
            out[term] = ln((n - df + 0.5f) / (df + 0.5f) + 1f)
        }
        return out
    }

    /**
     * Score [docId] against [queryTokens]. Higher is more relevant.
     *
     * BM25 formula: Σ idf(t) · ((k1 + 1) · tf) / (tf + k1 · (1 − b + b · |d| / avgdl))
     */
    fun score(queryTokens: List<String>, docId: Int): Float =
        scoreWeighted(queryTokens.distinct().associateWith { 1f }, docId)

    /**
     * Weighted variant: each query term contributes `weight × bm25(term, doc)`.
     * Used by [ModuleKnowledgeIndex] so synonym/abbreviation EXPANSION terms can
     * be down-weighted relative to the words the CHW actually typed — full-weight
     * expansion is how a "low BP 90/60" question ends up ranking the hypertension
     * module top (the expansion vocabulary outweighs the original query).
     */
    fun scoreWeighted(termWeights: Map<String, Float>, docId: Int): Float {
        if (termWeights.isEmpty() || avgDocLen == 0f) return 0f
        val dl = docLengths[docId].toFloat()
        val normalization = 1f - b + b * (dl / avgDocLen)

        var sum = 0f
        for ((term, weight) in termWeights) {
            if (weight <= 0f) continue
            val termIdf = idf[term] ?: continue
            val tf = invertedIndex[term]?.firstOrNull { it.docId == docId }?.tf?.toFloat() ?: continue
            sum += weight * termIdf * ((k1 + 1f) * tf) / (tf + k1 * normalization)
        }
        return sum
    }

    /**
     * Number of indexed documents containing [term]. Exposed so the query
     * expander can distinguish a *vocabulary bridge* (the CHW's term does not
     * exist anywhere in the corpus → expansion carries full weight) from an
     * *alias* of a term the corpus already covers (expansion is only a hint →
     * down-weighted).
     */
    fun documentFrequency(term: String): Int = invertedIndex[term]?.size ?: 0

    /** Top-K most relevant documents, sorted by descending score. */
    fun topK(queryTokens: List<String>, k: Int): List<ScoredDoc> =
        topKWeighted(queryTokens.distinct().associateWith { 1f }, k)

    /** Weighted [topK] — see [scoreWeighted]. */
    fun topKWeighted(termWeights: Map<String, Float>, k: Int): List<ScoredDoc> {
        if (k <= 0 || documents.isEmpty()) return emptyList()
        return documents.indices
            .map { ScoredDoc(it, scoreWeighted(termWeights, it)) }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(k)
    }

    data class ScoredDoc(val docId: Int, val score: Float)
}
