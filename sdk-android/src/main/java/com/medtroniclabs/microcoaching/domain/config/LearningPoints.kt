package com.medtroniclabs.microcoaching.domain.config

import com.medtroniclabs.microcoaching.data.db.entity.ConfigThresholdEntity

/**
 * Resolved learning-points configuration — the per-event XP weights shared
 * across the SDK's coaching surfaces.
 *
 * Sourced from the cached `config_threshold` rows synced via `GET /sync/config`
 * ([com.medtroniclabs.microcoaching.sync.SyncApi.pullConfig]). Falls back to the
 * documented v3 defaults per key when a row is missing or its value isn't an
 * integer.
 *
 * Display-only today: these weights drive the "+N XP" the CHW sees (per correct
 * answer, and the module-completion total on the result screen). No running XP
 * balance is persisted.
 */
data class LearningPoints(
    val moduleDelivered: Int = DEFAULT_MODULE_DELIVERED,
    val moduleCardViewed: Int = DEFAULT_MODULE_CARD_VIEWED,
    val quizAttemptedBase: Int = DEFAULT_QUIZ_ATTEMPTED_BASE,
    val quizScoreMultiplier: Int = DEFAULT_QUIZ_SCORE_MULTIPLIER,
    val moduleCompleted: Int = DEFAULT_MODULE_COMPLETED,
    val spiceActionObserved: Int = DEFAULT_SPICE_ACTION_OBSERVED,
) {

    /**
     * Total XP earned for finishing a module quiz, per the agreed formula:
     *
     * ```
     *   (questionsAttempted × quizAttemptedBase)   // a base reward per question attempted
     * + (correctAnswers     × quizScoreMultiplier) // a bonus per correct answer
     * + moduleCompleted                            // reaching the result screen ⇒ completed
     * ```
     *
     * Reaching the result screen means every question was attempted, so callers
     * pass the total question count as [questionsAttempted].
     */
    fun moduleQuizXp(questionsAttempted: Int, correctAnswers: Int): Int =
        (questionsAttempted * quizAttemptedBase) +
            (correctAnswers * quizScoreMultiplier) +
            moduleCompleted

    companion object {
        const val KEY_MODULE_DELIVERED = "learning_points_module_delivered"
        const val KEY_MODULE_CARD_VIEWED = "learning_points_module_card_viewed"
        const val KEY_QUIZ_ATTEMPTED_BASE = "learning_points_module_quiz_attempted_base"
        const val KEY_QUIZ_SCORE_MULTIPLIER = "learning_points_module_quiz_score_multiplier"
        const val KEY_MODULE_COMPLETED = "learning_points_module_completed"
        const val KEY_SPICE_ACTION_OBSERVED = "learning_points_spice_action_observed"

        // Fallbacks used when a config_threshold row is missing or non-integer.
        const val DEFAULT_MODULE_DELIVERED = 5
        const val DEFAULT_MODULE_CARD_VIEWED = 10
        const val DEFAULT_QUIZ_ATTEMPTED_BASE = 15
        const val DEFAULT_QUIZ_SCORE_MULTIPLIER = 15
        const val DEFAULT_MODULE_COMPLETED = 20
        const val DEFAULT_SPICE_ACTION_OBSERVED = 3

        /**
         * Build from cached global `config_threshold` rows, falling back to the
         * per-key default when a row is missing or its value isn't an integer.
         */
        fun from(rows: List<ConfigThresholdEntity>): LearningPoints {
            val byKey = rows.associate { it.key to it.value }
            fun valueOf(key: String, default: Int): Int =
                byKey[key]?.trim()?.toIntOrNull() ?: default
            return LearningPoints(
                moduleDelivered = valueOf(KEY_MODULE_DELIVERED, DEFAULT_MODULE_DELIVERED),
                moduleCardViewed = valueOf(KEY_MODULE_CARD_VIEWED, DEFAULT_MODULE_CARD_VIEWED),
                quizAttemptedBase = valueOf(KEY_QUIZ_ATTEMPTED_BASE, DEFAULT_QUIZ_ATTEMPTED_BASE),
                quizScoreMultiplier = valueOf(KEY_QUIZ_SCORE_MULTIPLIER, DEFAULT_QUIZ_SCORE_MULTIPLIER),
                moduleCompleted = valueOf(KEY_MODULE_COMPLETED, DEFAULT_MODULE_COMPLETED),
                spiceActionObserved = valueOf(KEY_SPICE_ACTION_OBSERVED, DEFAULT_SPICE_ACTION_OBSERVED),
            )
        }
    }
}
