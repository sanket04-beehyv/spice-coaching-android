package com.medtroniclabs.microcoaching.domain.gaps.ondevice

/**
 * Pure fold of one [NormalizedGapEvent] onto a [GapState], mirroring the backend
 * gap-escalation handler (`gap_escalation_handler.py` + `gap_state_service.py`).
 *
 * Faithful replica rules (verified against backend code):
 *  - Every event records an observation (`occurrenceCount++`, windowed).
 *  - Quiz `correct`   → `failedAttemptsCount − 1` (floor 0); reaching 0 ⇒ resolved.
 *  - Quiz `incorrect` → `failedAttemptsCount + 1` (windowed); escalate at the threshold.
 *  - Quiz unknown     → score-based: pass ⇒ reset; else treated as a failure.
 *  - Assessment `incorrect` → same failure increment; any other outcome ⇒ observation only
 *    (the backend has no decrement path for assessment events).
 *
 * Stateless and deterministic — `nowMillis` is passed in (the event's timestamp),
 * never read from the clock, so it runs in the plain JUnit source set.
 */
object GapStateReducer {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun reduce(
        state: GapState,
        event: NormalizedGapEvent,
        config: GapStateConfig,
        nowMillis: Long,
    ): GapState {
        val observed = recordObservation(state, config, nowMillis)
        return when (event.kind) {
            GapEventKind.QUIZ -> when (event.outcome) {
                GapOutcome.CORRECT -> recordCorrect(observed, config)
                GapOutcome.INCORRECT -> recordFailed(observed, config, nowMillis)
                GapOutcome.UNKNOWN -> {
                    val score = event.quizScorePct
                    if (score != null && score >= config.passThreshold) resetAfterPass(observed, nowMillis)
                    else recordFailed(observed, config, nowMillis)
                }
            }
            GapEventKind.ASSESSMENT -> when (event.outcome) {
                GapOutcome.INCORRECT -> recordFailed(observed, config, nowMillis)
                else -> observed // observation only — no decrement on assessment correctness
            }
        }
    }

    /** `occurrenceCount++`, resetting to 1 when the prior observation is outside the window. */
    private fun recordObservation(state: GapState, config: GapStateConfig, now: Long): GapState {
        val windowMs = config.occurrenceWindowDays.toLong() * DAY_MS
        val withinWindow = state.lastObservedAt != null && (now - state.lastObservedAt) <= windowMs
        return state.copy(
            occurrenceCount = if (withinWindow) state.occurrenceCount + 1 else 1,
            firstObservedAt = state.firstObservedAt ?: now,
            lastObservedAt = now,
        )
    }

    /** Correct quiz answer: decrement toward 0; resolve at 0; clear escalation when below threshold. */
    private fun recordCorrect(state: GapState, config: GapStateConfig): GapState {
        if (state.failedAttemptsCount <= 0) return state
        val next = state.failedAttemptsCount - 1
        return state.copy(
            failedAttemptsCount = next,
            status = if (next == 0) GapStatus.RESOLVED else state.status,
            escalatedToSupervisor = state.escalatedToSupervisor && next >= config.escalationFailureCount,
        )
    }

    /** Incorrect outcome: windowed increment; re-activate; escalate at the threshold. */
    private fun recordFailed(state: GapState, config: GapStateConfig, now: Long): GapState {
        val windowMs = config.escalationWindowDays.toLong() * DAY_MS
        val withinWindow = state.lastFailedAttemptAt != null && (now - state.lastFailedAttemptAt) <= windowMs
        val next = if (withinWindow) state.failedAttemptsCount + 1 else 1
        return state.copy(
            failedAttemptsCount = next,
            lastFailedAttemptAt = now,
            status = GapStatus.ACTIVE,
            escalatedToSupervisor = state.escalatedToSupervisor || next >= config.escalationFailureCount,
        )
    }

    /** Module-level pass (score-based path): clear failures and stamp reinforcement. */
    private fun resetAfterPass(state: GapState, now: Long): GapState =
        state.copy(
            failedAttemptsCount = 0,
            escalatedToSupervisor = false,
            lastReinforcedAt = now,
            status = GapStatus.RESOLVED,
        )
}
