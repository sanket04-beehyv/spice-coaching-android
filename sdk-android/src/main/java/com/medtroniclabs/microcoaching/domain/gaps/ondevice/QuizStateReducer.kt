package com.medtroniclabs.microcoaching.domain.gaps.ondevice

/**
 * Pure fold of one quiz-attempt outcome onto a [QuizState], mirroring the backend
 * `QuizQuestionStateService` (quiz-level telemetry mode):
 *  - incorrect → `failedAttemptsCount + 1` (reset to 1 when the previous failure is
 *    outside the escalation window), `status = ACTIVE`, escalate at the threshold;
 *  - correct → `failedAttemptsCount − 1` (floor 0); reaching 0 ⇒ `RESOLVED`;
 *  - unknown → record the attempt timestamp only (no per-question signal).
 *
 * Stateless and deterministic — `nowMillis` (the event's timestamp) is passed in,
 * never read from the clock, so it runs in the plain JUnit source set.
 */
object QuizStateReducer {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun reduce(
        state: QuizState,
        outcome: GapOutcome,
        config: GapStateConfig,
        nowMillis: Long,
    ): QuizState {
        val firstAttemptAt = state.firstAttemptAt ?: nowMillis
        return when (outcome) {
            GapOutcome.INCORRECT -> {
                val outsideWindow = state.lastFailedAttemptAt != null &&
                    nowMillis - state.lastFailedAttemptAt > config.escalationWindowDays.toLong() * DAY_MS
                val failed = if (outsideWindow) 1 else state.failedAttemptsCount + 1
                state.copy(
                    failedAttemptsCount = failed,
                    lastFailedAttemptAt = nowMillis,
                    lastAttemptAt = nowMillis,
                    firstAttemptAt = firstAttemptAt,
                    status = GapStatus.ACTIVE,
                    escalatedToSupervisor = failed >= config.escalationFailureCount,
                )
            }
            GapOutcome.CORRECT -> {
                val failed = (state.failedAttemptsCount - 1).coerceAtLeast(0)
                state.copy(
                    failedAttemptsCount = failed,
                    lastAttemptAt = nowMillis,
                    firstAttemptAt = firstAttemptAt,
                    status = if (failed == 0) GapStatus.RESOLVED else state.status,
                    escalatedToSupervisor = state.escalatedToSupervisor && failed >= config.escalationFailureCount,
                )
            }
            GapOutcome.UNKNOWN -> state.copy(lastAttemptAt = nowMillis, firstAttemptAt = firstAttemptAt)
        }
    }
}
