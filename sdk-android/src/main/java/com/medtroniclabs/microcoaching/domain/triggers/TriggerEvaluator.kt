package com.medtroniclabs.microcoaching.domain.triggers

import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.data.db.dao.BehaviouralGapDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwGapProfileDao
import com.medtroniclabs.microcoaching.data.db.dao.ConfigThresholdDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleTriggerBindingDao
import com.medtroniclabs.microcoaching.data.db.dao.TriggerDefinitionDao
import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Picks the next [ModuleEntity] to surface for a CHW given a signal from SPICE.
 *
 * Inputs:
 *  - The cached [TriggerDefinitionEntity] rows ([TriggerDefinitionDao])
 *  - The cached module bindings ([ModuleTriggerBindingDao])
 *  - The CHW's current [com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity]
 *    state ([ChwGapProfileDao])
 *  - The local config thresholds ([ConfigThresholdDao]) with
 *    [MicroCoachingConfig] defaults as fallback
 *
 * Output: one [ModuleEntity] or null.
 *
 * Predicate shapes (raw JSON, per data-model v3 §7.1):
 *  - **gap**: `{ "behavioural_gap_id": "<uuid>", "occurrence_count_threshold": N?, "window_days": D? }`
 *  - **workflow_event**: `{ "spice_event_code": "<code>", "payload_filters": { k: v } }`
 *  - **content_push**: server-side only — ignored on device
 */
class TriggerEvaluator(
    private val triggerDao: TriggerDefinitionDao,
    private val bindingDao: ModuleTriggerBindingDao,
    private val moduleDao: ModuleDao,
    private val gapProfileDao: ChwGapProfileDao,
    private val behaviouralGapDao: BehaviouralGapDao,
    private val configDao: ConfigThresholdDao,
    private val config: MicroCoachingConfig,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Trigger signal coming from SPICE or the SDK's own lifecycle hooks. */
    sealed class Signal {
        /** Morning routine open — surface the highest-priority gap module. */
        object MorningOpen : Signal()
        /** SPICE workflow event (form submission, rule fired, assessment, …). */
        data class WorkflowEvent(
            val spiceEventCode: String,
            val payload: Map<String, String> = emptyMap(),
        ) : Signal()
    }

    suspend fun evaluate(chwId: String, signal: Signal): ModuleEntity? {
        val candidates = collectCandidates(chwId, signal)
        return candidates.maxByOrNull { it.second }?.first
    }

    private suspend fun collectCandidates(
        chwId: String,
        signal: Signal,
    ): List<Pair<ModuleEntity, Int>> {
        val activeBindings = bindingDao.getAll()
        if (activeBindings.isEmpty()) return emptyList()

        val candidates = mutableListOf<Pair<ModuleEntity, Int>>()
        for (binding in activeBindings) {
            val trigger = triggerDao.getById(binding.triggerDefinitionId) ?: continue
            if (trigger.status != "active") continue
            val matches = when (trigger.triggerKind) {
                "gap" -> signal is Signal.MorningOpen && matchesGapPredicate(chwId, trigger)
                "workflow_event" -> signal is Signal.WorkflowEvent &&
                    matchesWorkflowPredicate(signal, trigger)
                else -> false
            }
            if (!matches) continue
            val moduleEntity = moduleDao.getByFamilyId(binding.moduleFamilyId) ?: continue
            candidates += moduleEntity to binding.priorityWeight
        }
        return candidates
    }

    private suspend fun matchesGapPredicate(
        chwId: String,
        trigger: TriggerDefinitionEntity,
    ): Boolean {
        val predicate = parsePredicate(trigger.predicateJson) ?: return false
        val gapId = predicate["behavioural_gap_id"]?.jsonPrimitive?.contentOrNull
            ?: return false
        val gap = behaviouralGapDao.getById(gapId) ?: return false

        val threshold = predicate["occurrence_count_threshold"]
            ?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: resolveConfigInt(null, "trigger_occurrence_threshold", config.triggerOccurrenceThreshold)
        val windowDays = predicate["window_days"]
            ?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: resolveConfigInt(null, "trigger_window_days", config.triggerWindowDays)
        val windowMillis = windowDays.toLong() * 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()

        val profiles = gapProfileDao.getAllForChw(chwId).filter { it.gapActive }
        val matching = profiles.firstOrNull { profile ->
            profile.behaviouralGapId == gap.gapCode || profile.behaviouralGapId == gap.gapId
        } ?: return false

        if (matching.wrongCount < threshold) return false
        val lastSeen = matching.lastSeen ?: return true
        return (now - lastSeen) <= windowMillis
    }

    private fun matchesWorkflowPredicate(
        signal: Signal.WorkflowEvent,
        trigger: TriggerDefinitionEntity,
    ): Boolean = matchesWorkflowPredicate(
        predicateJson = trigger.predicateJson,
        spiceEventCode = signal.spiceEventCode,
        payload = signal.payload,
    )

    private suspend fun resolveConfigInt(
        moduleFamilyId: String?,
        key: String,
        default: Int,
    ): Int {
        val row = configDao.resolve(moduleFamilyId ?: "", key) ?: return default
        return row.value.toIntOrNull() ?: default
    }

    private fun parsePredicate(rawJson: String): JsonObject? = runCatching {
        json.parseToJsonElement(rawJson).jsonObject
    }.getOrNull()
}

/**
 * Update [ChwModuleCompletionEntity] after a quiz attempt.
 * Returns the new row that the caller is expected to upsert.
 */
fun buildModuleCompletion(
    previous: ChwModuleCompletionEntity?,
    chwId: String,
    moduleFamilyId: String,
    moduleId: String?,
    scoreFraction: Float,
    passed: Boolean,
    reinforcementDays: Int,
    nowMillis: Long = System.currentTimeMillis(),
): ChwModuleCompletionEntity {
    val attemptsSincePass = when {
        passed -> 0
        previous == null -> 1
        else -> previous.attemptsSinceLastPass + 1
    }
    val completedAt = if (passed) nowMillis else previous?.completedAt
    val latestCompletedModuleId = if (passed) moduleId else previous?.latestCompletedModuleId
    val dueAt = if (passed) nowMillis + reinforcementDays.toLong() * 24L * 60L * 60L * 1000L
    else previous?.reinforcementDueAt
    return ChwModuleCompletionEntity(
        chwId = chwId,
        moduleFamilyId = moduleFamilyId,
        latestCompletedModuleId = latestCompletedModuleId,
        latestAttemptModuleId = moduleId,
        completedAt = completedAt,
        latestAttemptAt = nowMillis,
        latestQuizScore = scoreFraction,
        latestAttemptPassed = passed,
        attemptsSinceLastPass = attemptsSincePass,
        reinforcementDueAt = dueAt,
    )
}
