package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BehaviouralGapDao {

    @Query("SELECT * FROM behavioural_gap_cache WHERE status = 'active' ORDER BY domain, gap_code")
    fun getAllActive(): Flow<List<BehaviouralGapEntity>>

    /** One-shot read of all active gaps — used to resolve severity for the refresher tiles. */
    @Query("SELECT * FROM behavioural_gap_cache WHERE status = 'active'")
    suspend fun getAllActiveOnce(): List<BehaviouralGapEntity>

    /**
     * Active gaps that carry a non-empty `detection_rule` envelope. Used by
     * [com.medtroniclabs.microcoaching.domain.gaps.GapRuleDispatcher] to iterate
     * action-path rules. Quiz-only gaps (rule is null or `"{}"`) are filtered
     * out per GAP_DETECTION_SDK.md §3.
     */
    @Query(
        "SELECT * FROM behavioural_gap_cache " +
            "WHERE status = 'active' " +
            "AND detection_rule IS NOT NULL " +
            "AND detection_rule != '{}'",
    )
    suspend fun getActiveWithRules(): List<BehaviouralGapEntity>

    @Query("SELECT * FROM behavioural_gap_cache WHERE gap_id = :gapId")
    suspend fun getById(gapId: String): BehaviouralGapEntity?

    @Query("SELECT * FROM behavioural_gap_cache WHERE gap_code = :gapCode")
    suspend fun getByCode(gapCode: String): BehaviouralGapEntity?

    @Query("SELECT * FROM behavioural_gap_cache WHERE domain = :domain AND status = 'active'")
    suspend fun getByDomain(domain: String): List<BehaviouralGapEntity>

    @Query("SELECT COUNT(*) FROM behavioural_gap_cache WHERE status = 'active'")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(gaps: List<BehaviouralGapEntity>)

    @Query("DELETE FROM behavioural_gap_cache WHERE gap_id IN (:gapIds)")
    suspend fun deleteByIds(gapIds: List<String>)

    @Query("DELETE FROM behavioural_gap_cache")
    suspend fun deleteAll()
}
