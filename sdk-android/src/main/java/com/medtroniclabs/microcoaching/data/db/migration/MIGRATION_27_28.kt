package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v27 → v28: module_cache title/description as JSON locale maps.
 *
 * Replaces flat `title_bn` / `title_en` / `description_bn` / `description_en`
 * with `title_json` / `description_json` (`{"bn":"...","en":"..."}`).
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE module_cache ADD COLUMN title_json TEXT NOT NULL DEFAULT '{}'",
        )
        db.execSQL(
            "ALTER TABLE module_cache ADD COLUMN description_json TEXT NOT NULL DEFAULT '{}'",
        )
        db.execSQL(
            """
            UPDATE module_cache
            SET title_json = CASE
                WHEN title_en IS NOT NULL AND title_en != ''
                THEN json_object('bn', title_bn, 'en', title_en)
                ELSE json_object('bn', title_bn)
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE module_cache
            SET description_json = CASE
                WHEN description_bn IS NOT NULL AND description_bn != ''
                     AND description_en IS NOT NULL AND description_en != ''
                THEN json_object('bn', description_bn, 'en', description_en)
                WHEN description_bn IS NOT NULL AND description_bn != ''
                THEN json_object('bn', description_bn)
                WHEN description_en IS NOT NULL AND description_en != ''
                THEN json_object('en', description_en)
                ELSE '{}'
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS module_cache_new (
                module_id TEXT NOT NULL PRIMARY KEY,
                module_family_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                title_json TEXT NOT NULL DEFAULT '{}',
                description_json TEXT NOT NULL DEFAULT '{}',
                domain TEXT NOT NULL,
                sub_domain TEXT,
                module_type TEXT NOT NULL,
                tenant_id TEXT,
                estimated_minutes INTEGER NOT NULL,
                difficulty_level TEXT NOT NULL,
                pass_threshold_override REAL,
                clinically_reviewed INTEGER NOT NULL,
                published_at_iso TEXT,
                updated_at_iso TEXT NOT NULL,
                cards_json TEXT NOT NULL DEFAULT '[]',
                quiz_json TEXT NOT NULL DEFAULT '[]',
                source_document_ids_json TEXT NOT NULL DEFAULT '[]',
                source_documents_json TEXT NOT NULL DEFAULT '[]',
                search_metadata_json TEXT NOT NULL DEFAULT '{}',
                primary_gap_id TEXT,
                behavioural_gap_ids_json TEXT NOT NULL DEFAULT '[]',
                has_thumbnail INTEGER NOT NULL DEFAULT 0,
                thumbnail_url TEXT,
                thumbnail_expires_at_epoch_sec INTEGER,
                last_synced INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO module_cache_new (
                module_id, module_family_id, version, title_json, description_json,
                domain, sub_domain, module_type, tenant_id, estimated_minutes,
                difficulty_level, pass_threshold_override, clinically_reviewed,
                published_at_iso, updated_at_iso, cards_json, quiz_json,
                source_document_ids_json, source_documents_json, search_metadata_json,
                primary_gap_id, behavioural_gap_ids_json, has_thumbnail,
                thumbnail_url, thumbnail_expires_at_epoch_sec, last_synced
            )
            SELECT
                module_id, module_family_id, version, title_json, description_json,
                domain, sub_domain, module_type, tenant_id, estimated_minutes,
                difficulty_level, pass_threshold_override, clinically_reviewed,
                published_at_iso, updated_at_iso, cards_json, quiz_json,
                source_document_ids_json, source_documents_json, search_metadata_json,
                primary_gap_id, behavioural_gap_ids_json, has_thumbnail,
                thumbnail_url, thumbnail_expires_at_epoch_sec, last_synced
            FROM module_cache
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE module_cache")
        db.execSQL("ALTER TABLE module_cache_new RENAME TO module_cache")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_module_cache_module_family_id ON module_cache (module_family_id)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_module_cache_domain ON module_cache (domain)",
        )
    }
}
