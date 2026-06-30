package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/replace access for the published source-document catalogue that backs the
 * Knowledge section. The list is server-authoritative and refreshed wholesale
 * each sync, so the only mutating operation is the atomic [replaceAll].
 */
@Dao
interface PublishedSourceDocumentDao {

    /** Reactive Knowledge-grid source, ordered by the server-supplied [rank]. */
    @Query("SELECT * FROM published_source_document ORDER BY rank ASC")
    fun getAllOrdered(): Flow<List<PublishedSourceDocumentEntity>>

    /** The latest presigned URL for one document (used at download time). */
    @Query("SELECT * FROM published_source_document WHERE source_document_id = :id")
    suspend fun getById(id: String): PublishedSourceDocumentEntity?

    @Query("SELECT COUNT(*) FROM published_source_document")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PublishedSourceDocumentEntity>)

    @Query("DELETE FROM published_source_document")
    suspend fun deleteAll()

    /**
     * Atomically swap the whole catalogue for [rows] — the published list is a
     * full server snapshot, so stale rows (documents unpublished server-side)
     * must not linger. Skips the wipe when [rows] is empty so a transient blank
     * response can't clear a populated grid.
     */
    @Transaction
    suspend fun replaceAll(rows: List<PublishedSourceDocumentEntity>) {
        if (rows.isEmpty()) return
        deleteAll()
        upsertAll(rows)
    }
}
