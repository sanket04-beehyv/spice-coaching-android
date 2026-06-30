package com.medtroniclabs.microcoaching.data.repository

import com.medtroniclabs.microcoaching.data.db.dao.ChatMessageDao
import com.medtroniclabs.microcoaching.data.db.entity.ChatMessageEntity
import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Repository for chat message persistence.
 *
 * Not exposed directly to SPICE — SPICE accesses chat data via [CoachingDataRepository].
 *
 * Persistence is **keyed on `chwId`**, not on the per-VM `sessionId`. The
 * sessionId is still stamped on each row for telemetry / debugging joins, but
 * `getRecentHistory(chwId, …)` is what drives chat-history restoration on
 * sheet reopen — a stable identity that survives the fresh-UUID-per-VM model.
 */
private val json = Json { ignoreUnknownKeys = true }

open class ChatRepositoryImpl(private val dao: ChatMessageDao) {

    /**
     * Persist a chat message. The [chwId] is required because the recency
     * query that restores history queries by CHW. The [conversationId] is the
     * future-feature placeholder and remains null until threading ships.
     */
    suspend fun saveMessage(
        message: ChatMessage,
        chwId: String,
        conversationId: String? = null,
    ): Long = dao.insert(message.toEntity(chwId = chwId, conversationId = conversationId))

    /**
     * The last [limit] messages for [chwId] across all sessions and
     * conversations, in chronological order (oldest → newest). The DAO returns
     * DESC + LIMIT so the cut-off happens at the SQL layer; we reverse here so
     * the UI can append straight into a LazyColumn.
     */
    suspend fun getRecentHistory(chwId: String, limit: Int = DEFAULT_HISTORY_LIMIT): List<ChatMessage> =
        dao.getRecentByChw(chwId, limit)
            .asReversed()
            .map { it.toModel() }

    /** Hard-delete every chat message for this CHW. Backs the "Clear chat" action. */
    suspend fun clearChwHistory(chwId: String) = dao.deleteByChw(chwId)

    // ── Legacy / debugging helpers (kept for tests + telemetry tooling) ────────

    suspend fun getHistory(sessionId: String): List<ChatMessage> =
        dao.getBySession(sessionId).map { it.toModel() }

    fun observeSession(sessionId: String): Flow<List<ChatMessage>> =
        dao.observeSession(sessionId).map { list -> list.map { it.toModel() } }

    suspend fun getAllSessionIds(): List<String> = dao.getAllSessionIds()

    suspend fun getAllMessages(): List<ChatMessage> = dao.getAll().map { it.toModel() }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun ChatMessage.toEntity(
        chwId: String,
        conversationId: String?,
    ) = ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        chwId = chwId,
        conversationId = conversationId,
        role = role,
        text = text,
        timestampMs = timestampMs,
        traceId = traceId,
        sourceDocumentsJson = encodeRefs(sourceDocuments),
        // Keep the deprecated id-only column populated (derived) for back-compat.
        sourceDocumentIdsJson = encodeStringList(sourceDocuments.map { it.id }),
        groundingModuleFamilyId = groundingModuleFamilyId,
        startPage = startPage,
    )

    private fun ChatMessageEntity.toModel() = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        text = text,
        timestampMs = timestampMs,
        traceId = traceId,
        // Prefer the rich column; fall back to id-only for pre-v19 history rows.
        sourceDocuments = decodeRefs(sourceDocumentsJson).ifEmpty {
            decodeStringList(sourceDocumentIdsJson).map { SourceDocumentRef(id = it) }
        },
        groundingModuleFamilyId = groundingModuleFamilyId,
        startPage = startPage,
    )

    private fun encodeRefs(refs: List<SourceDocumentRef>): String =
        if (refs.isEmpty()) "[]"
        else json.encodeToString(ListSerializer(SourceDocumentRef.serializer()), refs)

    private fun decodeRefs(jsonStr: String): List<SourceDocumentRef> =
        runCatching {
            json.decodeFromString(ListSerializer(SourceDocumentRef.serializer()), jsonStr)
                .filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

    private fun encodeStringList(list: List<String>): String =
        if (list.isEmpty()) "[]"
        else JsonArray(list.map { JsonPrimitive(it) }).toString()

    private fun decodeStringList(jsonStr: String): List<String> =
        runCatching {
            Json.parseToJsonElement(jsonStr).jsonArray
                .map { it.jsonPrimitive.content }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 50
    }
}
