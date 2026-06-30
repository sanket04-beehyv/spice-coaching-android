package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalSynonymMapTest {

    // ── expandQuery: BN → EN expansion ───────────────────────────────────────

    @Test
    fun `expandQuery on BN diarrhoea token includes English equivalent`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("ডায়রিয়া"))
        assertTrue("must include diarrhoea", result.contains("diarrhoea"))
        assertTrue("must include diarrhea", result.contains("diarrhea"))
    }

    @Test
    fun `expandQuery on BN chest token includes chest indrawing aliases`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("বুক"))
        assertTrue("must include chest indrawing", result.contains("chest indrawing"))
        assertTrue("must include indrawing", result.contains("indrawing"))
    }

    @Test
    fun `expandQuery on BN anaemia token includes both spellings and haemoglobin`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("অ্যানিমিয়া"))
        assertTrue("must include anaemia", result.contains("anaemia"))
        assertTrue("must include রক্তশূন্যতা", result.contains("রক্তশূন্যতা"))
        assertTrue("must include হিমোগ্লোবিন", result.contains("হিমোগ্লোবিন"))
    }

    // ── expandQuery: EN → BN expansion ───────────────────────────────────────

    @Test
    fun `expandQuery on EN anaemia includes BN equivalents`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("anaemia"))
        assertTrue("must include অ্যানিমিয়া", result.contains("অ্যানিমিয়া"))
        assertTrue("must include রক্তশূন্যতা", result.contains("রক্তশূন্যতা"))
    }

    @Test
    fun `expandQuery on EN dehydration includes BN equivalent`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("dehydration"))
        assertTrue("must include ডিহাইড্রেশন", result.contains("ডিহাইড্রেশন"))
    }

    // ── expandQuery: morphological / substring matching ───────────────────────

    @Test
    fun `expandQuery on বুকের (inflected) matches chest_indrawing group`() {
        // "বুকের" contains "বুক" so it should trigger the chest_indrawing group
        val result = ClinicalSynonymMap.expandQuery(listOf("বুকের"))
        assertTrue("inflected form must pull in chest indrawing aliases", result.contains("chest indrawing"))
    }

    // ── expandQuery: unknown token passes through unchanged ──────────────────

    @Test
    fun `unknown token passes through without expansion`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("xyzunknown"))
        assertTrue("unknown token must be preserved", result.contains("xyzunknown"))
        // Must not add clinical noise
        assertFalse(result.contains("diarrhoea"))
        assertFalse(result.contains("anaemia"))
    }

    @Test
    fun `expandQuery preserves all original tokens`() {
        val input = listOf("রেফার", "করতে", "হবে")
        val result = ClinicalSynonymMap.expandQuery(input)
        input.forEach { assertTrue("original token '$it' must be preserved", result.contains(it)) }
    }

    // ── Regression: the bidirectional-substring diarrhoea-bleed bug ──────────
    //
    // Before the fix, expandQuery matched aliases via `token in alias || alias in token`,
    // so the stop-word "to" (substring of "loose stool") pulled the entire diarrhoea
    // group into every English query containing "to". That silently grounded unrelated
    // questions (e.g. "low BP 90/60") on diarrhoea content. These cases guard against
    // that class of false positive — exact word match for English, phrase for multi-word,
    // prefix for Bangla.

    @Test
    fun `low BP query does not pull in the diarrhoea group`() {
        val out = ClinicalSynonymMap.expandQuery(
            listOf("what", "should", "advise", "to", "pw", "with", "low", "bp", "90", "60")
        ).toSet()
        assertFalse("diarrhoea must not bleed into a BP query", out.contains("diarrhoea"))
        assertFalse(out.contains("loose stool"))
        assertFalse(out.contains("ডায়রিয়া"))
    }

    @Test
    fun `stop word to alone must not match loose stool`() {
        assertFalse(ClinicalSynonymMap.expandQuery(listOf("to")).contains("diarrhoea"))
    }

    @Test
    fun `advance must not match the anc concept`() {
        val out = ClinicalSynonymMap.expandQuery(listOf("advance", "the", "form")).toSet()
        assertFalse("'advance' must not match alias 'anc'", out.contains("antenatal"))
        assertFalse(out.contains("anc"))
    }

    @Test
    fun `exact diarrhoea English token expands the diarrhoea group`() {
        val out = ClinicalSynonymMap.expandQuery(listOf("child", "has", "diarrhoea")).toSet()
        assertTrue(out.contains("loose stool"))
        assertTrue(out.contains("ডায়রিয়া"))
    }

    @Test
    fun `multi-word alias matches only as a contiguous phrase`() {
        val out = ClinicalSynonymMap.expandQuery(listOf("loose", "stool", "since", "morning")).toSet()
        assertTrue("contiguous 'loose stool' must trigger diarrhoea group", out.contains("diarrhoea"))
    }

    @Test
    fun `unrelated clinical query is not cross-contaminated`() {
        val out = ClinicalSynonymMap
            .expandQuery(listOf("how", "to", "manage", "high", "blood", "pressure"))
            .toSet()
        assertFalse(out.contains("diarrhoea"))
        assertFalse(out.contains("anaemia"))
        assertFalse(out.contains("loose stool"))
    }

    // ── allTerms: flat set covers key aliases ────────────────────────────────

    @Test
    fun `allTerms contains BN and EN for critical concepts`() {
        val terms = ClinicalSynonymMap.allTerms
        assertTrue(terms.contains("ডায়রিয়া"))
        assertTrue(terms.contains("diarrhoea"))
        assertTrue(terms.contains("বুক"))
        assertTrue(terms.contains("chest indrawing"))
        assertTrue(terms.contains("অ্যানিমিয়া"))
        assertTrue(terms.contains("anaemia"))
        assertTrue(terms.contains("ডিহাইড্রেশন"))
        assertTrue(terms.contains("dehydration"))
    }

    // ── expandQueryWeighted: two-tier weights ────────────────────────────────
    //
    // Original query tokens score at full weight; expansion terms are hints at
    // 0.4 — UNLESS the bridging source token has zero document frequency in the
    // corpus (vocabulary bridge), in which case the expansion is the only path
    // to grounding and carries full weight.

    @Test
    fun `original tokens always weigh 1`() {
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("low", "bp", "90/60")) { 5 }
        assertTrue(w["low"] == 1.0f)
        assertTrue(w["bp"] == 1.0f)
        assertTrue(w["90/60"] == 1.0f)
    }

    @Test
    fun `bp bridge expands to blood pressure at hint weight when bp exists in corpus`() {
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("bp")) { term ->
            if (term == "bp") 3 else 0
        }
        assertTrue("must add 'blood'", w.containsKey("blood"))
        assertTrue("must add 'pressure'", w.containsKey("pressure"))
        assertTrue("must add the phrase bigram", w.containsKey("blood_pressure"))
        assertTrue("alias weight must be < 1", w["blood"]!! < 1.0f)
    }

    @Test
    fun `engorgement bridge carries full weight when term is absent from corpus`() {
        // df("engorgement") == 0 → the CHW's word does not exist anywhere in the
        // module corpus; the breastfeeding bridge is the only route to grounding.
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("engorgement")) { 0 }
        assertTrue(w["breastfeeding"] == 1.0f)
        assertTrue(w["engorgement"] == 1.0f)
    }

    @Test
    fun `pw bridge adds pregnant woman vocabulary`() {
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("pw")) { 1 }
        assertTrue(w.containsKey("pregnant"))
        assertTrue(w.containsKey("woman"))
        assertTrue(w.containsKey("pregnant_woman"))
        assertTrue(w.containsKey("গর্ভবতী"))
    }

    @Test
    fun `weighted expansion never downgrades an original token`() {
        // "blood" is both an original token and a bridge target of "bp" — the
        // original weight 1.0 must win over the 0.4 expansion weight.
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("blood", "bp")) { 5 }
        assertTrue(w["blood"] == 1.0f)
    }

    @Test
    fun `group multi-word aliases decompose into matchable tokens`() {
        // "loose stool" can never match a tokenized document as one string —
        // the weighted expansion must emit its words plus the `_` bigram form.
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("diarrhoea")) { 5 }
        assertTrue(w.containsKey("loose"))
        assertTrue(w.containsKey("stool"))
        assertTrue(w.containsKey("loose_stool"))
        assertFalse(w.containsKey("loose stool"))
    }

    // ── Tier 2 bridges: fits → convulsion, itn → mosquito net ────────────────
    //
    // "fits"/"ITN" are CHW vocabulary that never tokens-match the card prose
    // ("convulsion", "mosquito net"). Both are out-of-vocabulary in the corpus
    // (df==0) so the bridge carries full weight — the only route to grounding.

    @Test
    fun `fits bridge expands to convulsion vocabulary at full weight when absent from corpus`() {
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("fits")) { 0 }
        assertTrue("must add 'convulsion'", w.containsKey("convulsion"))
        assertTrue("must add 'seizure'", w.containsKey("seizure"))
        assertTrue("OOV source → full weight", w["convulsion"] == 1.0f)
    }

    @Test
    fun `itn bridge expands to mosquito net vocabulary`() {
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("itn")) { 0 }
        assertTrue(w.containsKey("mosquito"))
        assertTrue(w.containsKey("net"))
        assertTrue(w.containsKey("insecticide"))
        assertTrue(w.containsKey("মশারি"))
    }

    @Test
    fun `fits and itn bridges are directional — unrelated query is untouched`() {
        // The bridge fires only when the literal source token is present.
        val out = ClinicalSynonymMap.expandQuery(listOf("how", "to", "manage", "fever")).toSet()
        assertFalse(out.contains("convulsion"))
        assertFalse(out.contains("mosquito"))
    }

    @Test
    fun `fits bridge does not fire on the substring benefits`() {
        // Exact-token gate: "benefits" must not trigger the "fits" bridge.
        val w = ClinicalSynonymMap.expandQueryWeighted(listOf("benefits", "of", "anc")) { 0 }
        assertFalse("'benefits' must not pull in convulsion vocabulary", w.containsKey("convulsion"))
    }
}
