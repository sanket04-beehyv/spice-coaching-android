package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the MicroCoaching Knowledge Layer backend.
 *
 * Base URL: [com.medtroniclabs.microcoaching.MicroCoachingConfig.backendUrl]
 * Auth:     `Authorization: Bearer {authToken}` (injected by NetworkModule)
 * Docs:     http://<host>/docs  (FastAPI Swagger UI)
 *
 * Endpoint reference:
 *   POST /coaching/counselling       — UC-2 online card generation (router not yet mounted)
 *   GET  /scenarios/sync             — DEPRECATED, removed in v3 (legacy stub kept until Phase 5 cleanup)
 *   GET  /sync/modules               — v3 module pipeline (cards + quiz inline)
 *   GET  /sync/gaps                  — v3 behavioural-gap taxonomy
 *   GET  /sync/triggers              — v3 trigger definitions + module bindings
 *   GET  /sync/snippets              — v3 reusable card snippets
 *   GET  /sync/config                — v3 config thresholds
 *   GET  /morning/cards/{chw_id}     — online morning module selection (router not yet mounted)
 *   POST /telemetry/events           — outbound event upload
 *   GET  /health                     — liveness probe
 */
interface CoachingApiService {

    // ── Sync endpoints ────────────────────────────────────────────────────────

    /**
     * Upload a batch of SDK events to the backend for analytics and gap-profile computation.
     *
     * NOTE: This endpoint is not yet implemented in the Knowledge Layer. The backend
     * team should add `POST /telemetry/events` in Phase B server work. Until then,
     * all calls to this function will receive a mocked response from [MockCoachingApiService].
     */
    @POST("telemetry/events")
    suspend fun pushTelemetry(@Body batch: TelemetryBatch): Response<TelemetryAckResponse>

    /**
     * Fetch the v3 module sync bundle (modules + their inline quiz + module-family rows).
     *
     * [since] is the ISO 8601 watermark from the previous call (`server_time_utc`
     * in the response). The backend requires this query parameter on every call;
     * pass [SyncDefaults.EPOCH_ISO] for first sync to receive the full catalogue.
     *
     * [userId] is the host-supplied CHW id. The backend returns the **full module
     * catalogue** regardless (cached in `module_cache`, powers BM25) AND, when
     * `user_id` is present, the `assigned_module_ids` for that user — so one call
     * feeds both the catalogue and the `assigned_module` table. Nullable: Retrofit
     * drops a null `@Query`, in which case `assigned_module_ids` comes back empty.
     */
    @GET("sync/modules")
    suspend fun pullModules(
        @Query("since") since: String,
        @Query("user_id") userId: String? = null,
    ): Response<ModulesSyncBundle>

    /**
     * Fetch the v3 behavioural-gap taxonomy. When [chwId] is supplied, the
     * response also includes per-CHW gap state and module-completion rows.
     */
    @GET("sync/gaps")
    suspend fun pullGaps(
        @Query("since") since: String? = null,
        @Query("chw_id") chwId: String? = null,
    ): Response<GapsSyncBundle>

    /**
     * Fetch the v3 trigger definitions + module-trigger bindings updated since
     * [since]. Backend requires the query parameter.
     */
    @GET("sync/triggers")
    suspend fun pullTriggers(
        @Query("since") since: String,
    ): Response<TriggersSyncBundle>

    /**
     * Fetch the v3 config-threshold snapshot. Returns a flat key→value dict
     * representing all thresholds the backend wants the device to honour.
     */
    @GET("sync/config")
    suspend fun pullConfig(): Response<ConfigSyncBundle>

    /**
     * Resolve a batch of source-document UUIDs to short-lived presigned GET
     * URLs. Used by the chat citation chips to open the original training PDF
     * a module's content was authored from.
     *
     * Backend returns partial-success — IDs the server couldn't find or that
     * the caller doesn't have permission for come back in `missing_ids` instead
     * of an error. Callers should snackbar-error those individually.
     */
    @POST("sync/source-documents/presigned-urls")
    suspend fun getSourceDocumentPresignedUrls(
        @Body request: SourceDocumentPresignedUrlRequest,
    ): Response<SourceDocumentPresignedUrlResponse>

    /**
     * Fetch one page of the published source-document catalogue — the durable
     * source for the Knowledge section. Each entry carries display metadata plus
     * inline presigned URLs for the document and (when present) its thumbnail, so
     * no follow-up presigned call is needed to render or open it.
     *
     * Paginated via [limit] / [offset]; the SDK pulls with a large [limit]
     * (≈200) and advances [offset] until a short page signals the end.
     */
    @GET("sync/source-documents/published")
    suspend fun getPublishedSourceDocuments(
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
    ): Response<PublishedSourceDocumentsResponse>

    /**
     * Resolve a batch of source-document IDs to short-lived presigned GET URLs
     * for their **thumbnail** images. Mirrors the module-thumbnail endpoint.
     * Partial-success — missing IDs come back in `missing_ids`.
     */
    @POST("sync/source-documents/presigned-thumbnails")
    suspend fun getSourceDocumentThumbnailPresignedUrls(
        @Body request: SourceDocumentThumbnailPresignedUrlRequest,
    ): Response<SourceDocumentThumbnailPresignedUrlResponse>

    /**
     * Resolve a batch of module `module_id`s to short-lived presigned GET URLs
     * for their thumbnail images. Used to populate the module list tiles and the
     * module detail header.
     *
     * Backend returns partial-success — IDs the server couldn't find come back
     * in `missing_ids` instead of an error. Presigned URLs expire
     * (`expires_seconds`, ~24h) and are re-fetched on the next sync once stale.
     */
    @POST("sync/modules/presigned-thumbnails")
    suspend fun getModuleThumbnailPresignedUrls(
        @Body request: ModuleThumbnailPresignedUrlRequest,
    ): Response<ModuleThumbnailPresignedUrlResponse>

    /**
     * Resolve a single media `object_name` (embedded in rich card bodies as
     * image/video nodes, e.g. `media/<uuid>_<file>.png`) to a short-lived presigned
     * GET URL. `bucket/object` references are also accepted by the backend.
     *
     * @param objectName  object name returned by upload (required).
     * @param expiresSeconds  URL lifetime in seconds (default 600, max 86400).
     * @param disposition  `auto` (inline for PDF/images, attachment otherwise),
     *                      or force `inline` / `attachment`.
     */
    @GET("admin/v3/files/presigned-url")
    suspend fun getMediaPresignedUrl(
        @Query("object_name") objectName: String,
        @Query("expires_seconds") expiresSeconds: Int = 600,
        @Query("disposition") disposition: String = "auto",
    ): Response<MediaPresignedUrlResponse>

    // ── Coaching card generation ───────────────────────────────────────────────

    /**
     * Generate a coaching card for the given context pack (UC-2 online mode).
     * Sends [ContextPackRequest] matching the backend ContextPack schema.
     */
    @POST("coaching/counselling")
    suspend fun generateCounsellingCard(@Body request: ContextPackRequest): Response<CounsellingCardResponse>

    /**
     * Backend RAG chat endpoint. Embeds [RagQueryRequest.question], retrieves top similar
     * published modules server-side, and returns a grounded answer with source documents.
     *
     * Used as the primary chat path when the device is online (any device class).
     * On failure the caller falls back to the local path appropriate for the device
     * (Gemma for normal, BM25 for low-end).
     */
    @POST("coaching/rag-query")
    suspend fun ragQuery(@Body request: RagQueryRequest): Response<RagQueryResponse>

    /**
     * Retrieve the backend-prioritised morning module list for a CHW.
     * Returns gap-driven and recently-added modules ranked by the backend;
     * the device falls back to the local [morning_card_cache] when offline.
     *
     * Both params are optional — omitting [chwId] returns recently-added
     * modules only (no gap personalisation).
     */
    @GET("morning/cards")
    suspend fun getMorningCards(
        @Query("chw_id") chwId: String?,
        @Query("tenant_id") tenantId: String?,
    ): Response<MorningCardsResponse>

    // ── Liveness ──────────────────────────────────────────────────────────────

    @GET("health")
    suspend fun health(): Response<HealthResponse>
}

// ── Telemetry batch (POST /telemetry/events) ──────────────────────────────────

/**
 * Backend-aligned batch envelope. Every event type is flattened into [events].
 *
 * [chwId] is passed through verbatim — the SDK does not validate the format.
 * SPICE forwards its own integer user id today; the backend has been relaxed
 * to accept whatever identifier shape the host supplies.
 *
 * [tenantId] is a String for the same reason — callers forward whatever value
 * the host already has.
 */
@Serializable
data class TelemetryBatch(
    @SerialName("events") val events: List<TelemetryEventPayload>,
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("chw_id") val chwId: String,
    @SerialName("tenant_id") val tenantId: String? = null,
)

/**
 * Unified event payload matching the backend TelemetryEvent schema.
 *
 * All three local entity types (CoachingEvent, LlmTrace, DigitalProficiency) are
 * serialised into this shape. LlmTrace and DigitalProficiency store entity-specific
 * fields in [payloadJson].
 *
 * [eventDate] is derived from [timestampLocal] at mapper time (YYYY-MM-DD, UTC).
 */
@Serializable
data class TelemetryEventPayload(
    @SerialName("id") val id: String,
    @SerialName("event_family") val eventFamily: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("event_date") val eventDate: String,
    @SerialName("event_schema_version") val eventSchemaVersion: Int = 1,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("patient_visit_id") val patientVisitId: String? = null,
    @SerialName("patient_track_id") val patientTrackId: String? = null,
    @SerialName("patient_id_hash") val patientIdHash: String? = null,
    @SerialName("village_id") val villageId: String? = null,
    @SerialName("upazila_id") val upazilaId: String? = null,
    @SerialName("module_family_id") val moduleFamilyId: String? = null,
    @SerialName("module_id") val moduleId: String? = null,
    // Wire names are `card_id` / `quiz_id` per v1.1 Events-Modelling spec.
    // Kotlin field names keep the *_family_id form because the local entity
    // columns (and existing callers) use that convention — the wire and the
    // local schema were aligned earlier in v3.x but the v1.1 backend renamed
    // them to drop the `_family_` suffix.
    @SerialName("card_id") val cardFamilyId: String? = null,
    @SerialName("quiz_id") val quizFamilyId: String? = null,
    @SerialName("module_version") val moduleVersion: Int? = null,
    @SerialName("quiz_score_pct") val quizScorePct: Float? = null,
    @SerialName("clinical_domain") val clinicalDomain: String? = null,
    @SerialName("card_type") val cardType: String? = null,
    @SerialName("trigger_type") val triggerType: String? = null,
    @SerialName("inference_mode") val inferenceMode: String? = null,
    @SerialName("outcome") val outcome: String? = null,
    @SerialName("validator_status") val validatorStatus: String? = null,
    @SerialName("fallback_used") val fallbackUsed: Boolean? = null,
    @SerialName("network_state") val networkState: String? = null,
    @SerialName("payload_json") val payloadJson: JsonObject = JsonObject(emptyMap()),
    @SerialName("timestamp_utc") val timestampUtc: Long? = null,
    @SerialName("timestamp_local") val timestampLocal: Long,
)

/** Backend acknowledgment. [accepted]/[rejected]/[duplicates]/[buffered] are event IDs, not counts. */
@Serializable
data class TelemetryAckResponse(
    @SerialName("accepted") val accepted: List<String> = emptyList(),
    @SerialName("rejected") val rejected: List<String> = emptyList(),
    @SerialName("duplicates") val duplicates: List<String> = emptyList(),
    @SerialName("buffered") val buffered: List<String> = emptyList(),
    @SerialName("errors") val errors: List<String> = emptyList(),
)

/** Helpers tied to v3 sync semantics. */
object SyncDefaults {
    /** ISO 8601 epoch — used as the `since` cursor on first sync. */
    const val EPOCH_ISO: String = "1970-01-01T00:00:00Z"
}

// ── Modules sync (GET /sync/modules) ──────────────────────────────────────────

/**
 * Backend response shape — mirrors `ModulesSyncBundle` in the deployed
 * coaching-platform OpenAPI spec.
 */
@Serializable
data class ModulesSyncBundle(
    @SerialName("modules") val modules: List<ModuleSyncPayload> = emptyList(),
    @SerialName("module_families") val moduleFamilies: List<ModuleFamilySyncPayload> = emptyList(),
    /**
     * Module families the backend has terminally retired (no published version
     * remains) since the requested `since`. Optional / forward-looking: today's
     * backend does not send it, so it defaults to empty and is a no-op. When the
     * backend adds it (the family-level "no published version remains" signal —
     * NOT version-level `deprecated_at`), [com.medtroniclabs.microcoaching.sync.SyncApi.pullModules]
     * deletes these incrementally without waiting for the periodic full-catalogue
     * reconcile.
     */
    @SerialName("retired_family_ids") val retiredFamilyIds: List<String> = emptyList(),
    /**
     * Module ids (the version `module.id`, the backend's unit of assignment)
     * assigned to the `user_id` on the request. The full current assignment set,
     * resolved across every assignment_type (individual / po_sk / geographical /
     * group). Drives the `assigned_module` table — the Training screen filters to
     * these while the chatbot/BM25 keeps the full `modules` catalogue. Empty when
     * no `user_id` was sent or the user has no assignments.
     */
    @SerialName("assigned_module_ids") val assignedModuleIds: List<String> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

/**
 * One module version row. `id` is the version UUID, stable across the
 * lifetime of a single published module; `module_family_id` groups versions.
 *
 * `cards` are opaque to Room and parsed at UI render time. `quiz` is typed.
 */
@Serializable
data class ModuleSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("module_family_id") val moduleFamilyId: String,
    @SerialName("version") val version: Int,
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("title_bn") val titleBnLegacy: String? = null,
    @SerialName("title_en") val titleEnLegacy: String? = null,
    @SerialName("description") val description: LocalizedText? = null,
    @SerialName("description_bn") val descriptionBnLegacy: String? = null,
    @SerialName("description_en") val descriptionEnLegacy: String? = null,
    @SerialName("domain") val domain: String,
    @SerialName("sub_domain") val subDomain: String? = null,
    @SerialName("module_type") val moduleType: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("estimated_minutes") val estimatedMinutes: Int,
    @SerialName("difficulty_level") val difficultyLevel: String,
    @SerialName("pass_threshold_override") val passThresholdOverride: Float? = null,
    @SerialName("clinically_reviewed") val clinicallyReviewed: Boolean,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("cards") val cards: List<JsonObject> = emptyList(),
    /** Opaque quiz rows — preserved verbatim in `quiz_json` (new + legacy shapes). */
    @SerialName("quiz") val quiz: List<JsonObject> = emptyList(),
    /**
     * UUIDs of training-PDF source documents the module was authored from.
     * Surfaced in chat citation chips; dereferenced via
     * `POST /sync/source-documents/presigned-urls`. Empty for modules that
     * haven't been linked to any source document yet.
     */
    @SerialName("source_document_ids") val sourceDocumentIds: List<String> = emptyList(),
    /**
     * Rich source-document references (v3 `source_documents`): each carries the
     * document id plus display metadata (`title`, `original_filename`). Replaces
     * the bare-UUID [sourceDocumentIds] list, which is kept only as a fallback
     * for older backends. Surfaced as chat citation chips and dereferenced via
     * `POST /sync/source-documents/presigned-urls`.
     */
    @SerialName("source_documents") val sourceDocuments: List<SourceDocumentRef> = emptyList(),
    /**
     * Whether this module version has a thumbnail image available. Drives which
     * `module_id`s the SDK requests presigned URLs for via
     * `POST /sync/modules/presigned-thumbnails`. Defaults to false for legacy
     * payloads that don't carry the flag.
     */
    @SerialName("has_thumbnail") val hasThumbnail: Boolean = false,
    /**
     * Module-level author/clinician-curated retrieval hints (`keywords_en`,
     * `keywords_bn`, `search_phrases_en/bn`, `synonyms_en`, `topic_tags`,
     * `clinical_conditions`, …). Kept opaque here and parsed structurally on the
     * device by [com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex]
     * — the shape carries many optional sub-keys and is additive, so we don't
     * pin a typed model. Null/absent for legacy payloads.
     */
    @SerialName("search_metadata") val searchMetadata: JsonObject? = null,
    /** The module's primary behavioural gap; null when the module has none. */
    @SerialName("primary_gap_id") val primaryGapId: String? = null,
    /** All behavioural-gap UUIDs this module addresses (primary + secondary). */
    @SerialName("behavioural_gap_ids") val behaviouralGapIds: List<String> = emptyList(),
) {
    /** Merged title from nested `title` map or legacy flat keys. */
    fun resolvedTitle(): LocalizedText {
        if (title != null && !title.isBlank()) return title
        return LocalizedText.fromBnEn(titleBnLegacy, titleEnLegacy)
    }

    /** Merged description from nested `description` map or legacy flat keys. */
    fun resolvedDescription(): LocalizedText {
        if (description != null && !description.isBlank()) return description
        return LocalizedText.fromBnEn(descriptionBnLegacy, descriptionEnLegacy)
    }
}

/**
 * One rich source-document reference from the module sync payload's
 * `source_documents` array. Only the fields the SDK renders/dereferences are
 * modelled; the rest of the backend object is ignored (`ignoreUnknownKeys`).
 *
 * Reused as the persisted + UI shape (module_cache / chat_messages
 * `source_documents_json`, [com.medtroniclabs.microcoaching.ui.chat.ChatMessage]),
 * so the same serializer round-trips wire → Room → UI.
 */
@Serializable
data class SourceDocumentRef(
    @SerialName("source_document_id") val id: String,
    @SerialName("title") val title: String? = null,
    @SerialName("original_filename") val originalFilename: String? = null,
    @SerialName("has_thumbnail") val hasThumbnail: Boolean = false,
)

// ── Published source-document catalogue (GET /sync/source-documents/published) ─

/**
 * Response for the published source-document catalogue. Mirrors the
 * `source-doc-knowledge.json` reference shape. Unknown fields are ignored.
 */
@Serializable
data class PublishedSourceDocumentsResponse(
    @SerialName("source_documents") val sourceDocuments: List<PublishedSourceDocumentItem> = emptyList(),
    @SerialName("missing_ids") val missingIds: List<String> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

/**
 * One published source document with inline presigned URLs. The `*_expires_seconds`
 * fields are URL lifetimes (relative seconds), converted to absolute epoch-second
 * expiries when persisted.
 */
@Serializable
data class PublishedSourceDocumentItem(
    @SerialName("source_document_id") val sourceDocumentId: String,
    @SerialName("title") val title: String? = null,
    @SerialName("original_filename") val originalFilename: String? = null,
    @SerialName("presigned_url") val presignedUrl: String? = null,
    @SerialName("presigned_expires_seconds") val presignedExpiresSeconds: Long? = null,
    @SerialName("thumbnail_presigned_url") val thumbnailPresignedUrl: String? = null,
    @SerialName("thumbnail_presigned_expires_seconds") val thumbnailPresignedExpiresSeconds: Long? = null,
)

// ── Source-document presigned URL endpoint ────────────────────────────────────

@Serializable
data class SourceDocumentPresignedUrlRequest(
    @SerialName("source_document_ids") val sourceDocumentIds: List<String>,
)

@Serializable
data class SourceDocumentPresignedUrlEntry(
    @SerialName("source_document_id") val sourceDocumentId: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("presigned_url") val presignedUrl: String,
    @SerialName("expires_seconds") val expiresSeconds: Long,
)

@Serializable
data class SourceDocumentPresignedUrlResponse(
    @SerialName("urls") val urls: List<SourceDocumentPresignedUrlEntry> = emptyList(),
    @SerialName("missing_ids") val missingIds: List<String> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

// ── Source-document thumbnail presigned URL endpoint ─────────────────────────

@Serializable
data class SourceDocumentThumbnailPresignedUrlRequest(
    @SerialName("source_document_ids") val sourceDocumentIds: List<String>,
)

@Serializable
data class SourceDocumentThumbnailPresignedUrlEntry(
    @SerialName("source_document_id") val sourceDocumentId: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("presigned_url") val presignedUrl: String,
    @SerialName("expires_seconds") val expiresSeconds: Long,
)

@Serializable
data class SourceDocumentThumbnailPresignedUrlResponse(
    @SerialName("urls") val urls: List<SourceDocumentThumbnailPresignedUrlEntry> = emptyList(),
    @SerialName("missing_ids") val missingIds: List<String> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

// ── Module thumbnail presigned URL endpoint ───────────────────────────────────

@Serializable
data class ModuleThumbnailPresignedUrlRequest(
    @SerialName("module_ids") val moduleIds: List<String>,
)

@Serializable
data class ModuleThumbnailPresignedUrlEntry(
    @SerialName("module_id") val moduleId: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("presigned_url") val presignedUrl: String,
    @SerialName("expires_seconds") val expiresSeconds: Long,
)

@Serializable
data class ModuleThumbnailPresignedUrlResponse(
    @SerialName("urls") val urls: List<ModuleThumbnailPresignedUrlEntry> = emptyList(),
    @SerialName("missing_ids") val missingIds: List<String> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

// ── Media (rich-card image/video) presigned URL endpoint ──────────────────────

@Serializable
data class MediaPresignedUrlResponse(
    @SerialName("url") val url: String,
    @SerialName("bucket_name") val bucketName: String? = null,
    @SerialName("object_name") val objectName: String? = null,
    @SerialName("expires_seconds") val expiresSeconds: Long = 600,
)

@Serializable
data class ModuleFamilySyncPayload(
    @SerialName("id") val id: String,
    @SerialName("module_code") val moduleCode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("current_published_module_id") val currentPublishedModuleId: String? = null,
)

/**
 * Typed quiz payload — the canonical SDK source for module quiz rendering.
 * `id` is the question version UUID; backend regenerates a new UUID when a
 * quiz is rewritten.
 */
@Serializable
data class ModuleQuizQuestionPayload(
    @SerialName("id") val id: String,
    @SerialName("question_order") val questionOrder: Int? = null,
    @SerialName("question_bn") val questionBn: String,
    @SerialName("question_en") val questionEn: String? = null,
    @SerialName("case_setup_bn") val caseSetupBn: String? = null,
    @SerialName("case_setup_en") val caseSetupEn: String? = null,
    @SerialName("options_bn") val optionsBn: List<JsonElement> = emptyList(),
    @SerialName("options_en") val optionsEn: List<JsonElement>? = null,
    @SerialName("correct_indices") val correctIndices: List<Int> = emptyList(),
    @SerialName("explanation_bn") val explanationBn: String? = null,
    @SerialName("explanation_en") val explanationEn: String? = null,
    @SerialName("difficulty") val difficulty: String,
)

// ── Gaps sync (GET /sync/gaps) ────────────────────────────────────────────────

/**
 * Backend bundle: the gap taxonomy plus optional per-CHW state when [chwId]
 * was passed on the request.
 */
@Serializable
data class GapsSyncBundle(
    @SerialName("behavioural_gaps") val behaviouralGaps: List<BehaviouralGapSyncPayload> = emptyList(),
    @SerialName("chw_behavioural_gap_states") val chwBehaviouralGapStates: List<ChwBehaviouralGapStateSyncPayload> = emptyList(),
    @SerialName("chw_quiz_question_states") val chwQuizQuestionStates: List<ChwQuizQuestionStateSyncPayload> = emptyList(),
    @SerialName("chw_module_completions") val chwModuleCompletions: List<ChwModuleCompletionSyncPayload> = emptyList(),
    @SerialName("chw_module_partial_completions") val chwModulePartialCompletions: List<ChwModulePartialCompletionSyncPayload> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

@Serializable
data class BehaviouralGapSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("gap_code") val gapCode: String,
    @SerialName("description") val description: String,
    @SerialName("domain") val domain: String,
    @SerialName("severity_default") val severityDefault: String,
    @SerialName("detection_rule_jsonb") val detectionRule: JsonObject = JsonObject(emptyMap()),
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ChwBehaviouralGapStateSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("behavioural_gap_id") val behaviouralGapId: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("severity_current") val severityCurrent: String,
    @SerialName("first_observed_at") val firstObservedAt: String? = null,
    @SerialName("last_observed_at") val lastObservedAt: String? = null,
    @SerialName("last_reinforced_at") val lastReinforcedAt: String? = null,
    @SerialName("occurrence_count") val occurrenceCount: Int,
    @SerialName("failed_attempts_count") val failedAttemptsCount: Int,
    @SerialName("last_failed_attempt_at") val lastFailedAttemptAt: String? = null,
    @SerialName("escalated_to_supervisor") val escalatedToSupervisor: Boolean,
    @SerialName("status") val status: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Quiz-level refresher state (`chw_quiz_question_state`); quiz-mode `/sync/gaps`. */
@Serializable
data class ChwQuizQuestionStateSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("quiz_id") val quizId: String,
    @SerialName("module_id") val moduleId: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("failed_attempts_count") val failedAttemptsCount: Int,
    @SerialName("last_failed_attempt_at") val lastFailedAttemptAt: String? = null,
    @SerialName("first_attempt_at") val firstAttemptAt: String? = null,
    @SerialName("last_attempt_at") val lastAttemptAt: String? = null,
    @SerialName("escalated_to_supervisor") val escalatedToSupervisor: Boolean,
    @SerialName("status") val status: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ChwModuleCompletionSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("module_family_id") val moduleFamilyId: String,
    @SerialName("latest_completed_module_id") val latestCompletedModuleId: String? = null,
    @SerialName("latest_attempt_module_id") val latestAttemptModuleId: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("latest_attempt_at") val latestAttemptAt: String? = null,
    @SerialName("latest_quiz_score") val latestQuizScore: Float? = null,
    @SerialName("latest_attempt_passed") val latestAttemptPassed: Boolean,
    @SerialName("attempts_since_last_pass") val attemptsSinceLastPass: Int,
    @SerialName("reinforcement_due_at") val reinforcementDueAt: String? = null,
    @SerialName("tenant_id") val tenantId: String? = null,
)

/**
 * Server-derived per-CHW per-module set of question IDs the CHW still needs to
 * answer correctly. Hydrated from per-question `module_quiz_attempted` events
 * (any question never attempted or whose latest answer was wrong). Authoritative
 * source for cross-device "to-reinforce" recovery — see `ToReinforceResolver`.
 */
@Serializable
data class ChwModulePartialCompletionSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("module_id") val moduleId: String? = null,
    @SerialName("module_family_id") val moduleFamilyId: String,
    @SerialName("incomplete_quiz_ids") val incompleteQuizIds: List<String> = emptyList(),
    @SerialName("tenant_id") val tenantId: String? = null,
)

// ── Triggers sync (GET /sync/triggers) ────────────────────────────────────────

@Serializable
data class TriggersSyncBundle(
    @SerialName("triggers") val triggers: List<TriggerDefinitionSyncPayload> = emptyList(),
    @SerialName("bindings") val bindings: List<ModuleTriggerBindingSyncPayload> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

@Serializable
data class TriggerDefinitionSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("trigger_kind") val triggerKind: String,
    @SerialName("trigger_code") val triggerCode: String,
    @SerialName("description") val description: String? = null,
    @SerialName("predicate_jsonb") val predicate: JsonObject = JsonObject(emptyMap()),
    @SerialName("predicate_schema_version") val predicateSchemaVersion: Int = 1,
    @SerialName("status") val status: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ModuleTriggerBindingSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("trigger_definition_id") val triggerDefinitionId: String,
    // Backend binds a trigger to a specific published `module_id`; the SDK resolves
    // it to the module's family (one binding row per family) at sync time.
    @SerialName("module_id") val moduleId: String,
    @SerialName("relationship") val relationship: String,
    @SerialName("priority_weight") val priorityWeight: Int,
    @SerialName("notes") val notes: String? = null,
)

// ── Config sync (GET /sync/config) ────────────────────────────────────────────

/**
 * Backend ships a flat key→value dict of thresholds. Values are mixed types;
 * we hold them as raw JSON elements and coerce at read time.
 */
@Serializable
data class ConfigSyncBundle(
    @SerialName("thresholds") val thresholds: Map<String, JsonElement> = emptyMap(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

// ── Counselling card generation (POST /coaching/counselling) ──────────────────

/** Matches the backend ContextPack schema exactly. */
@Serializable
data class ContextPackRequest(
    @SerialName("patient_snapshot") val patientSnapshot: PatientSnapshotDto,
    @SerialName("chw_context") val chwContext: ChwContextDto = ChwContextDto(),
    @SerialName("clinical_domain") val clinicalDomain: String? = null,
    @SerialName("action_type") val actionType: String? = null,
    @SerialName("risk_level") val riskLevel: String? = null,
)

@Serializable
data class PatientSnapshotDto(
    @SerialName("age") val age: Int? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("pregnancy_status") val pregnancyStatus: Boolean? = null,
    @SerialName("ncd_conditions") val ncdConditions: List<String> = emptyList(),
    @SerialName("current_readings") val currentReadings: CurrentReadingsDto? = null,
    @SerialName("risk_flags") val riskFlags: RiskFlagsDto? = null,
    @SerialName("med_adherence_flag") val medAdherenceFlag: Boolean? = null,
    @SerialName("upazila") val upazila: String? = null,
)

@Serializable
data class CurrentReadingsDto(
    @SerialName("systolic") val systolic: Int? = null,
    @SerialName("diastolic") val diastolic: Int? = null,
    @SerialName("glucose_value") val glucoseValue: Float? = null,
    @SerialName("bmi") val bmi: Float? = null,
)

@Serializable
data class RiskFlagsDto(
    @SerialName("risk_level") val riskLevel: String? = null,
)

@Serializable
data class ChwContextDto(
    @SerialName("chw_id") val chwId: String? = null,
    @SerialName("known_gaps") val knownGaps: List<String> = emptyList(),
    @SerialName("visit_id") val visitId: String? = null,
    @SerialName("trigger_type") val triggerType: String? = null,
    @SerialName("session_mode") val sessionMode: String? = null,
)

/** Matches the backend CoachingCardResponse schema. */
@Serializable
data class CounsellingCardResponse(
    @SerialName("patient_message") val patientMessage: String,
    @SerialName("warning_signs") val warningSigns: List<String> = emptyList(),
    @SerialName("next_step") val nextStep: String = "",
    @SerialName("referral_destination") val referralDestination: String? = null,
    @SerialName("source_module_id") val sourceModuleId: String,
    @SerialName("score") val score: Float = 0f,
    @SerialName("validation_warnings") val validationWarnings: List<String> = emptyList(),
    @SerialName("chw_id") val chwId: String? = null,
)

// ── Morning cards (GET /morning/cards) ────────────────────────────────────────

/** Response from `GET /morning/cards` — MorningCardsResponse schema in the deployed OpenAPI. */
@Serializable
data class MorningCardsResponse(
    @SerialName("items") val items: List<MorningModuleSuggestionItem> = emptyList(),
)

/** A single module suggestion from the morning-cards endpoint. */
@Serializable
data class MorningModuleSuggestionItem(
    @SerialName("module_id") val moduleId: String,
    @SerialName("module_family_id") val moduleFamilyId: String,
    /** "quiz" | "gap" | "fallback" — drives the GAP badge in the refresher list. */
    @SerialName("source") val source: String,
    /** Non-null when source == "gap". Forwarded to telemetry payload_json. */
    @SerialName("behavioural_gap_id") val behaviouralGapId: String? = null,
    /** Non-null when source == "quiz" — the `module_quiz_question.id`. */
    @SerialName("quiz_id") val quizId: String? = null,
)

// ── Health (GET /health) ──────────────────────────────────────────────────────

@Serializable
data class HealthResponse(
    @SerialName("status") val status: String,
)

// ── RAG chat (POST /coaching/rag-query) ───────────────────────────────────────

@Serializable
data class RagQueryRequest(
    @SerialName("question") val question: String,
    @SerialName("response_language") val responseLanguage: String,
    @SerialName("module_limit") val moduleLimit: Int = 5,
    @SerialName("presigned_url_ttl_seconds") val presignedUrlTtlSeconds: Int = 3600,
)

@Serializable
data class RagQueryResponse(
    @SerialName("answer") val answer: String,
    @SerialName("retrieved_modules") val retrievedModules: List<RagRetrievedModule> = emptyList(),
    @SerialName("source_documents") val sourceDocuments: List<RagSourceDocument> = emptyList(),
    @SerialName("model") val model: String? = null,
    @SerialName("cited_module_ids") val citedModuleIds: List<String> = emptyList(),
)

@Serializable
data class RagRetrievedModule(
    @SerialName("module_id") val moduleId: String,
    @SerialName("title_bn") val titleBn: String? = null,
    @SerialName("title_en") val titleEn: String? = null,
    @SerialName("domain") val domain: String? = null,
    @SerialName("cosine_distance") val cosineDistance: Double? = null,
)

@Serializable
data class RagSourceDocument(
    @SerialName("source_document_id") val sourceDocumentId: String,
    @SerialName("title") val title: String? = null,
    @SerialName("original_filename") val originalFilename: String? = null,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("page_numbers") val pageNumbers: List<Int> = emptyList(),
    @SerialName("source_pages") val sourcePages: List<RagSourcePage> = emptyList(),
    /**
     * Presigned URL returned inline by the backend. Captured for future optimisation
     * (skipping the separate getSourceDocumentPresignedUrls call) but not used yet —
     * DocumentPreviewActivity always fetches via the existing endpoint.
     */
    @SerialName("presigned_url") val presignedUrl: String? = null,
    @SerialName("presigned_expires_seconds") val presignedExpiresSeconds: Int? = null,
    @SerialName("linked_module_ids") val linkedModuleIds: List<String> = emptyList(),
)

@Serializable
data class RagSourcePage(
    @SerialName("page_number") val pageNumber: Int,
    @SerialName("start_ms") val startMs: Int? = null,
    @SerialName("end_ms") val endMs: Int? = null,
)
