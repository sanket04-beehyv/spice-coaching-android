package com.medtroniclabs.microcoaching.domain.triggers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val workflowPredicateJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Pure match of a `workflow_event` trigger predicate against a SPICE signal.
 *
 * Predicate shape: `{ "spice_event_code": "<code>", "payload_filters": { k: v } }`.
 * Matches iff `spice_event_code` equals [spiceEventCode] AND every `payload_filters`
 * key is present-and-equal in [payload]. A predicate with no `payload_filters`
 * matches on the code alone; an unparseable predicate or missing code never matches.
 *
 * Used by [TriggerEvaluator] for host SPICE callbacks (`form_submitted`, `rule_fired`,
 * …). Visit refreshers use the separate `assessment_due` matcher
 * ([com.medtroniclabs.microcoaching.domain.gaps.ondevice.matchesVisitTrigger]).
 */
internal fun matchesWorkflowPredicate(
    predicateJson: String,
    spiceEventCode: String,
    payload: Map<String, String>,
): Boolean {
    val predicate = runCatching { workflowPredicateJson.parseToJsonElement(predicateJson).jsonObject }
        .getOrNull() ?: return false
    val expectedCode = predicate["spice_event_code"]?.jsonPrimitive?.contentOrNull ?: return false
    if (expectedCode != spiceEventCode) return false
    val payloadFilters = predicate["payload_filters"]?.jsonObject ?: return true
    for ((k, v) in payloadFilters) {
        val expected = (v as? JsonPrimitive)?.contentOrNull ?: continue
        if (payload[k] != expected) return false
    }
    return true
}
