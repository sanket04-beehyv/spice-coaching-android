package com.medtroniclabs.microcoaching.ui.learn

import android.util.Log
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedArray
import com.medtroniclabs.microcoaching.data.localized.toOptionLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "QuizJsonParser"

/**
 * Parse the raw `quiz_json` blob stored on `module_cache.quiz_json` into a
 * typed [QuizQuestion] list. Used by both [LearnViewModel] (full quiz flow)
 * and the v0.3.2 Quick learn banner (single-question taste).
 *
 * @param lang ISO-639-1 code for the preferred language ("bn" or "en").
 *   Reads nested locale maps (`question: {bn, en}`) first, then legacy
 *   `question_$lang` / `question_bn` flat keys.
 *
 * Malformed rows are logged and skipped — empty list is the worst case.
 */
internal fun parseInlineQuiz(quizJson: String, lang: String = "bn"): List<QuizQuestion> {
    val arr = try {
        Json.parseToJsonElement(quizJson).jsonArray
    } catch (e: Exception) {
        Log.w(TAG, "quiz_json is not a JSON array (len=${quizJson.length}): ${e.message}")
        return emptyList()
    }

    return arr.mapIndexedNotNull { idx, el ->
        runCatching {
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe()
                ?: error("missing or null `id`")
            val question = obj.readLocalized("question")
            val text = question.forLang(lang).orEmpty()
            val options = obj.readLocalizedArray("options", lang)
                .ifEmpty { obj.readLocalizedArray("options", "bn") }
            val correct = obj["correct_indices"]?.jsonArray
                ?.firstOrNull()?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val explanation = obj.readLocalized("explanation").forLang(lang).orEmpty()
            val caseSetup = obj.readLocalized("case_setup").forLang(lang).orEmpty()

            QuizQuestion(
                id = id,
                questionText = text,
                answers = options,
                correctIndex = correct,
                explanation = explanation,
                caseSetup = caseSetup,
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to parse quiz row #$idx: ${e.message}")
        }.getOrNull()
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else content
