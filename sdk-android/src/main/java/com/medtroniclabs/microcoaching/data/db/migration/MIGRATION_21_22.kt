package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v21 → v22: module-level `search_metadata` cache for BM25 retrieval.
 *
 * Adds `module_cache.search_metadata_json` (`TEXT NOT NULL DEFAULT '{}'`) — the
 * raw author/clinician-curated retrieval hints the backend ships per module
 * (`keywords_en`, `keywords_bn`, `search_phrases_en/bn`, `synonyms_en`,
 * `topic_tags`, `clinical_conditions`). `ModuleKnowledgeIndex.build` parses it and
 * injects the terms into the matching per-language BM25 token stream so a CHW's
 * query can match curated keywords that don't appear in the card body.
 *
 * Pre-migration rows default to `"{}"` and contribute no extra index tokens until
 * the next inbound module sync re-populates them.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE module_cache ADD COLUMN search_metadata_json TEXT NOT NULL DEFAULT '{}'")
    }
}
