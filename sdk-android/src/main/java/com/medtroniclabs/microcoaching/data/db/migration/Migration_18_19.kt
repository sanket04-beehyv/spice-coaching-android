package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v18 → v19. Offline asset cache metadata.
 *
 * Creates `cached_asset`, the metadata table backing
 * [com.medtroniclabs.microcoaching.data.asset.AssetCache] — one row per locally
 * cached remote asset (image now; video / PDF later), keyed by a hash of the
 * asset's stable identity. Additive only; no existing tables change.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_asset (
                key_hash TEXT NOT NULL PRIMARY KEY,
                asset_key TEXT NOT NULL,
                kind TEXT NOT NULL,
                local_path TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                mime TEXT,
                fetched_at INTEGER NOT NULL,
                last_access_at INTEGER NOT NULL,
                is_pinned INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_asset_last_access_at ON cached_asset(last_access_at)")
    }
}
