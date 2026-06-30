package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * One piece of curriculum content surfaced by [ModuleKnowledgeIndex] as evidence
 * for the LLM's answer (B1 of chat_plan.md).
 *
 * Chunks are built from module cards only. The carrier is intentionally untyped
 * beyond [source] so the validator and refusal layers (B4) can treat them
 * uniformly. The English half (`titleEn` / `bodyEn`) is what goes into the LLM
 * prompt; the Bangla half is kept for refusal-path fallback (L4 serves card
 * `bodyBn` verbatim when the LLM's answer is rejected).
 */
data class GroundingChunk(
    val source: Source,
    val moduleFamilyId: String,
    val positionalId: Int,
    val titleEn: String?,
    val bodyEn: String?,
    val titleBn: String?,
    val bodyBn: String?,
    val score: Float,
    /**
     * (document, page) anchors parsed from the card's `source_pages` field on
     * inbound module sync; null when the card has no anchor (or for legacy
     * modules that predate the field). Drives the per-message PDF deep-link in
     * chat — `ChatViewModel.resolveSourceAttribution` reads the first entry to
     * pick BOTH the exact source document and the page, then persists them on
     * the assistant `ChatMessage`.
     */
    val sourcePages: List<SourcePageRef>? = null,
    /**
     * Reserved for a future answer-shaped grounding snippet on the chunk. Not
     * populated by [ModuleKnowledgeIndex] (cards-only index). Kept so tests and
     * the groundedness gate can still exercise explanation-aware scoring when set.
     */
    val explanationEn: String? = null,
    val explanationBn: String? = null,
) {
    enum class Source { CARD, QUIZ }

    /**
     * One (document, page) anchor from a card's `source_pages[]` entry.
     * [sourceDocumentId] is null only for the legacy bare-int payload shape.
     */
    data class SourcePageRef(
        val sourceDocumentId: String?,
        val pageNumber: Int,
    )

    /** Identifier suitable for telemetry / debugging. Stable per (module, source, position). */
    val chunkId: String = "$moduleFamilyId:${source.name.lowercase()}:$positionalId"

    /** First positive page number (document id ignored) — for callers that only need a page anchor. */
    val firstPageNumber: Int? get() = sourcePages?.firstOrNull { it.pageNumber > 0 }?.pageNumber

    /** English text injected into the LLM reference block. Falls back to BN if no EN side. */
    fun referenceText(): String = when {
        !bodyEn.isNullOrBlank() -> {
            val header = titleEn ?: titleBn ?: source.name
            "$header — $bodyEn"
        }
        !bodyBn.isNullOrBlank() -> {
            val header = titleEn ?: titleBn ?: source.name
            "$header — $bodyBn"
        }
        else -> titleEn ?: titleBn ?: ""
    }

    /**
     * The text the prompt builder should ground on. When [explanationEn] or
     * [explanationBn] is set (tests / future use), prefers that concise snippet;
     * otherwise falls back to [referenceText].
     */
    fun groundingSnippet(): String {
        val explanation = explanationEn?.takeIf { it.isNotBlank() }
            ?: explanationBn?.takeIf { it.isNotBlank() }
        if (explanation != null) {
            val header = titleEn ?: titleBn ?: source.name
            return "$header — $explanation"
        }
        return referenceText()
    }
}
