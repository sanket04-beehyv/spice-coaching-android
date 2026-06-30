package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadata for one locally-cached remote asset (image now; video / PDF later).
 *
 * Keyed by [keyHash] — a hash of a **stable asset identity** ([assetKey], e.g. a
 * media `object_name`, a source-document `storage_path`, or the path component
 * of a presigned URL). The row is **not** linked to any module/message: two
 * entities referencing the same asset share one cache entry, which is how
 * de-duplication happens.
 *
 * Drives offline lookup (does a local file exist for this key?) and LRU eviction
 * (oldest [lastAccessAt] first, skipping [isPinned] rows) against a size budget.
 */
@Entity(
    tableName = "cached_asset",
    indices = [Index(value = ["last_access_at"])],
)
data class CachedAssetEntity(

    /** SHA-256 of [assetKey] — stable, filesystem-safe primary key. */
    @PrimaryKey
    @ColumnInfo(name = "key_hash")
    val keyHash: String,

    /** The stable asset identity this entry caches (for debugging / audits). */
    @ColumnInfo(name = "asset_key")
    val assetKey: String,

    /** "IMAGE" | "VIDEO" | "DOCUMENT" — see `AssetKind`. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /** Absolute path of the downloaded file under `filesDir/asset_cache/`. */
    @ColumnInfo(name = "local_path")
    val localPath: String,

    /** File size in bytes — summed for the eviction budget. */
    @ColumnInfo(name = "bytes")
    val bytes: Long,

    /** Best-effort MIME type from the download response, or null. */
    @ColumnInfo(name = "mime")
    val mime: String? = null,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long = System.currentTimeMillis(),

    /** Bumped on every cache hit; LRU eviction order. */
    @ColumnInfo(name = "last_access_at")
    val lastAccessAt: Long = System.currentTimeMillis(),

    /** Exempt from eviction when true. Reserved; unused in v1. */
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,
)
