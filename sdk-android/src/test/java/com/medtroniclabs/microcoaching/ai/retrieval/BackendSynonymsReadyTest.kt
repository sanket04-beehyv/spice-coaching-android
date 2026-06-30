package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the `synonyms_en` keys the backend should backfill to retire
 * [ClinicalSynonymMap] hardcoded BRIDGES (Phase 3). Android already consumes
 * module `search_metadata.synonyms_en` into [ModuleKnowledgeIndex] query expansion.
 */
class BackendSynonymsReadyTest {

  private fun module(searchMetadataJson: String) = moduleEntityFixture(
      moduleId = "m1",
      moduleFamilyId = "fam1",
      cardsJson = """[{"title_en":"TB therapy","body_en":"Use directly observed treatment."}]""",
      searchMetadataJson = searchMetadataJson,
  )

  /** Keys from execution plan §6.4 / Android BRIDGES — backend should ship these in `synonyms_en`. */
  private val expectedBackendSynonymKeys = setOf(
      "pw", "bp", "বিপি", "ors", "htn", "hb", "itn", "fits",
      "140/90", "cervix", "unconscious", "hypotension", "engorgement",
  )

  @Test
  fun `BE synonyms_en keys the backend should backfill`() {
    // Contract checklist for the backend developer — not an runtime assertion against BRIDGES.
    assertTrue(expectedBackendSynonymKeys.contains("bp"))
    assertTrue(expectedBackendSynonymKeys.contains("ors"))
    assertTrue(expectedBackendSynonymKeys.size >= 10)
  }

  @Test
  fun `module synonyms_en reaches retrieval without static BRIDGES`() {
      val meta = """
          {
            "schema_version": 2,
            "synonyms_en": {
              "DOTS": "directly observed treatment",
              "BP": "blood pressure"
            }
          }
      """.trimIndent()
      val index = ModuleKnowledgeIndex.build(listOf(module(meta)))
      val dotsHits = index.search("DOTS", k = 2, scoreThreshold = 0f)
      assertTrue("synonyms_en must bridge DOTS to indexed body vocabulary", dotsHits.isNotEmpty())
  }
}
