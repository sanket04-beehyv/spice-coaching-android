package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted chat message in the SDK's Room database.
 *
 * Privacy note: message text is stored locally only.
 * It is never included in OTel span attributes.
 * SPICE can export this data via [CoachingDataRepository.exportAllData].
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["session_id"]),
        // Composite index for the recency-by-CHW query used by chat-history
        // restoration. SQLite picks this for `WHERE chw_id = ? ORDER BY
        // timestamp_ms DESC LIMIT N` without needing a separate file scan.
        Index(value = ["chw_id", "timestamp_ms"]),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Session this message belongs to. */
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /**
     * Owning CHW — the grouping key for chat-history restoration.
     *
     * Legacy rows from before this column existed get `''` via the
     * `MIGRATION_14_15` ALTER TABLE. Those rows won't surface in
     * CHW-filtered queries, which is acceptable: they predate the persistence
     * feature and can be ignored or cleared via the new "Clear chat" action.
     */
    @ColumnInfo(name = "chw_id")
    val chwId: String,

    /**
     * Optional conversation thread the message belongs to. Always `null` today;
     * reserved for the future Conversation feature so threading can ship
     * without another schema migration.
     */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,

    /** "user" or "assistant". */
    @ColumnInfo(name = "role")
    val role: String,

    /** The message text (stored locally; not transmitted in telemetry). */
    @ColumnInfo(name = "text")
    val text: String,

    /** Unix epoch millis. */
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long = System.currentTimeMillis(),

    /**
     * OTel trace ID of the inference span associated with this message.
     * Null for user messages. Allows correlating UI events to OTel spans.
     */
    @ColumnInfo(name = "trace_id")
    val traceId: String? = null,

    /** Optional patient ID context (from SPICE) — stored as hashed value only. */
    @ColumnInfo(name = "patient_id_hash")
    val patientIdHash: String? = null,

    /**
     * JSON-encoded `List<String>` of source-document UUIDs attached to this
     * message at persist time. Carried verbatim from the BM25-matched
     * [com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity.sourceDocumentIds]
     * so citation chips render in history without re-resolving the module.
     * Default `'[]'` for legacy rows (added in v17 migration).
     */
    @ColumnInfo(name = "source_document_ids_json")
    val sourceDocumentIdsJson: String = "[]",

    /**
     * JSON array of rich source-document references (`source_document_id`,
     * `title`, `original_filename`) attached at persist time. Supersedes
     * [sourceDocumentIdsJson]; lets citation chips render their own document
     * titles in history without re-resolving the module. Default `'[]'` for
     * legacy rows (added in the v17→v18 migration).
     */
    @ColumnInfo(name = "source_documents_json")
    val sourceDocumentsJson: String = "[]",

    /**
     * Dominant BM25-matched module family for this assistant message.
     * `ChatViewModel` uses it to resolve the chip-row label
     * (`"{module title} — SA N"`) in the active SDK locale at render time.
     * Null for user messages, refusals, and pre-v17 history.
     */
    @ColumnInfo(name = "grounding_module_family_id")
    val groundingModuleFamilyId: String? = null,

    /**
     * 1-indexed PDF page the in-app source-document viewer should open at
     * when a citation chip on this message is tapped. Sourced from the
     * BM25-matched card's `source_pages` field at message-persist time.
     * Null for user messages, refusals, messages whose grounding card has
     * no `source_pages`, and pre-v20 history — all of which open the
     * source PDF at page 1.
     */
    @ColumnInfo(name = "start_page")
    val startPage: Int? = null,
)
