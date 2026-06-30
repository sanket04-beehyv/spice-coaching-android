package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists presigned thumbnail URLs for source documents (PDFs, slides, etc.)
 * referenced by modules.
 *
 * Mirrors how [ModuleEntity] stores module thumbnails, but as a separate table
 * because source-document IDs are stored in `module_cache.source_documents_json`
 * (not as individual rows). One row per unique `source_document_id`.
 *
 * [thumbnailUrl] is null until fetched from `/sync/source-documents/presigned-thumbnails`.
 * [thumbnailExpiresAt] is the absolute epoch-second expiry; rows with expired or
 * missing URLs are re-fetched on the next inbound sync pass.
 */
@Entity(tableName = "source_document_thumbnail")
data class SourceDocumentThumbnailEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_document_id")
    val sourceDocumentId: String,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "thumbnail_expires_at_epoch_sec")
    val thumbnailExpiresAt: Long? = null,
)
