package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v24 → v25: quiz-level refresher state.
 *
 * Adds the `chw_quiz_question_state` table (the on-device mirror of the backend
 * `chw_quiz_question_state`, keyed `(chw_id, quiz_id)`) and a nullable
 * `morning_card_cache.quiz_id` so quiz-driven cards (`source = "quiz"`) carry the
 * `module_quiz_question.id` they came from. Both are populated on the next
 * `/sync/gaps` + `/morning/cards` sync.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chw_quiz_question_state (
                chw_id TEXT NOT NULL,
                quiz_id TEXT NOT NULL,
                module_id TEXT NOT NULL,
                failed_attempts_count INTEGER NOT NULL DEFAULT 0,
                last_failed_attempt_at INTEGER,
                first_attempt_at INTEGER,
                last_attempt_at INTEGER,
                escalated_to_supervisor INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'active',
                last_synced INTEGER,
                PRIMARY KEY (chw_id, quiz_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chw_quiz_question_state_chw_id ON chw_quiz_question_state (chw_id)")
        db.execSQL("ALTER TABLE morning_card_cache ADD COLUMN quiz_id TEXT")
    }
}
