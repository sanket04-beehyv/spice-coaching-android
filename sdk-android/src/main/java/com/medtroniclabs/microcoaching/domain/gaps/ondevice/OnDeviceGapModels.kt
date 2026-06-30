package com.medtroniclabs.microcoaching.domain.gaps.ondevice

/**
 * Pure data model for the on-device gap-state replica. These types carry no
 * Room/coroutine dependencies so the reducer and selector can be unit-tested in
 * the plain JUnit source set.
 *
 * The shapes mirror the backend `chw_behavioural_gap_state` row and the
 * telemetry events the gap-escalation worker consumes — see
 * `docs/offline_refresher_generation_plan.md`.
 */

/** Lifecycle of a per-CHW gap, mirroring the backend status enum. */
enum class GapStatus { ACTIVE, MONITORING, RESOLVED, SUPPRESSED }

/**
 * The on-device link from a refresher module's family to the **real** action/
 * compliance gap that surfaced it (e.g. `referral_location_upazila`), published by
 * [OnDeviceMorningGenerator] and consumed by
 * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore].
 *
 * Carries the gap id (so the store can use the true gap's severity/context instead
 * of the backend's `module_primary_gap_*` placeholder) plus [lastWrongReferralAt]
 * — the timestamp of the mistake currently keeping the gap active. The store uses
 * that timestamp to gate visibility: the refresher is dismissed once the CHW
 * re-passes the quiz *after* this moment, and re-surfaces on the next mistake.
 */
data class ActionGapLink(
    val gapId: String,
    /** Epoch millis of the latest incorrect referral for [gapId]; null if unknown. */
    val lastWrongReferralAt: Long?,
)

/**
 * A coaching-module family bound to one of the CHW's today's-visits via a matched
 * `assessment_due` `workflow_event` trigger binding, carrying that binding's
 * `priority_weight`. Produced by [VisitModuleResolver] and ranked/deduped by
 * [OnDeviceMorningSelector.selectFromTodaysAppointments].
 */
data class VisitCandidate(
    val moduleFamilyId: String,
    val priorityWeight: Int,
)

/** Normalised outcome of a single gap-affecting event. */
enum class GapOutcome { CORRECT, INCORRECT, UNKNOWN }

/** Which update rule applies — quiz events decrement on correct, assessment events don't. */
enum class GapEventKind { QUIZ, ASSESSMENT }

/**
 * Effective per-CHW state for one behavioural gap, computed by replaying events
 * over the synced baseline. Field semantics match the backend so the local
 * result is indistinguishable once events sync.
 */
data class GapState(
    val behaviouralGapId: String,
    val failedAttemptsCount: Int = 0,
    val occurrenceCount: Int = 0,
    val status: GapStatus = GapStatus.ACTIVE,
    val escalatedToSupervisor: Boolean = false,
    val firstObservedAt: Long? = null,
    val lastObservedAt: Long? = null,
    val lastFailedAttemptAt: Long? = null,
    val lastReinforcedAt: Long? = null,
)

/**
 * Effective per-CHW state for one **quiz question** (`quiz_id = module_quiz_question.id`),
 * the on-device mirror of the backend `chw_quiz_question_state`. Computed by
 * [OnDeviceQuizStateEngine] = synced baseline + unsynced `module_quiz_attempted`
 * replay. A quiz is refresher-eligible when [status] is [GapStatus.ACTIVE] and
 * [failedAttemptsCount] > 0.
 */
data class QuizState(
    val quizId: String,
    val moduleId: String,
    val failedAttemptsCount: Int = 0,
    val lastFailedAttemptAt: Long? = null,
    val firstAttemptAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val escalatedToSupervisor: Boolean = false,
    val status: GapStatus = GapStatus.ACTIVE,
)

/**
 * A single event already attributed to a gap and normalised for the reducer.
 *
 * @param behaviouralGapId resolved gap (from the module's primary gap for quiz
 *   events, or the event payload for assessment events).
 * @param quizScorePct module-level score (0..1) used only for the score-based
 *   fallback when [outcome] is [GapOutcome.UNKNOWN]; null otherwise.
 * @param timestamp event time in epoch millis (the "now" the reducer applies).
 */
data class NormalizedGapEvent(
    val behaviouralGapId: String,
    val kind: GapEventKind,
    val outcome: GapOutcome,
    val quizScorePct: Float?,
    val timestamp: Long,
)

/**
 * Thresholds/windows for the reducer, sourced from [com.medtroniclabs.microcoaching.MicroCoachingConfig]
 * (which already carries these as the backend defaults).
 *
 * @param passThreshold fraction (0..1) — a module-level score at/above this is a pass.
 * @param escalationFailureCount failures within [escalationWindowDays] that escalate.
 * @param escalationWindowDays window for the failure-escalation counter.
 * @param occurrenceWindowDays window after which the observation counter resets to 1.
 */
data class GapStateConfig(
    val passThreshold: Float = 0.70f,
    val escalationFailureCount: Int = 3,
    val escalationWindowDays: Int = 30,
    val occurrenceWindowDays: Int = 14,
)

/**
 * Which on-device morning-card sources are active, from
 * [com.medtroniclabs.microcoaching.MicroCoachingConfig.refresherTuning]. Defaults
 * match the backend (quiz-level only); the other three are implemented but off and
 * re-enable by flipping one flag. See [OnDeviceMorningGenerator].
 */
data class MorningSourcesConfig(
    val quiz: Boolean = true,
    val gap: Boolean = false,
    val referral: Boolean = false,
    val visit: Boolean = false,
)
