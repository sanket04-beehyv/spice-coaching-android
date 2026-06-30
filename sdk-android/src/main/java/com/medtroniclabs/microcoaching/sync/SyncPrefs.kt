package com.medtroniclabs.microcoaching.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Lightweight SharedPreferences wrapper for sync state.
 *
 * Stores ISO 8601 watermarks for each v3 sync resource so [InboundSyncWorker]
 * fetches only the delta on subsequent calls. Not part of the Room schema —
 * sync metadata, not content.
 */
class SyncPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch millis of the last successful outbound sync. 0 = never synced. */
    var lastOutboundSyncAt: Long
        get() = prefs.getLong(KEY_LAST_OUTBOUND_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OUTBOUND_SYNC_AT, value).apply()

    /** Epoch millis of the last successful inbound sync. 0 = never synced. */
    var lastInboundSyncAt: Long
        get() = prefs.getLong(KEY_LAST_INBOUND_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_INBOUND_SYNC_AT, value).apply()

    /**
     * Observe [lastInboundSyncAt] as a Flow that emits the current value
     * immediately and again on every change — so a "last synced" UI label
     * refreshes live the moment an inbound sync completes (without the screen
     * needing to be reopened). Backed by a SharedPreferences change listener;
     * the listener is unregistered when the collector is cancelled.
     */
    fun observeLastInboundSyncAt(): Flow<Long> = callbackFlow {
        trySend(lastInboundSyncAt)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAST_INBOUND_SYNC_AT) trySend(lastInboundSyncAt)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * Epoch millis of the last **full-catalogue** modules reconcile. The normal
     * modules pull is incremental (watermark-based) and so can never observe a
     * retirement (a terminally-retired family simply stops appearing in deltas).
     * Periodically — see [InboundSyncWorker.MODULES_RECONCILE_INTERVAL_MS] — the
     * worker forces a full `since=EPOCH` fetch that diffs the local cache against
     * the published set and deletes families with no published version remaining.
     * 0 = never reconciled.
     */
    var lastModulesReconcileAt: Long
        get() = prefs.getLong(KEY_LAST_MODULES_RECONCILE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_MODULES_RECONCILE_AT, value).apply()

    /**
     * ISO 8601 server watermark from the last `GET /sync/modules` response.
     * Forwarded as the `since` query param on the next call so the backend
     * returns only modules updated after this point.
     * `null` (the default) on first sync triggers a full bundle.
     */
    var modulesWatermark: String?
        get() = prefs.getString(KEY_MODULES_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_MODULES_WATERMARK, value).apply()

    /**
     * ISO 8601 server watermark from the last `GET /sync/gaps` response.
     * Same semantics as [modulesWatermark].
     */
    var gapsWatermark: String?
        get() = prefs.getString(KEY_GAPS_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_GAPS_WATERMARK, value).apply()

    /** ISO 8601 server watermark from the last `GET /sync/triggers` response. */
    var triggersWatermark: String?
        get() = prefs.getString(KEY_TRIGGERS_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_TRIGGERS_WATERMARK, value).apply()

    /** ISO 8601 server watermark from the last `GET /sync/config` response. */
    var configWatermark: String?
        get() = prefs.getString(KEY_CONFIG_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_CONFIG_WATERMARK, value).apply()

    // ── Config thresholds (formerly delivered via ScenarioSyncBundle; now defaults
    //    until the SDK consumes /config/sync — see Phase 3+ in
    //    docs/spice-2.0/04-integration-timeline.md). ──

    /** Max number of morning briefing cards to show. Default: 5. */
    var morningCardsMax: Int
        get() = prefs.getInt(KEY_MORNING_CARDS_MAX, 5)
        set(value) = prefs.edit().putInt(KEY_MORNING_CARDS_MAX, value).apply()

    /** Correct-answer count before a gap scenario is resolved. Default: 3. */
    var gapResolveThreshold: Int
        get() = prefs.getInt(KEY_GAP_RESOLVE_THRESHOLD, 3)
        set(value) = prefs.edit().putInt(KEY_GAP_RESOLVE_THRESHOLD, value).apply()

    /** Wrong-answer count before a soft-trigger fires. Default: 2. */
    var softTriggerWrongCountThreshold: Int
        get() = prefs.getInt(KEY_SOFT_TRIGGER_WRONG_COUNT, 2)
        set(value) = prefs.edit().putInt(KEY_SOFT_TRIGGER_WRONG_COUNT, value).apply()

    /**
     * Room schema version observed on the previous boot. Compared against the live
     * version on init so a destructive migration can be detected and the inbound
     * watermarks cleared — otherwise the next sync returns an empty delta against
     * a freshly-wiped Room and progress stays at 0%. 0 = never recorded.
     */
    var lastKnownRoomVersion: Int
        get() = prefs.getInt(KEY_LAST_KNOWN_ROOM_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_KNOWN_ROOM_VERSION, value).apply()

    /**
     * Module family IDs the backend has terminally retired. Accumulated across sync
     * pulls so [com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex.build]
     * can exclude them even before Room deletion propagates to the index Flow.
     */
    var retiredFamilyIds: Set<String>
        get() = prefs.getStringSet(KEY_RETIRED_FAMILY_IDS, emptySet()) ?: emptySet()
        private set(value) = prefs.edit().putStringSet(KEY_RETIRED_FAMILY_IDS, value).apply()

    fun addRetiredFamilyIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        retiredFamilyIds = retiredFamilyIds + ids
    }

    fun reset() = prefs.edit().clear().apply()

    /**
     * Clear only the inbound sync watermarks; leave config thresholds and
     * sync-time markers intact. Called after a destructive Room migration so the
     * next inbound sync request rehydrates the full backend snapshot.
     */
    fun resetWatermarksOnly() = prefs.edit()
        .remove(KEY_MODULES_WATERMARK)
        .remove(KEY_GAPS_WATERMARK)
        .remove(KEY_TRIGGERS_WATERMARK)
        .remove(KEY_CONFIG_WATERMARK)
        .remove(KEY_RETIRED_FAMILY_IDS)
        .apply()

    companion object {
        private const val PREFS_NAME = "micro_coaching_sync"
        private const val KEY_LAST_OUTBOUND_SYNC_AT = "last_outbound_sync_at"
        private const val KEY_LAST_INBOUND_SYNC_AT = "last_inbound_sync_at"
        private const val KEY_LAST_MODULES_RECONCILE_AT = "last_modules_reconcile_at"
        private const val KEY_MODULES_WATERMARK = "modules_watermark"
        private const val KEY_GAPS_WATERMARK = "gaps_watermark"
        private const val KEY_TRIGGERS_WATERMARK = "triggers_watermark"
        private const val KEY_CONFIG_WATERMARK = "config_watermark"
        private const val KEY_MORNING_CARDS_MAX = "morning_cards_max"
        private const val KEY_GAP_RESOLVE_THRESHOLD = "gap_resolve_threshold"
        private const val KEY_SOFT_TRIGGER_WRONG_COUNT = "soft_trigger_wrong_count_threshold"
        private const val KEY_LAST_KNOWN_ROOM_VERSION = "last_known_room_version"
        private const val KEY_RETIRED_FAMILY_IDS = "retired_family_ids"
    }
}
