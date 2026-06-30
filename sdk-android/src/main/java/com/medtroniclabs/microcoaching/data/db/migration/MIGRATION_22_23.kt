package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v22 → v23: provenance flag on `morning_card_cache`.
 *
 * Adds `morning_card_cache.on_device` (`INTEGER NOT NULL DEFAULT 0`) so the two
 * writers can manage their own rows independently:
 *   - the backend `GET /morning/cards` fetch replaces `on_device = 0` rows,
 *   - [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator]
 *     replaces `on_device = 1` rows.
 *
 * Before this, both did a full `replaceAll`, so the backend fetch and the
 * on-device generator wiped each other's cards (the morning card flapped between
 * a backend pick and the on-device referral card). Pre-migration rows default to
 * `0` (backend) and are refreshed on the next sync.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE morning_card_cache ADD COLUMN on_device INTEGER NOT NULL DEFAULT 0")
    }
}
