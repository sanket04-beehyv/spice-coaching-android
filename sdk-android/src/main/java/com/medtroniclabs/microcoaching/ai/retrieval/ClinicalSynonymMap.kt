package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * Clinical concept synonym groups used by both the scope classifier and the BM25
 * query expander. Each entry maps a canonical concept name to the full set of
 * aliases in English, Bangla, and common morphological variants.
 *
 * To add a new concept as scope grows: add one entry to [GROUPS]. Both
 * [ScopeClassifier.buildFrom] (via [allTerms]) and [ModuleKnowledgeIndex.search]
 * (via [expandQuery]) benefit automatically — no other code changes needed.
 */
object ClinicalSynonymMap {

    private val GROUPS: Map<String, Set<String>> = mapOf(
        "anaemia" to setOf(
            "anaemia", "anemia", "haemoglobin", "hemoglobin", "severe anaemia",
            "অ্যানিমিয়া", "রক্তশূন্যতা", "হিমোগ্লোবিন", "রক্তস্বল্পতা",
        ),
        "diarrhoea" to setOf(
            "diarrhoea", "diarrhea", "loose stool", "watery stool",
            "ডায়রিয়া", "পাতলা পায়খানা",
        ),
        "chest_indrawing" to setOf(
            "chest indrawing", "chest retraction", "indrawing", "respiratory distress",
            "বুক", "টান", "বুকের টান", "বুকের ভিতরে টান",
        ),
        "dehydration" to setOf(
            "dehydration", "dehydrated",
            "ডিহাইড্রেশন", "পানিশূন্যতা",
        ),
        "jaundice" to setOf(
            "jaundice", "icterus",
            "জন্ডিস", "পীলিয়া",
        ),
        "sepsis" to setOf(
            "sepsis", "blood infection", "neonatal sepsis",
            "সেপসিস", "রক্তের বিষক্রিয়া",
        ),
        "malnutrition" to setOf(
            "malnutrition", "stunting", "wasting", "undernutrition",
            "অপুষ্টি", "পুষ্টিহীনতা",
        ),
        "referral" to setOf(
            "referral", "refer", "hospital referral",
            "রেফার", "রেফারেল", "হাসপাতাল পাঠানো",
        ),
        "anc_visit" to setOf(
            "anc", "antenatal", "antenatal visit", "anc visit", "rounds",
            "এএনসি", "দফা", "গর্ভকালীন সেবা",
        ),
        // Add new groups here as scope expands — no logic changes needed.
    )

    /**
     * Directional abbreviation / vocabulary bridges for query expansion.
     *
     * PHASE3_RETIRE: delete when backend `synonyms_en` backfills bridge keys (see execution plan §6.4).
     */
    private val BRIDGES: Map<String, List<String>> = mapOf(
        "pw" to listOf("pregnant", "woman", "pregnancy", "pregnant_woman", "গর্ভবতী", "গর্ভ"),
        "bp" to listOf("blood", "pressure", "blood_pressure", "রক্তচাপ"),
        "বিপি" to listOf("রক্তচাপ", "blood", "pressure", "blood_pressure"),
        "প্রেসার" to listOf("রক্তচাপ", "blood", "pressure", "blood_pressure"),
        "htn" to listOf("hypertension", "উচ্চ_রক্তচাপ"),
        "hb" to listOf("haemoglobin", "hemoglobin", "হিমোগ্লোবিন"),
        "engorgement" to listOf("breastfeeding", "breast", "স্তন্যপান"),
        "engorged" to listOf("breastfeeding", "breast", "স্তন্যপান"),
        "hypotension" to listOf("low", "blood", "pressure", "blood_pressure", "রক্তচাপ"),
        "ors" to listOf("oral", "rehydration", "খাবার_স্যালাইন", "স্যালাইন"),
        // "fits" is the CHW's everyday word for the clinical "convulsion"/"seizure";
        // it tokens-matches neither, so a "fits during delivery" query latched onto
        // the generic delivery/danger/sign tokens of the wrong card. Directional and
        // exact-token-gated, so non-clinical uses of "fits" only add a low-impact
        // body/keyword signal (the TITLE field is scored on the CHW's literal words).
        "fits" to listOf("convulsion", "convulsions", "seizure"),
        // "ITN" (insecticide-treated net) is out-of-vocabulary in card prose; without
        // this the generic "use" token pulled a "tobacco use" card over malaria.
        "itn" to listOf(
            "mosquito", "net", "mosquito_net", "insecticide", "treated", "bed_net", "মশারি",
        ),
        // Add new bridges here — keep them directional and specific.
    )

    /** Weight applied to expansion terms whose source token EXISTS in the corpus. */
    private const val ALIAS_WEIGHT = 0.4f

    /** Flat set of all aliases across every group. Consumed by [ScopeClassifier.buildFrom]. */
    val allTerms: Set<String> by lazy { GROUPS.values.flatten().toSet() }

    /**
     * Expand a tokenized query with the synonyms of any clinical concept the query
     * ACTUALLY mentions. Matching is deliberately conservative (see
     * `ClinicalSynonymMapTest`):
     *
     *  - multi-word alias ("loose stool")  → matched as a contiguous phrase against
     *    the whole query, never against individual tokens.
     *  - single-word English alias         → EXACT token match only.
     *  - single-word Bangla alias          → prefix match, so the agglutinated form
     *    "বুকের" still matches the "বুক" stem (Bangla inflects by suffix).
     *
     * Why this is strict: the previous version matched bidirectional substrings
     * (`token in alias || alias in token`). The stop-word "to" is a substring of
     * "loose stool", so EVERY English query containing "to" pulled the entire
     * diarrhoea group into the BM25 query — silently grounding unrelated questions
     * (e.g. "low BP 90/60") on diarrhoea content. Likewise "anc" is a substring of
     * "advance"/"chance". Restricting English aliases to exact word matches and
     * Bangla aliases to prefix matches removes that whole class of false positive
     * while preserving BN↔EN coverage.
     *
     * Consumed by [ModuleKnowledgeIndex.search] to widen BM25 token coverage across
     * BN↔EN vocabulary boundaries without requiring stemming or embeddings.
     */
    fun expandQuery(tokens: List<String>): List<String> {
        val lower = tokens.map { it.lowercase() }
        val queryTokens = lower.toSet()
        val queryText = lower.joinToString(" ")
        val expanded = lower.toMutableSet()
        for (aliases in GROUPS.values) {
            if (aliases.any { alias -> aliasMatchesQuery(alias, queryTokens, queryText) }) {
                expanded += aliases
            }
        }
        return expanded.toList()
    }

    /**
     * Weighted query expansion — the retrieval-quality successor to [expandQuery].
     *
     * Returns `term → weight` for [Bm25Scorer.topKWeighted]:
     *  - every original query token keeps weight **1.0** — what the CHW typed is
     *    always the strongest signal;
     *  - [GROUPS] aliases and [BRIDGES] targets join at **0.4** — enough to pull
     *    the right module into range, never enough to outvote the original words
     *    (full-weight expansion verifiably flips a "low BP" query onto the
     *    hypertension module);
     *  - EXCEPT when the bridging source token has **zero document frequency** in
     *    the index being queried ([df] returns 0). Then the CHW's word simply does
     *    not exist in the corpus ("engorgement"), the bridge is the only path to
     *    any grounding, and its targets get full weight 1.0.
     *
     * Multi-word aliases are decomposed into their words plus the `_`-joined
     * bigram token, so they actually match the indexed vocabulary (the unweighted
     * [expandQuery] adds them as single space-joined strings, which can never
     * match a tokenized document).
     */
    fun expandQueryWeighted(tokens: List<String>, df: (String) -> Int): Map<String, Float> =
        expandQueryWeighted(tokens, df, dynamicBridges = emptyMap())

    /**
     * Weighted query expansion with per-corpus [dynamicBridges] layered on top of the
     * static [GROUPS]/[BRIDGES]. The dynamic bridges carry a module's `synonyms_en`
     * (e.g. `{"DOTS": ["directly observed treatment, short-course"]}`) — directional
     * abbreviation→expansion, exactly like the static [BRIDGES]. Synonyms belong in
     * QUERY expansion, not in the document index (indexing them dumps undifferentiated
     * tokens into every card of a module and flattens within-module ranking); routing
     * them here widens recall across the abbreviation/expansion boundary without that
     * cost. Keys are matched case-insensitively against the query tokens.
     */
    fun expandQueryWeighted(
        tokens: List<String>,
        df: (String) -> Int,
        dynamicBridges: Map<String, List<String>>,
    ): Map<String, Float> {
        val lower = tokens.map { it.lowercase() }
        val queryTokens = lower.toSet()
        val queryText = lower.joinToString(" ")
        val weights = LinkedHashMap<String, Float>()
        lower.forEach { weights[it] = 1.0f }

        fun addExpansion(term: String, weight: Float) {
            // Multi-word alias → its words + the matching word-bigram token. Split on
            // punctuation too so a synonym value like "treatment, short-course" yields
            // clean tokens rather than "short-course," fragments.
            val parts = term.split(' ', '_', ',', '.', ';', ':', '-').filter { it.isNotBlank() }
            val expansionTokens = if (parts.size > 1) parts + parts.zipWithNext { a, b -> "${a}_$b" } else parts
            for (t in expansionTokens) {
                weights[t] = maxOf(weights[t] ?: 0f, weight)
            }
        }

        for (aliases in GROUPS.values) {
            if (aliases.any { alias -> aliasMatchesQuery(alias, queryTokens, queryText) }) {
                aliases.forEach { addExpansion(it, ALIAS_WEIGHT) }
            }
        }
        for ((source, targets) in BRIDGES) {
            if (source !in queryTokens) continue
            val weight = if (df(source) == 0) 1.0f else ALIAS_WEIGHT
            targets.forEach { addExpansion(it, weight) }
        }
        for ((rawSource, targets) in dynamicBridges) {
            val source = rawSource.lowercase()
            if (source !in queryTokens) continue
            val weight = if (df(source) == 0) 1.0f else ALIAS_WEIGHT
            targets.forEach { addExpansion(it.lowercase(), weight) }
        }
        return weights
    }

    /**
     * Clinical concepts touched by [tokens] — a concept is included when any of its
     * aliases matches one of the tokens. Used by the groundedness gate to treat a
     * response token as grounded when it is a known synonym of a reference token
     * (e.g. "anemia" ↔ "anaemia") rather than demanding a literal string match,
     * which would punish honest paraphrase and BN↔EN translation.
     */
    fun conceptsFor(tokens: Collection<String>): Set<String> {
        if (tokens.isEmpty()) return emptySet()
        val lower = tokens.map { it.lowercase() }.toSet()
        val text = lower.joinToString(" ")
        val out = mutableSetOf<String>()
        for ((concept, aliases) in GROUPS) {
            if (aliases.any { alias -> aliasMatchesQuery(alias, lower, text) }) out += concept
        }
        return out
    }

    /** True when [token] belongs to any of [concepts] (same matching rules as [conceptsFor]). */
    fun sharesConcept(token: String, concepts: Set<String>): Boolean {
        if (concepts.isEmpty()) return false
        val t = token.lowercase()
        return concepts.any { concept ->
            GROUPS[concept]?.any { alias -> aliasMatchesQuery(alias, setOf(t), t) } == true
        }
    }

    private fun aliasMatchesQuery(
        alias: String,
        queryTokens: Set<String>,
        queryText: String,
    ): Boolean = when {
        alias.contains(' ') -> queryText.contains(alias)
        alias.all { it.code < 128 } -> alias in queryTokens
        else -> queryTokens.any { token -> token.startsWith(alias) }
    }
}
