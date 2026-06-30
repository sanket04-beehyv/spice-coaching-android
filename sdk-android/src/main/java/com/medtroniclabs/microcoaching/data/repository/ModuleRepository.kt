package com.medtroniclabs.microcoaching.data.repository

import com.medtroniclabs.microcoaching.data.db.dao.BehaviouralGapDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.sortedForDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read interface for the v3 module cache. Each `ModuleEntity` is one
 * published version; lookups by version id (`module_id`) or by family
 * (`module_family_id`, returns the latest version).
 */
interface ModuleRepository {
    fun getAllActive(): Flow<List<ModuleEntity>>

    /** Look up a specific module version by its `module_id`. */
    suspend fun getById(moduleId: String): ModuleEntity?

    /** Look up the latest version of a module family. */
    suspend fun getByFamilyId(familyId: String): ModuleEntity?

    suspend fun countActive(): Int

    /**
     * Resolve a behavioural-gap UUID to its human-readable code. Returns
     * `null` when the gap row hasn't synced yet or [gapId] is null.
     */
    suspend fun resolveGapCode(gapId: String?): String?
}

class ModuleRepositoryImpl(
    private val moduleDao: ModuleDao,
    private val gapDao: BehaviouralGapDao,
) : ModuleRepository {

    override fun getAllActive(): Flow<List<ModuleEntity>> =
        moduleDao.getAllActive().map { it.sortedForDisplay() }

    override suspend fun getById(moduleId: String): ModuleEntity? = moduleDao.getById(moduleId)

    override suspend fun getByFamilyId(familyId: String): ModuleEntity? =
        moduleDao.getByFamilyId(familyId)

    override suspend fun countActive(): Int = moduleDao.countActive()

    override suspend fun resolveGapCode(gapId: String?): String? =
        gapId?.let { gapDao.getById(it)?.gapCode }
}
