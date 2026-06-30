package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import com.medtroniclabs.microcoaching.ui.chat.SourceAttributionResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Offline pipeline eval harness. It cannot run Gemma (native MediaPipe), so it
 * exercises the deterministic parts that govern the two field failures — retrieval
 * (right card / honest miss), the [OffTopicGuard] backstop, and source attribution
 * (exact doc + page). Run after any retrieval/gate change:
 *   ./gradlew :sdk-android:testDebugUnitTest --tests '*ChatPipelineEvalTest*'
 * and read the printed `falseRefusals=… falseServes=… attributionMisses=…` line.
 */
class ChatPipelineEvalTest {

    private fun module(
        familyId: String,
        titleEn: String,
        cardsJson: String,
        quizJson: String = "[]",
        searchMetadataJson: String = "{}",
        sourceDocumentsJson: String = "[]",
    ) = moduleEntityFixture(
        moduleId = "v-$familyId",
        moduleFamilyId = familyId,
        titleBn = titleEn,
        titleEn = titleEn,
        cardsJson = cardsJson,
        quizJson = quizJson,
        searchMetadataJson = searchMetadataJson,
    ).copy(sourceDocumentsJson = sourceDocumentsJson)

    private val tbDocsJson =
        """[{"source_document_id":"tb-doc","title":"TB Training","original_filename":"tb.pdf"}]"""
    private val ancDocsJson =
        """[{"source_document_id":"anc-doc","title":"ANC Guide","original_filename":"anc.pdf"}]"""

    private val modules = listOf(
        module(
            familyId = "fam-tb",
            titleEn = "Tuberculosis Identification and Management",
            cardsJson = """
                [
                  {"title_en":"What is Tuberculosis?","body_en":"Tuberculosis is caused by the bacterium Mycobacterium Tuberculosis.",
                   "source_pages":[{"source_document_id":"tb-doc","page_number":159}]},
                  {"title_en":"Suspecting TB: Initial Steps","body_en":"If TB is suspected, begin treatment at once and refer the patient to a doctor immediately.",
                   "source_pages":[{"source_document_id":"tb-doc","page_number":161}]}
                ]
            """.trimIndent(),
            quizJson = """
                [
                  {"question_order":2,"question_en":"What should a CHW do first if TB is suspected?",
                   "explanation_en":"Start treatment immediately and refer the patient quickly to a doctor. (Card 2)"}
                ]
            """.trimIndent(),
            searchMetadataJson = """{"keywords_en":["sputum test","DOTS"],"clinical_conditions":["tuberculosis"]}""",
            sourceDocumentsJson = tbDocsJson,
        ),
        module(
            familyId = "fam-anc",
            titleEn = "Antenatal Care Management",
            cardsJson = """
                [
                  {"title_en":"ANC Visit Materials","body_en":"The health worker carries a BP machine, stethoscope, thermometer, tape, and a weighing scale.",
                   "source_pages":[{"source_document_id":"anc-doc","page_number":12}]},
                  {"title_en":"Danger Signs During Pregnancy","body_en":"Danger signs include vaginal bleeding, severe abdominal pain, high fever, and convulsions. Refer immediately.",
                   "source_pages":[{"source_document_id":"anc-doc","page_number":20}]}
                ]
            """.trimIndent(),
            sourceDocumentsJson = ancDocsJson,
        ),
    )

    private data class EvalCase(
        val query: String,
        val expectFamily: String?,   // null → expect no grounding
        val expectDocId: String?,
        val expectPage: Int?,
    )

    private val cases = listOf(
        EvalCase("what causes tuberculosis", "fam-tb", "tb-doc", 159),
        EvalCase("what should I do first if TB is suspected", "fam-tb", "tb-doc", 161),
        EvalCase("what are the danger signs to refer a pregnant woman", "fam-anc", "anc-doc", 20),
        EvalCase("who won the football match last night", null, null, null),
    )

    @Test
    fun `offline pipeline eval dashboard`() {
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val byFamily = modules.associateBy { it.moduleFamilyId }

        var falseRefusals = 0   // expected grounding, got none
        var falseServes = 0     // expected no grounding, served something
        var wrongModule = 0
        var attributionMisses = 0

        for (case in cases) {
            val hits = index.search(case.query, k = 3)
            val top = hits.firstOrNull()
            val served = top != null && !OffTopicGuard.isClearlyUnanswerable(
                query = case.query,
                topHit = top,
                clinicalTerms = scope.scopeTerms(),
            )

            if (case.expectFamily == null) {
                if (served) falseServes++
                continue
            }
            if (!served) {
                falseRefusals++
                continue
            }
            if (top!!.moduleFamilyId != case.expectFamily) {
                wrongModule++
                continue
            }
            val module = byFamily[top.moduleFamilyId]
            val resolved = SourceAttributionResolver.resolve(top, module!!.sourceDocuments)
            if (resolved.docs.firstOrNull()?.id != case.expectDocId || resolved.startPage != case.expectPage) {
                attributionMisses++
            }
        }

        println(
            "[ChatPipelineEval] cases=${cases.size} falseRefusals=$falseRefusals " +
                "falseServes=$falseServes wrongModule=$wrongModule attributionMisses=$attributionMisses",
        )
        assertEquals("false refusals", 0, falseRefusals)
        assertEquals("false serves", 0, falseServes)
        assertEquals("wrong module", 0, wrongModule)
        assertEquals("attribution misses", 0, attributionMisses)
    }
}
