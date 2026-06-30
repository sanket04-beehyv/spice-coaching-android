package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v26 → v27: `assigned_module` join table.
 *
 * Maps a user (CHW) to the module families assigned to them. The Training
 * Modules screen filters its library to the current user's rows; the chatbot
 * keeps reading the full `module_cache` catalogue. Populated by the "assigned"
 * `/sync/modules` call (the one carrying `user_id`); starts empty and the
 * Training list falls back to the full catalogue until it's populated.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS assigned_module (
                user_id TEXT NOT NULL,
                module_id TEXT NOT NULL,
                module_family_id TEXT,
                assigned_at INTEGER,
                last_synced INTEGER NOT NULL,
                PRIMARY KEY (user_id, module_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_assigned_module_user_id ON assigned_module (user_id)")
    }
}
