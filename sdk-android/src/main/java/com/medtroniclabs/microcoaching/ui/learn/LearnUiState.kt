package com.medtroniclabs.microcoaching.ui.learn

/**
 * UI state for [LearnViewModel]. Consumed by all learn screens via [CoachingNavGraph].
 *
 * State machine:
 *   Loading → ModuleList → (tap a module) → ModuleReady → LessonContent
 *              → QuizQuestion(0..n) → QuizResult → (back) → ModuleList
 *
 * [Error] is reachable from [Loading] if the DB/seed step fails.
 */
sealed class LearnUiState {

    /** Brief loading state while the DB is initialised and scenarios are fetched. */
    object Loading : LearnUiState()

    /** Error state — shown if seed or DB load fails entirely. */
    data class Error(val message: String) : LearnUiState()

    /**
     * All available learning modules are ready to display.
     * Ordered by gap priority: active-gap scenarios first, then unstarted, then completed.
     */
    data class ModuleList(val modules: List<LearnModule>) : LearnUiState()

    /**
     * A specific module card is ready — CHW has tapped it and is about to start.
     */
    data class ModuleReady(val module: LearnModule) : LearnUiState()

    /**
     * The lesson content (Bangla card) is being displayed.
     */
    data class LessonContent(val module: LearnModule) : LearnUiState()

    /** Unused in UC1 — reserved for expandable reference content. */
    object LessonComplete : LearnUiState()

    /**
     * A quiz question is active.
     *
     * @param questions Full list of questions for the active scenario.
     * @param answers Map of question index → selected answer index.
     */
    data class QuizInProgress(
        val questions: List<QuizQuestion>,
        val answers: Map<Int, Int> = emptyMap(),
    ) : LearnUiState()

    /**
     * Quiz is finished; result screen is shown.
     */
    data class QuizResult(
        val scorePercent: Int,
        val correctCount: Int,
        val totalCount: Int,
        val badgeLabel: String,
        val completedModuleFamilyId: String,
        val questions: List<QuizQuestion> = emptyList(),
        val answers: Map<Int, Int> = emptyMap(),
        /**
         * XP earned for completing this module quiz, computed from the resolved
         * [com.medtroniclabs.microcoaching.domain.config.LearningPoints]
         * (attempted × base + correct × multiplier + completion). Display-only.
         */
        val earnedXp: Int = 0,
    ) : LearnUiState()
}
