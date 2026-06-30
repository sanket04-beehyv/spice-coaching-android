package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Local mirror of the CHW's **per-quiz-question** refresher state — the on-device
 * counterpart of the backend `chw_quiz_question_state` table (quiz-level telemetry
 * mode, the backend default).
 *
 * Keyed on `(chw_id, quiz_id)` where `quiz_id = module_quiz_question.id`. Seeded from
 * `/sync/gaps` (`chw_quiz_question_states[]`) and used as the baseline the on-device
 * [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceQuizStateEngine]
 * layers unsynced `module_quiz_attempted` replay on top of.
 *
 * State model (mirrors the backend `QuizQuestionStateService`): incorrect →
 * `failedAttemptsCount += 1`; correct → `-= 1`, `status = "resolved"` at 0; a later
 * incorrect re-increments. A quiz is refresher-eligible when `status = "active"` and
 * `failedAttemptsCount > 0`.
 */
@Entity(
    tableName = "chw_quiz_question_state",
    primaryKeys = ["chw_id", "quiz_id"],
    indices = [Index(value = ["chw_id"])],
)
data class ChwQuizQuestionStateEntity(

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    /** `module_quiz_question.id` — the quiz this state tracks. */
    @ColumnInfo(name = "quiz_id")
    val quizId: String,

    /** The module the quiz belongs to (used to resolve the refresher card's module/family). */
    @ColumnInfo(name = "module_id")
    val moduleId: String,

    @ColumnInfo(name = "failed_attempts_count")
    val failedAttemptsCount: Int = 0,

    @ColumnInfo(name = "last_failed_attempt_at")
    val lastFailedAttemptAt: Long? = null,

    @ColumnInfo(name = "first_attempt_at")
    val firstAttemptAt: Long? = null,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "escalated_to_supervisor")
    val escalatedToSupervisor: Boolean = false,

    /** "active" | "resolved". */
    @ColumnInfo(name = "status")
    val status: String = "active",

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long? = null,
)
