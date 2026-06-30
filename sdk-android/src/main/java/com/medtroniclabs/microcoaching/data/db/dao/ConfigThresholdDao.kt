package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ConfigThresholdEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigThresholdDao {

    /**
     * Module-scoped lookup. Returns the row scoped to [moduleFamilyId] when one
     * exists, else the global row, else null.
     */
    @Query(
        """
        SELECT * FROM config_threshold
        WHERE key = :key AND module_family_id IN (:moduleFamilyId, '')
        ORDER BY CASE WHEN module_family_id = :moduleFamilyId THEN 0 ELSE 1 END
        LIMIT 1
        """,
    )
    suspend fun resolve(moduleFamilyId: String, key: String): ConfigThresholdEntity?

    @Query("SELECT * FROM config_threshold WHERE module_family_id = '' AND key = :key")
    suspend fun getGlobal(key: String): ConfigThresholdEntity?

    @Query("SELECT * FROM config_threshold")
    suspend fun getAll(): List<ConfigThresholdEntity>

    /** Reactive view of the global (non-module-scoped) rows — drives the SDK's cached learning-points config. */
    @Query("SELECT * FROM config_threshold WHERE module_family_id = ''")
    fun getGlobalFlow(): Flow<List<ConfigThresholdEntity>>

    @Query("SELECT COUNT(*) FROM config_threshold")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ConfigThresholdEntity>)

    @Query("DELETE FROM config_threshold")
    suspend fun deleteAll()
}
