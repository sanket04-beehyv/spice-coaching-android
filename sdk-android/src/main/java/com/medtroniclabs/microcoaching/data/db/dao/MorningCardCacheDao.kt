package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MorningCardCacheDao {

    /** Live-observable list of all cached morning cards in backend priority order. */
    @Query("SELECT * FROM morning_card_cache ORDER BY rank ASC")
    fun getAllOrdered(): Flow<List<MorningCardCacheEntity>>

    /** One-shot read — used by [LearnViewModel] and [QuickLearnViewModel] to enrich modules. */
    @Query("SELECT * FROM morning_card_cache ORDER BY rank ASC")
    suspend fun getAllOrderedOnce(): List<MorningCardCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MorningCardCacheEntity>)

    @Query("DELETE FROM morning_card_cache")
    suspend fun clearAll()

    @Query("DELETE FROM morning_card_cache WHERE on_device = 0")
    suspend fun clearBackend()

    @Query("DELETE FROM morning_card_cache WHERE on_device = 1")
    suspend fun clearOnDevice()

    /**
     * Atomically replace the cache contents. Room wraps the delete + insert in a
     * single SQLite transaction, so a process death or coroutine cancellation
     * between the two operations cannot leave the cache empty.
     */
    @Transaction
    suspend fun replaceAll(items: List<MorningCardCacheEntity>) {
        clearAll()
        if (items.isNotEmpty()) upsertAll(items)
    }

    /**
     * Replace only the **backend** rows (`on_device = 0`), leaving on-device rows
     * intact. Used by the `GET /morning/cards` fetch so it never wipes the
     * on-device gap cards (e.g. referral compliance). Items are stamped
     * `onDevice = false`.
     */
    @Transaction
    suspend fun replaceBackend(items: List<MorningCardCacheEntity>) {
        clearBackend()
        if (items.isNotEmpty()) upsertAll(items.map { it.copy(onDevice = false) })
    }

    /**
     * Replace only the **on-device** rows (`on_device = 1`), leaving backend rows
     * intact. Used by [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator]
     * so it never wipes the backend's morning cards. Items are stamped
     * `onDevice = true`.
     */
    @Transaction
    suspend fun replaceOnDevice(items: List<MorningCardCacheEntity>) {
        clearOnDevice()
        if (items.isNotEmpty()) upsertAll(items.map { it.copy(onDevice = true) })
    }

    /** Null when the cache has never been populated. */
    @Query("SELECT MAX(fetched_at) FROM morning_card_cache")
    suspend fun latestFetchedAt(): Long?

    @Query("SELECT COUNT(*) FROM morning_card_cache")
    suspend fun count(): Int
}
