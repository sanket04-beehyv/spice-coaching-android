package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * L1 of chat_plan.md §B4 — cheap pre-LLM gate that rejects out-of-domain queries
 * before any retrieval or inference cost is incurred.
 *
 * Implementation: a static allow-list of clinical / SPICE-workflow terms in both
 * English and Bangla, optionally augmented at index-build time with dynamic terms
 * harvested from each module's `domain` / `subDomain` / card titles. Match ≥ 1
 * term anywhere in the (lower-cased) input → in-scope.
 *
 * Intentionally lossy on the false-negative side: if the model is asked a clinical
 * question in unusual wording, the scope check may miss and the retrieval-threshold
 * gate (L2) catches it. The reverse — letting a sports question through to the LLM —
 * is the failure mode this layer exists to prevent.
 */
class ScopeClassifier(private val terms: Set<String>) {

    fun isInScope(query: String): Boolean {
        if (query.isBlank()) return false
        val lower = query.lowercase()
        return terms.any { it in lower }
    }

    /**
     * Hard out-of-scope check. Returns true when the query contains a term
     * from [DENY_TERMS] — non-clinical topics the LLM has repeatedly answered
     * incorrectly (coding, sports, weather, etc.). Independent of the
     * allow-list path: even if a keyword in [terms] matches, a deny hit wins.
     *
     * Used by ChatViewModel to short-circuit BEFORE the LLM is called, in both
     * Strict and ExtendedClinical modes — the 1B model has proven unreliable
     * at refusing these on its own.
     */
    fun isOutOfScope(query: String): Boolean {
        if (query.isBlank()) return false
        val lower = query.lowercase()
        return DENY_TERMS.any { it in lower }
    }

    /**
     * Read-only view of the static + dynamic term set. Used by
     * [com.medtroniclabs.microcoaching.ui.chat.ChatViewModel] to hand the LLM a
     * concrete scope list in the open-scope system prompt, so its judgement on
     * "is this in scope?" reflects domains actually present in the indexed
     * module corpus — not just hard-coded categories.
     */
    fun scopeTerms(): Set<String> = terms

    companion object {

        // Static seed list — covers the ~6 module families in the pilot plus the
        // SPICE digital-help surface. Lowercased on construction. Expanded after
        // CHW field testing surfaced gaps (newborn / breastfeeding / breath /
        // engorgement / eyecare etc.) that wrongly tripped the Strict refusal.
        private val STATIC_TERMS: Set<String> = setOf(
            // Hypertension
            "hypertension", "blood pressure", "bp", "systolic", "diastolic",
            "উচ্চ", "রক্তচাপ", "প্রেসার", "চাপ",
            // Diabetes
            "diabetes", "glucose", "sugar", "insulin",
            "ডায়াবেটিস", "চিনি", "গ্লুকোজ", "ইনসুলিন",
            // Maternal / ANC / postpartum
            "anc", "antenatal", "pregnancy", "pregnant", "fundal", "maternal",
            "postpartum", "postnatal", "pnc", "labour", "labor", "delivery",
            "breastfeed", "breastfeeding", "lactation", "milk", "engorgement",
            "nipple", "breast",
            "গর্ভ", "মাতৃ", "প্রসব", "এএনসি", "পিএনসি", "স্তন্যপান", "স্তন্য", "দুধ",
            // Newborn / child health
            "newborn", "neonate", "neonatal", "infant", "baby", "child",
            "breath", "breaths", "breathing", "respiration", "respiratory", "cough",
            "fever", "weight", "growth", "vaccine", "vaccination", "immunization",
            "immunisation", "diarrhoea", "diarrhea", "vomit", "rash", "jaundice",
            "নবজাতক", "শিশু", "বাচ্চা", "জ্বর", "শ্বাস", "টিকা",
            // Eyecare
            "eye", "eyes", "eyecare", "eye care", "vision", "cataract", "glaucoma",
            "চোখ", "দৃষ্টি",
            // Family planning
            "family planning", "contraception", "contraceptive", "condom", "iud",
            "পরিবার পরিকল্পনা", "জন্মনিয়ন্ত্রণ",
            // Emergency / referrals / danger signs
            "danger", "bleeding", "emergency", "refer", "referral",
            "convulsion", "seizure", "fits", "unconscious", "shock",
            "বিপদ", "রক্তপাত", "জরুরি", "রেফার",
            // Nutrition / counselling
            "nutrition", "diet", "counsel", "counselling", "counseling",
            "পুষ্টি", "খাদ্য", "পরামর্শ",
            // SPICE digital surface
            "spice", "login", "log in", "sync", "form", "screen", "app",
            "সিনক", "লগইন", "ফর্ম", "অ্যাপ",
            // CHW workflow
            "chw", "patient", "visit", "household", "assessment", "screening",
            "রোগী", "ভিজিট", "বাড়ি",
            // Infectious diseases (malaria, TB, sepsis — modules present but terms absent)
            "malaria", "tuberculosis", "tb", "sepsis", "itn", "mosquito net",
            "ম্যালেরিয়া", "যক্ষ্মা", "সেপসিস", "মশারি",
            // Cancer screening
            "cancer", "cervical", "cervix", "breast cancer",
            "ক্যান্সার", "জরায়ু", "স্তন ক্যান্সার",
            // Low birth weight / malnutrition
            "birth weight", "low birth weight", "lbw", "preterm", "malnutrition",
            "জন্ম ওজন", "কম ওজন", "অপুষ্টি",
            // Jaundice (EN already present above; BN was missing)
            "জন্ডিস",
            // Missing BN equivalents confirmed absent in v3 field evaluation (R2)
            "ডায়রিয়া",                    // diarrhoea
            "অ্যানিমিয়া", "রক্তশূন্যতা", // anaemia (two common spellings)
            "বুক", "টান",                   // chest / indrawing — Q3 critical failure
            "ডিহাইড্রেশন",                  // dehydration
            "হিমোগ্লোবিন",                  // haemoglobin
            "দফা",                          // rounds/steps (ANC visit count)
        )

        /**
         * Hard out-of-scope terms. A query matching ANY of these is rejected
         * before the LLM is consulted — both in Strict and ExtendedClinical
         * modes. Maintained as a small, surgical list of categories the
         * Gemma-3 1B model repeatedly leaked into ("Tell me about coding"
         * answered with a JavaScript explainer, etc.).
         *
         * Keep entries SHORT and unambiguous (single tokens that don't collide
         * with clinical text). "code" was rejected for collision with ICD-10
         * code mentions; we use "coding" / "programming" instead.
         */
        private val DENY_TERMS: Set<String> = setOf(
            // Software / technology
            "coding", "programming", "programmer", "software", "developer",
            "javascript", "python", "kotlin", "java ", "html", "css ",
            "computer", "laptop", "keyboard", "internet", "website", "api",
            "algorithm", "machine learning", "artificial intelligence",
            "tech stack", "framework",
            "কোডিং", "প্রোগ্রামিং", "সফটওয়্যার", "কম্পিউটার", "ইন্টারনেট",
            // Sports
            "football", "cricket", "basketball", "tennis", "hockey", "olympic",
            "world cup", "league",
            "ফুটবল", "ক্রিকেট", "খেলা",
            // Weather / news / politics
            "weather", "rain ", "temperature today", "forecast",
            "election", "politics", "prime minister", "president ",
            "আবহাওয়া", "রাজনীতি", "নির্বাচন",
            // Entertainment
            "movie", "film", "song", "music", "celebrity", "actor", "actress",
            "joke", "tell me a joke", "story unrelated",
            "চলচ্চিত্র", "গান", "গল্প",
            // Creative-text generation — the LLM happily obliges, and on the low-end
            // path a "poem about nature" latched onto a "Nature of Diarrhoea" card.
            // Kept to unambiguous nouns (no bare "write"/"story" — they collide with
            // "rewrite"/"history" and would over-refuse clinical requests).
            "poem", "lyrics", "essay",
            "কবিতা",
            // Out-of-scope work topics
            "agriculture", "farming", "crop", "harvest", "fishing",
            "business", "stock market", "cryptocurrency", "bitcoin",
            "কৃষি", "ব্যবসা",
            // Misc casual
            "recipe", "cooking", "restaurant",
            "রান্না", "রেসিপি",
        )

        /**
         * Build a classifier seeded from [STATIC_TERMS] plus terms harvested from
         * the current module corpus — domain, subdomain, and individual title words.
         * Keeps the scope check current as new modules land without redeploying the SDK.
         *
         * Titles are split into individual words (not stored as full phrases) so a
         * query containing any single word from a module title (e.g. "ম্যালেরিয়া"
         * from "ম্যালেরিয়া বোঝা") passes the in-scope check and reaches BM25 retrieval.
         * Storing full phrases caused false negatives when the user's vocabulary differed
         * from the exact module title.
         *
         * Harvested words are filtered against [BanglaTokenizer.STOPWORDS] — the
         * `length >= 3` gate alone admits function words like "and"/"the" (from
         * titles such as "Maternal and Neonatal Referral Process"), which then
         * count as *clinical* overlap in [OffTopicGuard]. That is the verified
         * hole that let "Breast Engorgement and Pain" pass the garbage guard
         * against a newborn-warmth card: the only shared "clinical" token was "and".
         */
        /**
         * Structural / framing words that appear in card titles & metadata but carry
         * no clinical meaning. Excluded from harvested DYNAMIC terms only (never from
         * [STATIC_TERMS] or [ClinicalSynonymMap]) so an everyday word like "nature"
         * (from "…Nature of Diarrhoea") can't widen the Strict-mode allow-list or
         * count as clinical overlap in [OffTopicGuard]. Real clinical vocabulary is
         * unaffected — it lives in the static seed list.
         */
        private val GENERIC_HARVEST_STOPWORDS: Set<String> = setOf(
            "nature", "type", "types", "overview", "importance", "function", "functions",
            "identifying", "recognizing", "understanding", "examples", "example",
            "measures", "process", "procedures", "procedure", "role", "basic", "general",
            "introduction", "about", "definition", "meaning", "guide", "guidelines",
        )

        fun buildFrom(modules: List<ModuleEntity>): ScopeClassifier {
            val dynamic = mutableSetOf<String>()
            fun harvestTitleWords(title: String?) {
                title?.takeIf { it.isNotBlank() }?.lowercase()
                    ?.split(Regex("\\s+"))
                    ?.filter { w ->
                        w.length >= 3 &&
                            w !in BanglaTokenizer.STOPWORDS &&
                            w !in GENERIC_HARVEST_STOPWORDS
                    }
                    ?.forEach { dynamic.add(it) }
            }
            for (m in modules) {
                m.domain.takeIf { it.isNotBlank() }?.let { dynamic.add(it.lowercase()) }
                m.subDomain?.takeIf { it.isNotBlank() }?.let { dynamic.add(it.lowercase()) }
                harvestTitleWords(m.titleBn)
                harvestTitleWords(m.titleEn)
                // Curated search_metadata vocabulary (keywords / topic tags /
                // clinical conditions, EN + BN) widens the allow-list with terms a
                // CHW might type that don't appear in the module title — fewer false
                // off-scope refusals. Routed through the same word-harvest +
                // stop-word filter as titles. Full search_phrases are deliberately
                // skipped: they're whole sentences and would inflate the clinical
                // overlap set OffTopicGuard reads from scopeTerms().
                harvestMetadataTerms(m.searchMetadataJson).forEach { harvestTitleWords(it) }
            }
            val all = STATIC_TERMS.map { it.lowercase() }.toSet() +
                ClinicalSynonymMap.allTerms +
                dynamic
            return ScopeClassifier(all)
        }

        private val scopeJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Pull the concise clinical terms out of a module's raw `search_metadata`
         * JSON: `keywords_en`, `keywords_bn`, `topic_tags`, `clinical_conditions`.
         * Tolerates missing keys / wrong shapes / unparseable JSON (→ empty list).
         */
        private fun harvestMetadataTerms(jsonText: String): List<String> {
            val obj = runCatching { scopeJson.parseToJsonElement(jsonText) as? JsonObject }
                .getOrNull() ?: return emptyList()
            fun strArr(key: String): List<String> =
                (obj[key] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
            return strArr("keywords_en") + strArr("keywords_bn") +
                strArr("topic_tags") + strArr("clinical_conditions")
        }
    }
}
