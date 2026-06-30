package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * English stemmer for ASCII alphabetic tokens (classic Porter algorithm, Release 3).
 *
 * Used symmetrically at index-build and query time via [BanglaTokenizer] dual-emit:
 * each stemmable token contributes both its surface form and its stem so exact-match
 * queries are unchanged while morphology variants (prevent/prevention, refer/referral)
 * gain overlap.
 *
 * Implementation ported from Apache Lucene's [PorterStemmer] (ASL 2.0).
 * Bangla tokens are never stemmed — they already use character-bigram matching.
 */
object EnglishStemmer {

    /**
     * Clinical abbreviations, numeric thresholds, and short tokens that must never
     * be stemmed — over-stemming here would break exact-match on BP readings and
     * protocol codes.
     */
    val STOP_STEM: Set<String> = setOf(
        "anc", "ors", "bp", "itn", "pnc", "pw", "htn", "hb", "dots",
        "90/60", "140/90", "cervix", "fits", "tb", "hiv", "aids",
        "unconscious", "hypotension", "engorgement",
    )

    private val porter = PorterStemmer()

    /**
     * Stem [surface] when it is an ASCII alphabetic token outside [STOP_STEM].
     * Returns null when no extra stem token should be emitted (unchanged, too short,
     * or non-stemmable).
     */
    fun stemIfApplicable(surface: String): String? {
        if (surface in STOP_STEM) return null
        if (!isStemmableAscii(surface)) return null
        val stem = porter.stem(surface)
        return stem.takeIf { it != surface && it.length >= 2 }
    }

    /** True when the token is long enough and purely lowercase ASCII letters. */
    fun isStemmableAscii(token: String): Boolean =
        token.length >= 3 && token.all { it in 'a'..'z' }

    /**
     * Classic Porter stemmer (Release 3), ported from Apache Lucene.
     * See https://snowballstem.org/algorithms/porter/stemmer.html
     */
    private class PorterStemmer {
        private var b = CharArray(50)
        private var i = 0
        private var j = 0
        private var k = 0
        private var k0 = 0
        private var dirty = false

        fun stem(s: String): String =
            if (stem(s.toCharArray(), s.length)) String(b, 0, i) else s

        private fun stem(word: CharArray, wordLen: Int): Boolean {
            i = 0
            dirty = false
            if (b.size < wordLen) b = CharArray(wordLen.coerceAtLeast(50))
            word.copyInto(b, 0, 0, wordLen)
            i = wordLen
            return stemInternal(0)
        }

        private fun stemInternal(i0: Int): Boolean {
            k = i - 1
            k0 = i0
            if (k > k0 + 1) {
                step1()
                step2()
                step3()
                step4()
                step5()
                step6()
            }
            if (i != k + 1) dirty = true
            i = k + 1
            return dirty
        }

        private fun cons(i: Int): Boolean = when (b[i]) {
            'a', 'e', 'i', 'o', 'u' -> false
            'y' -> if (i == k0) true else !cons(i - 1)
            else -> true
        }

        private fun m(): Int {
            var n = 0
            var idx = k0
            while (true) {
                if (idx > j) return n
                if (!cons(idx)) break
                idx++
            }
            idx++
            while (true) {
                while (true) {
                    if (idx > j) return n
                    if (cons(idx)) break
                    idx++
                }
                idx++
                n++
                while (true) {
                    if (idx > j) return n
                    if (!cons(idx)) break
                    idx++
                }
                idx++
            }
        }

        private fun vowelinstem(): Boolean {
            for (idx in k0..j) if (!cons(idx)) return true
            return false
        }

        private fun doublec(j: Int): Boolean {
            if (j < k0 + 1) return false
            if (b[j] != b[j - 1]) return false
            return cons(j)
        }

        private fun cvc(i: Int): Boolean {
            if (i < k0 + 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) return false
            val ch = b[i]
            return ch != 'w' && ch != 'x' && ch != 'y'
        }

        private fun ends(s: String): Boolean {
            val l = s.length
            val o = k - l + 1
            if (o < k0) return false
            for (idx in 0 until l) if (b[o + idx] != s[idx]) return false
            j = k - l
            return true
        }

        private fun setto(s: String) {
            val l = s.length
            val o = j + 1
            for (idx in 0 until l) b[o + idx] = s[idx]
            k = j + l
            dirty = true
        }

        private fun r(s: String) {
            if (m() > 0) setto(s)
        }

        private fun step1() {
            if (b[k] == 's') {
                when {
                    ends("sses") -> k -= 2
                    ends("ies") -> setto("i")
                    b[k - 1] != 's' -> k--
                }
            }
            if (ends("eed")) {
                if (m() > 0) k--
            } else if ((ends("ed") || ends("ing")) && vowelinstem()) {
                k = j
                when {
                    ends("at") -> setto("ate")
                    ends("bl") -> setto("ble")
                    ends("iz") -> setto("ize")
                    doublec(k) -> {
                        val ch = b[k--]
                        if (ch == 'l' || ch == 's' || ch == 'z') k++
                    }
                    m() == 1 && cvc(k) -> setto("e")
                }
            }
        }

        private fun step2() {
            if (ends("y") && vowelinstem()) {
                b[k] = 'i'
                dirty = true
            }
        }

        private fun step3() {
            if (k == k0) return
            when (b[k - 1]) {
                'a' -> {
                    if (ends("ational")) r("ate")
                    else if (ends("tional")) r("tion")
                }
                'c' -> {
                    if (ends("enci")) r("ence")
                    else if (ends("anci")) r("ance")
                }
                'e' -> if (ends("izer")) r("ize")
                'l' -> when {
                    ends("bli") -> r("ble")
                    ends("alli") -> r("al")
                    ends("entli") -> r("ent")
                    ends("eli") -> r("e")
                    ends("ousli") -> r("ous")
                }
                'o' -> when {
                    ends("ization") -> r("ize")
                    ends("ation") -> r("ate")
                    ends("ator") -> r("ate")
                }
                's' -> when {
                    ends("alism") -> r("al")
                    ends("iveness") -> r("ive")
                    ends("fulness") -> r("ful")
                    ends("ousness") -> r("ous")
                }
                't' -> when {
                    ends("aliti") -> r("al")
                    ends("iviti") -> r("ive")
                    ends("biliti") -> r("ble")
                }
                'g' -> if (ends("logi")) r("log")
            }
        }

        private fun step4() {
            when (b[k]) {
                'e' -> when {
                    ends("icate") -> r("ic")
                    ends("ative") -> r("")
                    ends("alize") -> r("al")
                }
                'i' -> if (ends("iciti")) r("ic")
                'l' -> when {
                    ends("ical") -> r("ic")
                    ends("ful") -> r("")
                }
                's' -> if (ends("ness")) r("")
            }
        }

        private fun step5() {
            if (k == k0) return
            val matched = when (b[k - 1]) {
                'a' -> ends("al")
                'c' -> ends("ance") || ends("ence")
                'e' -> ends("er")
                'i' -> ends("ic")
                'l' -> ends("able") || ends("ible")
                'n' -> ends("ant") || ends("ement") || ends("ment") || ends("ent")
                'o' -> (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || ends("ou")
                's' -> ends("ism")
                't' -> ends("ate") || ends("iti")
                'u' -> ends("ous")
                'v' -> ends("ive")
                'z' -> ends("ize")
                else -> false
            }
            if (matched && m() > 1) k = j
        }

        private fun step6() {
            j = k
            if (b[k] == 'e') {
                val a = m()
                if (a > 1 || (a == 1 && !cvc(k - 1))) k--
            }
            if (b[k] == 'l' && doublec(k) && m() > 1) k--
        }
    }
}
