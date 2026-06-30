package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v19 → v20: per-message PDF page deep-link anchor.
 *
 * Adds `chat_messages.start_page` (nullable `INTEGER`) — populated at
 * message-persist time from the BM25-matched card's `source_pages` field.
 * The in-app PDF viewer reads this column when a citation chip is tapped
 * and jumps straight to the page the card was authored from.
 *
 * Null on pre-migration rows and on messages whose grounding card has no
 * `source_pages` — both cases degrade gracefully (PDF opens at page 1).
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN start_page INTEGER")
    }
}
