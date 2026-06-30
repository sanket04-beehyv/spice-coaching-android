package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity

/**
 * Bidirectional module↔gap map built from the **module fields** the backend ships
 * on `/sync/modules` (`module.primary_gap_id` + `module.behavioural_gap_ids`) — the
 * SDK-side mirror of the backend's `module_behavioural_gap` junction. (Trigger
 * bindings are no longer used for morning generation.)
 *
 * @param familyToPrimaryGap moduleFamilyId → its `primary_gap_id`. Used to attribute
 *   quiz events to the module's primary gap, exactly as the backend's
 *   `gap_escalation_handler` does.
 * @param gapToFamilies behaviouralGapId → module families that address it (the
 *   inverted `behavioural_gap_ids`, de-duplicated). Used to find candidate modules
 *   per gap during selection.
 */
data class ModuleGapIndex(
    val familyToPrimaryGap: Map<String, String>,
    val gapToFamilies: Map<String, List<String>>,
) {
    companion object {
        val EMPTY = ModuleGapIndex(emptyMap(), emptyMap())

        /**
         * Build the index from the synced modules. Pass one module per family
         * (latest version). Pure, so it's unit-testable without Room.
         *
         * `module_primary_gap_*` per-module placeholder gaps are kept like any
         * other gap: the placeholder is the module's quiz-attribution signal, so
         * a failed quiz on a placeholder-primary module must still surface it as a
         * refresher — exactly as the backend's `module_suggestion_service` does
         * (it never filters by `gap_code`).
         */
        fun buildFromModules(
            modules: Collection<ModuleEntity>,
        ): ModuleGapIndex {
            // family → primary gap (skip null/blank only).
            val familyToPrimaryGap = LinkedHashMap<String, String>()
            // gap → families (any link), order-stable and de-duplicated.
            val gapToFamilies = LinkedHashMap<String, MutableList<String>>()

            for (module in modules) {
                val family = module.moduleFamilyId
                module.primaryGapId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { familyToPrimaryGap[family] = it }

                for (gapId in module.behaviouralGapIds) {
                    if (gapId.isBlank()) continue
                    val families = gapToFamilies.getOrPut(gapId) { mutableListOf() }
                    if (family !in families) families.add(family)
                }
            }

            return ModuleGapIndex(
                familyToPrimaryGap = familyToPrimaryGap,
                gapToFamilies = gapToFamilies.mapValues { it.value.toList() },
            )
        }
    }
}
