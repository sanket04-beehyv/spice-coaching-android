package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.SourceDocumentThumbnailEntity

@Dao
interface SourceDocumentThumbnailDao {

    /** Bulk-read thumbnail rows for the given source-document IDs. */
    @Query("SELECT * FROM source_document_thumbnail WHERE source_document_id IN (:ids)")
    suspend fun getForIds(ids: List<String>): List<SourceDocumentThumbnailEntity>

    /**
     * Insert-or-replace a batch of placeholder rows (thumbnail_url = null) so
     * [idsNeedingThumbnail] can return them for the first-time fetch. Rows that
     * already have a valid URL are left untouched by the caller — this is only
     * used to seed new document IDs.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entities: List<SourceDocumentThumbnailEntity>)

    /**
     * Returns all source-document IDs whose thumbnail URL is missing or expired.
     * Callers batch-fetch these from the server and call [updateThumbnail].
     */
    @Query(
        """
        SELECT source_document_id FROM source_document_thumbnail
        WHERE thumbnail_url IS NULL
           OR thumbnail_expires_at_epoch_sec IS NULL
           OR thumbnail_expires_at_epoch_sec < :nowEpochSec
        """,
    )
    suspend fun idsNeedingThumbnail(nowEpochSec: Long): List<String>

    /** Targeted update — touches only the two thumbnail columns, not the whole row. */
    @Query(
        """
        UPDATE source_document_thumbnail
        SET thumbnail_url = :url, thumbnail_expires_at_epoch_sec = :expiresAt
        WHERE source_document_id = :id
        """,
    )
    suspend fun updateThumbnail(id: String, url: String, expiresAt: Long)
}
