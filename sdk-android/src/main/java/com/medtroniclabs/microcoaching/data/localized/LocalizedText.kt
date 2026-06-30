package com.medtroniclabs.microcoaching.data.localized

import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val localizedJson = Json { ignoreUnknownKeys = true }

/**
 * Bilingual string map (`{"bn": "...", "en": "..."}`) used across module sync,
 * cards, quiz, and Room `*_json` columns.
 */
@Serializable
data class LocalizedText(
    @SerialName("bn") val bn: String? = null,
    @SerialName("en") val en: String? = null,
) {
    fun forLang(lang: String): String? = when (lang) {
        "en" -> en?.takeIf { it.isNotBlank() } ?: bn?.takeIf { it.isNotBlank() }
        else -> bn?.takeIf { it.isNotBlank() } ?: en?.takeIf { it.isNotBlank() }
    }

    fun forSdkLanguage(): String {
        val lang = runCatching { MicroCoachingSDK.getInstance().config.language }
            .getOrDefault(Language.BANGLA)
        return when (lang) {
            Language.ENGLISH -> en?.takeIf { it.isNotBlank() } ?: bn.orEmpty()
            Language.BANGLA -> bn?.takeIf { it.isNotBlank() } ?: en.orEmpty()
        }
    }

    fun isBlank(): Boolean = bn.isNullOrBlank() && en.isNullOrBlank()

    companion object {
        val EMPTY = LocalizedText()

        fun fromBnEn(bn: String?, en: String? = null): LocalizedText =
            LocalizedText(
                bn = bn?.takeIf { it.isNotBlank() },
                en = en?.takeIf { it.isNotBlank() },
            )

        fun decode(jsonText: String): LocalizedText =
            runCatching {
                localizedJson.decodeFromString(serializer(), jsonText)
            }.getOrDefault(EMPTY)
    }
}

fun LocalizedText.toJsonString(): String =
    localizedJson.encodeToString(LocalizedText.serializer(), this)

/**
 * Read a localized field from a JSON object. Tries the nested locale map first
 * (`title: {bn, en}`), then legacy flat keys (`title_bn`, `title_en`).
 */
fun JsonObject.readLocalized(baseKey: String): LocalizedText {
    val nested = this[baseKey] as? JsonObject
    if (nested != null) {
        val bn = nested.stringValue("bn")
        val en = nested.stringValue("en")
        if (!bn.isNullOrBlank() || !en.isNullOrBlank()) {
            return LocalizedText(bn = bn, en = en)
        }
    }
    val legacyBn = stringValue("${baseKey}_bn")
    val legacyEn = stringValue("${baseKey}_en")
    if (!legacyBn.isNullOrBlank() || !legacyEn.isNullOrBlank()) {
        return LocalizedText(bn = legacyBn, en = legacyEn)
    }
    // Some legacy cards used a bare primitive `title` string.
    val bare = stringValue(baseKey)
    if (!bare.isNullOrBlank()) {
        return LocalizedText(bn = bare)
    }
    return LocalizedText.EMPTY
}

/**
 * Read a localized string array (`options: {bn: [...], en: [...]}` or legacy
 * `options_bn` / `options_en`).
 */
fun JsonObject.readLocalizedArray(baseKey: String, lang: String): List<String> {
    val nested = this[baseKey] as? JsonObject
    val fromNested = nested?.jsonArray(lang)?.mapNotNull { it.toOptionLabel() }.orEmpty()
    if (fromNested.isNotEmpty()) return fromNested
    val legacyKey = "${baseKey}_$lang"
    val legacyFallback = "${baseKey}_bn"
    return (this[legacyKey] ?: this[legacyFallback])?.jsonArray()
        ?.mapNotNull { it.toOptionLabel() }
        .orEmpty()
}

/**
 * Read a localized body field. Each locale value may be a markdown string, a
 * TipTap block array, a bare object, or empty `{}`. Returns the raw form
 * suitable for [com.medtroniclabs.microcoaching.ui.richtext.RichCardBody].
 */
fun JsonObject.readLocalizedBody(baseKey: String, lang: String): String? {
    val nested = this[baseKey] as? JsonObject
    nested?.get(lang)?.bodyContent()?.let { return it }
    return this["${baseKey}_$lang"]?.bodyContent()
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.primitiveTextOrNull()

private fun JsonPrimitive.primitiveTextOrNull(): String? =
    if (this is JsonNull) null else content.takeIf { it.isNotBlank() }

private fun JsonElement.jsonArray(key: String): JsonArray? =
    (this as? JsonObject)?.get(key) as? JsonArray

private fun JsonElement?.jsonArray(): JsonArray? = this as? JsonArray

private fun JsonElement.bodyContent(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    is JsonObject -> if (isEmpty()) null else "[$this]"
    is JsonArray -> toString()
}

/** Normalise quiz/card option entries (string or `{label: ...}` object). */
fun JsonElement.toOptionLabel(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else content
    is JsonObject -> {
        this["label"]?.jsonPrimitive?.primitiveTextOrNull()
            ?: this["text"]?.jsonPrimitive?.primitiveTextOrNull()
            ?: this["answer"]?.jsonPrimitive?.primitiveTextOrNull()
    }
    else -> null
}
