package com.medtroniclabs.microcoaching.data.repository

import com.medtroniclabs.microcoaching.data.db.dao.ChwGapProfileDao
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read-only access to the CHW's local gap profile (`chw_gap_profile_local`).
 *
 * The table is **backend-authored** — written only by the `/sync/gaps` inbound
 * sync. Effective gap state (baseline + replay of unsynced events) is computed by
 * [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceGapStateEngine];
 * nothing mutates this table locally, which is what keeps the baseline + replay
 * merge well-defined.
 */
interface GapProfileRepository {
    fun getActiveGaps(chwId: String): Flow<List<ChwGapProfileEntity>>
    suspend fun getAllForChw(chwId: String): List<ChwGapProfileEntity>
}

class GapProfileRepositoryImpl(private val dao: ChwGapProfileDao) : GapProfileRepository {

    override fun getActiveGaps(chwId: String): Flow<List<ChwGapProfileEntity>> =
        dao.getActiveGaps(chwId)

    override suspend fun getAllForChw(chwId: String): List<ChwGapProfileEntity> =
        dao.getAllForChw(chwId)
}
