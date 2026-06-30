package com.medtroniclabs.microcoaching.ui.learn

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.CoachingTtsHelper
import com.medtroniclabs.microcoaching.data.repository.ModuleRepository
import com.medtroniclabs.microcoaching.data.repository.ModuleRepositoryImpl
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.domain.telemetry.triggerTypeFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ViewModel for the v3 module → lesson → quiz → result flow.
 *
 * Module-only. The scenario-cache fallback was removed in 0.3.0; all rendering
 * now comes from `module_cache` rows synced via `/sync/modules`. Per-CHW
 * completion state is held in memory for the session and also persisted:
 * `finishQuiz` calls `sdk.onModuleQuizCompleted`, which upserts a
 * [com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity] row.
 *
 * @param chwId The CHW currently using the app. Defaults to "unknown_chw" so
 *   the flow never crashes when SPICE hasn't supplied the ID yet.
 */
class LearnViewModel(
    private val context: Context,
    private val chwId: String,
    private val moduleRepo: ModuleRepository,
    private val telemetry: EventRecorder,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LearnUiState>(LearnUiState.Loading)
    val uiState: StateFlow<LearnUiState> = _uiState.asStateFlow()

    /**
     * Knowledge section (document list + download/preview). Extracted to
     * [KnowledgeDocController]; the four flows below are passthroughs so existing
     * collectors (CoachingNavGraph, the modules screens) keep reading
     * `learnVm.knowledgeDocuments` / `cachedDocIds` / `docEvents` /
     * `downloadProgress` unchanged. Uses [viewModelScope] so an in-flight
     * download cancels with the screen.
     */
    private val knowledgeDocs = KnowledgeDocController(viewModelScope, context)
    val knowledgeDocuments: StateFlow<List<KnowledgeDocument>> get() = knowledgeDocs.knowledgeDocuments
    val cachedDocIds: StateFlow<Set<String>> get() = knowledgeDocs.cachedDocIds
    val docEvents: SharedFlow<DocEvent> get() = knowledgeDocs.docEvents
    val downloadProgress: StateFlow<DownloadProgress?> get() = knowledgeDocs.downloadProgress

    /**
     * Categorised module lists + the shared featured pick, projected from the
     * SDK-owned [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore]
     * so the modules screen and the home card consume the same source of truth.
     */
    val refresherModules: StateFlow<List<LearnModule>>
        get() = MicroCoachingSDK.getInstance().coachingModuleStore.refresherModules
    val trainingModules: StateFlow<List<LearnModule>>
        get() = MicroCoachingSDK.getInstance().coachingModuleStore.trainingModules
    val selectedMorningCard: StateFlow<LearnModule?>
        get() = MicroCoachingSDK.getInstance().coachingModuleStore.selectedMorningCard

    /** Questions for the currently active module — loaded when the quiz starts. */
    private var activeQuestions: List<QuizQuestion> = emptyList()

    /** The module the CHW is currently working through. */
    private var activeModule: LearnModule? = null

    /**
     * Last-known mapped module list from [observeModules]. Used by [popToModuleList] to
     * restore the [LearnUiState.ModuleList] state without re-running [initialise] and
     * showing a Loading spinner (back-navigation should feel instant).
     */
    private var lastKnownModules: List<LearnModule> = emptyList()

    /** True when the CHW entered the quiz via [startCourse] (lesson-player path). Used by
     *  [CoachingNavGraph] to decide whether "Try Again" should restart the full course. */
    var startedViaCourse: Boolean = false
        private set

    /** True when the CHW entered the quiz via [selectModuleForQuiz] from the refresher list.
     *  Used by [QuizResultScreen] to show "Back to Refreshers" instead of "More Modules". */
    var startedViaRefresher: Boolean = false
        private set

    private var _quizCorrectCount = 0
    private var _quizTotalCount = 0

    // ── Listen-aloud (TTS) ────────────────────────────────────────────────────

    /** Speaks lesson card bodies in the SDK's current language. */
    private val tts: CoachingTtsHelper = CoachingTtsHelper(context, ttsLocaleForSdkLanguage())

    private val _autoSpeakEnabled = MutableStateFlow(false)
    /**
     * When `true`, [com.medtroniclabs.microcoaching.ui.learn.LessonPlayerScreen]
     * auto-plays each card and auto-advances on TTS completion. Toggled by the
     * "Listen" button on [com.medtroniclabs.microcoaching.ui.learn.ModuleDetailScreen]
     * and the speaker icon in the lesson player header.
     */
    val autoSpeakEnabled: StateFlow<Boolean> = _autoSpeakEnabled.asStateFlow()

    fun toggleAutoSpeak() {
        val next = !_autoSpeakEnabled.value
        _autoSpeakEnabled.value = next
        if (!next) tts.stop()
    }

    /** Speak [text] aloud; [onDone] fires when the utterance completes. */
    fun speakAloud(text: String, onDone: () -> Unit = {}) {
        tts.speak(text, onDone)
    }

    /** Stop any in-flight TTS utterance. */
    fun stopSpeaking() {
        tts.stop()
    }

    init {
        viewModelScope.launch { initialise() }
    }

    override fun onCleared() {
        tts.release()
        super.onCleared()
    }

    private fun ttsLocaleForSdkLanguage(): Locale = when (MicroCoachingSDK.getInstance().config.language) {
        Language.ENGLISH -> Locale.US
        Language.BANGLA -> Locale("bn", "BD")
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private suspend fun initialise() {
        try {
            val sdk = MicroCoachingSDK.getInstance()
            val moduleCount = moduleRepo.countActive()
            if (moduleCount == 0 && sdk.config.backendUrl.isNotBlank()) {
                Log.i(TAG, "module_cache empty on Learn open — triggering inbound sync.")
                sdk.syncCoordinator.triggerNow()
            }
            observeModules(sdk)
        } catch (e: Exception) {
            Log.e(TAG, "Initialisation failed: ${e.message}", e)
            _uiState.value = LearnUiState.Error(localized(R.string.learn_error_failed_load))
        }
    }

    private suspend fun observeModules(sdk: MicroCoachingSDK) {
        // Project the mapped list from the SDK-owned store (the single source of
        // truth, shared with the home card). The store already reacts to module-
        // cache changes, coaching_event inserts, and morning-card refreshes.
        // Combine with the raw module-cache flow so the empty/error decision stays
        // cache-first: cached rows are always shown; the empty-state message fires
        // only when the cache is genuinely empty. Knowledge docs are owned by
        // [KnowledgeDocController], which observes the module cache itself.
        combine(
            sdk.coachingModuleStore.allModules,
            moduleRepo.getAllActive(),
        ) { mapped, rawRows -> mapped to rawRows }
            .collectLatest { (mapped, rawRows) ->
                // Cache the latest mapped list for fast pop-back (avoid Loading flash).
                lastKnownModules = mapped

                // Only push state if we're currently in a list-viewing state. If the
                // CHW is deep in the flow (LessonContent, QuizInProgress, QuizResult),
                // a Flow emission here would otherwise override their state and cause
                // blank / spinner screens on ModuleDetailScreen, LessonPlayerScreen.
                val current = _uiState.value
                val isListState = current is LearnUiState.Loading ||
                    current is LearnUiState.ModuleList ||
                    current is LearnUiState.Error
                if (isListState) {
                    _uiState.value = if (rawRows.isEmpty()) {
                        LearnUiState.Error(emptyMessage(sdk))
                    } else {
                        if (mapped.isEmpty()) {
                            Log.w(
                                TAG,
                                "module_cache has ${rawRows.size} row(s) but the store mapped 0 — " +
                                    "data issue, not connectivity. Showing empty ModuleList instead of Error.",
                            )
                        }
                        LearnUiState.ModuleList(mapped)
                    }
                }
            }
    }

    /**
     * Picks the right empty-state message based on what actually caused the
     * empty list:
     *  - backend not configured → static "no backend" message
     *  - device offline → "no internet, retry" (the Retry button will succeed
     *    once connectivity returns)
     *  - device online but the backend genuinely returned no modules →
     *    informational "nothing published yet". Not an error condition;
     *    distinguished from the offline case so the CHW isn't told to fix
     *    their connection when there's nothing wrong with it.
     */
    private fun emptyMessage(sdk: MicroCoachingSDK): String = when {
        sdk.config.backendUrl.isBlank() -> localized(R.string.learn_empty_no_backend)
        !sdk.isNetworkAvailable() -> localized(R.string.learn_empty_no_internet)
        else -> localized(R.string.learn_empty_no_modules)
    }

    /**
     * Re-triggers inbound sync from the empty/error Learn screen. Flips the
     * state to [LearnUiState.Loading] so the CHW sees immediate feedback; the
     * running `observeModules` collector will replace it with [ModuleList]
     * the moment new modules land in Room. If the sync fails silently (no
     * Room change, no flow re-emit), the watchdog restores the Error state
     * after a few seconds so the CHW can retry again instead of being stuck
     * on a spinner.
     */
    fun retrySync() {
        val sdk = MicroCoachingSDK.getInstance()
        if (sdk.config.backendUrl.isBlank()) return // no backend → retry is a no-op
        _uiState.value = LearnUiState.Loading
        sdk.syncCoordinator.triggerNow()
        viewModelScope.launch {
            delay(5_000)
            if (_uiState.value is LearnUiState.Loading) {
                // Cache-first: only fall back to Error if `module_cache` is genuinely
                // empty. If cached rows exist, prefer showing them over an error —
                // the sync may have failed but the CHW has content to work with.
                val cacheCount = moduleRepo.countActive()
                _uiState.value = if (cacheCount == 0) {
                    LearnUiState.Error(emptyMessage(sdk))
                } else {
                    LearnUiState.ModuleList(lastKnownModules)
                }
            }
        }
    }

    private fun localized(@androidx.annotation.StringRes resId: Int): String {
        val sdkLanguage = MicroCoachingSDK.getInstance().language
        val ctx = com.medtroniclabs.microcoaching.ui.SdkLocaleHelper.wrap(context, sdkLanguage)
        return ctx.getString(resId)
    }

    // ── Knowledge documents (delegated to KnowledgeDocController) ───────────────

    /** Download/stream + preview a Knowledge document — see [KnowledgeDocController]. */
    fun openKnowledgeDocument(doc: KnowledgeDocument) = knowledgeDocs.openKnowledgeDocument(doc)

    // ── Navigation transitions ────────────────────────────────────────────────

    fun selectModule(module: LearnModule) {
        activeModule = module
        // Don't downgrade a completed module to "in_progress" just because the
        // CHW opened it again (revisit-from-KnowledgeRow). The persisted DB
        // status from `chw_module_completion.latestAttemptPassed = true` would
        // be overridden by this in-memory map and the module would jump back
        // to the Training row.
        if (module.status != "completed") {
            MicroCoachingSDK.getInstance().coachingModuleStore
                .setInSessionStatus(module.moduleFamilyId, "in_progress")
        }
        _uiState.value = LearnUiState.ModuleReady(module)
        viewModelScope.launch {
            // Surfacing a module to the CHW maps to backend `module_delivered`.
            telemetry.recordCoachingEvent(
                eventType = "module_delivered",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
        }
    }

    /**
     * Restores state to [LearnUiState.LessonContent] without emitting telemetry.
     * Called when the CHW presses back from the quiz to return to module detail.
     */
    fun restoreModuleDetail() {
        val module = activeModule ?: return
        _uiState.value = LearnUiState.LessonContent(module)
    }

    /**
     * Lightweight back-navigation reset: restores [LearnUiState.ModuleList] using the
     * last-known mapped list cached by [observeModules]. Used everywhere the user
     * is returning to the module list — both popping back from [ModuleDetailScreen]
     * and finishing a quiz via "Next Module" on [QuizResultScreen].
     *
     * When the cache is empty (e.g. back before first observeModules emission)
     * falls back to [LearnUiState.Loading] + [initialise] so the screen shows a
     * spinner instead of going blank.
     */
    fun popToModuleList() {
        activeModule = null
        activeQuestions = emptyList()
        startedViaCourse = false
        startedViaRefresher = false
        val cached = lastKnownModules
        _uiState.value = if (cached.isNotEmpty()) {
            LearnUiState.ModuleList(cached)
        } else {
            // No cache yet (e.g., back tapped before first observeModules emission) —
            // fall back to the full re-init path which shows a centered spinner.
            LearnUiState.Loading
        }
        if (cached.isEmpty()) {
            viewModelScope.launch { initialise() }
        }
    }

    /**
     * Force a fresh DB read + remap and transition the UI back to
     * [LearnUiState.ModuleList] regardless of current state. Called from the
     * refresher bottom-sheet dismiss path — the previous "re-emit cache only
     * when state is already ModuleList" implementation silently dropped the
     * refresh when the state was `QuizResult` (the post-quiz state when the
     * sheet dismisses), leaving stale refresher / banner / morning-card UI.
     *
     * Reads the latest mapped list straight from the SDK store (which already
     * reflects the freshly-synced progress, since the dismiss path triggers a
     * coaching_event / refilter that the store reacts to) so the result matches
     * what the live [observeModules] Flow would produce on its next tick.
     */
    fun refreshModuleCounts() {
        viewModelScope.launch {
            val sdk = MicroCoachingSDK.getInstance()
            // Nudge the store to re-read morning_card_cache + progress, then take
            // its current snapshot for an instant restore (no Loading flash).
            sdk.coachingModuleStore.invalidate()
            val mapped = sdk.coachingModuleStore.allModules.value
            lastKnownModules = mapped
            // Cache-first: gate on raw `module_cache` row count, not mapped size.
            val rawCount = moduleRepo.countActive()
            _uiState.value = if (rawCount == 0) {
                LearnUiState.Error(emptyMessage(sdk))
            } else {
                LearnUiState.ModuleList(mapped)
            }
        }
    }

    /**
     * Marks the active module as in-progress when the CHW taps "Start Course".
     * Navigation to [LessonPlayerScreen] is handled by [CoachingNavGraph].
     *
     * Skips the in-progress flip for completed modules — the "Read course"
     * CTA on a completed module goes through this same lesson-player surface
     * but must not downgrade the persisted pass state in the in-memory map.
     */
    fun startCourse() {
        val module = activeModule ?: return
        if (module.status != "completed") {
            MicroCoachingSDK.getInstance().coachingModuleStore
                .setInSessionStatus(module.moduleFamilyId, "in_progress")
        }
        startedViaCourse = true
    }

    /**
     * Whether the active module is currently re-quizzable. Encapsulates the
     * [QuizRetryGate] rule so the nav graph doesn't have to import the gate
     * directly — keeps the "Try Again" surface on [QuizResultScreen] in
     * lock-step with the LessonPlayer's `readOnly` decision.
     *
     * Returns `false` when there's no active module or the retry window has
     * closed; `true` while the window is still open.
     *
     * When removing the retry-window feature (see [QuizRetryGate] "How to
     * remove"), this helper can return `true` unconditionally (or be deleted
     * with the gate). The QuizResultScreen will then always surface the
     * "Try Again" button when `onTryAgain` is non-null.
     */
    fun canRetryActiveQuiz(): Boolean {
        val module = activeModule ?: return false
        return !QuizRetryGate.isRetryWindowClosed(module)
    }

    /**
     * Restarts the lesson-player course after a failed quiz ("Try Again").
     * Resets quiz counters and restores [LearnUiState.LessonContent] so
     * [LessonPlayerScreen] has a module to render (QuizResult would show blank).
     */
    fun retryCourse() {
        val module = activeModule ?: return
        _quizCorrectCount = 0
        _quizTotalCount = 0
        startedViaCourse = true
        _uiState.value = LearnUiState.LessonContent(module)
    }

    /**
     * Parses the active module's `cardsJson` into a typed [LessonCard] list.
     * Used by [CoachingNavGraph] to supply cards to [LessonPlayerScreen].
     */
    fun getCurrentCards(): List<LessonCard> =
        parseLessonCards(activeModule?.cardsJson ?: "[]")

    /**
     * Emits a `module_card_viewed` telemetry event when the CHW views a card
     * in [LessonPlayerScreen]. Called via `LaunchedEffect(currentIndex)`.
     */
    fun recordCardShown(cardIndex: Int) {
        val module = activeModule ?: return
        viewModelScope.launch {
            telemetry.recordCoachingEvent(
                eventType = "module_card_viewed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    fun startLesson() {
        val module = activeModule ?: return
        _uiState.value = LearnUiState.LessonContent(module)
        viewModelScope.launch {
            // Recording the first card view as the CHW enters the lesson body.
            telemetry.recordCoachingEvent(
                eventType = "module_card_viewed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
        }
    }

    fun startQuiz() {
        val module = activeModule ?: return
        _quizCorrectCount = 0
        _quizTotalCount = 0
        viewModelScope.launch {
            activeQuestions = module.inlineQuestions ?: emptyList()
            _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
            telemetry.recordCoachingEvent(
                eventType = "quiz_started",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    /**
     * Refresher shortcut: jump straight from the module list to the quiz,
     * skipping the lesson-content state. Used by the v0.3.2 RefresherQuiz
     * bottom sheet. Telemetry path: `module_delivered` → `quiz_started` →
     * `module_quiz_attempted` (×N, per question) → `module_completed`.
     */
    fun selectModuleForQuiz(module: LearnModule) {
        activeModule = module
        MicroCoachingSDK.getInstance().coachingModuleStore
            .setInSessionStatus(module.moduleFamilyId, "in_progress")
        startedViaRefresher = true
        _quizCorrectCount = 0
        _quizTotalCount = 0
        val questions = module.inlineQuestions.orEmpty()
        Log.d(
            TAG,
            "selectModuleForQuiz: family=${module.moduleFamilyId} moduleId=${module.moduleId} " +
                "type=${module.moduleType} questionCount=${questions.size}",
        )
        viewModelScope.launch {
            telemetry.recordCoachingEvent(
                eventType = "module_delivered",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
            activeQuestions = questions
            _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
            telemetry.recordCoachingEvent(
                eventType = "quiz_started",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    /**
     * One-shot taste used by the Quick learn banner. Emits a single
     * `module_quiz_attempted` carrying both the per-question outcome and the
     * attempt-level score so a wrong banner answer forms a gap state on the
     * backend — enabling the refresher to surface on the next morning-cards
     * call.
     */
    fun recordQuickLearnAnswer(
        module: LearnModule,
        question: QuizQuestion,
        answerIndex: Int,
    ) {
        val isCorrect = answerIndex == question.correctIndex
        viewModelScope.launch {
            telemetry.recordCoachingEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                quizQuestionId = question.id,
                selectedOption = answerIndex,
                isCorrect = isCorrect,
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                quizFamilyId = question.id,
                quizScorePct = if (isCorrect) 1f else 0f,
                outcomeOverride = if (isCorrect) "correct" else "wrong",
                behaviouralGapId = module.behaviouralGapId,
                triggerType = triggerTypeFor(module.source),
            )
            MicroCoachingSDK.getInstance().flushTelemetryNow()
        }
    }

    fun selectAnswer(questionIndex: Int, answerIndex: Int) {
        _uiState.update { state ->
            if (state is LearnUiState.QuizInProgress) {
                state.copy(answers = state.answers + (questionIndex to answerIndex))
            } else state
        }
        val question = activeQuestions.getOrNull(questionIndex) ?: return
        val isCorrect = answerIndex == question.correctIndex
        _quizTotalCount++
        if (isCorrect) _quizCorrectCount++
        val scorePct = _quizCorrectCount.toFloat() / _quizTotalCount
        viewModelScope.launch {
            telemetry.recordCoachingEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = activeModule?.clinicalDomain,
                cardType = "quiz",
                quizQuestionId = question.id,
                selectedOption = answerIndex,
                isCorrect = isCorrect,
                moduleFamilyId = activeModule?.moduleFamilyId,
                moduleId = activeModule?.moduleId,
                moduleVersion = activeModule?.moduleVersion,
                quizFamilyId = question.id,
                quizScorePct = scorePct,
                triggerType = triggerTypeFor(activeModule?.source),
            )
        }
    }

    fun hasQuestion(index: Int): Boolean = index < activeQuestions.size

    /**
     * Completes the quiz in [LearnUiState.QuizInProgress], persists the
     * completion row + telemetry events, and (by default) flushes outbound
     * telemetry plus chains an inbound sync.
     *
     * Pass [deferSync] = `true` from any caller that is mid-flow inside a
     * bottom sheet — the refresher sheet, where the CHW still has lesson
     * cards to read after the quiz portion. Triggering inbound sync there
     * causes `refilterMorningModules` to race against `RefresherContent`'s
     * recomposition and can blank out the sheet. The bottom sheet's
     * `onDismiss` does the flush + sync once the CHW has finished the whole
     * experience.
     */
    fun finishQuiz(deferSync: Boolean = false) {
        val state = _uiState.value as? LearnUiState.QuizInProgress ?: return
        val module = activeModule ?: return

        val sdk = MicroCoachingSDK.getInstance()
        val passThreshold = sdk.config.quizPassThreshold
        val correctCount = state.questions.indices.count { idx ->
            state.answers[idx] == state.questions[idx].correctIndex
        }
        val scorePercent = if (state.questions.isEmpty()) 0
        else (correctCount * 100) / state.questions.size
        val passed = scorePercent >= passThreshold

        val badge = when {
            scorePercent >= 80 -> localized(R.string.badge_expert)
            scorePercent >= passThreshold -> localized(R.string.badge_learner)
            else -> localized(R.string.badge_practice)
        }

        MicroCoachingSDK.getInstance().coachingModuleStore
            .setInSessionStatus(module.moduleFamilyId, if (passed) "completed" else "in_progress")

        // XP from the shared learning-points config: every attempted question
        // earns the base, each correct answer the multiplier, plus a flat
        // completion reward (reaching this screen ⇒ all questions attempted).
        val earnedXp = sdk.learningPoints.value.moduleQuizXp(
            questionsAttempted = state.questions.size,
            correctAnswers = correctCount,
        )

        _uiState.value = LearnUiState.QuizResult(
            scorePercent = scorePercent,
            correctCount = correctCount,
            totalCount = state.questions.size,
            badgeLabel = badge,
            completedModuleFamilyId = module.moduleFamilyId,
            questions = state.questions,
            answers = state.answers,
            earnedXp = earnedXp,
        )

        viewModelScope.launch {
            // Gap state is no longer mutated here: the per-question
            // `module_quiz_attempted` events emitted by `selectAnswer` are the
            // single input, and `OnDeviceGapStateEngine` derives gap state from
            // them (replayed over the synced baseline). Keeping the baseline
            // table backend-authored is what makes the merge correct.

            // Persist module completion + emit quiz_completed telemetry.
            sdk.onModuleQuizCompleted(
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                scoreFraction = scorePercent / 100f,
                passed = passed,
            )
            telemetry.recordCoachingEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                quizScorePct = scorePercent / 100f,
                outcomeOverride = if (passed) "correct" else "wrong",
                behaviouralGapId = module.behaviouralGapId,
                triggerType = triggerTypeFor(module.source),
            )
            if (passed) {
                telemetry.recordCoachingEvent(
                    eventType = "module_completed",
                    clinicalDomain = module.clinicalDomain,
                    cardType = "info",
                    moduleFamilyId = module.moduleFamilyId,
                    moduleId = module.moduleId,
                    moduleVersion = module.moduleVersion,
                )
            }

            // Quiz attempt is a meaningful milestone — flush the batch
            // immediately rather than waiting for the 15-min WorkManager tick.
            // The synced module_quiz_attempted events are how the backend
            // records each answer; the legacy /coaching/quiz-answer endpoint
            // was retired in v3. Then chain an inbound pull so the freshly-
            // computed `chw_module_partial_completion` row lands locally and
            // the refresher / banner / morning-card filter reflect server
            // truth without waiting for the next 15-min tick.
            //
            // Skipped when [deferSync] is true (refresher-sheet flow) — see
            // the kdoc; the sheet's `onDismiss` runs the same calls once the
            // CHW has finished the lesson cards.
            if (!deferSync) {
                sdk.flushTelemetryNow()
                sdk.syncCoordinator.triggerNow()
            }
        }
    }

    // ── Companion / Factory ───────────────────────────────────────────────────

    companion object {
        private const val TAG = "LearnViewModel"

        fun factory(context: Context, chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sdk = MicroCoachingSDK.getInstance()
                    val db = sdk.database
                    val appCtx = context.applicationContext
                    // Preserve the exact value recordEvent historically wrote to
                    // the `sdk_version` column: the SPICE host app's versionName.
                    val appVersion = runCatching {
                        appCtx.packageManager
                            .getPackageInfo(appCtx.packageName, 0)
                            .versionName
                    }.getOrNull() ?: "0.0"
                    return LearnViewModel(
                        context = appCtx,
                        chwId = chwId,
                        moduleRepo = ModuleRepositoryImpl(db.moduleDao(), db.behaviouralGapDao()),
                        telemetry = EventRecorder(
                            dao = db.coachingEventDao(),
                            sessionId = sdk.coachingSessionId,
                            chwId = chwId,
                            appVersionName = appVersion,
                        ),
                    ) as T
                }
            }
    }
}
