package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalHintOverlayTest {

    @Test
    fun `mergeOverlayFromJson injects retrieval hints into the target card`() {
        val module = moduleEntityFixture(
            moduleId = "m1",
            moduleFamilyId = "fam-overlay",
            cardsJson = """
                [
                  {"title_en":"Overview","body_en":"Intro."},
                  {"title_en":"Target","body_en":"Detail."}
                ]
            """.trimIndent(),
        )
        val merged = RetrievalHintOverlay.mergeOverlayFromJson(
            module,
            """
            {
              "module_family_id": "fam-overlay",
              "card_hints": [
                {"card_index": 1, "retrieval_hints_en": ["specific sibling query phrase"]}
              ]
            }
            """.trimIndent(),
        )!!
        val index = ModuleKnowledgeIndex.build(listOf(merged))
        val hits = index.search("specific sibling query phrase", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
        assertEquals(1, hits.first().positionalId)
    }
}
