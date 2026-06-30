package com.medtroniclabs.microcoaching.ai.retrieval

import java.text.Normalizer

/**
 * Tokenizer used by [ModuleKnowledgeIndex] for both index-build and query-time.
 *
 * Design:
 * - Normalises to Unicode NFC and strips zero-width joiner / non-joiner
 *   (ZWJ U+200D, ZWNJ U+200C) up front, so visually identical Bangla that differs
 *   only in code-point composition or invisible joiners tokenises identically.
 * - Splits on whitespace, ASCII punctuation, comparison operators (`< > ≥ ≤ = + ±`)
 *   and the Bangla full stop `।`.
 * - Lowercases ASCII characters; Bangla characters are case-less, so no-op there.
 * - Drops single-character tokens and pure punctuation.
 * - Emits **character bigrams of Bangla-script runs** alongside the whole-word
 *   tokens, so morphologically inflected forms — common in Bangla compounding
 *   (e.g. টিকার vs টিকা) — still match.
 * - **Dual-emits English stems** (classic Porter via [EnglishStemmer]) alongside the
 *   surface token so morphology variants (prevent/prevention, refer/referral)
 *   overlap without losing exact-match behaviour on the surface form.
 *
 * Crucially, bigrams are emitted on **both** the index and query sides (this method
 * is the single tokeniser for both passes): a query bigram only contributes to BM25
 * if the same bigram exists in the index vocabulary. Restricting bigrams to Bangla
 * script keeps the index bounded — ASCII/English stays whole-word — which is more
 * than affordable for the ≤ 200 chunk pilot corpus. Same trick as Lucene's CJK
 * analyser: cheap, no stemmer needed.
 *
 * Query-side helpers ([tokenizeQuery], [wordBigrams]) additionally drop EN/BN
 * stop-words. Stop-words stay IN the index documents (so document length and TF
 * statistics are stable) but are removed from queries — the verified failure this
 * closes is BM25 ranking "Immunization Schedule" top for "What should I advise to
 * a PW with Low BP 90/60?" purely on `advise`/`should`/`to`.
 */
object BanglaTokenizer {

    // Splits on whitespace, ASCII punctuation, comparison/relational symbols, and
    // the Bangla full stop. The `/` and `.` alternations only fire when at least
    // one side is NOT a digit, so clinical number patterns like "90/60" (blood
    // pressure), "140/90", or "100.4" (temperature) survive as a single token —
    // BP readings and decimal thresholds are meaningful only whole. The
    // comparison symbols matter for the same clinical text: card bodies write
    // "BP ≥140/90 or <90/60"; without splitting on `<`/`≥` the index token is
    // "<90/60", which a query token "90/60" can never match.
    private val splitPattern =
        Regex("""(?:[\s।,?!;:।\-()\[\]{}"'“”‘’<>≥≤=+±*#%&|~`]|(?<!\d)/|/(?!\d)|(?<!\d)\.|\.(?!\d))+""")
    private val zeroWidthJoiners = setOf(Char(0x200C), Char(0x200D)) // ZWNJ, ZWJ
    private val banglaRange = 0x0980..0x09FF

    /**
     * Function words excluded from QUERY tokenisation (never from index documents).
     * English and Bangla sides are maintained together so retrieval behaves the
     * same for both UI languages. Deliberately conservative: clinically meaningful
     * qualifiers ("low", "high", "pain", "severe", "danger", না-র মতো নেগেশন বাদে
     * প্রায় সব প্রশ্নবোধক/সংযোজক শব্দ) are NOT listed.
     */
    val STOPWORDS: Set<String> = setOf(
        // English
        "a", "an", "the", "and", "or", "but", "if", "then", "else",
        "when", "where", "why", "how", "what", "which", "who", "whom", "whose",
        "this", "that", "these", "those", "there", "here",
        "i", "me", "my", "we", "our", "us", "you", "your", "he", "him", "his",
        "she", "her", "it", "its", "they", "them", "their",
        "am", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "done",
        "will", "would", "can", "could", "shall", "should", "may", "might", "must",
        "of", "in", "on", "at", "to", "for", "with", "from", "by", "as",
        "into", "about", "over", "under", "between", "during", "after", "before",
        "again", "further", "once", "all", "any", "both", "each", "few",
        "more", "most", "other", "some", "such", "only", "own", "same",
        "so", "than", "too", "very", "just", "also", "please", "etc",
        // Bangla (question words, conjunctions, pronouns, auxiliaries)
        "কি", "কী", "কেন", "কীভাবে", "কিভাবে", "কোথায়", "কখন", "কে", "কারা",
        "কোন", "কোনো", "এবং", "ও", "আর", "বা", "অথবা", "কিন্তু", "তবে",
        "যদি", "তাহলে", "এই", "এটা", "এটি", "ঐ", "ওই", "সেই", "সে", "তা",
        "তার", "তাদের", "আমি", "আমরা", "আমার", "আপনি", "আপনার", "তুমি", "তোমার",
        "হয়", "হবে", "হলে", "হচ্ছে", "ছিল", "করা", "করে", "করতে", "করবেন", "করব",
        "দিয়ে", "দিতে", "জন্য", "থেকে", "সাথে", "সঙ্গে", "মধ্যে", "উপর",
        "এর", "একটি", "একটা", "কিছু", "সব", "অনেক", "খুব",
        "যা", "যে", "যেতে", "পারে", "পারি", "পারেন", "উচিত",
    )

    /** Tokenise a free-text string for indexing or querying — symmetric on both sides. */
    fun tokenize(text: String): List<String> {
        val words = splitWords(text)
        if (words.isEmpty()) return emptyList()
        // No de-duplication: term frequency must survive for BM25. The previous
        // implementation called .distinct() whenever Bangla bigrams were present,
        // which silently flattened ALL term frequencies to 1 for Bangla documents
        // — the Bangla index scored on presence only while English kept full TF.
        return words + words.flatMap(::banglaBigramsOf)
    }

    /**
     * Tokenise a QUERY: same pipeline as [tokenize] but with [STOPWORDS] removed
     * before bigram emission (so stop-word fragments don't leak back in through
     * the Bangla character-bigram channel either).
     */
    fun tokenizeQuery(text: String): List<String> {
        val words = splitWords(text).filterNot { it in STOPWORDS }
        if (words.isEmpty()) return emptyList()
        return words + words.flatMap(::banglaBigramsOf)
    }

    /**
     * Adjacent-pair word bigrams over the stop-word-filtered token sequence,
     * joined with `_` (e.g. "danger signs" → `danger_signs`, "উচ্চ রক্তচাপ" →
     * `উচ্চ_রক্তচাপ`). Emitted on BOTH index and query sides — a phrase match
     * like `danger_signs` is far stronger evidence than the two words scattered
     * through a long card body, which is exactly the signal plain unigram BM25
     * lacks on a corpus this small. Works identically for EN and BN words.
     */
    fun wordBigrams(text: String): List<String> {
        val content = splitSurfaceWords(text).filterNot { it in STOPWORDS }
        if (content.size < 2) return emptyList()
        return (0 until content.size - 1).map { i -> "${content[i]}_${content[i + 1]}" }
    }

    /**
     * Shared first pass: normalise, split, lowercase, drop sub-token fragments.
     * Surface forms only — used by [wordBigrams] so phrase tokens are not broken
     * by stem duplicates inserted between adjacent words.
     */
    private fun splitSurfaceWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            .filterNot { it in zeroWidthJoiners }
        return splitPattern
            .split(normalized)
            .map { it.lowercase() }
            .filter { it.length >= 2 || it.isBanglaCharacter() }
            .filter { it.isNotBlank() }
    }

    /**
     * [splitSurfaceWords] plus Porter stem dual-emit for ASCII alphabetic tokens.
     */
    private fun splitWords(text: String): List<String> =
        splitSurfaceWords(text).flatMap { word ->
            val stem = EnglishStemmer.stemIfApplicable(word)
            if (stem == null) listOf(word) else listOf(word, stem)
        }

    /**
     * Character bigrams of the Bangla-script runs inside a single word token.
     * Runs are isolated first so a bigram never mixes scripts. (Words arrive
     * pre-split, so a bigram can never straddle a word boundary.)
     */
    private fun banglaBigramsOf(word: String): List<String> {
        val out = mutableListOf<String>()
        val run = StringBuilder()
        fun flush() {
            for (i in 0 until run.length - 1) out.add(run.substring(i, i + 2))
            run.setLength(0)
        }
        for (ch in word) {
            if (ch.code in banglaRange) run.append(ch) else flush()
        }
        flush()
        return out
    }

    private fun String.isBanglaCharacter(): Boolean =
        length == 1 && this[0].code in banglaRange
}
