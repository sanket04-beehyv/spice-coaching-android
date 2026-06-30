package com.medtroniclabs.microcoaching.ai.retrieval

import android.util.Log
import com.medtroniclabs.microcoaching.content.richtext.bodyToPlainText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedArray
import com.medtroniclabs.microcoaching.data.localized.readLocalizedBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * In-memory retrieval index over the on-device module corpus (B1 of chat_plan.md).
 *
 * Each module contributes one chunk per entry in `cards_json` (title + body, BN/EN
 * where available). Quiz JSON is **not** indexed — cards + `search_metadata` only.
 *
 * Scoring is **field-weighted** rather than a single flat token bag: each chunk is
 * tokenised into four independent BM25 fields per language —
 *   - TITLE    (strong, specific)
 *   - BODY     (the card prose)
 *   - QUESTION (per-card `retrieval_hints_*` / `questions_*`)
 *   - KEYWORD  (module `keywords`/`search_phrases_*` + per-card `keywords_*` +
 *              legacy per-card `retrieval_metadata`; low-weight recall hint)
 * and the per-field scores are combined as a weighted sum. Each field is
 * length-normalised against its OWN peers, so module-level metadata can no longer
 * inflate a card's body length and depress its real body TF — the bug that flattened
 * within-module ranking and de-calibrated the old absolute threshold. `synonyms_en`
 * is NOT indexed; it rides query expansion ([ClinicalSynonymMap]) instead.
 *
 * Two languages stay fully separate (EN query never scores against BN tokens).
 * Build cost is ≪ 100 ms for ~200 chunks. Rebuild on app start and after every
 * successful inbound module sync — never per query.
 */
class ModuleKnowledgeIndex private constructor(
    private val chunks: List<GroundingChunk>,
    private val scorersEn: Map<Field, Bm25Scorer>,
    private val scorersBn: Map<Field, Bm25Scorer>,
    /** Per-corpus `synonyms_en` (abbreviation → expansion) fed to query expansion. */
    private val dynamicSynonyms: Map<String, List<String>>,
) {

    /** Which per-language index a [search] call scores against. */
    enum class Lang { EN, BN }

    /** BM25 fields scored independently and combined by [fieldWeight]. */
    enum class Field { TITLE, BODY, QUESTION, KEYWORD }

    /**
     * Top-K most relevant chunks for [query]. Returns empty when nothing clears the
     * gate — caller treats that as the "no grounding found" refusal trigger (L2 in
     * chat_plan.md §B4).
     *
     * @param scoreThreshold absolute floor on the combined field-weighted score.
     *   `0f` (used by unit tests) bypasses the gate to isolate "was it indexed?" from
     *   production tuning. The default [DEFAULT_SCORE_THRESHOLD] is deliberately low —
     *   the semantic backstop is `OffTopicGuard` (clinical-token overlap) plus the
     *   downstream groundedness gate, not a hand-tuned BM25 magnitude.
     * @param language which per-language index to score against.
     */
    fun search(
        query: String,
        k: Int = 2,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        language: Lang = Lang.EN,
    ): List<GroundingChunk> {
        if (query.isBlank() || chunks.isEmpty()) {
            Log.i(TAG, "search lang=$language → 0 hits (blankQuery=${query.isBlank()} emptyIndex=${chunks.isEmpty()})")
            return emptyList()
        }
        val rawTokens = BanglaTokenizer.tokenize(query)
        val tokens = BanglaTokenizer.tokenizeQuery(query)
        if (tokens.isEmpty()) {
            Log.i(
                TAG,
                "search lang=$language → 0 hits (no content tokens; " +
                    "raw=${rawTokens.size} all-stopword=${rawTokens.isNotEmpty()})",
            )
            return emptyList()
        }
        val bigrams = BanglaTokenizer.wordBigrams(query)
        val scorers = if (language == Lang.BN) scorersBn else scorersEn
        // Document frequency for the bridge gate = max across fields: a term "exists in
        // the corpus" if it appears in ANY field, so a bridge only carries full weight
        // when its source word is genuinely absent everywhere (e.g. "engorgement").
        val df: (String) -> Int = { t -> scorers.values.maxOf { it.documentFrequency(t) } }
        val termWeights =
            ClinicalSynonymMap.expandQueryWeighted(tokens + bigrams, df, dynamicSynonyms)
        // The TITLE field is scored with the CHW's ACTUAL words only — never query
        // expansions. A title carries the heaviest weight, so letting a generic
        // expansion term land there (e.g. the "pw"→"pregnancy" bridge hitting a
        // "…During Pregnancy" title) drowns out the real discriminator the CHW typed
        // (e.g. "90/60"). Expansions still widen recall in BODY/QUESTION/KEYWORD.
        val originalWeights = (tokens + bigrams).associateWith { 1.0f }

        val combined = chunks.indices.map { i ->
            var s = 0f
            for (field in Field.entries) {
                val weights = if (field == Field.TITLE) originalWeights else termWeights
                val fieldScore = scorers.getValue(field).scoreWeighted(weights, i)
                if (fieldScore != 0f) s += fieldWeight(field) * fieldScore
            }
            i to s
        }
        val result = combined
            .filter { (_, s) -> if (scoreThreshold <= 0f) s > 0f else s >= scoreThreshold }
            .sortedByDescending { it.second }
            .take(k)
            .map { (i, s) -> chunks[i].copy(score = s) }

        val expansionTerms = termWeights.filterKeys { it !in tokens && it !in bigrams }
        val expansionNote = if (expansionTerms.isEmpty()) "" else {
            " expanded(+${expansionTerms.size})=" +
                expansionTerms.entries.joinToString(prefix = "[", postfix = "]") { (t, w) -> "$t(w=$w)" }
        }
        val droppedStopwords = rawTokens.count { it in BanglaTokenizer.STOPWORDS }
        Log.i(
            TAG,
            "search lang=$language thr=$scoreThreshold tokens=$tokens bigrams=$bigrams " +
                "stopwordsDropped=$droppedStopwords$expansionNote → " +
                "${result.size} hits: " +
                result.joinToString { "[%s %s %.2f]".format(it.source, it.moduleFamilyId, it.score) },
        )
        return result
    }

    /** Total number of chunks currently indexed. Exposed for telemetry / diagnostics. */
    val size: Int = chunks.size

    companion object {

        private const val TAG = "ModuleKnowledgeIndex"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Field weights for the combined score. TITLE is the strongest single-field
         * signal (replaces the old title×3 token-duplication hack); QUESTION is second
         * so per-card `retrieval_hints_*` strongly pull the intended card without
         * out-voting a literal title hit; KEYWORD is a low-weight recall floor
         * that lifts the right module into range without flattening within-module rank.
         */
        private const val W_TITLE = 3.0f
        private const val W_BODY = 1.0f
        private const val W_QUESTION = 2.5f
        private const val W_KEYWORD = 0.5f

        /**
         * Production retrieval floor on the combined score. Low by design (was an
         * absolute 3.0 that the metadata-in-body bug de-calibrated): with metadata moved
         * to its own field the body score is clean again, and the real semantic guards
         * are `OffTopicGuard` + the groundedness gate. A low floor here cuts false
         * refusals; it cannot admit hallucination on its own (the model never sees
         * un-retrieved content).
         */
        private const val DEFAULT_SCORE_THRESHOLD = 1.5f

        private fun fieldWeight(field: Field): Float = when (field) {
            Field.TITLE -> W_TITLE
            Field.BODY -> W_BODY
            Field.QUESTION -> W_QUESTION
            Field.KEYWORD -> W_KEYWORD
        }

        /** Build an empty index — useful as a no-op fallback before sync runs. */
        fun empty(): ModuleKnowledgeIndex {
            val emptyScorers = Field.entries.associateWith { Bm25Scorer(emptyList()) }
            return ModuleKnowledgeIndex(emptyList(), emptyScorers, emptyScorers, emptyMap())
        }

        private fun fieldTokens(text: String?): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            return BanglaTokenizer.tokenize(text) + BanglaTokenizer.wordBigrams(text)
        }

        /**
         * Build the index from a fresh module list. Caller is responsible for invoking
         * on a background dispatcher — the JSON parse + tokenisation is bounded but not
         * free. Only card content and `search_metadata` reach the index.
         */
        fun build(
            modules: List<ModuleEntity>,
            retiredFamilyIds: Set<String> = emptySet(),
        ): ModuleKnowledgeIndex {
            val activeModules = if (retiredFamilyIds.isEmpty()) {
                modules
            } else {
                modules.filter { it.moduleFamilyId !in retiredFamilyIds }
            }
            val chunks = mutableListOf<GroundingChunk>()

            // Per-field, per-language parallel token-document lists (same chunk indices).
            val titleEn = mutableListOf<List<String>>(); val bodyEn = mutableListOf<List<String>>()
            val questionEn = mutableListOf<List<String>>(); val keywordEn = mutableListOf<List<String>>()
            val titleBn = mutableListOf<List<String>>(); val bodyBn = mutableListOf<List<String>>()
            val questionBn = mutableListOf<List<String>>(); val keywordBn = mutableListOf<List<String>>()

            val dynamicSynonyms = LinkedHashMap<String, MutableList<String>>()
            var cardsWithSearchMetadata = 0
            var hintPhraseCount = 0

            for (m in activeModules) {
                val meta = parseSearchMetadata(m.searchMetadataJson)
                meta.synonyms.forEach { (k, vs) ->
                    dynamicSynonyms.getOrPut(k.lowercase()) { mutableListOf() }.addAll(vs)
                }

                val cardChunks = extractCardChunks(m)
                cardChunks.forEach { (_, _, cardMeta) ->
                    cardMeta.synonyms.forEach { (k, vs) ->
                        dynamicSynonyms.getOrPut(k.lowercase()) { mutableListOf() }.addAll(vs)
                    }
                }
                cardChunks.forEach { (chunk, rmeta, cardMeta) ->
                    if (cardMeta.hasSearchableContent) {
                        cardsWithSearchMetadata++
                        hintPhraseCount += cardMeta.hintsEn.size + cardMeta.hintsBn.size
                    }
                    chunks += chunk

                    titleEn += fieldTokens(chunk.titleEn)
                    bodyEn += fieldTokens(chunk.bodyEn)
                    questionEn += (
                        cardMeta.hintsEn + cardMeta.questionsEn
                        ).flatMap { fieldTokens(it) }
                    keywordEn += (
                        meta.keywordsEn + meta.phrasesEn + rmeta.allTerms + cardMeta.keywordsEn
                        ).flatMap { fieldTokens(it) }

                    titleBn += fieldTokens(chunk.titleBn)
                    bodyBn += fieldTokens(chunk.bodyBn)
                    questionBn += (
                        cardMeta.hintsBn + cardMeta.questionsBn
                        ).flatMap { fieldTokens(it) }
                    keywordBn += (
                        meta.keywordsBn + meta.phrasesBn + rmeta.allTerms + cardMeta.keywordsBn
                        ).flatMap { fieldTokens(it) }
                }
            }

            val scorersEn = mapOf(
                Field.TITLE to Bm25Scorer(titleEn),
                Field.BODY to Bm25Scorer(bodyEn),
                Field.QUESTION to Bm25Scorer(questionEn),
                Field.KEYWORD to Bm25Scorer(keywordEn),
            )
            val scorersBn = mapOf(
                Field.TITLE to Bm25Scorer(titleBn),
                Field.BODY to Bm25Scorer(bodyBn),
                Field.QUESTION to Bm25Scorer(questionBn),
                Field.KEYWORD to Bm25Scorer(keywordBn),
            )
            Log.i(
                TAG,
                "Built index — ${chunks.size} card chunks across ${activeModules.size} modules " +
                    "(retiredFamilies=${retiredFamilyIds.size} " +
                    "cardsWithSearchMetadata=$cardsWithSearchMetadata hintPhrases=$hintPhraseCount)",
            )
            return ModuleKnowledgeIndex(chunks, scorersEn, scorersBn, dynamicSynonyms)
        }

        /** Structured view over a module's `search_metadata`, split by index field + language. */
        private data class ParsedMetadata(
            val keywordsEn: List<String>,   // keywords_en + topic_tags + clinical_conditions
            val keywordsBn: List<String>,   // keywords_bn
            val phrasesEn: List<String>,    // search_phrases_en (QUESTION field)
            val phrasesBn: List<String>,    // search_phrases_bn (QUESTION field)
            val synonyms: Map<String, List<String>>, // synonyms_en → query expansion (NOT indexed)
        ) {
            companion object {
                val EMPTY = ParsedMetadata(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            }
        }

        /**
         * Parse `search_metadata` into KEYWORD streams (recall hints), QUESTION streams
         * (natural-language phrases), and the `synonyms_en` map (routed to query
         * expansion, never indexed). `audience`/`rationale`/`schema_version` ignored.
         * Any missing key, wrong shape, or unparseable JSON degrades to [ParsedMetadata.EMPTY].
         */
        private fun parseSearchMetadata(jsonText: String): ParsedMetadata {
            val obj = runCatching { json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
                ?: return ParsedMetadata.EMPTY
            fun strArr(key: String): List<String> =
                (obj[key] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
            val synonyms = (obj["synonyms_en"] as? JsonObject)?.entries.orEmpty()
                .mapNotNull { (k, v) ->
                    val key = k.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val value = (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    key to listOf(value)
                }.toMap()
            return ParsedMetadata(
                keywordsEn = obj.localizedStrArr("keywords", "en") +
                    strArr("topic_tags") + strArr("clinical_conditions"),
                keywordsBn = obj.localizedStrArr("keywords", "bn"),
                phrasesEn = obj.localizedStrArr("search_phrases", "en"),
                phrasesBn = obj.localizedStrArr("search_phrases", "bn"),
                synonyms = synonyms,
            )
        }

        /**
         * Optional per-card clinician-approved index hints carried as
         * `"retrieval_metadata": {"keywords": [...], "aliases": [...], "concepts": [...]}`.
         * Absent in current production content (folded into the KEYWORD field for any
         * legacy module that still ships it). Empty when the card has no metadata.
         */
        private data class RetrievalMetadata(
            val keywords: List<String>,
            val aliases: List<String>,
            val concepts: List<String>,
        ) {
            val allTerms: List<String> = keywords + aliases + concepts

            companion object {
                val EMPTY = RetrievalMetadata(emptyList(), emptyList(), emptyList())
            }
        }

        /**
         * Per-card `search_metadata` from the backend generator. Top-level keys are
         * supported for fixture overlays; production payloads nest under `search_metadata`.
         *
         * Field routing:
         *  - `retrieval_hints_*` + `questions_*` → QUESTION (×2.5)
         *  - `keywords_*` → KEYWORD on this card only (×0.5)
         *  - `synonyms_en` → query expansion (not indexed)
         */
        private data class CardSearchMetadata(
            val hintsEn: List<String>,
            val hintsBn: List<String>,
            val questionsEn: List<String>,
            val questionsBn: List<String>,
            val keywordsEn: List<String>,
            val keywordsBn: List<String>,
            val synonyms: Map<String, List<String>>,
        ) {
            val hasSearchableContent: Boolean
                get() = hintsEn.isNotEmpty() || hintsBn.isNotEmpty() ||
                    questionsEn.isNotEmpty() || questionsBn.isNotEmpty() ||
                    keywordsEn.isNotEmpty() || keywordsBn.isNotEmpty() ||
                    synonyms.isNotEmpty()

            companion object {
                val EMPTY = CardSearchMetadata(
                    emptyList(), emptyList(), emptyList(), emptyList(),
                    emptyList(), emptyList(), emptyMap(),
                )
            }
        }

        private fun extractCardChunks(
            module: ModuleEntity,
        ): List<Triple<GroundingChunk, RetrievalMetadata, CardSearchMetadata>> {
            val arr = runCatching {
                json.parseToJsonElement(module.cardsJson).jsonArray
            }.getOrNull() ?: return emptyList()
            return arr.mapIndexedNotNull { idx, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val title = obj.readLocalized("title")
                val titleBn = title.bn
                val titleEn = title.en
                val bodyBn = obj.localizedBodyPlain("body", "bn")
                val bodyEn = obj.localizedBodyPlain("body", "en")
                if (titleBn.isNullOrBlank() && bodyBn.isNullOrBlank() &&
                    titleEn.isNullOrBlank() && bodyEn.isNullOrBlank()
                ) return@mapIndexedNotNull null
                val chunk = GroundingChunk(
                    source = GroundingChunk.Source.CARD,
                    moduleFamilyId = module.moduleFamilyId,
                    positionalId = idx,
                    titleEn = titleEn,
                    bodyEn = bodyEn,
                    titleBn = titleBn,
                    bodyBn = bodyBn,
                    score = 0f,
                    sourcePages = obj.sourcePageRefs(),
                )
                Triple(chunk, obj.retrievalMetadata(), obj.cardSearchMetadata())
            }
        }

        private fun JsonObject.cardSearchMetadata(): CardSearchMetadata {
            val nested = this["search_metadata"] as? JsonObject
            return CardSearchMetadata(
                hintsEn = mergedLocalized("retrieval_hints", "en"),
                hintsBn = mergedLocalized("retrieval_hints", "bn"),
                questionsEn = mergedLocalized("questions", "en"),
                questionsBn = mergedLocalized("questions", "bn"),
                keywordsEn = mergedLocalized("keywords", "en"),
                keywordsBn = mergedLocalized("keywords", "bn"),
                synonyms = nested.synonymsEn(),
            )
        }

        private fun JsonObject.mergedLocalized(baseKey: String, lang: String): List<String> =
            localizedStrArr(baseKey, lang).ifEmpty {
                (this["search_metadata"] as? JsonObject)?.localizedStrArr(baseKey, lang).orEmpty()
            }

        private fun JsonObject.localizedStrArr(baseKey: String, lang: String): List<String> =
            readLocalizedArray(baseKey, lang).ifEmpty { strArr("${baseKey}_$lang") }

        private fun JsonObject.strArr(key: String): List<String> =
            (this[key] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList()

        private fun JsonObject?.synonymsEn(): Map<String, List<String>> {
            val obj = this ?: return emptyMap()
            return (obj["synonyms_en"] as? JsonObject)?.entries.orEmpty()
                .mapNotNull { (k, v) ->
                    val key = k.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val value = (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    key to listOf(value)
                }.toMap()
        }

        private fun JsonObject.retrievalMetadata(): RetrievalMetadata {
            val obj = this["retrieval_metadata"] as? JsonObject ?: return RetrievalMetadata.EMPTY
            return RetrievalMetadata(
                keywords = obj.strArr("keywords"),
                aliases = obj.strArr("aliases"),
                concepts = obj.strArr("concepts"),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        /**
         * Parse a card's `source_pages` into (document, page) anchors, keeping the
         * `source_document_id` so chat attribution can cite the exact document the card
         * was authored from. The current backend ships objects —
         * `[{"source_document_id":…,"page_number":159}, …]`; older payloads used a bare
         * int array `[159, 160]` (document id null). Returns null when absent, not an
         * array, or yielding no positive pages; non-positive entries are dropped.
         */
        private fun JsonObject.sourcePageRefs(): List<GroundingChunk.SourcePageRef>? =
            (this["source_pages"] as? JsonArray)
                ?.mapNotNull { el ->
                    when (el) {
                        is JsonObject -> {
                            val page = (el["page_number"] as? JsonPrimitive)?.intOrNull
                                ?: return@mapNotNull null
                            if (page <= 0) return@mapNotNull null
                            val docId = (el["source_document_id"] as? JsonPrimitive)?.contentOrNull
                            GroundingChunk.SourcePageRef(sourceDocumentId = docId, pageNumber = page)
                        }
                        is JsonPrimitive -> el.intOrNull?.takeIf { it > 0 }
                            ?.let { GroundingChunk.SourcePageRef(sourceDocumentId = null, pageNumber = it) }
                        else -> null
                    }
                }
                ?.takeIf { it.isNotEmpty() }

        /**
         * Extract a body field as plain, index-ready prose. Supports nested locale
         * maps (`body: {bn: [...], en: [...]}`), legacy flat keys, markdown strings,
         * and TipTap block arrays.
         */
        private fun JsonObject.localizedBodyPlain(baseKey: String, lang: String): String? {
            readLocalizedBody(baseKey, lang)?.let { raw ->
                return bodyToPlainText(raw).ifBlank { null }
            }
            return bodyText("${baseKey}_$lang")
        }

        /**
         * Extract a legacy flat body field as plain, index-ready prose. The field may be a markdown
         * string or a TipTap/ProseMirror block array — [bodyToPlainText] strips markup so
         * the BM25 index never sees node types, attrs, or media URLs. A bare [JsonObject]
         * (single block) is wrapped in `[…]` so the TipTap parser accepts it.
         */
        private fun JsonObject.bodyText(key: String): String? {
            val raw = when (val el = this[key]) {
                null -> return null
                is JsonPrimitive -> el.contentOrNull
                is JsonObject -> "[${el}]"
                else -> el.toString()
            } ?: return null
            return bodyToPlainText(raw).ifBlank { null }
        }
    }
}
