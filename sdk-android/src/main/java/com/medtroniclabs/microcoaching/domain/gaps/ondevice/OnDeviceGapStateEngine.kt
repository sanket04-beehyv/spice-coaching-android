package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.dao.ChwGapProfileDao
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity

/**
 * Computes effective per-CHW gap state with the **baseline + replay delta**
 * model: seed from the `/sync/gaps` snapshot (`chw_gap_profile_local`), then
 * apply local events from [CoachingEventDao.getReplayableForGapState]:
 *
 *  - **quiz** (`module_quiz_attempted`, unsynced only) — folded event-by-event
 *    through [GapStateReducer], faithful to the backend's ±1 model. Synced quiz
 *    events are already in the baseline, so they're not replayed (no double-count).
 *  - **observed SPICE actions** (`spice_action_observed`, synced included) —
 *    reduced to the **latest outcome per gap**: the gap is (re)activated iff the
 *    CHW's most recent action for it was incorrect. Interim measure while the
 *    backend has no referral-gap calculation, so the baseline never reflects these
 *    and the local latest-incorrect is what keeps a wrong referral surfaced.
 *
 * Result is in-memory only — this never writes `chw_gap_profile_local`, so that
 * table stays backend-authoritative and the replay set stays well-defined.
 */
internal class OnDeviceGapStateEngine(
    private val gapProfileDao: ChwGapProfileDao,
    private val eventDao: CoachingEventDao,
    private val config: GapStateConfig,
) {

    /**
     * @param index the module↔gap map, used to attribute quiz events to the
     *   module's primary gap exactly as the backend does.
     * @param enableAssessment when false, `spice_action_observed` (referral) events
     *   are NOT applied — the referral source is off (the backend dropped it). The
     *   quiz-gap replay still runs. Defaults to true (legacy behaviour).
     */
    suspend fun computeStates(
        chwId: String,
        index: ModuleGapIndex,
        enableAssessment: Boolean = true,
    ): Map<String, GapState> {
        val states = gapProfileDao.getAllForChw(chwId)
            .associate { it.behaviouralGapId to it.toBaselineState() }
            .toMutableMap()
        val baselineActive = states.filterValues { it.isActive() }.keys
        val events = eventDao.getReplayableForGapState(chwId)
        Log.i(
            TAG,
            "engine: chw=$chwId baselineGaps=${states.size} baselineActive=$baselineActive " +
                "replayEvents=${events.size}",
        )

        var quizReplayed = 0
        // Latest SPICE action per gap. Events arrive oldest-first, so the last put
        // for a gap is its most recent action.
        val latestAssessmentByGap = LinkedHashMap<String, NormalizedGapEvent>()
        for (event in events) {
            val normalised = normalise(event, index)
            if (normalised == null) {
                val reason = if (event.eventType == "module_quiz_attempted") {
                    "no primary gap bound to family ${event.moduleFamilyId}"
                } else {
                    "no behavioural_gap_id on the event"
                }
                Log.i(TAG, "engine.replay: skip ${event.eventType} ($reason)")
                continue
            }
            when (normalised.kind) {
                GapEventKind.QUIZ -> {
                    val current = states[normalised.behaviouralGapId]
                        ?: GapState(behaviouralGapId = normalised.behaviouralGapId)
                    val next = GapStateReducer.reduce(current, normalised, config, normalised.timestamp)
                    states[normalised.behaviouralGapId] = next
                    quizReplayed++
                    Log.i(
                        TAG,
                        "engine.replay: gap=${normalised.behaviouralGapId} kind=QUIZ " +
                            "outcome=${normalised.outcome} → failed=${next.failedAttemptsCount} status=${next.status}",
                    )
                }
                GapEventKind.ASSESSMENT -> latestAssessmentByGap[normalised.behaviouralGapId] = normalised
            }
        }

        // Apply the latest SPICE action per gap: incorrect re-activates the gap;
        // a non-incorrect latest is left as-is (a correct referral is recorded
        // gap-less today, so it never lands here — see onReferralSubmitted).
        // Skipped entirely when the referral source is disabled.
        if (enableAssessment) for ((gapId, latest) in latestAssessmentByGap) {
            if (latest.outcome == GapOutcome.INCORRECT) {
                val current = states[gapId] ?: GapState(behaviouralGapId = gapId)
                val next = GapStateReducer.reduce(current, latest, config, latest.timestamp)
                states[gapId] = next
                Log.i(
                    TAG,
                    "engine.assessment: gap=$gapId latest=INCORRECT → failed=${next.failedAttemptsCount} " +
                        "status=${next.status}",
                )
            } else {
                Log.i(TAG, "engine.assessment: gap=$gapId latest=${latest.outcome} → not activated")
            }
        }

        val effectiveActive = states.filterValues { it.isActive() }.keys
        Log.i(
            TAG,
            "engine: effectiveActive=$effectiveActive " +
                "(quizReplayed=$quizReplayed assessmentGaps=${latestAssessmentByGap.size})",
        )
        return states
    }

    private fun GapState.isActive() = status == GapStatus.ACTIVE || status == GapStatus.MONITORING

    /** Project a raw event onto a gap, or null when it can't be attributed. */
    private fun normalise(event: CoachingEventEntity, index: ModuleGapIndex): NormalizedGapEvent? {
        val timestamp = event.timestampUtc ?: event.timestampLocal
        return when (event.eventType) {
            "module_quiz_attempted" -> {
                // Faithful to the backend: the gap is the MODULE's primary gap,
                // not whatever id the event happens to carry.
                val gapId = event.moduleFamilyId?.let { index.familyToPrimaryGap[it] } ?: return null
                NormalizedGapEvent(
                    behaviouralGapId = gapId,
                    kind = GapEventKind.QUIZ,
                    outcome = outcomeOf(event),
                    quizScorePct = event.quizScorePct,
                    timestamp = timestamp,
                )
            }
            "spice_action_observed" -> {
                val gapId = event.behaviouralGapId ?: return null
                NormalizedGapEvent(
                    behaviouralGapId = gapId,
                    kind = GapEventKind.ASSESSMENT,
                    outcome = outcomeOf(event),
                    quizScorePct = null,
                    timestamp = timestamp,
                )
            }
            else -> null
        }
    }

    /** Prefer the per-question `is_correct`, then the explicit outcome string. */
    private fun outcomeOf(event: CoachingEventEntity): GapOutcome {
        event.isCorrect?.let { return if (it) GapOutcome.CORRECT else GapOutcome.INCORRECT }
        return when (event.outcome?.lowercase()) {
            "correct" -> GapOutcome.CORRECT
            "wrong", "incorrect" -> GapOutcome.INCORRECT
            else -> GapOutcome.UNKNOWN
        }
    }

    private fun ChwGapProfileEntity.toBaselineState() = GapState(
        behaviouralGapId = behaviouralGapId,
        failedAttemptsCount = failedAttemptsCount,
        // The /sync/gaps mapper stores the backend occurrence_count in wrong_count.
        occurrenceCount = wrongCount,
        status = if (gapActive) GapStatus.ACTIVE else GapStatus.RESOLVED,
        escalatedToSupervisor = escalatedToSupervisor,
        firstObservedAt = null,
        lastObservedAt = lastSeen,
        lastFailedAttemptAt = lastFailedAttemptAt,
        lastReinforcedAt = lastReinforcedAt,
    )

    private companion object {
        // Shared with OnDeviceMorningGenerator so one logcat filter shows the whole
        // pipeline: `adb logcat -s OnDeviceMorningTrace:I`.
        const val TAG = "OnDeviceMorningTrace"
    }
}
