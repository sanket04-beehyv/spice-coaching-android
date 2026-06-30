package com.medtroniclabs.microcoaching.ui.common

import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.localized.LocalizedText

/**
 * Returns the display string for the current SDK language.
 *
 * Tries [en] first when the SDK is configured as [Language.ENGLISH], falling
 * back to [bn] when [en] is absent or blank. Tries [bn] first for [Language.BANGLA],
 * falling back to [en]. Returns an empty string only when both are absent.
 *
 * Usage:
 * ```kotlin
 * Text(translatedText(bn = card.titleBn, en = card.titleEn))
 * ```
 */
fun translatedText(bn: String?, en: String?): String {
    val lang = MicroCoachingSDK.getInstance().config.language
    return when (lang) {
        Language.ENGLISH -> en?.takeIf { it.isNotBlank() } ?: bn?.takeIf { it.isNotBlank() } ?: ""
        Language.BANGLA  -> bn?.takeIf { it.isNotBlank() } ?: en?.takeIf { it.isNotBlank() } ?: ""
    }
}

/** Locale-map overload — delegates to [translatedText] with bn/en fields. */
fun translatedText(text: LocalizedText?): String =
    translatedText(bn = text?.bn, en = text?.en)

