package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.dao.ChwQuizQuestionStateDao
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.entity.ChwQuizQuestionStateEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity

/**
 * Computes effective **per-quiz-question** refresher state with the same
 * **baseline + replay delta** model as the gap engine, but keyed by
 * `quiz_id` (`module_quiz_question.id`) — the backend's quiz-level default mode.
 *
 * Seed from the `/sync/gaps` quiz-state snapshot ([ChwQuizQuestionStateDao]), then
 * fold the CHW's **unsynced** `module_quiz_attempted` events
 * ([CoachingEventDao.getUnsyncedQuizAttempts]) through the ±1 rule, mirroring the
 * backend `QuizQuestionStateService`:
 *  - incorrect → `failedAttemptsCount += 1` (reset to 1 if the previous failure is
 *    outside the escalation window), `status = ACTIVE`, escalate at the threshold;
 *  - correct → `failedAttemptsCount -= 1`, `status = RESOLVED` when it reaches 0.
 *
 * Synced quiz events are already folded into the baseline, so replaying only the
 * unsynced ones avoids double-counting (same rule as the gap engine's quiz arm).
 * Result is in-memory only — never writes `chw_quiz_question_state`, so that table
 * stays backend-authoritative and the replay set stays well-defined.
 */
internal class OnDeviceQuizStateEngine(
    private val quizStateDao: ChwQuizQuestionStateDao,
    private val eventDao: CoachingEventDao,
    private val config: GapStateConfig,
) {
    suspend fun computeStates(chwId: String): Map<String, QuizState> {
        val states = quizStateDao.getAllForChw(chwId)
            .associate { it.quizId to it.toQuizState() }
            .toMutableMap()
        val baselineActive = states.filterValues { it.status == GapStatus.ACTIVE && it.failedAttemptsCount > 0 }.keys
        val events = eventDao.getUnsyncedQuizAttempts(chwId)
        Log.i(
            TAG,
            "quizEngine: chw=$chwId baselineQuizzes=${states.size} baselineActive=${baselineActive.size} " +
                "replayEvents=${events.size}",
        )

        var replayed = 0
        for (event in events) {
            val quizId = event.quizQuestionId?.takeIf(String::isNotBlank) ?: continue
            val moduleId = event.moduleId ?: states[quizId]?.moduleId ?: continue
            val ts = event.timestampUtc ?: event.timestampLocal
            val outcome = outcomeOf(event)
            val current = states[quizId] ?: QuizState(quizId = quizId, moduleId = moduleId)
            val next = QuizStateReducer.reduce(current.copy(moduleId = moduleId), outcome, config, ts)
            states[quizId] = next
            replayed++
            Log.i(
                TAG,
                "quizEngine.replay: quiz=$quizId outcome=$outcome → " +
                    "failed=${next.failedAttemptsCount} status=${next.status}",
            )
        }

        val active = states.filterValues { it.status == GapStatus.ACTIVE && it.failedAttemptsCount > 0 }.keys
        Log.i(TAG, "quizEngine: effectiveActive=${active.size} (replayed=$replayed)")
        return states
    }

    private fun outcomeOf(event: CoachingEventEntity): GapOutcome {
        event.isCorrect?.let { return if (it) GapOutcome.CORRECT else GapOutcome.INCORRECT }
        return when (event.outcome?.lowercase()) {
            "correct" -> GapOutcome.CORRECT
            "wrong", "incorrect" -> GapOutcome.INCORRECT
            else -> GapOutcome.UNKNOWN
        }
    }

    private fun ChwQuizQuestionStateEntity.toQuizState() = QuizState(
        quizId = quizId,
        moduleId = moduleId,
        failedAttemptsCount = failedAttemptsCount,
        lastFailedAttemptAt = lastFailedAttemptAt,
        firstAttemptAt = firstAttemptAt,
        lastAttemptAt = lastAttemptAt,
        escalatedToSupervisor = escalatedToSupervisor,
        status = if (status == "resolved") GapStatus.RESOLVED else GapStatus.ACTIVE,
    )

    private companion object {
        const val TAG = "OnDeviceMorningTrace"
    }
}
