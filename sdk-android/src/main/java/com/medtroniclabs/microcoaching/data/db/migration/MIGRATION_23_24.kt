package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v23 → v24: embed the gap↔module link on the module row.
 *
 * Adds `module_cache.primary_gap_id` (`TEXT`, nullable) and
 * `module_cache.behavioural_gap_ids_json` (`TEXT NOT NULL DEFAULT '[]'`) so the
 * on-device gap index is built from `module.primary_gap_id` + `behavioural_gap_ids`
 * (the `module_behavioural_gap` data the backend ships on `/sync/modules`) instead
 * of trigger bindings. Pre-migration rows default to null / `[]` and are refreshed
 * on the next module sync.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE module_cache ADD COLUMN primary_gap_id TEXT")
        db.execSQL("ALTER TABLE module_cache ADD COLUMN behavioural_gap_ids_json TEXT NOT NULL DEFAULT '[]'")
    }
}
