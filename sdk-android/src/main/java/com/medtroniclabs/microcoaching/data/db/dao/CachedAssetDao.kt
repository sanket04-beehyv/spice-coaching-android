package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.CachedAssetEntity

@Dao
interface CachedAssetDao {

    @Query("SELECT * FROM cached_asset WHERE key_hash = :keyHash")
    suspend fun get(keyHash: String): CachedAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: CachedAssetEntity)

    /** Bump LRU recency on a cache hit. */
    @Query("UPDATE cached_asset SET last_access_at = :now WHERE key_hash = :keyHash")
    suspend fun touch(keyHash: String, now: Long)

    /** Total cached bytes — drives the eviction budget. Null when empty. */
    @Query("SELECT SUM(bytes) FROM cached_asset")
    suspend fun totalBytes(): Long?

    /** Oldest-accessed evictable rows first (pinned rows excluded). */
    @Query(
        "SELECT * FROM cached_asset WHERE is_pinned = 0 ORDER BY last_access_at ASC LIMIT :limit",
    )
    suspend fun oldestUnpinned(limit: Int): List<CachedAssetEntity>

    @Query("DELETE FROM cached_asset WHERE key_hash = :keyHash")
    suspend fun delete(keyHash: String)

    @Query("DELETE FROM cached_asset")
    suspend fun clearAll()
}
