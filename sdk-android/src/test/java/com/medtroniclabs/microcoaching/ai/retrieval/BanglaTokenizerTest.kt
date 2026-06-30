package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BanglaTokenizerTest {

    @Test
    fun `NFC normalizes composed and decomposed forms to the same tokens`() {
        // "café" composed (é = U+00E9) vs decomposed (e + combining acute U+0301).
        val composed = BanglaTokenizer.tokenize("caf\u00E9 visit")
        val decomposed = BanglaTokenizer.tokenize("cafe\u0301 visit")
        assertEquals("NFC must collapse composed/decomposed to identical tokens", composed, decomposed)
    }

    @Test
    fun `zero-width joiners are stripped before tokenizing`() {
        // ZWNJ (U+200C) embedded inside a word must not split or alter the token.
        val withZwnj = BanglaTokenizer.tokenize("vaccine‌dose")
        val plain = BanglaTokenizer.tokenize("vaccinedose")
        assertEquals(plain, withZwnj)
    }

    @Test
    fun `bangla bigrams are symmetric so an inflected query overlaps the base form`() {
        // টিকা (vaccine) vs টিকার (of the vaccine — inflected). Whole-word tokens
        // differ, but the shared character bigrams must overlap on both sides.
        val base = BanglaTokenizer.tokenize("টিকা").toSet()
        val inflected = BanglaTokenizer.tokenize("টিকার").toSet()
        assertTrue(
            "inflected Bangla must share at least one bigram with the base form",
            base.intersect(inflected).isNotEmpty(),
        )
    }

    @Test
    fun `english stays whole-word with no character bigrams`() {
        val tokens = BanglaTokenizer.tokenize("referral threshold")
        assertTrue(tokens.contains("referral"))
        assertTrue(tokens.contains("threshold"))
        // No 2-char Latin bigrams like "re"/"th" should be emitted for ASCII.
        assertFalse(tokens.contains("re"))
        assertFalse(tokens.contains("th"))
    }

    // ── Clinical number patterns: digit/digit must survive as one token ──────
    //
    // The retrieval index loses the "this is a BP reading" signal if "90/60"
    // is split into ["90","60"] — those bare numbers are weak IDF and collide
    // with random page numbers or counts in card bodies.

    @Test
    fun `BP ratio survives as a single token`() {
        val tokens = BanglaTokenizer.tokenize("low BP 90/60")
        assertTrue("must keep '90/60' as one token", tokens.contains("90/60"))
        assertFalse("must NOT split into bare '90'", tokens.contains("90"))
        assertFalse("must NOT split into bare '60'", tokens.contains("60"))
    }

    @Test
    fun `hypertension threshold survives as a single token`() {
        val tokens = BanglaTokenizer.tokenize("BP 140/90 is the threshold")
        assertTrue(tokens.contains("140/90"))
    }

    @Test
    fun `slash between non-digits still splits`() {
        val tokens = BanglaTokenizer.tokenize("counselling/advice")
        assertTrue(tokens.contains("counselling"))
        assertTrue(tokens.contains("advice"))
        assertFalse(tokens.contains("counselling/advice"))
    }

    // ── Comparison operators: clinical thresholds must tokenize bare ─────────
    //
    // Card prose writes "BP ≥140/90 or <90/60". Without splitting on the
    // comparison symbol the indexed token is "<90/60", which the query token
    // "90/60" can never match — the verified reason the High-Risk ANC card
    // missed a "Low BP 90/60" query.

    @Test
    fun `comparison operators split off so thresholds match bare ratios`() {
        val tokens = BanglaTokenizer.tokenize("BP ≥140/90 or <90/60")
        assertTrue("must index '140/90' bare", tokens.contains("140/90"))
        assertTrue("must index '90/60' bare", tokens.contains("90/60"))
        assertFalse(tokens.contains("<90/60"))
        assertFalse(tokens.contains("≥140/90"))
    }

    @Test
    fun `sentence-final period does not stick to the word`() {
        val tokens = BanglaTokenizer.tokenize("Their heads should be covered.")
        assertTrue(tokens.contains("covered"))
        assertFalse(tokens.contains("covered."))
    }

    @Test
    fun `decimal numbers survive as one token`() {
        val tokens = BanglaTokenizer.tokenize("temperature 100.4 degrees")
        assertTrue("decimal threshold must stay whole", tokens.contains("100.4"))
    }

    // ── Term frequency must survive tokenization (BN scoring regression) ─────

    @Test
    fun `repeated bangla words keep their term frequency`() {
        // The previous implementation deduplicated all tokens whenever Bangla
        // bigrams were present, flattening TF to 1 for every Bangla document.
        val tokens = BanglaTokenizer.tokenize("টিকা টিকা টিকা")
        assertEquals("word TF must be preserved", 3, tokens.count { it == "টিকা" })
    }

    // ── Query-side stop-word removal (EN + BN parity) ─────────────────────────

    @Test
    fun `tokenizeQuery drops english stopwords but keeps clinical content`() {
        val tokens = BanglaTokenizer.tokenizeQuery("What should I advise to a PW with Low BP 90/60?")
        assertFalse(tokens.contains("what"))
        assertFalse(tokens.contains("should"))
        assertFalse(tokens.contains("to"))
        assertFalse(tokens.contains("with"))
        assertTrue("clinical qualifier 'low' must survive", tokens.contains("low"))
        assertTrue(tokens.contains("pw"))
        assertTrue(tokens.contains("bp"))
        assertTrue(tokens.contains("90/60"))
        assertTrue("generic verbs like 'advise' are content, not stopwords", tokens.contains("advise"))
    }

    @Test
    fun `tokenizeQuery drops bangla stopwords but keeps clinical content`() {
        // "গর্ভবতী মহিলার বিপদ চিহ্ন কী কী?" — "কী" (what) is a function word.
        val tokens = BanglaTokenizer.tokenizeQuery("গর্ভবতী মহিলার বিপদ চিহ্ন কী কী?")
        assertFalse(tokens.contains("কী"))
        assertTrue(tokens.contains("গর্ভবতী"))
        assertTrue(tokens.contains("বিপদ"))
    }

    @Test
    fun `tokenize keeps stopwords for index documents`() {
        // Index side must keep function words so document TF/length stay stable.
        val tokens = BanglaTokenizer.tokenize("praise the mother and reinforce")
        assertTrue(tokens.contains("the"))
        assertTrue(tokens.contains("and"))
    }

    // ── Word bigrams: phrase evidence on both sides, EN and BN ───────────────

    @Test
    fun `wordBigrams joins adjacent content words and skips stopwords`() {
        val bigrams = BanglaTokenizer.wordBigrams("What are the danger signs to refer")
        assertTrue("phrase token must be emitted", bigrams.contains("danger_signs"))
        assertTrue("stopword 'to' must not break adjacency", bigrams.contains("signs_refer"))
        assertFalse(bigrams.contains("the_danger"))
    }

    @Test
    fun `wordBigrams works for bangla phrases`() {
        val bigrams = BanglaTokenizer.wordBigrams("উচ্চ রক্তচাপ নিয়ন্ত্রণ")
        assertTrue(bigrams.contains("উচ্চ_রক্তচাপ"))
        assertTrue(bigrams.contains("রক্তচাপ_নিয়ন্ত্রণ"))
    }
}
