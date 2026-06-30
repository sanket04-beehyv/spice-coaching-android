package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v20 → v21: source-document thumbnail cache.
 *
 * Adds the `source_document_thumbnail` table — one row per unique source-document
 * ID referenced across all cached modules. The SDK fetches presigned thumbnail
 * URLs from `/sync/source-documents/presigned-thumbnails` and stores them here
 * (with an expiry) so the Knowledge section can display per-document cover images
 * offline after the first sync.
 *
 * Null `thumbnail_url` means "not yet fetched" (first sync after upgrade);
 * the next inbound sync pass will populate it for all documents that have
 * `has_thumbnail = true` in the server's source-document metadata.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_document_thumbnail (
                source_document_id TEXT NOT NULL PRIMARY KEY,
                thumbnail_url TEXT,
                thumbnail_expires_at_epoch_sec INTEGER
            )
            """.trimIndent(),
        )
    }
}
