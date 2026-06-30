package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local mirror of a single [MorningModuleSuggestionItem] returned by
 * `GET /morning/cards?chw_id=&tenant_id=`.
 *
 * The table is replaced atomically on every successful morning-cards fetch
 * ([MorningCardCacheDao.clearAll] + [MorningCardCacheDao.upsertAll]).
 * On failure the previous cache is left intact so the SDK can still surface
 * modules from the last-known backend-prioritised list.
 *
 * [rank] preserves the backend's suggested ordering (0 = highest priority).
 * [source] distinguishes gap-driven ("gap") from recently-added ("fallback") cards.
 * [behaviouralGapId] — when [source] == "gap", the backend's behavioural gap UUID
 * that triggered this suggestion; forwarded into `module_quiz_attempted`
 * [TelemetryEventPayload.payloadJson] so the backend can resolve the gap on
 * a correct answer.
 */
@Entity(tableName = "morning_card_cache")
data class MorningCardCacheEntity(

    /** Version-specific module UUID — the primary key matches [ModuleEntity.moduleId]. */
    @PrimaryKey
    @ColumnInfo(name = "module_id")
    val moduleId: String,

    /** Stable family UUID — matches [ModuleEntity.moduleFamilyId]. */
    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String,

    /**
     * "quiz" | "gap" | "fallback" | "visit" — used for the GAP badge on [RefresherTile].
     * "quiz" = the backend's default quiz-level refresher (carries [quizId]); "visit" =
     * an on-device card from the CHW's today's-visit stand-in (no behavioural gap; see
     * [OnDeviceMorningSelector.selectFromTodaysAppointments]).
     */
    @ColumnInfo(name = "source")
    val source: String,

    /** Non-null when [source] == "gap". Forwarded to telemetry payload. */
    @ColumnInfo(name = "behavioural_gap_id")
    val behaviouralGapId: String? = null,

    /** Non-null when [source] == "quiz" — the `module_quiz_question.id` that triggered this. */
    @ColumnInfo(name = "quiz_id")
    val quizId: String? = null,

    /** Backend-suggested priority order. Lower rank = higher priority. */
    @ColumnInfo(name = "rank")
    val rank: Int = 0,

    /** Epoch millis when this cache row was written. */
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long = System.currentTimeMillis(),

    /**
     * Provenance: `false` = written by the backend `GET /morning/cards` fetch;
     * `true` = computed on-device by [OnDeviceMorningGenerator] (gaps the backend
     * doesn't compute, e.g. referral compliance). The two writers replace **only
     * their own** rows ([MorningCardCacheDao.replaceBackend] / `replaceOnDevice`),
     * so backend and on-device cards COEXIST instead of overwriting each other.
     */
    @ColumnInfo(name = "on_device")
    val onDevice: Boolean = false,
)
