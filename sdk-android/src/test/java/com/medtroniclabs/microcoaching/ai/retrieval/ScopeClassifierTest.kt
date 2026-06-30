package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeClassifierTest {

    private fun moduleWithTitles(titleBn: String, titleEn: String? = null) = moduleEntityFixture(
        moduleId = "m1",
        moduleFamilyId = "fam1",
        titleBn = titleBn,
        titleEn = titleEn,
    )

    // ── Title tokenization fix ────────────────────────────────────────────────

    @Test
    fun `single word from multi-word Bengali title passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("ম্যালেরিয়া বোঝা")))
        // Query contains only the first word of the title, not the full phrase.
        // Before the fix this returned false because "ম্যালেরিয়া বোঝা" as a single
        // term is not a substring of "ম্যালেরিয়া জীবাণুর ধ্বংস".
        assertTrue(classifier.isInScope("ম্যালেরিয়া জীবাণুর ধ্বংস"))
    }

    @Test
    fun `full title phrase still passes scope check after tokenization`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("ম্যালেরিয়া বোঝা")))
        // The full phrase must also pass because "ম্যালেরিয়া" is present as a word.
        assertTrue(classifier.isInScope("ম্যালেরিয়া বোঝা সম্পর্কে"))
    }

    @Test
    fun `single word from English title passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("টাইটেল", "Malaria Overview")))
        assertTrue(classifier.isInScope("tell me about malaria treatment"))
    }

    @Test
    fun `short title words under 3 chars are not added to scope terms`() {
        // "ANC" is 3 chars (passes); "AN" would be 2 (filtered out)
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("AN বোঝা")))
        // "AN" is 2 chars so it must NOT be added — no false scope widening
        assertFalse(classifier.isInScope("an overview"))
        // But "বোঝা" (4 chars) must be present
        assertTrue(classifier.isInScope("বোঝা"))
    }

    // ── STATIC_TERMS additions ────────────────────────────────────────────────

    @Test
    fun `malaria term in STATIC_TERMS passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("malaria symptoms"))
        assertTrue(classifier.isInScope("ম্যালেরিয়া জ্বর"))
    }

    @Test
    fun `sepsis term in STATIC_TERMS passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("নবজাতকের সেপসিস কী"))
        assertTrue(classifier.isInScope("neonatal sepsis signs"))
    }

    @Test
    fun `cancer terms in STATIC_TERMS pass scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("cervical cancer screening"))
        assertTrue(classifier.isInScope("ক্যান্সার স্ক্রিনিং কীভাবে করতে হয়"))
    }

    @Test
    fun `malnutrition terms in STATIC_TERMS pass scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("child malnutrition prevention"))
        assertTrue(classifier.isInScope("অপুষ্টি প্রতিরোধ"))
    }

    // ── New BN terms from v3 field evaluation (R2) ───────────────────────────

    @Test
    fun `chest indrawing BN query is in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        // Q3 from the evaluation was a critical clinical failure — chest indrawing was L1 blocked
        assertTrue(classifier.isInScope("বুকের ভিতরে টান থাকলে কী করণীয়"))
        assertTrue(classifier.isInScope("বুকের টান"))
    }

    @Test
    fun `diarrhoea BN term is in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("ডায়রিয়ার মারাত্মক লক্ষণ কী"))
        assertTrue(classifier.isInScope("শিশুর ডায়রিয়া হলে কী করব"))
    }

    @Test
    fun `anaemia BN terms are in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("তীব্র অ্যানিমিয়ার লক্ষণ"))
        assertTrue(classifier.isInScope("রক্তশূন্যতার চিকিৎসা কী"))
    }

    @Test
    fun `dehydration BN term is in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("ডিহাইড্রেশন পরীক্ষা কীভাবে করবেন"))
    }

    @Test
    fun `synonym map contributes additional scope terms`() {
        // ClinicalSynonymMap.allTerms is merged into the classifier; পানিশূন্যতা is an alias
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("পানিশূন্যতা নির্ণয়"))
    }

    // ── Title harvest must not admit function words as clinical terms ────────
    //
    // The `length >= 3` gate alone let "and"/"the" into the gazetteer (from
    // titles like "Maternal and Neonatal Referral Process"), and OffTopicGuard
    // then counted "and" as clinical overlap — the verified hole that let a
    // breast-engorgement query pass the garbage guard against a newborn-warmth
    // card whose only shared "clinical" token was "and".

    @Test
    fun `function words from module titles are not harvested as scope terms`() {
        val classifier = ScopeClassifier.buildFrom(
            listOf(
                moduleWithTitles("মডিউল", "Maternal and Neonatal Referral Process"),
                moduleWithTitles("মডিউল", "Keeping the Newborn Warm"),
            ),
        )
        assertFalse("'and' must not be a scope term", classifier.scopeTerms().contains("and"))
        assertFalse("'the' must not be a scope term", classifier.scopeTerms().contains("the"))
        assertTrue(classifier.scopeTerms().contains("maternal"))
        assertTrue(classifier.scopeTerms().contains("newborn"))
    }

    @Test
    fun `guard refuses engorgement query against newborn-warmth card after harvest fix`() {
        // End-to-end regression of the 2026-06-11 failure: gazetteer built from
        // real-shaped module titles, guard evaluated against the actual top hit.
        val classifier = ScopeClassifier.buildFrom(
            listOf(
                moduleWithTitles("মডিউল", "Keeping the Newborn Warm"),
                moduleWithTitles("মডিউল", "Maternal and Neonatal Referral Process"),
            ),
        )
        val newbornWarmthChunk = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "fam1",
            positionalId = 1,
            titleEn = "How to Keep Newborns Warm",
            bodyEn = "To keep newborns warm, they should be dried quickly after birth and wrapped properly.",
            titleBn = null,
            bodyBn = null,
            score = 8.39f,
        )
        assertTrue(
            "zero genuine clinical overlap must refuse — 'and' no longer counts",
            OffTopicGuard.isClearlyUnanswerable(
                query = "How can Breast Engorgement and Pain be managed?",
                topHit = newbornWarmthChunk,
                clinicalTerms = classifier.scopeTerms(),
            ),
        )
    }

    // ── Deny list independence ────────────────────────────────────────────────

    @Test
    fun `deny list still blocks clearly out-of-scope queries`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("ম্যালেরিয়া বোঝা")))
        assertTrue(classifier.isOutOfScope("ফুটবল খেলার নিয়ম"))
        assertTrue(classifier.isOutOfScope("cricket world cup"))
        assertTrue(classifier.isOutOfScope("python programming tutorial"))
    }

    @Test
    fun `deny list wins even when clinical term is also present`() {
        // "ক্রিকেট" is in DENY_TERMS; even if a clinical word appears,
        // isOutOfScope should return true.
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isOutOfScope("ম্যালেরিয়া ক্রিকেট"))
    }

    // ── Tier 3a: creative-generation requests are denied ─────────────────────
    //
    // "poem about nature" latched onto a "Nature of Diarrhoea" card on the
    // low-end path (no L1 in ExtendedClinical), so L0 deny is the gate.

    @Test
    fun `creative generation requests are out of scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isOutOfScope("Can you write me a poem about nature?"))
        assertTrue(classifier.isOutOfScope("প্রকৃতি নিয়ে একটা কবিতা লিখে দাও"))
        assertTrue(classifier.isOutOfScope("write the lyrics to a song"))
        assertTrue(classifier.isOutOfScope("write an essay about rivers"))
    }

    @Test
    fun `deny list additions do not over-refuse clinical requests`() {
        // Substring-collision guard: bare "write"/"story" were deliberately NOT
        // added, so these clinical phrasings must NOT be denied.
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertFalse("'history' must not trip 'story'", classifier.isOutOfScope("take the patient history"))
        assertFalse("'write a referral' must not be denied", classifier.isOutOfScope("write a referral note"))
        assertFalse(classifier.isOutOfScope("রোগীর হিস্ট্রি নিন"))
    }

    // ── Tier 3b: generic structural words are not harvested as scope terms ───

    @Test
    fun `generic title words are not harvested as scope terms`() {
        val classifier = ScopeClassifier.buildFrom(
            listOf(moduleWithTitles("মডিউল", "Identifying the Nature of Diarrhoea")),
        )
        assertFalse("'nature' must not become a scope term", classifier.scopeTerms().contains("nature"))
        assertFalse("'identifying' must not become a scope term", classifier.scopeTerms().contains("identifying"))
        // The genuine clinical word from the title survives.
        assertTrue(classifier.scopeTerms().contains("diarrhoea"))
    }

    @Test
    fun `nature alone is no longer in scope but diarrhoea still is`() {
        val classifier = ScopeClassifier.buildFrom(
            listOf(moduleWithTitles("মডিউল", "Identifying the Nature of Diarrhoea")),
        )
        // "poem about nature" no longer matches a clinical term via the harvested
        // "nature"; the diarrhoea card title word remains in scope.
        assertFalse(classifier.isInScope("a poem about nature"))
        assertTrue(classifier.isInScope("child has diarrhoea"))
    }

    // ── Tier 2 STATIC_TERMS parity: fits / itn pass Strict-mode L1 ───────────

    @Test
    fun `fits and itn are in scope for strict-mode L1`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("is fits during delivery a danger sign"))
        assertTrue(classifier.isInScope("what is ITN and when to use it"))
    }
}
