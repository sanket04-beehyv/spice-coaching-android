package com.medtroniclabs.microcoaching.ui.learn

import android.util.Log
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "LessonCardsJsonParser"

/**
 * Parse the raw `cards_json` blob stored on `module_cache.cards_json` into a
 * typed [LessonCard] list. Sibling to [parseInlineQuiz].
 *
 * Both Bangla and English fields are always extracted so the composable can
 * select the correct language at render time without re-parsing.
 *
 * Supports the v3 locale-map shape (`title: {bn, en}`, `body: {bn: [...], en: [...]}`)
 * and legacy flat keys (`title_bn`, `body_bn`, …).
 *
 * Malformed rows are logged and skipped — empty list is the worst case.
 */
internal fun parseLessonCards(cardsJson: String): List<LessonCard> {
    val arr = try {
        Json.parseToJsonElement(cardsJson).jsonArray
    } catch (e: Exception) {
        Log.w(TAG, "cards_json is not a JSON array (len=${cardsJson.length}): ${e.message}")
        return emptyList()
    }

    return arr.mapIndexedNotNull { idx, el ->
        runCatching {
            val obj = el.jsonObject
            val title = obj.readLocalized("title")
            LessonCard(
                titleBn = title.bn.orEmpty(),
                titleEn = title.en,
                bodyBn = obj.readLocalizedBody("body", "bn") ?: "",
                bodyEn = obj.readLocalizedBody("body", "en"),
                cardFamilyId = obj["card_family_id"]?.jsonPrimitive?.let { prim ->
                    if (prim is JsonNull) null else prim.content
                },
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to parse card #$idx: ${e.message}")
        }.getOrNull()
    }
}
