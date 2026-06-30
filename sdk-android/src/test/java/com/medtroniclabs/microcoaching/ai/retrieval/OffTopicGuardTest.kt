package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffTopicGuardTest {

    private val gazetteer = setOf(
        "hypertension", "blood pressure", "bp",
        "diarrhoea", "loose stool",
        "anaemia", "haemoglobin",
        "ডায়রিয়া", "রক্তচাপ", "বুক",
    )

    private fun chunk(title: String? = null, body: String? = null) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = "fam1",
        positionalId = 0,
        titleEn = title,
        bodyEn = body,
        titleBn = null,
        bodyBn = null,
        score = 1f,
    )

    @Test
    fun `null top hit is unanswerable`() {
        assertTrue(OffTopicGuard.isClearlyUnanswerable("anything", null, gazetteer))
    }

    @Test
    fun `BP query matched to BP chunk passes`() {
        val top = chunk(title = "Hypertension threshold", body = "BP above 140/90 is high.")
        assertFalse(OffTopicGuard.isClearlyUnanswerable("low BP 90/60", top, gazetteer))
    }

    @Test
    fun `BP query matched to diarrhoea chunk is unanswerable`() {
        // The diarrhoea-bleed scenario: BM25 returned a diarrhoea card for a BP
        // question. The guard must catch this even if the score was high.
        val top = chunk(title = "Diarrhoea treatment", body = "ORS for loose stool and dehydration.")
        assertTrue(OffTopicGuard.isClearlyUnanswerable("low BP 90/60", top, gazetteer))
    }

    @Test
    fun `query with no clinical tokens passes through (L1 handles it)`() {
        // L1 is the right gate for "weather today" — this guard must not double-refuse.
        val top = chunk(title = "Hypertension", body = "BP")
        assertFalse(OffTopicGuard.isClearlyUnanswerable("weather today", top, gazetteer))
    }

    @Test
    fun `bangla query matched to bangla chunk passes`() {
        val top = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "fam1", positionalId = 0,
            titleEn = null, bodyEn = null,
            titleBn = "ডায়রিয়ার চিকিৎসা",
            bodyBn = "শিশুকে ORS দিন।",
            score = 1f,
        )
        assertFalse(OffTopicGuard.isClearlyUnanswerable("ডায়রিয়া হলে কী করব", top, gazetteer))
    }
}
