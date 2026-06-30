package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao

/**
 * Builds the [ModuleGapIndex] from the synced **module fields**
 * (`module.primary_gap_id` + `module.behavioural_gap_ids`, from `/sync/modules`) —
 * the SDK-side mirror of the backend's `module_behavioural_gap` junction. Trigger
 * bindings are no longer used for morning generation.
 *
 * `module_primary_gap_*` placeholder gaps are kept like any other gap: the
 * placeholder is the module's quiz-attribution signal (active only after a failed
 * quiz, self-resolving on a correct one), so it must still surface its module as a
 * refresher — matching the backend, which never filters suggestions by `gap_code`.
 * Pure assembly is delegated to [ModuleGapIndex.buildFromModules].
 */
internal class ModuleGapResolver(
    private val moduleDao: ModuleDao,
) {
    suspend fun loadIndex(): ModuleGapIndex {
        // One module per family (latest version), as the selector also uses.
        val latestByFamily = moduleDao.getAllOrderedOnce()
            .groupBy { it.moduleFamilyId }
            .mapValues { (_, versions) -> versions.maxByOrNull { it.version }!! }
        return ModuleGapIndex.buildFromModules(latestByFamily.values)
    }
}
