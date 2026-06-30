package com.medtroniclabs.microcoaching.ui.chat

import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import com.medtroniclabs.microcoaching.network.SourceDocumentRef

/**
 * Pure (Room-free, Android-free) core of chat source attribution, so the
 * doc-selection logic is unit-testable without a database.
 *
 * Goal: an offline answer must cite the EXACT source document + page of the card it
 * was grounded on. When the card has no page anchor, fall back to the module's
 * first source document (page null → the in-app viewer opens page 1).
 */
object SourceAttributionResolver {

    data class Resolved(
        val docs: List<SourceDocumentRef>,
        val startPage: Int?,
    )

    /**
     * Resolve the document(s) + start page to cite for [top], given the module's
     * full [moduleDocs] list.
     *
     * - Card cites a specific `{source_document_id, page}` → surface THAT one document
     *   at THAT page.
     * - Legacy bare-int `source_pages` (page but no document id) → keep the module's
     *   docs and use the page.
     * - No `source_pages` at all → the module's first source document, page null.
     */
    fun resolve(top: GroundingChunk, moduleDocs: List<SourceDocumentRef>): Resolved {
        val pageRef = top.sourcePages?.firstOrNull { it.pageNumber > 0 }
        if (pageRef != null) {
            val matched = pageRef.sourceDocumentId?.let { id -> moduleDocs.firstOrNull { it.id == id } }
            if (matched != null) return Resolved(listOf(matched), pageRef.pageNumber)
            // Page known but document id absent/unmatched (legacy payload) — keep all
            // module docs so a chip still renders, anchored to the known page.
            return Resolved(moduleDocs, pageRef.pageNumber)
        }
        val firstDoc = moduleDocs.firstOrNull()
        return Resolved(if (firstDoc != null) listOf(firstDoc) else moduleDocs, null)
    }
}
