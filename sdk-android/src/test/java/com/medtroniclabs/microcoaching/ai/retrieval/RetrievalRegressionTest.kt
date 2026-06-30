package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the three verified retrieval failures from the
 * 2026-06-11 field log (`ChatTrace`, on-device corpus):
 *
 *  1. "What should I advise to a PW with Low BP 90/60?" — BM25 ranked the
 *     Immunization card top purely on `advise`/`should`/`to`; the High-Risk ANC
 *     card (which literally contains "BP ≥140/90 or <90/60") sat at rank 3.
 *  2. "How can Breast Engorgement and Pain be managed?" — "How to Keep Newborns
 *     Warm" won on `how`(title ×3) + `be` + `and`; no module covers engorgement
 *     at all, so the closest safe grounding is the breastfeeding card.
 *  3. "What are the danger signs to refer a pregnant woman?" — the explicit
 *     "Danger Signs During Pregnancy" card was out-ranked by cards matching
 *     scattered `woman`/`refer` tokens.
 *
 * The card bodies below are abridged from the real modules so the failures
 * reproduce structurally (same winning/losing token patterns), keeping the test
 * corpus small and readable.
 */
class RetrievalRegressionTest {

    private var familySeq = 0

    private fun module(titleEn: String, cardsJson: String) = moduleEntityFixture(
        moduleId = "m${++familySeq}",
        moduleFamilyId = "fam-$familySeq-${titleEn.lowercase().replace(Regex("[^a-z]+"), "-").take(24)}",
        titleEn = titleEn,
        cardsJson = cardsJson,
    )

    private fun buildCorpus(): ModuleKnowledgeIndex = ModuleKnowledgeIndex.build(
        listOf(
            module(
                "Child Health Services and Immunization Protocols",
                """[{"title_en":"Immunization Schedule","body_en":"To ensure child immunization, the Health Worker must counsel the mother to take the child for 10 vaccine-preventable doses. The Health Worker should advise the mother to visit the vaccination center 6 times and keep the information recorded."}]""",
            ),
            module(
                "Antenatal Care (ANC) Management",
                """[
                    {"title_en":"High-Risk Factors in ANC","body_en":"There are several criteria for determining high risk in pregnant women: 1. BP ≥140/90 or <90/60. 2. If edema is present AND BP≥140/90. 3. Hemoglobin levels: Mild <11, Moderate <10, Severe <8."},
                    {"title_en":"Danger Signs During Pregnancy","body_en":"Danger signs during pregnancy include vaginal bleeding, reduced fetal movements, severe abdominal pain, high fever, and convulsions. Refer immediately when any danger sign is present."}
                ]""",
            ),
            module(
                "Keeping the Newborn Warm",
                """[{"title_en":"How to Keep Newborns Warm","body_en":"To keep newborns warm, they should be dried quickly after birth and wrapped properly. Their heads should be covered. Parents should be educated on these points to help keep their newborns warm."}]""",
            ),
            module(
                "Effective Communication and Counselling Skills",
                """[{"title_en":"How to Give Advice","body_en":"First praise the mother for how well she is coping with the child and reinforce the good practices she is following. Praise must always precede any other advice."}]""",
            ),
            module(
                "Postnatal Care (PNC) Procedures and Guidelines",
                """[{"title_en":"Breastfeeding Counselling","body_en":"Provide breastfeeding counselling, teach correct breastfeeding techniques, and support the mother if she faces difficulties."}]""",
            ),
            module(
                "Recognising Postpartum Danger Signs",
                """[{"title_en":"Recognizing Danger Signs in Postpartum Women","body_en":"There are several danger signs in postpartum women that need to be recognized. These signs include excessive bleeding if the woman experiences bleeding of 100 milliliters or more, severe abdominal pain if the woman has severe pain, fever if the woman's temperature is 100.4 degrees or higher, difficulty breathing if the woman has trouble breathing, and mental stress if the woman feels exhausted."}]""",
            ),
        ),
    )

    @Test
    fun `low BP query grounds on the high-risk ANC card not immunization`() {
        val hits = buildCorpus().search("What should I advise to a PW with Low BP 90/60?", k = 3)
        assertTrue("expected at least one hit", hits.isNotEmpty())
        assertEquals(
            "top hit must be the card containing the BP <90/60 threshold",
            "High-Risk Factors in ANC",
            hits.first().titleEn,
        )
    }

    @Test
    fun `engorgement query bridges to breastfeeding instead of newborn warmth`() {
        val hits = buildCorpus().search("How can Breast Engorgement and Pain be managed?", k = 3)
        assertTrue("expected at least one hit", hits.isNotEmpty())
        assertEquals(
            "vocabulary bridge must surface the breastfeeding card",
            "Breastfeeding Counselling",
            hits.first().titleEn,
        )
        assertTrue(
            "stopword-driven newborn-warmth card must not be retrieved at all",
            hits.none { it.titleEn == "How to Keep Newborns Warm" },
        )
    }

    @Test
    fun `pregnant danger-signs query surfaces the pregnancy danger card in top hits`() {
        val hits = buildCorpus().search("What are the danger signs to refer a pregnant woman?", k = 3)
        assertTrue("expected hits", hits.isNotEmpty())
        assertTrue(
            "Danger Signs During Pregnancy must be retrieved (was missed entirely at k=2); got: " +
                hits.map { it.titleEn },
            hits.any { it.titleEn == "Danger Signs During Pregnancy" },
        )
    }

    @Test
    fun `prevent query reaches prevention card via stemming`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    "Diarrhea Management",
                    """[
                        {"title_en":"What is Diarrhea?","body_en":"Diarrhea is loose stools passed more than three times a day."},
                        {"title_en":"Diarrhea Prevention Measures","body_en":"Prevention includes hand washing, safe water, and ORS use."}
                    ]""",
                ),
            ),
        )
        val hits = index.search("What should be done to prevent diarrhea?", k = 3, scoreThreshold = 0f)
        assertTrue("expected hits", hits.isNotEmpty())
        assertEquals(
            "stemming must bridge prevent→prevention for the prevention card",
            "Diarrhea Prevention Measures",
            hits.first().titleEn,
        )
    }

    @Test
    fun `all-stopword query returns nothing instead of a random card`() {
        val hits = buildCorpus().search("what should I do and how can it be", k = 3)
        assertTrue("function-word-only query must not ground", hits.isEmpty())
    }

    // ── Tier 2 bridges: CHW vocabulary reaches the right card ────────────────
    //
    // From the chw_eval_uc3 bm25-low run: "fits" (→ convulsion) and "ITN"
    // (→ insecticide-treated net) are CHW words absent from the card prose, so
    // BM25 latched onto a generic-token card. The convulsion / malaria cards
    // below share NO original query token — they are retrievable ONLY via the
    // bridge, so their presence proves the bridge works.

    @Test
    fun `fits query reaches the convulsion card via the bridge`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    "Bleeding in Pregnancy",
                    """[{"title_en":"Heavy Vaginal Bleeding Before or After Delivery","body_en":"Heavy vaginal bleeding before or after delivery is a danger sign in a pregnant woman that needs emergency referral."}]""",
                ),
                module(
                    "Obstetric Emergencies",
                    """[{"title_en":"Recognizing Convulsions or Unconsciousness","body_en":"A convulsion or seizure with unconsciousness must be treated as an emergency; protect the airway and refer urgently."}]""",
                ),
            ),
        )
        // scoreThreshold=0f isolates "is it reachable via the bridge?" from the
        // production magnitude floor — a 2-card corpus deflates IDF (the real
        // ~200-chunk corpus scores the bridged term well above the floor).
        val hits = index.search("Is a woman having fits during delivery a danger sign?", k = 3, scoreThreshold = 0f)
        assertTrue(
            "the convulsion card is reachable only through the fits→convulsion bridge; got: " +
                hits.map { it.titleEn },
            hits.any { it.titleEn == "Recognizing Convulsions or Unconsciousness" },
        )
    }

    @Test
    fun `ITN query surfaces the malaria-prevention card over tobacco`() {
        val index = ModuleKnowledgeIndex.build(
            listOf(
                module(
                    "Tobacco Cessation",
                    """[{"title_en":"Harmful Effects of Tobacco Use","body_en":"Tobacco use causes tooth decay, gum disease, and many long-term harmful effects. Counsel patients to stop tobacco use."}]""",
                ),
                module(
                    "Malaria Prevention",
                    """[{"title_en":"Measures to Prevent Malaria","body_en":"Sleep under an insecticide-treated mosquito net every night. A treated bed net protects the whole family from mosquito bites."}]""",
                ),
            ),
        )
        val hits = index.search(
            "ভাই, ITN মানে কী এবং কখন use করতে হয়?",
            k = 3,
            scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.EN,
        )
        assertTrue("expected hits", hits.isNotEmpty())
        val malariaRank = hits.indexOfFirst { it.titleEn == "Measures to Prevent Malaria" }
        val tobaccoRank = hits.indexOfFirst { it.titleEn == "Harmful Effects of Tobacco Use" }
        assertTrue("malaria-prevention card must be retrieved; got: " + hits.map { it.titleEn }, malariaRank >= 0)
        assertTrue(
            "malaria must outrank tobacco (itn bridge beats the generic 'use' match); got: " + hits.map { it.titleEn },
            tobaccoRank < 0 || malariaRank < tobaccoRank,
        )
    }
}
