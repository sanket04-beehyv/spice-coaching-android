package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssignedModuleDao {

    /**
     * Reactive list of the rows assigned to [userId] — drives the Training
     * library filter in
     * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore],
     * which matches a module by EITHER its `module_id` or its (nullable)
     * `module_family_id`.
     */
    @Query("SELECT * FROM assigned_module WHERE user_id = :userId")
    fun getAssignedForUser(userId: String): Flow<List<AssignedModuleEntity>>

    @Query("SELECT COUNT(*) FROM assigned_module WHERE user_id = :userId")
    suspend fun countForUser(userId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<AssignedModuleEntity>)

    /**
     * Reconcile un-assignments after a full assigned-snapshot pull: drop the
     * user's rows whose `module_id` is no longer in [moduleIds]. Safe only because
     * the assigned call always fetches the full set (`since=EPOCH`); callers must
     * skip this on an empty response so a transient blank bundle can't wipe the
     * table.
     */
    @Query("DELETE FROM assigned_module WHERE user_id = :userId AND module_id NOT IN (:moduleIds)")
    suspend fun deleteForUserNotIn(userId: String, moduleIds: List<String>)

    @Query("DELETE FROM assigned_module WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}
