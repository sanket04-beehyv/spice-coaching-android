package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v25 → v26: published source-document catalogue for the Knowledge section.
 *
 * Adds the `published_source_document` table — the durable mirror of
 * `GET /sync/source-documents/published`. The Knowledge grid now lists every
 * published source document (not just those referenced by a module), reading
 * this table reactively. The whole table is replaced on each inbound sync, so it
 * starts empty and populates on the next `/sync/source-documents/published` pull.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS published_source_document (
                source_document_id TEXT NOT NULL,
                title TEXT,
                original_filename TEXT,
                presigned_url TEXT,
                presigned_expires_at_epoch_sec INTEGER,
                thumbnail_url TEXT,
                thumbnail_expires_at_epoch_sec INTEGER,
                rank INTEGER NOT NULL DEFAULT 0,
                last_synced INTEGER,
                PRIMARY KEY (source_document_id)
            )
            """.trimIndent(),
        )
    }
}
