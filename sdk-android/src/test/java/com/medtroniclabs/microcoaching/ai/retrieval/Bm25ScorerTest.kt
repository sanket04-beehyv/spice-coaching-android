package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [Bm25Scorer] — the field scorers in [ModuleKnowledgeIndex]
 * all depend on this behaviour, especially the empty-corpus no-op (an empty field
 * produces an empty scorer that must score 0 without throwing).
 */
class Bm25ScorerTest {

    @Test
    fun `empty corpus scores zero and does not throw`() {
        val scorer = Bm25Scorer(emptyList())
        assertEquals(0f, scorer.scoreWeighted(mapOf("anything" to 1f), 0), 0f)
        assertTrue(scorer.topKWeighted(mapOf("anything" to 1f), 3).isEmpty())
    }

    @Test
    fun `topKWeighted ranks the document containing the rarer query term`() {
        val docs = listOf(
            listOf("common", "common", "alpha"),
            listOf("common", "beta", "gamma"),
        )
        val scorer = Bm25Scorer(docs)
        val top = scorer.topKWeighted(mapOf("beta" to 1f), 2)
        assertTrue(top.isNotEmpty())
        assertEquals("doc 1 holds the queried term", 1, top.first().docId)
    }

    @Test
    fun `documentFrequency counts documents containing the term`() {
        val scorer = Bm25Scorer(listOf(listOf("a", "b"), listOf("a", "c")))
        assertEquals(2, scorer.documentFrequency("a"))
        assertEquals(1, scorer.documentFrequency("b"))
        assertEquals(0, scorer.documentFrequency("z"))
    }

    @Test
    fun `term weight scales its contribution`() {
        val docs = listOf(listOf("alpha", "beta"))
        val scorer = Bm25Scorer(docs)
        val full = scorer.scoreWeighted(mapOf("alpha" to 1.0f), 0)
        val hint = scorer.scoreWeighted(mapOf("alpha" to 0.4f), 0)
        assertTrue("full weight must out-score a 0.4 hint", full > hint)
        assertEquals(full * 0.4f, hint, 1e-4f)
    }
}
