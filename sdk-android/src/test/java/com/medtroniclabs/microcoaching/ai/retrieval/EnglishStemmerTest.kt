package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishStemmerTest {

    @Test
    fun `morphology pairs stem to shared root`() {
        assertEquals("prevent", EnglishStemmer.stemIfApplicable("prevention"))
        assertEquals("refer", EnglishStemmer.stemIfApplicable("referring"))
        assertEquals("manag", EnglishStemmer.stemIfApplicable("management"))
        // Classic Porter strips -al to -r (referr); query surface "refer" still matches
        // via the surface token on the query side.
        assertEquals("referr", EnglishStemmer.stemIfApplicable("referral"))
    }

    @Test
    fun `surface form is unchanged when already the stem`() {
        assertNull(EnglishStemmer.stemIfApplicable("prevent"))
        assertNull(EnglishStemmer.stemIfApplicable("refer"))
    }

    @Test
    fun `clinical stop-stem tokens are never stemmed`() {
        for (token in EnglishStemmer.STOP_STEM) {
            assertNull("stop-stem token must not emit extra stem: $token", EnglishStemmer.stemIfApplicable(token))
        }
    }

    @Test
    fun `bp readings and short tokens are preserved`() {
        assertFalse(EnglishStemmer.isStemmableAscii("90/60"))
        assertFalse(EnglishStemmer.isStemmableAscii("140/90"))
        assertFalse(EnglishStemmer.isStemmableAscii("bp"))
        assertNull(EnglishStemmer.stemIfApplicable("90/60"))
    }

    @Test
    fun `bangla tokens are not stemmable ascii`() {
        assertFalse(EnglishStemmer.isStemmableAscii("টিকা"))
        assertFalse(EnglishStemmer.isStemmableAscii("রক্তচাপ"))
    }

    @Test
    fun `dual emit via tokenizer bridges morphology`() {
        val indexTokens = BanglaTokenizer.tokenize("Diarrhea prevention measures")
        val queryTokens = BanglaTokenizer.tokenizeQuery("What should be done to prevent diarrhea?")
        assertTrue(indexTokens.contains("prevent"))
        assertTrue(queryTokens.contains("prevent"))
    }
}
