package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val visitPredicateJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Pure match of an `assessment_due` visit trigger predicate against a today's-visit.
 *
 * Predicate shape (the visit-relevant subset of `predicate_jsonb`):
 * ```
 * { "filter_predicate": { "match": {
 *     "encounter_type_any": ["ANC", "PNC_MOTHER", …],
 *     "is_pregnant": true | false | null,
 *     …other fields the SDK does not model… } } }
 * ```
 *
 * Matching is deliberately minimal — visit refreshers are a fallback, and SPICE only
 * supplies a thin, PII-free visit signal. We evaluate **only** the fields the visit
 * carries ([encounterType], [isPregnant]); fields we don't model (diagnosis, reason,
 * age, program, patient_status) are ignored. A field is checked only when the trigger
 * constrains it **and** the visit provides a value:
 *  - `encounter_type_any` (non-empty) vs [encounterType] — case/space-normalised membership.
 *  - `is_pregnant` (non-null) vs [isPregnant] — equality.
 *
 * Returns true iff **at least one** such field was checked and every checked field
 * passed (no vacuous all-pass on triggers that only constrain unmodelled fields).
 * An unparseable predicate never matches.
 */
internal fun matchesVisitTrigger(
    predicateJson: String,
    encounterType: String?,
    isPregnant: Boolean?,
): Boolean {
    val match = runCatching {
        visitPredicateJson.parseToJsonElement(predicateJson)
            .jsonObject["filter_predicate"]?.jsonObject
            ?.get("match")?.jsonObject
    }.getOrNull() ?: return false

    var checked = 0

    val encounterTypeAny = match["encounter_type_any"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
        ?.map(::normalizeVisitToken)
        .orEmpty()
    if (encounterTypeAny.isNotEmpty() && !encounterType.isNullOrBlank()) {
        checked++
        if (normalizeVisitToken(encounterType) !in encounterTypeAny) return false
    }

    val isPregnantConstraint = match["is_pregnant"]?.jsonPrimitive?.booleanOrNull
    if (isPregnantConstraint != null && isPregnant != null) {
        checked++
        if (isPregnant != isPregnantConstraint) return false
    }

    return checked > 0
}

/** Upper-cases, trims, and folds spaces/`-` to `_` so SPICE values match the trigger's UPPER_SNAKE tokens. */
private fun normalizeVisitToken(value: String): String =
    value.trim().uppercase().replace(' ', '_').replace('-', '_')
