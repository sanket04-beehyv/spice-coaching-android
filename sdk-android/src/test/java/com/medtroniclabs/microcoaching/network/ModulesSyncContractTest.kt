package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.data.mapper.toEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Android side is ready for backend handoff: sync bundle shape,
 * per-card hints on cards, and `retired_family_ids` deserialization.
 */
class ModulesSyncContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `v2 sync bundle deserializes retired_family_ids and per-card retrieval hints`() {
        val payload = """
            {
              "modules": [
                {
                  "id": "mod-v2-1",
                  "module_family_id": "fam-active-1",
                  "version": 2,
                  "title_bn": "মডিউল",
                  "title_en": "Sanitary Latrines",
                  "domain": "wash",
                  "module_type": "initial_training",
                  "estimated_minutes": 10,
                  "difficulty_level": "moderate",
                  "clinically_reviewed": true,
                  "updated_at": "2026-06-23T00:00:00Z",
                  "cards": [
                    {
                      "title_en": "Overview",
                      "body_en": "General introduction."
                    },
                    {
                      "title_en": "Disease prevention",
                      "body_en": "Latrine use blocks contamination.",
                      "retrieval_hints_en": [
                        "how do sanitary latrines help prevent disease spread"
                      ],
                      "retrieval_hints_bn": [
                        "পায়খানা রোগ ছড়ানো রোধ করে কীভাবে"
                      ]
                    }
                  ],
                  "search_metadata": {
                    "schema_version": 2,
                    "synonyms_en": {
                      "BP": "blood pressure"
                    }
                  }
                }
              ],
              "module_families": [],
              "retired_family_ids": ["fam-retired-legacy"],
              "server_time_utc": "2026-06-23T12:00:00Z"
            }
        """.trimIndent()

        val bundle = json.decodeFromString<ModulesSyncBundle>(payload)
        assertEquals(listOf("fam-retired-legacy"), bundle.retiredFamilyIds)
        assertEquals(1, bundle.modules.size)

        val card = bundle.modules.first().cards[1]
        assertTrue(card["retrieval_hints_en"] != null)
        assertTrue(card["retrieval_hints_bn"] != null)

        val meta = bundle.modules.first().searchMetadata
        assertTrue(meta?.containsKey("schema_version") == true)

        val entity = bundle.modules.first().toEntity(lastSynced = 0L)
        val index = ModuleKnowledgeIndex.build(listOf(entity))
        val hits = index.search(
            "how do sanitary latrines help prevent disease spread",
            k = 2,
            scoreThreshold = 0f,
        )
        assertEquals(1, hits.first().positionalId)
    }

    @Test
    fun `backend nested search_metadata hints rank expected card after sync mapping`() {
        val payload = """
            {
              "modules": [
                {
                  "id": "mod-nested-1",
                  "module_family_id": "fam-nested-1",
                  "version": 2,
                  "title_bn": "মডিউল",
                  "title_en": "Sanitary Latrines",
                  "domain": "wash",
                  "module_type": "initial_training",
                  "estimated_minutes": 10,
                  "difficulty_level": "moderate",
                  "clinically_reviewed": true,
                  "updated_at": "2026-06-23T00:00:00Z",
                  "cards": [
                    {
                      "title_en": "Overview",
                      "body_en": "General introduction."
                    },
                    {
                      "title_en": "Disease prevention",
                      "body_en": "Latrine use blocks contamination.",
                      "search_metadata": {
                        "schema_version": 2,
                        "retrieval_hints_en": [
                          "how do sanitary latrines help prevent disease spread"
                        ],
                        "retrieval_hints_bn": [
                          "পায়খানা রোগ ছড়ানো রোধ করে কীভাবে"
                        ],
                        "keywords_en": ["fecal contamination"],
                        "questions_en": ["how do latrines prevent waterborne illness"]
                      }
                    }
                  ],
                  "search_metadata": {
                    "schema_version": 2,
                    "synonyms_en": {
                      "BP": "blood pressure"
                    }
                  }
                }
              ],
              "module_families": [],
              "retired_family_ids": [],
              "server_time_utc": "2026-06-23T12:00:00Z"
            }
        """.trimIndent()

        val bundle = json.decodeFromString<ModulesSyncBundle>(payload)
        val entity = bundle.modules.first().toEntity(lastSynced = 0L)
        val index = ModuleKnowledgeIndex.build(listOf(entity))
        val hits = index.search(
            "how do sanitary latrines help prevent disease spread",
            k = 2,
            scoreThreshold = 0f,
        )
        assertEquals(1, hits.first().positionalId)
    }

    @Test
    fun `localized title map deserializes and maps to title_json`() {
        val payload = """
            {
              "modules": [
                {
                  "id": "mod-localized-1",
                  "module_family_id": "fam-localized-1",
                  "version": 18,
                  "title": {"bn": "Test08", "en": "Test08 EN"},
                  "description": {"bn": "Desc BN", "en": "Desc EN"},
                  "domain": "clinical",
                  "module_type": "refresher",
                  "estimated_minutes": 10,
                  "difficulty_level": "moderate",
                  "clinically_reviewed": true,
                  "updated_at": "2026-06-23T08:09:50.093581Z",
                  "cards": [],
                  "quiz": [
                    {
                      "id": "q-1",
                      "question": {"bn": "প্রশ্ন", "en": "Question"},
                      "options": {"bn": ["এ"], "en": ["A"]},
                      "correct_indices": [0],
                      "explanation": {"bn": "ব্যাখ্যা"},
                      "difficulty": "medium"
                    }
                  ],
                  "search_metadata": {
                    "keywords": {"en": ["immunization"], "bn": ["টিকা"]},
                    "search_phrases": {"en": ["vaccine schedule"]}
                  }
                }
              ],
              "module_families": [],
              "server_time_utc": "2026-06-23T12:00:00Z"
            }
        """.trimIndent()

        val bundle = json.decodeFromString<ModulesSyncBundle>(payload)
        val mod = bundle.modules.first()
        assertEquals("Test08", mod.resolvedTitle().bn)
        assertEquals("Test08 EN", mod.resolvedTitle().en)

        val entity = mod.toEntity(lastSynced = 0L)
        assertEquals("Test08", entity.titleBn)
        assertEquals("Test08 EN", entity.titleEn)
        assertTrue(entity.quizJson.contains("\"question\""))
    }

    @Test
    fun `retirement set excludes families present in the same bundle`() {
        val retired = setOf("fam-a", "fam-b", "fam-c")
        val published = setOf("fam-a", "fam-d")
        val toRetire = retired - published
        assertEquals(setOf("fam-b", "fam-c"), toRetire)
    }
}
