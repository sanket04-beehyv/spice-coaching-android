package com.medtroniclabs.microcoaching.ui.learn.modules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for the Quick learn banner + morning refresher bottom sheet.
 *
 * Observes [MicroCoachingSDK.morningModules] and exposes the first wrong
 * quiz question of the top-priority module. When no module is surfaced or
 * no inline questions exist, [quickQuestion] is null and the banner hides.
 *
 * Telemetry: routes single-answer events through [LearnViewModel.recordQuickLearnAnswer].
 * For the full refresher quiz (all wrong questions), call [primeRefresherQuiz] first,
 * then use [filteredQuestionsForRefresher] with [learnViewModel] via SharedQuizInProgressContent.
 */
class QuickLearnViewModel(
    internal val learnViewModel: LearnViewModel,
    internal val morningModulesSource: StateFlow<List<ModuleEntity>>,
    private val morningCardsSource: StateFlow<List<MorningCardCacheEntity>>,
) : ViewModel() {

    private val _answerState = MutableStateFlow<AnswerOutcome?>(null)
    val answerState: StateFlow<AnswerOutcome?> = _answerState.asStateFlow()

    /**
     * Filtered list of questions the CHW has previously answered incorrectly for
     * the top morning module. Populated by [primeRefresherQuiz]; empty until called.
     * Falls back to all questions when the CHW has no wrong answers yet.
     */
    val filteredQuestionsForRefresher = MutableStateFlow<List<QuizQuestion>>(emptyList())

    /**
     * The module entity most recently primed by [primeRefresherQuiz]. Held so the
     * "Try again" CTA ([restartRefresherQuiz]) can re-arm the quiz even after the
     * module has dropped out of [morningModulesSource] (answering the last open
     * question correctly triggers refilterMorningModules mid-sheet).
     */
    private var lastPrimedEntity: ModuleEntity? = null

    /**
     * Count of questions the CHW has answered incorrectly for the top morning module.
     * Populated by [primeRefresherQuiz]. 0 while unpopulated.
     * Drives the question-count label on MorningCard / LearnCard.
     */
    private val _wrongQuestionCount = MutableStateFlow(0)
    val wrongQuestionCount: StateFlow<Int> = _wrongQuestionCount.asStateFlow()

    /**
     * Banner preview question. Combines [morningModulesSource] with the
     * `coaching_event` count flow so the banner re-evaluates after every
     * quiz answer. Walks the morning-modules list in backend priority order
     * (gap → fallback) and picks the first module that still has an
     * unmastered question — the [QuizRefresherCard] only hides when *every*
     * refresher-eligible module has been fully mastered, rather than
     * disappearing the moment the single top module is cleared.
     */
    val quickQuestion: StateFlow<QuickQuestion?> = combine(
        morningModulesSource,
        MicroCoachingSDK.getInstance().database.coachingEventDao().getEventCountFlow(),
        MicroCoachingSDK.getInstance().skippedRefresherFamilyIds,
    ) { modules, _, skipped ->
        // Drop refreshers the CHW swiped away this session so the banner advances
        // to the next queued module (or hides). They remain in the Refresher list.
        modules.filter { it.moduleFamilyId !in skipped }
    }
        .map { modules ->
            var picked: QuickQuestion? = null
            for (entity in modules) {
                val q = firstWrongQuestionOf(entity)
                if (q != null) { picked = q; break }
            }
            picked
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Family ids the CHW has skipped this app-open session (home MorningCard skip
     * or QuizRefresherCard swipe). App-session scoped in the SDK, so it persists
     * across home ⇄ modules ⇄ navigation. Drives the banner/list/home-card
     * "skipped → not re-featured, stays in list" behaviour and the badge count.
     */
    val skippedRefresherIds: StateFlow<Set<String>> =
        MicroCoachingSDK.getInstance().skippedRefresherFamilyIds

    /**
     * Categorised module lists + the shared featured pick, straight from the
     * SDK-owned [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore].
     * The modules screen reads these instead of categorising locally, so its
     * banner/list and the home MorningCard always agree (same store, same pick).
     * [featuredCard] already excludes skipped families and requires a quiz, so it
     * advances on skip and is null only when every refresher is skipped.
     */
    val refresherModules: StateFlow<List<LearnModule>> =
        MicroCoachingSDK.getInstance().coachingModuleStore.refresherModules
    val trainingModules: StateFlow<List<LearnModule>> =
        MicroCoachingSDK.getInstance().coachingModuleStore.trainingModules
    val featuredCard: StateFlow<LearnModule?> =
        MicroCoachingSDK.getInstance().coachingModuleStore.selectedMorningCard

    /**
     * The CHW swiped the [QuizRefresherCard] away. Marks the featured module as
     * skipped (persisted app-session → home "Coaching" tile badge + stays in the
     * RefresherList); the banner then advances to the next non-skipped refresher.
     */
    fun skipQuickRefresher(moduleFamilyId: String) {
        MicroCoachingSDK.getInstance().markRefresherSkipped(moduleFamilyId)
    }

    /**
     * Reconcile the skipped-refresher badge to the currently-active refresher
     * pool (passed by the modules screen) so the count reflects only unique,
     * still-pending skipped refreshers.
     */
    fun reconcileActiveSkipped(activeFamilyIds: List<String>) {
        MicroCoachingSDK.getInstance().retainActiveSkippedRefreshers(activeFamilyIds.toSet())
    }

    /**
     * The exact refresher modules to drive the bottom sheet, resolved straight
     * from the DB by the [familyIds] the modules screen passed (the RefresherList
     * + banner). This is the **shared source of truth**: the sheet's queue is
     * exactly what the CHW saw — no leakage from the broader morning set. Empty
     * for the home-screen flow (which falls back to [morningModulesSource]).
     */
    private val _refresherQueue = MutableStateFlow<List<ModuleEntity>>(emptyList())
    val refresherQueue: StateFlow<List<ModuleEntity>> = _refresherQueue.asStateFlow()

    /** Resolve [familyIds] (latest version of each) into [refresherQueue], preserving order. */
    suspend fun loadRefresherQueue(familyIds: List<String>) {
        if (familyIds.isEmpty()) {
            _refresherQueue.value = emptyList()
            return
        }
        val dao = MicroCoachingSDK.getInstance().database.moduleDao()
        _refresherQueue.value = familyIds.mapNotNull { dao.getByFamilyId(it) }
    }

    /**
     * First question to preview for [moduleFamilyId] on the [QuizRefresherCard] banner.
     * The first still-to-reinforce question (see [reinforceSlice]); if the module is
     * fully mastered (nothing outstanding), falls back to its first quiz question so the
     * banner still renders for the featured module — keeping it in lock-step with the
     * host's [com.medtroniclabs.microcoaching.ui.components.MorningCard] (both surface
     * the same featured refresher). Null only when the module has no quiz at all.
     */
    suspend fun firstReinforceQuestionFor(moduleFamilyId: String): QuizQuestion? {
        val sdk = MicroCoachingSDK.getInstance()
        val entity = sdk.database.moduleDao().getByFamilyId(moduleFamilyId) ?: return null
        return reinforceSlice(entity).firstOrNull()
            ?: parseInlineQuiz(entity.quizJson, sdkLang()).firstOrNull()
    }

    /** Look up the morning-card cache for the top module's source / gap id. */
    private fun morningCardFor(moduleId: String?): MorningCardCacheEntity? =
        if (moduleId == null) null
        else morningCardsSource.value.firstOrNull { it.moduleId == moduleId }

    private fun sdkLang(): String {
        val sdk = MicroCoachingSDK.getInstance()
        return if (sdk.config.language == Language.ENGLISH) "en" else "bn"
    }

    /**
     * Today's refresher question set for [entity]: the **full to-reinforce set** — every
     * quiz question still unanswered-correctly (wrong + never-answered/server-incomplete),
     * wrong-first for a stable, weak-spots-lead order. This is the single source of truth
     * shared with the tile count ([com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore]
     * `reinforceQuestionCount`) and the membership filter (`keepIfHasReinforceQuestions`),
     * so the sheet count matches the tile and answering them all clears the refresher.
     *
     * Empty when the module is fully mastered (it then drops from the morning list
     * upstream) — replaces the old k-subset daily nudge, which never let the CHW finish
     * a module's outstanding questions.
     */
    private suspend fun reinforceSlice(entity: ModuleEntity): List<QuizQuestion> {
        val allQ = parseInlineQuiz(entity.quizJson, sdkLang())
        if (allQ.isEmpty()) return emptyList()
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId ?: return allQ // no CHW context → present the whole quiz
        val allIds = allQ.map { it.id }.toSet()
        val toReinforce = toReinforceQuestionIds(sdk.database, chwId, entity.moduleFamilyId, allIds)
        if (toReinforce.isEmpty()) return emptyList()
        val wrong = sdk.database.coachingEventDao()
            .getLatestWrongQuestionIds(chwId, entity.moduleFamilyId).toSet()
        // Wrong-first, then the remaining outstanding questions; authored order per tier.
        val weak = allQ.filter { it.id in wrong && it.id in toReinforce }
        val rest = allQ.filter { it.id in toReinforce && it.id !in wrong }
        return weak + rest
    }

    fun submitAnswer(answerIndex: Int) {
        val current = quickQuestion.value ?: return
        if (_answerState.value != null) return
        learnViewModel.recordQuickLearnAnswer(
            module = current.module,
            question = current.question,
            answerIndex = answerIndex,
        )
        _answerState.value = AnswerOutcome(
            selectedIndex = answerIndex,
            isCorrect = answerIndex == current.question.correctIndex,
        )
    }

    fun reset() {
        _answerState.value = null
    }

    // ── Listen-aloud (proxies LearnViewModel) ────────────────────────────────

    /**
     * Mirrors [LearnViewModel.autoSpeakEnabled] so the refresher UI shares the
     * same toggle semantics as the main lesson player.
     */
    val autoSpeakEnabled: StateFlow<Boolean> get() = learnViewModel.autoSpeakEnabled

    fun toggleAutoSpeak() {
        learnViewModel.toggleAutoSpeak()
    }

    fun speakAloud(text: String, onDone: () -> Unit = {}) {
        learnViewModel.speakAloud(text, onDone)
    }

    fun stopSpeaking() {
        learnViewModel.stopSpeaking()
    }

    /**
     * Computes the wrong-question count for the **featured** module (the same one
     * the home card / modules banner show — the store's [selectedMorningCard]
     * mapped back to a [ModuleEntity] via `sdk.selectedMorningModule`) and updates
     * [wrongQuestionCount] for the home-screen card label. Targeting the featured
     * pick (not the raw morning-API top) keeps the label in sync with the title
     * shown. Does NOT prime [learnViewModel] — call from the banner composable via
     * LaunchedEffect.
     */
    suspend fun computeWrongQuestionCount() {
        val sdk = MicroCoachingSDK.getInstance()
        val entity = sdk.selectedMorningModule.value ?: run { _wrongQuestionCount.value = 0; return }
        // The banner count must equal what the refresher actually presents — the full
        // to-reinforce set (see [reinforceSlice]) — so the label matches both the tile
        // and the sheet.
        val count = reinforceSlice(entity).size
        _wrongQuestionCount.value = count
        android.util.Log.d(TAG, "computeWrongQuestionCount: module=${entity.moduleFamilyId} toReinforce=$count")
    }

    /**
     * Primes [learnViewModel] with the refresher question set for the top morning
     * module — the **full to-reinforce set** (every still-outstanding question,
     * wrong-first; see [reinforceSlice]), not a subset. Answering them all clears the
     * module so it stops re-surfacing. Also updates [wrongQuestionCount] (= the set
     * size) for the home-screen card label, keeping it in sync with the tile + sheet.
     */
    suspend fun primeRefresherQuiz(targetModuleFamilyId: String? = null) {
        // Resolve against the DB-loaded refresher queue (the exact list/banner the
        // CHW saw) and fall back to the morning set only for the home-screen flow.
        val pool = refresherQueue.value.ifEmpty { morningModulesSource.value }
        val entity = if (targetModuleFamilyId != null) {
            pool.firstOrNull { it.moduleFamilyId == targetModuleFamilyId }
                ?: pool.firstOrNull()
        } else {
            pool.firstOrNull()
        } ?: return
        lastPrimedEntity = entity

        // Full to-reinforce set. If the module is fully mastered (nothing outstanding)
        // — e.g. a skipped card the CHW already aced, still accessible from the list —
        // fall back to the WHOLE quiz so the tap opens a re-takeable sheet that matches
        // the tile's question count, instead of a blank screen.
        val selected = reinforceSlice(entity).ifEmpty { parseInlineQuiz(entity.quizJson, sdkLang()) }
        if (selected.isEmpty()) return // genuinely no quiz
        android.util.Log.i(TAG,
            "primeRefresherQuiz: module=${entity.moduleFamilyId} selected=${selected.size}")

        _wrongQuestionCount.value = selected.size
        filteredQuestionsForRefresher.value = selected

        val card = morningCardFor(entity.moduleId)
        val module = entity.toMinimalLearnModule(
            behaviouralGapId = card?.behaviouralGapId,
            source = card?.source,
        ).copy(inlineQuestions = selected)
        learnViewModel.selectModuleForQuiz(module)
    }

    /**
     * Re-arms the quiz with the SAME question set the CHW just attempted — backing
     * the "Try again" CTA on the refresher completion screen. Unlike
     * [primeRefresherQuiz] it does NOT re-filter against the to-reinforce set
     * (which shrinks as answers land), so it's a faithful redo of the questions
     * just shown. Reads [lastPrimedEntity] rather than [morningModulesSource]
     * because the module may have refiltered out of that flow mid-sheet.
     */
    fun restartRefresherQuiz() {
        val entity = lastPrimedEntity ?: return
        val questions = filteredQuestionsForRefresher.value
        if (questions.isEmpty()) return
        val card = morningCardFor(entity.moduleId)
        val module = entity.toMinimalLearnModule(
            behaviouralGapId = card?.behaviouralGapId,
            source = card?.source,
        ).copy(inlineQuestions = questions)
        learnViewModel.selectModuleForQuiz(module)
    }

    /**
     * Selects the first wrong question (or first question if no wrong history)
     * for the [QuizRefresherCard] banner preview.
     */
    private suspend fun firstWrongQuestionOf(entity: ModuleEntity): QuickQuestion? {
        // Preview = first question of the refresher's to-reinforce set (weak-first), so
        // the banner matches the quiz it launches. If the module is fully mastered, fall
        // back to its first quiz question so the featured module still previews (in
        // lock-step with the host MorningCard). Null only when the module ships no quiz.
        val slice = reinforceSlice(entity)
        val question = slice.firstOrNull()
            ?: parseInlineQuiz(entity.quizJson, sdkLang()).firstOrNull()
            ?: run {
                android.util.Log.d(TAG, "morning module '${entity.titleBn}' has no inline questions — banner hidden.")
                return null
            }
        android.util.Log.i(TAG,
            "QuickLearn selected: module='${entity.titleBn}' " +
            "toReinforce=${slice.size} questionId=${question.id} text='${question.questionText.take(60)}'")
        val card = morningCardFor(entity.moduleId)
        return QuickQuestion(
            module = entity.toMinimalLearnModule(
                behaviouralGapId = card?.behaviouralGapId,
                source = card?.source,
            ),
            question = question,
        )
    }

    companion object {
        private const val TAG = "QuickLearnVM"

        fun factory(context: Context, chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sdk = MicroCoachingSDK.getInstance()
                    val learnVm = LearnViewModel.factory(context, chwId).create(LearnViewModel::class.java)
                    return QuickLearnViewModel(
                        learnViewModel = learnVm,
                        morningModulesSource = sdk.morningModules,
                        morningCardsSource = sdk.morningCardsItems,
                    ) as T
                }
            }
    }
}

data class QuickQuestion(
    val module: LearnModule,
    val question: QuizQuestion,
)

data class AnswerOutcome(
    val selectedIndex: Int,
    val isCorrect: Boolean,
)

private fun ModuleEntity.toMinimalLearnModule(
    behaviouralGapId: String? = null,
    source: String? = null,
): LearnModule = LearnModule(
    moduleFamilyId = moduleFamilyId,
    title = translatedText(bn = titleBn, en = titleEn),
    body = translatedText(bn = descriptionBn ?: "", en = descriptionEn),
    clinicalDomain = domain,
    moduleId = moduleId,
    moduleVersion = version,
    moduleType = moduleType,
    behaviouralGapId = behaviouralGapId,
    source = source,
    cardsJson = cardsJson,
)
