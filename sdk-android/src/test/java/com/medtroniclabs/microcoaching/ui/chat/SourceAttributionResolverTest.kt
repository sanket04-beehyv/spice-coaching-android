package com.medtroniclabs.microcoaching.ui.chat

import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceAttributionResolverTest {

    private val docA = SourceDocumentRef(id = "doc-A", title = "A")
    private val docB = SourceDocumentRef(id = "doc-B", title = "B")

    private fun chunk(pages: List<GroundingChunk.SourcePageRef>?) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = "fam",
        positionalId = 0,
        titleEn = "t",
        bodyEn = "b",
        titleBn = null,
        bodyBn = null,
        score = 1f,
        sourcePages = pages,
    )

    @Test
    fun `picks the exact document and page the card cites`() {
        val r = SourceAttributionResolver.resolve(
            chunk(listOf(GroundingChunk.SourcePageRef("doc-B", 162))),
            listOf(docA, docB),
        )
        assertEquals(listOf(docB), r.docs)
        assertEquals(162, r.startPage)
    }

    @Test
    fun `legacy page without a document id keeps module docs`() {
        val r = SourceAttributionResolver.resolve(
            chunk(listOf(GroundingChunk.SourcePageRef(null, 162))),
            listOf(docA, docB),
        )
        assertEquals(listOf(docA, docB), r.docs)
        assertEquals(162, r.startPage)
    }

    @Test
    fun `no source pages falls back to module first doc with null page`() {
        val r = SourceAttributionResolver.resolve(chunk(null), listOf(docA, docB))
        assertEquals(listOf(docA), r.docs)
        assertNull(r.startPage)
    }

    @Test
    fun `unmatched document id keeps module docs but still uses the page`() {
        val r = SourceAttributionResolver.resolve(
            chunk(listOf(GroundingChunk.SourcePageRef("doc-missing", 5))),
            listOf(docA),
        )
        assertEquals(listOf(docA), r.docs)
        assertEquals(5, r.startPage)
    }
}
