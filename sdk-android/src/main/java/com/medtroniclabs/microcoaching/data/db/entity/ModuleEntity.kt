package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import com.medtroniclabs.microcoaching.data.localized.toJsonString
import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Tolerant decoder for the typed `source_documents_json` column. */
private val moduleEntityJson = Json { ignoreUnknownKeys = true }

/**
 * Local cache of a published module shipped by the v3 backend `/sync/modules`
 * endpoint (see `ModuleSyncPayload` in the deployed OpenAPI spec).
 *
 * Backend only sends **published** modules through this route, so lifecycle
 * state is not modelled here. When a module is retired backend-side it stops
 * appearing in future sync responses; pruning is by absence, not by flag.
 *
 * Cards and quiz are stored as raw JSON. Cards are unstructured by spec; quiz
 * JSON is parsed into typed UI models at render time (LearnViewModel).
 */
@Entity(
    tableName = "module_cache",
    indices = [
        Index(value = ["module_family_id"]),
        Index(value = ["domain"]),
    ],
)
data class ModuleEntity(

    /** Module version UUID (backend `id`). Primary key — one row per published version. */
    @PrimaryKey
    @ColumnInfo(name = "module_id")
    val moduleId: String,

    /** Stable family identifier — survives across version bumps. */
    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String,

    @ColumnInfo(name = "version")
    val version: Int,

    /** Bilingual title as `{"bn":"...","en":"..."}` (v28+). */
    @ColumnInfo(name = "title_json")
    val titleJson: String = "{}",

    /** Bilingual description as `{"bn":"...","en":"..."}` (v28+). */
    @ColumnInfo(name = "description_json")
    val descriptionJson: String = "{}",

    @ColumnInfo(name = "domain")
    val domain: String,

    @ColumnInfo(name = "sub_domain")
    val subDomain: String? = null,

    /** "refresher" | "content_update" | "digital_proficiency". */
    @ColumnInfo(name = "module_type")
    val moduleType: String,

    @ColumnInfo(name = "tenant_id")
    val tenantId: String? = null,

    @ColumnInfo(name = "estimated_minutes")
    val estimatedMinutes: Int,

    @ColumnInfo(name = "difficulty_level")
    val difficultyLevel: String,

    /** Per-module quiz pass override (0.0–1.0). When null, SDK config default governs. */
    @ColumnInfo(name = "pass_threshold_override")
    val passThresholdOverride: Float? = null,

    @ColumnInfo(name = "clinically_reviewed")
    val clinicallyReviewed: Boolean,

    @ColumnInfo(name = "published_at_iso")
    val publishedAtIso: String? = null,

    @ColumnInfo(name = "updated_at_iso")
    val updatedAtIso: String,

    /** JSON array of card objects — opaque to Room, parsed at read time. */
    @ColumnInfo(name = "cards_json")
    val cardsJson: String = "[]",

    /** JSON array of typed quiz rows — opaque to Room, parsed at read time. */
    @ColumnInfo(name = "quiz_json")
    val quizJson: String = "[]",

    /**
     * JSON array of source-document UUIDs (`["uuid", "uuid", …]`) that back the
     * module's content — chat citation chips dereference these via the
     * `/sync/source-documents/presigned-urls` endpoint.
     */
    @ColumnInfo(name = "source_document_ids_json")
    val sourceDocumentIdsJson: String = "[]",

    /**
     * JSON array of rich source-document references (v3 `source_documents`):
     * `[{"source_document_id","title","original_filename",…}, …]`. Supersedes
     * [sourceDocumentIdsJson]; chat citation chips read titles from here.
     * Empty `[]` for legacy rows (added in the v17→v18 migration).
     */
    @ColumnInfo(name = "source_documents_json")
    val sourceDocumentsJson: String = "[]",

    /**
     * Raw module-level `search_metadata` object — author/clinician-curated
     * retrieval hints (`keywords_en`, `keywords_bn`, `search_phrases_en/bn`,
     * `synonyms_en`, `topic_tags`, `clinical_conditions`). Opaque to Room; parsed
     * structurally by `ModuleKnowledgeIndex.build` and fed into the per-language
     * BM25 token streams (module-scoped). `"{}"` for legacy rows that predate the
     * v21→v22 migration.
     */
    @ColumnInfo(name = "search_metadata_json")
    val searchMetadataJson: String = "{}",

    /**
     * The module's primary behavioural gap (`module.primary_gap_id`) — the gap a
     * quiz attempt on this module is attributed to (mirrors the backend's
     * `module.primary_gap_id` used by `gap_escalation_handler`). Null for modules
     * with no behavioural gap. `"[]"`/null for legacy rows (v23→v24 migration).
     */
    @ColumnInfo(name = "primary_gap_id")
    val primaryGapId: String? = null,

    /**
     * JSON array of all behavioural-gap UUIDs this module addresses
     * (`module.behavioural_gap_ids` — the backend `module_behavioural_gap` junction
     * denormalised, primary + secondary). Inverted on-device into
     * [com.medtroniclabs.microcoaching.domain.gaps.ondevice.ModuleGapIndex.gapToFamilies].
     */
    @ColumnInfo(name = "behavioural_gap_ids_json")
    val behaviouralGapIdsJson: String = "[]",

    /**
     * Whether the backend has a thumbnail for this module version. Drives which
     * `module_id`s are sent to `/sync/modules/presigned-thumbnails`. Set from the
     * sync payload; the URL itself is populated separately by thumbnail sync.
     */
    @ColumnInfo(name = "has_thumbnail")
    val hasThumbnail: Boolean = false,

    /**
     * Presigned GET URL for the module thumbnail, or null until thumbnail sync
     * has resolved it. Re-fetched once [thumbnailExpiresAtEpochSec] passes.
     * Not set by the module-sync mapper (so a module REPLACE leaves it null and
     * thumbnail sync repopulates it in the same pass).
     */
    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    /**
     * Absolute expiry (epoch seconds) of [thumbnailUrl], computed at fetch time
     * as `now + expires_seconds - safety margin`. Null when no URL is cached.
     * A row is considered stale (needs re-fetch) when this is null or in the past.
     */
    @ColumnInfo(name = "thumbnail_expires_at_epoch_sec")
    val thumbnailExpiresAtEpochSec: Long? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis(),
) {
    @get:Ignore
    val title: LocalizedText
        get() = LocalizedText.decode(titleJson)

    @get:Ignore
    val titleBn: String
        get() = title.bn.orEmpty()

    @get:Ignore
    val titleEn: String?
        get() = title.en

    @get:Ignore
    val description: LocalizedText
        get() = LocalizedText.decode(descriptionJson)

    @get:Ignore
    val descriptionBn: String?
        get() = description.bn

    @get:Ignore
    val descriptionEn: String?
        get() = description.en

    @get:Ignore
    val questionCount: Int
        get() = runCatching { Json.parseToJsonElement(quizJson).jsonArray.size }.getOrDefault(0)

    /** Number of lesson cards in [cardsJson]. Mirrors [questionCount] pattern. */
    @get:Ignore
    val cardCount: Int
        get() = runCatching { Json.parseToJsonElement(cardsJson).jsonArray.size }.getOrDefault(0)

    /** Parsed view over [sourceDocumentsJson]. Empty list on malformed JSON. */
    @get:Ignore
    val sourceDocuments: List<SourceDocumentRef>
        get() = runCatching {
            moduleEntityJson.decodeFromString(
                ListSerializer(SourceDocumentRef.serializer()),
                sourceDocumentsJson,
            ).filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

    /**
     * Source-document UUIDs only. Derived from [sourceDocuments] (the rich
     * column); falls back to parsing the deprecated [sourceDocumentIdsJson] so
     * id-only consumers (presigned-URL fetch, document preview) keep working for
     * pre-v19 rows that predate the rich column.
     */
    @get:Ignore
    val sourceDocumentIds: List<String>
        get() = sourceDocuments.map { it.id }.ifEmpty {
            runCatching {
                Json.parseToJsonElement(sourceDocumentIdsJson).jsonArray
                    .map { it.jsonPrimitive.content }
                    .filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }

    /** All behavioural-gap UUIDs this module addresses, parsed from [behaviouralGapIdsJson]. */
    @get:Ignore
    val behaviouralGapIds: List<String>
        get() = runCatching {
            Json.parseToJsonElement(behaviouralGapIdsJson).jsonArray
                .map { it.jsonPrimitive.content }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
}

/**
 * Display sort: domain, then Bengali title (English fallback). Applied in Kotlin
 * because Android's bundled SQLite does not reliably expose `json_extract` for
 * ordering on `title_json`.
 */
fun List<ModuleEntity>.sortedForDisplay(): List<ModuleEntity> =
    sortedWith(
        compareBy(
            { it.domain },
            { it.titleBn.ifBlank { it.titleEn.orEmpty() } },
            { it.moduleId },
        ),
    )

/** Test/fixture builder — keeps bilingual fields in `title_json` / `description_json`. */
fun moduleEntityFixture(
    moduleId: String,
    moduleFamilyId: String,
    version: Int = 1,
    titleBn: String = "মডিউল",
    titleEn: String? = null,
    descriptionBn: String? = null,
    descriptionEn: String? = null,
    domain: String = "rmnch",
    moduleType: String = "initial_training",
    estimatedMinutes: Int = 10,
    difficultyLevel: String = "moderate",
    clinicallyReviewed: Boolean = true,
    updatedAtIso: String = "2026-06-01T00:00:00Z",
    cardsJson: String = "[]",
    quizJson: String = "[]",
    searchMetadataJson: String = "{}",
    primaryGapId: String? = null,
    behaviouralGapIdsJson: String = "[]",
): ModuleEntity = ModuleEntity(
    moduleId = moduleId,
    moduleFamilyId = moduleFamilyId,
    version = version,
    titleJson = LocalizedText.fromBnEn(titleBn, titleEn).toJsonString(),
    descriptionJson = LocalizedText.fromBnEn(descriptionBn, descriptionEn).toJsonString(),
    domain = domain,
    moduleType = moduleType,
    estimatedMinutes = estimatedMinutes,
    difficultyLevel = difficultyLevel,
    clinicallyReviewed = clinicallyReviewed,
    updatedAtIso = updatedAtIso,
    cardsJson = cardsJson,
    quizJson = quizJson,
    searchMetadataJson = searchMetadataJson,
    primaryGapId = primaryGapId,
    behaviouralGapIdsJson = behaviouralGapIdsJson,
)
