package com.medtroniclabs.microcoaching.ai.retrieval

import android.content.res.AssetManager
import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.putJsonArray

/**
 * Merges hand-authored per-card retrieval hints from APK assets into synced modules.
 *
 * Used for **benchmark / QA builds only** while waiting for backend
 * `retrieval_hints_*` on sync cards. Enable via
 * [com.medtroniclabs.microcoaching.MicroCoachingConfig.enableRetrievalHintFixtureOverlay].
 *
 * Drop overlay files under `assets/retrieval/overlays/` (one JSON file per module):
 * ```json
 * {
 *   "module_family_id": "b5a3f612-2590-4082-8fd0-a500b8908a69",
 *   "card_hints": [
 *     {
 *       "card_index": 1,
 *       "retrieval_hints_en": ["how do sanitary latrines help prevent disease spread"]
 *     }
 *   ]
 * }
 * ```
 */
object RetrievalHintOverlay {

    private const val TAG = "RetrievalHintOverlay"
    private const val OVERLAY_DIR = "retrieval/overlays"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    internal data class OverlayFile(
        @SerialName("module_family_id") val moduleFamilyId: String,
        @SerialName("card_hints") val cardHints: List<CardHint> = emptyList(),
    )

    @Serializable
    internal data class CardHint(
        @SerialName("card_index") val cardIndex: Int,
        @SerialName("retrieval_hints_en") val hintsEn: List<String> = emptyList(),
        @SerialName("retrieval_hints_bn") val hintsBn: List<String> = emptyList(),
    )

    /**
     * Returns [modules] with any matching overlay hints merged into `cards_json`.
     * No-op when [enabled] is false or the assets directory is empty.
     */
    fun apply(
        modules: List<ModuleEntity>,
        assets: AssetManager,
        enabled: Boolean,
    ): List<ModuleEntity> {
        if (!enabled || modules.isEmpty()) return modules
        val overlays = loadOverlays(assets)
        if (overlays.isEmpty()) return modules
        var mergedCount = 0
        val out = modules.map { module ->
            val overlay = overlays[module.moduleFamilyId] ?: return@map module
            val merged = merge(module, overlay) ?: return@map module
            mergedCount++
            merged
        }
        if (mergedCount > 0) {
            Log.i(TAG, "Applied retrieval hint overlays to $mergedCount module(s)")
        }
        return out
    }

    private fun loadOverlays(assets: AssetManager): Map<String, OverlayFile> {
        val files = runCatching { assets.list(OVERLAY_DIR)?.orEmpty().orEmpty() }
            .getOrDefault(emptyArray())
            .filter { it.endsWith(".json") }
        if (files.isEmpty()) return emptyMap()
        val byFamily = LinkedHashMap<String, OverlayFile>()
        for (name in files) {
            val text = runCatching {
                assets.open("$OVERLAY_DIR/$name").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val overlay = runCatching { json.decodeFromString<OverlayFile>(text) }.getOrNull()
                ?: continue
            byFamily[overlay.moduleFamilyId] = overlay
        }
        return byFamily
    }

    internal fun mergeOverlay(module: ModuleEntity, overlay: OverlayFile): ModuleEntity? {
        val arr = runCatching {
            json.parseToJsonElement(module.cardsJson).jsonArray
        }.getOrNull() ?: return null
        if (overlay.cardHints.isEmpty()) return null
        var changed = false
        val updated = buildJsonArray {
            arr.forEachIndexed { index, el ->
                val hint = overlay.cardHints.firstOrNull { it.cardIndex == index }
                if (hint == null || el !is JsonObject) {
                    add(el)
                    return@forEachIndexed
                }
                changed = true
                add(mergeHints(el, hint))
            }
        }
        if (!changed) return null
        return module.copy(cardsJson = updated.toString())
    }

    /** Test hook — merges a raw overlay JSON blob into [module]. */
    internal fun mergeOverlayFromJson(module: ModuleEntity, overlayJson: String): ModuleEntity? {
        val overlay = json.decodeFromString<OverlayFile>(overlayJson)
        return mergeOverlay(module, overlay)
    }

    private fun merge(module: ModuleEntity, overlay: OverlayFile): ModuleEntity? =
        mergeOverlay(module, overlay)

    private fun mergeHints(card: JsonObject, hint: CardHint): JsonObject =
        buildJsonObject {
            card.forEach { (k, v) -> put(k, v) }
            if (hint.hintsEn.isNotEmpty()) {
                putJsonArray("retrieval_hints_en") { hint.hintsEn.forEach { add(JsonPrimitive(it)) } }
            }
            if (hint.hintsBn.isNotEmpty()) {
                putJsonArray("retrieval_hints_bn") { hint.hintsBn.forEach { add(JsonPrimitive(it)) } }
            }
        }
}
