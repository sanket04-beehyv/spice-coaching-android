package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import com.medtroniclabs.microcoaching.data.localized.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * JSON fixture schema for offline retrieval validation before backend hints ship.
 * Files live in `src/test/resources/retrieval/` (suffix `_fixture.json`) and are exercised by
 * [RetrievalFixtureTest].
 */
@Serializable
data class RetrievalFixture(
    val id: String,
    @SerialName("module_family_id") val moduleFamilyId: String,
    @SerialName("module_id") val moduleId: String = "fixture-${id}",
    @SerialName("title_en") val titleEn: String = "Fixture $id",
    val cards: List<RetrievalFixtureCard>,
    @SerialName("search_metadata") val searchMetadata: JsonObject = JsonObject(emptyMap()),
    val queries: List<RetrievalFixtureQuery>,
)

@Serializable
data class RetrievalFixtureCard(
    @SerialName("title_en") val titleEn: String? = null,
    @SerialName("body_en") val bodyEn: String? = null,
    @SerialName("title_bn") val titleBn: String? = null,
    @SerialName("body_bn") val bodyBn: String? = null,
    @SerialName("retrieval_hints_en") val retrievalHintsEn: List<String> = emptyList(),
    @SerialName("retrieval_hints_bn") val retrievalHintsBn: List<String> = emptyList(),
)

@Serializable
data class RetrievalFixtureQuery(
    val query: String,
    @SerialName("expected_card_index") val expectedCardIndex: Int,
    val language: String = "EN",
    @SerialName("benchmark_ref") val benchmarkRef: String? = null,
)

object RetrievalFixtureLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): RetrievalFixture = json.decodeFromString(text)

    fun toModuleEntity(fixture: RetrievalFixture): ModuleEntity {
        val cardsJson = buildJsonArray {
            fixture.cards.forEach { card ->
                add(
                    buildJsonObject {
                        card.titleEn?.let { put("title_en", it) }
                        card.bodyEn?.let { put("body_en", it) }
                        card.titleBn?.let { put("title_bn", it) }
                        card.bodyBn?.let { put("body_bn", it) }
                        if (card.retrievalHintsEn.isNotEmpty()) {
                            putJsonArray("retrieval_hints_en") {
                                card.retrievalHintsEn.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                        if (card.retrievalHintsBn.isNotEmpty()) {
                            putJsonArray("retrieval_hints_bn") {
                                card.retrievalHintsBn.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    },
                )
            }
        }.toString()
        return ModuleEntity(
            moduleId = fixture.moduleId,
            moduleFamilyId = fixture.moduleFamilyId,
            version = 1,
            titleJson = LocalizedText.fromBnEn(bn = fixture.titleEn, en = fixture.titleEn).toJsonString(),
            domain = "rmnch",
            moduleType = "initial_training",
            estimatedMinutes = 10,
            difficultyLevel = "moderate",
            clinicallyReviewed = true,
            updatedAtIso = "2026-06-23T00:00:00Z",
            cardsJson = cardsJson,
            searchMetadataJson = fixture.searchMetadata.toString(),
        )
    }

    fun lang(query: RetrievalFixtureQuery): ModuleKnowledgeIndex.Lang =
        if (query.language.equals("BN", ignoreCase = true)) {
            ModuleKnowledgeIndex.Lang.BN
        } else {
            ModuleKnowledgeIndex.Lang.EN
        }
}
