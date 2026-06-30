package com.medtroniclabs.microcoaching.ui.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Backs the seed-suggestion chips above the chat input.
 *
 * **Source:** quiz questions from the locally cached `module_cache` table —
 * the same content that drives the BM25 retrieval index, so every offered
 * suggestion has a guaranteed answer (the matching quiz row is already
 * indexed and will be retrieved as the top BM25 hit). This replaces the
 * earlier curated `R.array.chat_seed_questions` list whose phrasing didn't
 * always match what the model could answer.
 *
 * **Per-session picking:** [BATCH_SIZE] modules are sampled at random
 * (each module contributes at most one suggestion), and one quiz question
 * is sampled at random from each module's quiz. The CHW sees a fresh,
 * diverse batch on every chat-sheet open.
 *
 * **De-dup:** tapped suggestions are persisted to SharedPreferences keyed on
 * their English text. If we ever exhaust the unused pool the row goes empty
 * — appropriate for the "user has seen everything once" terminal state.
 */
internal class ChatSuggestionsRepository(
    private val appContext: Context,
    private val moduleDao: ModuleDao,
) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns up to [BATCH_SIZE] quiz-question suggestions, each sourced from
     * a different cached module. Already-tapped questions are filtered out.
     * Empty list when the cache is empty, every cached module's quiz is empty,
     * or every available question has been tapped this device session.
     */
    suspend fun nextBatch(): List<SuggestedQuestion> {
        val used = prefs.getStringSet(KEY_USED, emptySet()).orEmpty()
        val modules = runCatching { moduleDao.getAllOrderedOnce() }
            .onFailure { Log.w(TAG, "moduleDao.getAllOrderedOnce threw: ${it.message}") }
            .getOrDefault(emptyList())
        if (modules.isEmpty()) {
            Log.d(TAG, "nextBatch: no cached modules — empty suggestions")
            return emptyList()
        }
        // Shuffle modules so a different set lands on every open. Pick a
        // random question per module; stop once we've collected BATCH_SIZE
        // unused suggestions.
        val batch = mutableListOf<SuggestedQuestion>()
        for (module in modules.shuffled()) {
            if (batch.size >= BATCH_SIZE) break
            val suggestion = pickRandomQuestion(module)
                ?.takeIf { it.question.isNotBlank() && it.question !in used }
                ?: continue
            batch += suggestion
        }
        Log.d(TAG, "nextBatch: picked ${batch.size} from ${modules.size} cached modules (used=${used.size})")
        return batch
    }

    /** Persists the English text of a tapped suggestion so it never re-appears. */
    fun markUsed(suggestion: SuggestedQuestion) {
        if (suggestion.question.isBlank()) return
        val existing = prefs.getStringSet(KEY_USED, emptySet()).orEmpty()
        if (suggestion.question in existing) return
        prefs.edit()
            .putStringSet(KEY_USED, existing + suggestion.question)
            .apply()
    }

    /**
     * Parse the module's `quizJson` and return one random quiz row as a
     * [SuggestedQuestion]. Null when the JSON can't be parsed, the array is
     * empty, or no row has any usable question text.
     */
    private fun pickRandomQuestion(module: ModuleEntity): SuggestedQuestion? {
        val arr = runCatching { json.parseToJsonElement(module.quizJson).jsonArray }
            .getOrNull()
            ?: return null
        if (arr.isEmpty()) return null
        // Convert every quiz row into a candidate suggestion, drop blanks,
        // then pick one. Cheap — quiz arrays are ≤ 10 entries per module.
        val candidates = arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val question = obj.readLocalized("question")
            val qEn = question.en
            val qBn = question.bn
            // English text is the stable identity used for de-dup. When the
            // backend only ships Bangla, fall back to BN as both fields so
            // markUsed still has something to key on.
            val identity = qEn?.takeIf { it.isNotBlank() } ?: qBn?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SuggestedQuestion(
                question = identity,
                banglaQuestion = qBn.orEmpty(),
                moduleFamilyId = module.moduleFamilyId,
            )
        }
        return candidates.randomOrNull()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "ChatSuggestionsRepo"
        const val PREFS_NAME = "microcoaching_chat_suggestions"
        const val KEY_USED = "used_question_keys"
        const val BATCH_SIZE = 3
    }
}
