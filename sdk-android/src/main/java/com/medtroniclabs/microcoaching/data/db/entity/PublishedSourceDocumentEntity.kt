package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable mirror of the backend's published source-document catalogue
 * (`GET /sync/source-documents/published`). Backs the Knowledge section grid,
 * which lists **every** published source document — independent of whether a
 * module references it (the prior list was derived from `module_cache`).
 *
 * The whole table is atomically replaced on each inbound sync
 * ([com.medtroniclabs.microcoaching.sync.SyncApi.pullPublishedSourceDocuments]),
 * so [rank] preserves the server's ordering and presigned URLs are refreshed
 * every cycle before they lapse.
 *
 * Presigned URLs are short-lived (~24h). They're persisted so the list renders
 * offline, but a download/thumbnail fetch is only attempted while online — and
 * the durable [com.medtroniclabs.microcoaching.data.asset.AssetCache] keys on the
 * stable URL *path*, so an already-cached file/thumbnail still resolves even
 * after the signature in [presignedUrl] / [thumbnailUrl] has rotated or expired.
 *
 * @param sourceDocumentId stable backend UUID — the [AssetCache] dedup key.
 * @param title display label.
 * @param originalFilename original upload name; drives the preview's
 *   extension-based routing and the "Downloading… <file>" snackbar.
 * @param presignedUrl latest presigned GET URL for the document, or null.
 * @param presignedExpiresAt absolute epoch-second expiry of [presignedUrl].
 * @param thumbnailUrl latest presigned thumbnail URL, or null when the document
 *   has no thumbnail.
 * @param thumbnailExpiresAt absolute epoch-second expiry of [thumbnailUrl].
 * @param rank server-supplied ordering (offset+index across pages).
 * @param lastSynced wall-clock ms of the sync that wrote this row.
 */
@Entity(tableName = "published_source_document")
data class PublishedSourceDocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_document_id")
    val sourceDocumentId: String,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "original_filename")
    val originalFilename: String? = null,

    @ColumnInfo(name = "presigned_url")
    val presignedUrl: String? = null,

    @ColumnInfo(name = "presigned_expires_at_epoch_sec")
    val presignedExpiresAt: Long? = null,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "thumbnail_expires_at_epoch_sec")
    val thumbnailExpiresAt: Long? = null,

    @ColumnInfo(name = "rank")
    val rank: Int = 0,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long? = null,
)
