package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleDao {

    /**
     * Backend only ships published modules through `/sync/modules`, so every
     * row in the cache is active. Sorted for stable UI ordering.
     */
    @Query("SELECT * FROM module_cache ORDER BY domain, module_id")
    fun getAllActive(): Flow<List<ModuleEntity>>

    /** One-shot read for joins — used by [MicroCoachingSDK.resolveFromCache]. */
    @Query("SELECT * FROM module_cache ORDER BY domain, module_id")
    suspend fun getAllOrderedOnce(): List<ModuleEntity>

    /** Look up by module version id (the primary key). */
    @Query("SELECT * FROM module_cache WHERE module_id = :moduleId")
    suspend fun getById(moduleId: String): ModuleEntity?

    /**
     * Look up the latest version of a module family. When multiple versions of
     * the same family are cached (after an update), this returns the highest
     * version number.
     */
    @Query("SELECT * FROM module_cache WHERE module_family_id = :familyId ORDER BY version DESC LIMIT 1")
    suspend fun getByFamilyId(familyId: String): ModuleEntity?

    @Query("SELECT * FROM module_cache WHERE domain = :domain ORDER BY module_id")
    fun getByDomain(domain: String): Flow<List<ModuleEntity>>

    @Query("SELECT COUNT(*) FROM module_cache")
    suspend fun countActive(): Int

    /**
     * Distinct module-family ids currently cached. Used by the full-catalogue
     * reconcile in [com.medtroniclabs.microcoaching.sync.SyncApi.pullModules] to
     * find families the server no longer publishes (terminally retired).
     */
    @Query("SELECT DISTINCT module_family_id FROM module_cache")
    suspend fun distinctFamilyIds(): List<String>

    /**
     * Module version ids that have a thumbnail but no usable cached URL yet —
     * either never fetched or the presigned URL has expired. Fed to
     * `/sync/modules/presigned-thumbnails` by [SyncApi.pullModuleThumbnails].
     */
    @Query(
        """
        SELECT module_id FROM module_cache
        WHERE has_thumbnail = 1
          AND (thumbnail_url IS NULL
               OR thumbnail_expires_at_epoch_sec IS NULL
               OR thumbnail_expires_at_epoch_sec < :nowEpochSec)
        """,
    )
    suspend fun moduleIdsNeedingThumbnail(nowEpochSec: Long): List<String>

    /**
     * Targeted update of just the thumbnail columns for one module version.
     * Deliberately not a REPLACE upsert — it touches only the thumbnail fields,
     * so it never collides with the module-sync `upsertAll`.
     */
    @Query(
        """
        UPDATE module_cache
        SET thumbnail_url = :url, thumbnail_expires_at_epoch_sec = :expiresAt
        WHERE module_id = :moduleId
        """,
    )
    suspend fun updateThumbnail(moduleId: String, url: String, expiresAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(modules: List<ModuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(module: ModuleEntity)

    /** Remove specific module versions. */
    @Query("DELETE FROM module_cache WHERE module_id IN (:moduleIds)")
    suspend fun deleteByIds(moduleIds: List<String>)

    /**
     * Remove **every** cached version of the given families. Used by the
     * full-catalogue reconcile to drop terminally-retired families (no published
     * version remains server-side). Keyed on `module_family_id`, NOT `module_id`,
     * because retirement is a family-level concept and a family may hold multiple
     * cached versions. Returns the number of rows deleted.
     */
    @Query("DELETE FROM module_cache WHERE module_family_id IN (:familyIds)")
    suspend fun deleteByFamilyIds(familyIds: List<String>): Int

    /**
     * Drop every cached version of a family except the latest. Used by sync
     * after a new published version arrives. Returns the number of rows deleted.
     */
    @Query(
        """
        DELETE FROM module_cache
        WHERE module_family_id = :familyId AND version < (
            SELECT MAX(version) FROM module_cache WHERE module_family_id = :familyId
        )
        """,
    )
    suspend fun pruneOldVersions(familyId: String): Int

    @Query("DELETE FROM module_cache")
    suspend fun deleteAll()
}
