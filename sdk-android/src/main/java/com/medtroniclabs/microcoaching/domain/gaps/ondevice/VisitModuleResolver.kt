package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity
import com.medtroniclabs.microcoaching.domain.context.TodaysVisit

/**
 * Resolves the CHW's today's-visits into coaching-module candidates via the synced
 * `assessment_due` **trigger bindings** (`/sync/triggers`). For each visit × active
 * `workflow_event` trigger, [matchesVisitTrigger] tests the trigger's
 * `filter_predicate.match` against the visit (encounter type + pregnancy only — see
 * that matcher); a match contributes the trigger's bound module families as candidates,
 * weighted by the binding's `priority_weight` (which already encodes primary > secondary).
 *
 * This is the cold-start visit→module link, used only when the CHW has no gap picks and
 * no backend cards. Ranking, dedup and capping are done by
 * [OnDeviceMorningSelector.selectFromTodaysAppointments].
 *
 * Pure (no DB): the caller supplies the already-loaded [triggers], [bindingsByTrigger]
 * and [modulesByFamily]. Bindings whose family has no synced module are dropped.
 */
internal object VisitModuleResolver {

    fun resolve(
        visits: List<TodaysVisit>,
        triggers: List<TriggerDefinitionEntity>,
        bindingsByTrigger: Map<String, List<ModuleTriggerBindingEntity>>,
        modulesByFamily: Map<String, ModuleEntity>,
    ): List<VisitCandidate> {
        if (visits.isEmpty() || triggers.isEmpty()) {
            Log.i(TAG, "visitResolve: skipped (visits=${visits.size} triggers=${triggers.size})")
            return emptyList()
        }
        // The clinical signals we actually match on (everything else in the predicate
        // is ignored — see matchesVisitTrigger).
        Log.i(TAG, "visitResolve: signals=${visits.map { "${it.encounterType ?: "?"}/preg=${it.isPregnant}" }}")

        // family → best (highest) priority weight across matching visit×trigger pairs.
        val bestWeightByFamily = LinkedHashMap<String, Int>()
        val matchedTriggers = LinkedHashSet<String>()
        var droppedBindings = 0
        for (visit in visits) {
            for (trigger in triggers) {
                if (!matchesVisitTrigger(trigger.predicateJson, visit.encounterType, visit.isPregnant)) continue
                matchedTriggers += trigger.triggerCode
                val bindings = bindingsByTrigger[trigger.triggerId].orEmpty()
                if (bindings.isEmpty()) {
                    Log.i(TAG, "visitResolve: trigger=${trigger.triggerCode} matched but has no bindings")
                }
                for (binding in bindings) {
                    if (binding.moduleFamilyId !in modulesByFamily) {
                        droppedBindings++ // bound module family not synced/cached → can't surface
                        continue
                    }
                    val current = bestWeightByFamily[binding.moduleFamilyId]
                    if (current == null || binding.priorityWeight > current) {
                        bestWeightByFamily[binding.moduleFamilyId] = binding.priorityWeight
                    }
                }
            }
        }

        val candidates = bestWeightByFamily.map { (family, weight) ->
            VisitCandidate(moduleFamilyId = family, priorityWeight = weight)
        }
        Log.i(
            TAG,
            "visitResolve: matchedTriggers=$matchedTriggers candidates=${candidates.size} " +
                "droppedBindings=$droppedBindings families=${candidates.map { it.moduleFamilyId }}",
        )
        return candidates
    }

    // Shared tag with the rest of the on-device pipeline:
    // `adb logcat -s OnDeviceMorningTrace:I`.
    private const val TAG = "OnDeviceMorningTrace"
}
