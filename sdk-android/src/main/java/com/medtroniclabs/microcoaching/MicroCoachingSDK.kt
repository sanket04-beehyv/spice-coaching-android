package com.medtroniclabs.microcoaching

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.medtroniclabs.microcoaching.data.asset.AssetCache
import com.medtroniclabs.microcoaching.data.db.MICRO_COACHING_ROOM_VERSION
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.sortedForDisplay
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.sync.SyncPrefs
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.ActionGapLink
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.GapStateConfig
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.MorningSourcesConfig
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator
import com.medtroniclabs.microcoaching.domain.morning.MorningModuleResolver
import com.medtroniclabs.microcoaching.domain.triggers.TriggerEvaluator
import com.medtroniclabs.microcoaching.domain.triggers.buildModuleCompletion
import com.medtroniclabs.microcoaching.ai.model.ModelCatalog
import com.medtroniclabs.microcoaching.ai.model.ModelProvider
import com.medtroniclabs.microcoaching.ai.model.ModelVariant
import com.medtroniclabs.microcoaching.data.repository.ChatRepositoryImpl
import com.medtroniclabs.microcoaching.data.repository.CoachingEventRepositoryImpl
import com.medtroniclabs.microcoaching.ai.model.ModelManager
import com.medtroniclabs.microcoaching.domain.context.CHWWorkContext
import com.medtroniclabs.microcoaching.domain.context.PatientSnapshot
import com.medtroniclabs.microcoaching.domain.context.TodaysVisit
import com.medtroniclabs.microcoaching.domain.gaps.BpAboveThresholdEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.GapRuleDispatcher
import com.medtroniclabs.microcoaching.domain.gaps.GlucoseAboveThresholdEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.MissingDangerSignsEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.WrongFacilityTierEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.evidence.EvidenceBuilder
import com.medtroniclabs.microcoaching.domain.lifecycle.VisitCompletedHandler
import com.medtroniclabs.microcoaching.ai.translation.OnDeviceTranslator
import com.medtroniclabs.microcoaching.domain.decision.CoachingMode
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.network.NetworkModule
import com.medtroniclabs.microcoaching.sdk.CoachingDataRepository
import com.medtroniclabs.microcoaching.sdk.MicroCoachingDataCallback
import com.medtroniclabs.microcoaching.sdk.SdkDataExport
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.domain.telemetry.PatientIdHasher
import com.medtroniclabs.microcoaching.domain.telemetry.TelemetryManager
import com.medtroniclabs.microcoaching.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import com.medtroniclabs.microcoaching.domain.validation.OutputValidator
import  com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager
import com.medtroniclabs.microcoaching.ai.voice.VoiceInputController
import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.ai.retrieval.RetrievalHintOverlay
import com.medtroniclabs.microcoaching.ai.model.ModelState
import com.medtroniclabs.microcoaching.ai.translation.TranslationModelState
import com.medtroniclabs.microcoaching.ai.voice.OfflineSttEngine
import com.medtroniclabs.microcoaching.ai.voice.AndroidSpeechRecognizerEngine
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController
import com.medtroniclabs.microcoaching.domain.system.DeviceCapability
import com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore
import com.medtroniclabs.microcoaching.domain.config.LearningPoints



/**
 * Singleton entry point for the MicroCoaching SDK.
 *
 * Initialize once in your Application.onCreate():
 * ```kotlin
 * MicroCoachingSDK.Builder(this)
 *     .language(Language.BANGLA)
 *     .backendUrl(BuildConfig.COACHING_BACKEND_URL)
 *     .authToken(SecuredPreference.getToken())
 *     .otelEndpoint(BuildConfig.OTEL_ENDPOINT)
 *     .otelHeaders(mapOf("signoz-access-token" to BuildConfig.SIGNOZ_TOKEN))
 *     .enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)
 *     .enableChat(true)
 *     .build()
 * ```
 *
 * Then retrieve the singleton from anywhere:
 * ```kotlin
 * val sdk = MicroCoachingSDK.getInstance()
 * ```
 *
 * Or inject [CoachingDataRepository] via Hilt:
 * ```kotlin
 * @Provides @Singleton
 * fun provideCoachingDataRepository(): CoachingDataRepository =
 *     MicroCoachingSDK.getInstance().dataRepository
 * ```
 */
/** Startup health snapshot returned by [MicroCoachingSDK.checkHealth]. */
data class SdkHealthReport(
    val isModelPresent: Boolean,
    val modelFileSizeBytes: Long,
    val modelStateName: String,
    val morningCardCount: Int,
)

class MicroCoachingSDK private constructor(val config: MicroCoachingConfig) {

    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One coaching session id per app process. Every learn-flow event
     * ([EventRecorder.recordCoachingEvent] writes through here via the
     * `LearnViewModel` recorder) is tagged with this id so the backend's
     * session inspector groups a CHW's module + quiz interactions together.
     *
     * Process-static (generated once with the SDK singleton) — identical to the
     * former `LearnViewModel.sessionId` companion. It is intentionally NOT reset
     * on logout / CHW switch; per-login sessions would be a separate change.
     */
    val coachingSessionId: String = UUID.randomUUID().toString()

    /**
     * `true` when this device falls below the 3 GB-RAM threshold and therefore
     * cannot host the on-device Gemma model. The chat runs in retrieval-only
     * mode on these devices — BM25 lookup over the indexed module corpus,
     * pre-authored Bangla card body served verbatim, no LLM round-trip.
     *
     * Probed once at construction via
     * [com.medtroniclabs.microcoaching.domain.system.DeviceCapability]. Hosts
     * can override the auto-detection via [MicroCoachingConfig.forceLowEndMode]
     * — useful for QA on capable hardware.
     */
    val isLowEndDevice: Boolean =
        config.forceLowEndMode
            ?: DeviceCapability
                .isLowEndDevice(config.context)

    /**
     * Active language for SDK UI and LLM prompts.
     *
     * Set once via [Builder.language] at init time. Can be changed at runtime via
     * [setLanguage] — the new value takes effect the next time any SDK screen is
     * opened (Compose roots re-wrap their locale context on entry).
     */
    @Volatile
    var language: Language = config.language
        private set

    /**
     * Change the coaching language at runtime without rebuilding the SDK.
     *
     * The new language takes effect immediately for LLM prompts and the next time
     * any SDK-owned screen (CoachingFlowActivity, CoachingChatFragment,
     * CoachingCardFragment) is opened or restarted — they each re-wrap their locale
     * context from this value on entry.
     *
     * Example — switch to English after login:
     * ```kotlin
     * MicroCoachingSDK.getInstance().setLanguage(Language.ENGLISH)
     * ```
     */
    fun setLanguage(language: Language) {
        this.language = language
        if (language == Language.BANGLA) {
            sdkScope.launch { translator.ensureModelReady() }
        }
    }

    private val _morningModules = MutableStateFlow<List<ModuleEntity>>(emptyList())
    /** Modules currently published. Populated by [onHomeScreenShown]. */
    val morningModules: StateFlow<List<ModuleEntity>> = _morningModules.asStateFlow()

    private val _morningRefresherDismissed = MutableStateFlow(false)
    /**
     * True when the CHW has completed or skipped the morning refresher in the current
     * home-screen session. Resets to false on each [onHomeScreenShown] call.
     * Observed by the SPICE home screen banner to hide MorningCard / LearnCard.
     */
    val morningRefresherDismissed: StateFlow<Boolean> = _morningRefresherDismissed.asStateFlow()

    /** Hide the morning banner for this session (called from RefresherContent on Done/Skip). */
    fun dismissMorningRefresher() {
        _morningRefresherDismissed.value = true
    }

    private val _morningCardsItems = MutableStateFlow<List<MorningCardCacheEntity>>(emptyList())
    /**
     * Last-known backend-prioritised morning-card list. Populated after a successful
     * `GET /morning/cards` call in [onHomeScreenShown] / [onMorningOpen] / [InboundSyncWorker].
     *
     * Consumed by [QuickLearnViewModel] and [LearnViewModel] to attach [MorningCardCacheEntity.behaviouralGapId]
     * and [MorningCardCacheEntity.source] to each surfaced module.
     */
    val morningCardsItems: StateFlow<List<MorningCardCacheEntity>> = _morningCardsItems.asStateFlow()

    // ── Skipped-refresher set (persisted per CHW) ─────────────────────────────
    // Family ids of refreshers the CHW skipped — via the MorningCard Skip button /
    // swipe, or by swiping away the QuizRefresherCard. Persisted to chwPrefs keyed by
    // CHW id, so a skipped refresher **stays skipped** across app restarts / re-login:
    // it's kept OUT of the morning card stack/banner but **remains in the Refresher
    // list** (still accessible to take). A skip is cleared only when the CHW actually
    // completes that refresher (see RefresherContent), not by a later wrong answer;
    // [retainActiveSkippedRefreshers] also prunes ids no longer in the active pool.
    private val _skippedRefresherFamilyIds = MutableStateFlow<Set<String>>(emptySet())

    /** The skipped-refresher family-id set (used by [QuickLearnViewModel] to hide skipped banners). */
    internal val skippedRefresherFamilyIds: StateFlow<Set<String>> = _skippedRefresherFamilyIds.asStateFlow()

    /**
     * Number of refreshers the CHW skipped this home session. The SPICE host
     * observes this to render a count badge on the "Coaching" home-grid tile.
     */
    val skippedRefresherCount: StateFlow<Int> =
        _skippedRefresherFamilyIds
            .map { it.size }
            .stateIn(sdkScope, SharingStarted.Eagerly, 0)

    /** Record that the CHW skipped a refresher (Skip button or swipe-away). Persisted per CHW. */
    fun markRefresherSkipped(moduleFamilyId: String) {
        if (moduleFamilyId.isBlank()) return
        _skippedRefresherFamilyIds.update { it + moduleFamilyId }
        persistSkippedRefreshers()
    }

    /** Drop a refresher from the skipped set once the CHW has completed it. */
    fun clearRefresherSkipped(moduleFamilyId: String) {
        if (moduleFamilyId.isBlank()) return
        if (moduleFamilyId !in _skippedRefresherFamilyIds.value) return
        _skippedRefresherFamilyIds.update { it - moduleFamilyId }
        persistSkippedRefreshers()
    }

    /**
     * Keep only the skipped ids that are still **active refreshers**. Called with
     * the current refresher pool (from the modules screen) so the badge counts
     * unique, still-pending skipped refreshers — completed/mastered ones that
     * left the pool stop counting.
     */
    fun retainActiveSkippedRefreshers(activeFamilyIds: Set<String>) {
        val before = _skippedRefresherFamilyIds.value
        val after = before intersect activeFamilyIds
        if (after != before) {
            _skippedRefresherFamilyIds.value = after
            persistSkippedRefreshers()
        }
    }

    private fun skippedRefreshersKey(chwId: String) = "skipped_refreshers_$chwId"

    /** Persist the skipped set for the current CHW (survives restart; scoped per user). */
    private fun persistSkippedRefreshers() {
        val chwId = currentCHWId ?: return
        chwPrefs.edit()
            .putString(skippedRefreshersKey(chwId), Json.encodeToString(_skippedRefresherFamilyIds.value))
            .apply()
    }

    /** Load [chwId]'s persisted skipped set into the in-memory flow (empty if none/undecodable). */
    private fun loadSkippedRefreshers(chwId: String) {
        val json = chwPrefs.getString(skippedRefreshersKey(chwId), null)
        _skippedRefresherFamilyIds.value = if (json == null) {
            emptySet()
        } else {
            runCatching { Json.decodeFromString<Set<String>>(json) }.getOrDefault(emptySet())
        }
    }

    private val _latestModule = MutableStateFlow<ModuleEntity?>(null)
    /**
     * The most recent module surfaced by a workflow- or gap-trigger evaluation.
     * Host surfaces (e.g. SPICE's assessment-summary banner) collect this to
     * decide when to show the coaching card.
     */
    val latestModule: StateFlow<ModuleEntity?> = _latestModule.asStateFlow()

    private val triggerEvaluator: TriggerEvaluator by lazy {
        TriggerEvaluator(
            triggerDao = database.triggerDefinitionDao(),
            bindingDao = database.moduleTriggerBindingDao(),
            moduleDao = database.moduleDao(),
            gapProfileDao = database.chwGapProfileDao(),
            behaviouralGapDao = database.behaviouralGapDao(),
            configDao = database.configThresholdDao(),
            config = config,
        )
    }

    /**
     * `moduleFamilyId → real action-gap id` resolved on-device by
     * [onDeviceMorningGenerator] (e.g. a wrong-referral module linked via its
     * `behavioural_gap_ids` to `referral_location_upazila`). The backend's morning
     * card for such a module may carry only its `module_primary_gap_*` placeholder
     * (no detection rule), so [coachingModuleStore] overlays this to recognise the
     * true action gap and keep the refresher surfaced even when the module is completed.
     * Empty until the generator runs.
     */
    private val _onDeviceActionGapLinks = MutableStateFlow<Map<String, ActionGapLink>>(emptyMap())

    /**
     * On-device replica of `GET /morning/cards` — computes gap state from cached
     * events and selects the refresher set the backend would, writing it into
     * `morning_card_cache`. Used by [morningModuleResolver] when the live morning
     * endpoint is unavailable. See `docs/offline_refresher_generation_plan.md`.
     */
    private val onDeviceMorningGenerator: OnDeviceMorningGenerator by lazy {
        OnDeviceMorningGenerator(
            database = database,
            config = GapStateConfig(
                passThreshold = config.quizPassThreshold / 100f,
                escalationFailureCount = config.escalationFailureCount,
                escalationWindowDays = config.escalationWindowDays,
                occurrenceWindowDays = config.triggerWindowDays,
            ),
            sources = MorningSourcesConfig(
                quiz = config.refresherTuning.enableQuizRefreshers,
                gap = config.refresherTuning.enableGapRefreshers,
                referral = config.refresherTuning.enableReferralRefreshers,
                visit = config.refresherTuning.enableVisitRefreshers,
            ),
            publishActionGapLinks = { _onDeviceActionGapLinks.value = it },
            loadTodaysVisits = { loadTodaysVisits() },
        )
    }

    /** 4-tier morning-module resolution, extracted from this class (F9). */
    private val morningModuleResolver: MorningModuleResolver by lazy {
        MorningModuleResolver(
            database = database,
            config = config,
            apiService = apiService,
            onDeviceMorningGenerator = onDeviceMorningGenerator,
            langCode = { if (language == Language.ENGLISH) "en" else "bn" },
            morningModules = _morningModules,
            morningCardsItems = _morningCardsItems,
        )
    }

    /**
     * Single source of truth for the categorised module lists (refreshers /
     * training) and the **one** featured refresher pick shared by the home
     * [com.medtroniclabs.microcoaching.ui.components.MorningCard] and the modules
     * screen. Lives here (not in a ViewModel) because those two surfaces sit in
     * different Activities — see [CoachingModuleStore].
     *
     * Lazy + accessed only post-construction (from UI / [onHomeScreenShown]), so
     * its eager Room collectors never race the lazy [database] delegate during
     * SDK construction (the init-order hazard documented above the `init` block).
     */
    internal val coachingModuleStore: CoachingModuleStore by lazy {
        CoachingModuleStore(
            database = database,
            scope = sdkScope,
            chwIdProvider = { currentCHWId },
            langProvider = { if (language == Language.ENGLISH) "en" else "bn" },
            skippedIds = _skippedRefresherFamilyIds.asStateFlow(),
            onDeviceActionGapLinks = _onDeviceActionGapLinks.asStateFlow(),
        )
    }

    /**
     * Resolved learning-points weights (per-correct-answer multiplier, module-
     * completion total, etc.), derived live from the cached `config_threshold`
     * rows synced via `GET /sync/config`. Falls back to the documented v3
     * defaults per key when a row is missing or its value isn't an integer.
     *
     * Shared across surfaces: the in-quiz "+N XP" burst reads
     * [LearningPoints.quizScoreMultiplier]; the result screen total comes from
     * [LearningPoints.moduleQuizXp]. Lazy so its eager collector starts only
     * post-construction (avoiding the lazy-`database` init-order hazard).
     */
    val learningPoints: StateFlow<LearningPoints> by lazy {
        database.configThresholdDao().getGlobalFlow()
            .map { LearningPoints.from(it) }
            .stateIn(sdkScope, SharingStarted.Eagerly, LearningPoints())
    }

    /**
     * The featured refresher resolved by [coachingModuleStore], mapped back to a
     * [ModuleEntity] so the SPICE home card keeps reading
     * `cardCount`/`questionCount`/`estimatedMinutes`/`title*` unchanged. Both the
     * home card and the modules banner derive from the same store pick, so they
     * always agree; the value advances when a refresher is skipped and goes null
     * only when every refresher has been skipped.
     */
    val selectedMorningModule: StateFlow<ModuleEntity?> by lazy {
        coachingModuleStore.selectedMorningCard
            .map { lm -> lm?.moduleFamilyId?.let { database.moduleDao().getByFamilyId(it) } }
            .stateIn(sdkScope, SharingStarted.Eagerly, null)
    }

    /**
     * GAP_DETECTION_SDK.md §4 — per-`rule_type` dispatcher loaded with the four
     * pilot evaluators. Today only [WrongFacilityTierEvaluator] is wired
     * end-to-end; the other three are file skeletons returning null until the
     * remaining TEAM-CONFIRM questions (C-SDK-1, C-SDK-2) are resolved.
     *
     * Gated by [MicroCoachingConfig.enableGapDetection] — when false the
     * dispatcher is skipped and [onAssessmentSubmitted] falls through to its
     * existing single-event emission (preserves today's behaviour exactly).
     */
    private val gapRuleDispatcher: GapRuleDispatcher by lazy {
        GapRuleDispatcher(
            gapDao = database.behaviouralGapDao(),
            evaluators = mapOf(
                WrongFacilityTierEvaluator().let { it.ruleType to it },
                BpAboveThresholdEvaluator().let { it.ruleType to it },
                GlucoseAboveThresholdEvaluator().let { it.ruleType to it },
                MissingDangerSignsEvaluator().let { it.ruleType to it },
            ),
        )
    }

    /** TP-7 — visit-close handler. See [onVisitCompleted]. */
    private val visitCompletedHandler: VisitCompletedHandler by lazy {
        VisitCompletedHandler(coachingEventDao = database.coachingEventDao())
    }

    /** Lazy-initialized OTel telemetry manager. Inactive if [MicroCoachingConfig.enableTelemetry] is false. */
    val telemetry: TelemetryManager by lazy { TelemetryManager(config) }

    /**
     * Builds an [EventRecorder] scoped to the SDK's SPICE-facing API hooks.
     * Used by [onAssessmentSubmitted] and [onRiskFlagObserved] to write
     * UC-2 / UC-3 events.
     *
     * Constructed per-call (not lazy) because [EventRecorder] captures
     * `chwId` at construction time, and [currentCHWId] may not yet be set
     * when the SDK is first initialised. Per-VM recorders inside
     * `LearnViewModel` / `ChatViewModel` stay as they are — those have a
     * fixed chwId for the VM's lifetime.
     *
     * Session id is fixed to `"sdk-hook"` so all SDK-API-originated rows
     * group together in the backend's session inspector.
     */
    private fun newSdkHookRecorder(chwId: String): EventRecorder =
        EventRecorder(
            dao = database.coachingEventDao(),
            sessionId = "sdk-hook",
            chwId = chwId,
        )

    /** Sync orchestrator — schedules and triggers outbound + inbound WorkManager jobs. */
    val syncCoordinator: SyncCoordinator by lazy { SyncCoordinator(config.context) }

    private val syncPrefs: SyncPrefs by lazy { SyncPrefs(config.context) }

    // The next two lazy delegates MUST be declared before the `init {}` block.
    // The init launches coroutines that reference `database` (and `translator`
    // when language=BANGLA) — if either lazy property's declaration ran AFTER
    // the init block, the IO dispatcher could resume the launched body before
    // the main thread reached the declaration, dereferencing a null Lazy
    // delegate and crashing with the NPE seen in recent crashes.

    /** SDK-owned Room database — separate from SPICE's NCDMergerDatabase. */
    internal val database: MicroCoachingDatabase by lazy {
        MicroCoachingDatabase.getInstance(config.context)
    }

    /**
     * Offline cache for remote assets (module thumbnails, lesson-card images;
     * video / PDF later). Resolves a stable asset key to a local file,
     * downloading + persisting on first online view so the asset renders offline
     * thereafter. See [com.medtroniclabs.microcoaching.data.asset.AssetCache].
     */
    val assetCache: AssetCache by lazy {
        AssetCache(config.context, database.cachedAssetDao(), sdkScope)
    }

    /** On-device EN↔BN translator. Active whenever [language] is [Language.BANGLA]. */
    val translator: OnDeviceTranslator by lazy { OnDeviceTranslator() }

    private val _chatKnowledgeIndex = MutableStateFlow(
        ModuleKnowledgeIndex.empty()
    )

    /**
     * In-memory BM25 index over the on-device module corpus, used by [ChatViewModel]
     * to ground chat answers in cards / quiz items (B1–B2 of docs/v3/chat_plan.md).
     *
     * Rebuilt on app start and after every inbound module sync — observed via
     * `ModuleDao.getAllActive()`. Build cost is ≪ 100 ms for ≤ 200 chunks so we
     * accept the per-emission rebuild rather than gating on a watermark.
     */
    val chatKnowledgeIndex: StateFlow<ModuleKnowledgeIndex> =
        _chatKnowledgeIndex.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkLost = false

    private val _networkAvailable = MutableStateFlow(true)

    /**
     * Reactive view of the SDK-observed network state, driven by the same
     * `ConnectivityManager.NetworkCallback` registered in [registerConnectivityCallback].
     * Emits `true` when at least one network has `NET_CAPABILITY_INTERNET`,
     * `false` when none do. Initial value reflects the snapshot taken when
     * the SDK was constructed.
     *
     * UI surfaces (e.g. the chat source-attribution chip row) collect this to
     * grey out chips immediately when connectivity drops, rather than polling
     * [isNetworkAvailable] on every recomposition.
     */
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()

    // ── Lazy SDK services referenced from `init` ──────────────────────────────
    // Kotlin initialises `by lazy` property delegates in lexical order alongside
    // init blocks. Coroutines launched in `init` that touch these delegates must
    // see a non-null backing field, so the declarations live ABOVE the init block.
    // (Moving them below caused MicroCoachingSDK.kt#L259 NPE on fresh-install
    // logins where Room schema setup delayed construction past the first dispatch.)

    /** Lazy-initialized model lifecycle manager (download, verify, state). */
    val modelManager: ModelManager by lazy { ModelManager(config) }

    /**
     * The on-device model this SDK is configured to download and load. The single
     * source of truth for the model's display name, parameter count, expected
     * download size ([ModelVariant.sizeInBytes]), runtime, and RAM class. Hosts use
     * it to render an accurate, dynamic size label (e.g. via
     * `Formatter.formatShortFileSize(context, sdk.selectedModelVariant().sizeInBytes)`)
     * instead of a hard-coded "~600 MB".
     */
    fun selectedModelVariant(): ModelVariant = config.selectedModelVariant()

    /**
     * The catalog of on-device models the host may offer in a picker. By default
     * only runnable variants are returned (the bundled MediaPipe engine); pass
     * [includeNonRunnable] = true to also list variants whose runtime is not yet
     * bundled (they can be displayed but won't load). Selection is applied at
     * init time via [Builder.selectedModel].
     */
    fun availableModels(includeNonRunnable: Boolean = false): List<ModelVariant> =
        if (includeNonRunnable) ModelCatalog.ALLOWLIST
        else ModelCatalog.ALLOWLIST.filter { ModelCatalog.isRunnable(it) }

    /**
     * Manager for the optional offline Bengali STT model (sherpa-onnx).
     *
     * Observe [SttModelManager.state]
     * from the chat UI to render the download banner. The SDK's default voice
     * controller (a [com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController]
     * when [Builder.enableVoice] is `true`) triggers a download automatically
     * when the user dictates Bengali while offline and the model isn't yet on
     * disk.
     */
    val sttModelManager: SttModelManager by lazy {
        SttModelManager(config)
    }

    init {
        if (config.backendUrl.isNotBlank()) {
            // Detect a destructive Room migration since last boot and clear inbound
            // sync watermarks so the next pullGaps/pullModules call returns a full
            // snapshot — otherwise the SharedPreferences watermark outlives the wiped
            // Room tables and progress never rehydrates from the backend.
            if (syncPrefs.lastKnownRoomVersion < MICRO_COACHING_ROOM_VERSION) {
                if (syncPrefs.lastKnownRoomVersion > 0) {
                    Log.i(
                        TAG,
                        "Room schema bumped ${syncPrefs.lastKnownRoomVersion} → $MICRO_COACHING_ROOM_VERSION " +
                            "— clearing inbound sync watermarks to force a full re-sync.",
                    )
                    syncPrefs.resetWatermarksOnly()
                }
                syncPrefs.lastKnownRoomVersion = MICRO_COACHING_ROOM_VERSION
            }
            syncCoordinator.schedulePeriodic()
            registerConnectivityCallback()
        }
        if (config.language == Language.BANGLA) {
            sdkScope.launch { translator.ensureModelReady() }
            // Proactive Bengali STT model download.
            //
            // On CAPABLE devices we chain off the AI model's Ready state: once
            // the Gemma file lands and chat is usable, kick the voice model
            // download in the background. Idempotent — sttModelManager.trigger
            // BengaliDownload() no-ops when the model is present or in flight.
            //
            // On LOW-END devices the AI model never reaches Ready (it's never
            // downloaded), so the chain would dead-end. Trigger the voice
            // download directly instead — same call, same idempotency, just
            // decoupled from a Ready emission that will never come.
            if (isLowEndDevice) {
                sdkScope.launch { ensureBengaliSttDownloadKickedOff() }
            } else {
                sdkScope.launch {
                    modelManager.state.collect { aiState ->
                        if (aiState is ModelState.Ready) {
                            ensureBengaliSttDownloadKickedOff()
                        }
                    }
                }
            }
        }

        // Keep the chat knowledge index in sync with the on-device module corpus.
        // Cheap to rebuild (< 100 ms for ≤ 200 chunks); refreshes after each
        // pullModules emits a new list.
        sdkScope.launch {
            database.moduleDao().getAllActive().collect { modules ->
                val indexedModules = RetrievalHintOverlay.apply(
                    modules = modules.sortedForDisplay(),
                    assets = config.context.assets,
                    enabled = config.enableRetrievalHintFixtureOverlay,
                )
                _chatKnowledgeIndex.value = ModuleKnowledgeIndex.build(
                    indexedModules,
                    retiredFamilyIds = syncPrefs.retiredFamilyIds,
                )
            }
        }

        // Re-apply the morning-cards reinforce filter on every coaching_event
        // insert so the SPICE home banner walks to the next unmastered module
        // the moment the CHW finishes the last wrong question of the current
        // one. `drop(1)` skips the initial cold emission since the publish
        // path already ran via `onHomeScreenShown` → `refreshMorningModules`.
        sdkScope.launch {
            database.coachingEventDao().getEventCountFlow().drop(1).collect {
                val chwId = currentCHWId ?: return@collect
                refilterMorningModules(chwId)
            }
        }
    }

    /**
     * The CHW identity last supplied by [onHomeScreenShown].
     * Read by [CoachingFlowActivity] when it is launched without an explicit chwId extra.
     */
    @Volatile
    var currentCHWId: String? = null
        internal set

    /** Retrofit API service for the Knowledge Layer backend. */
    val apiService: CoachingApiService by lazy { NetworkModule.createApiService(config) }

    /**
     * Observable lifecycle of the on-device translation pack.
     *
     * UI surfaces (chat, coaching card) render a status chip when this is in
     * [com.medtroniclabs.microcoaching.ai.translation.TranslationModelState.Downloading]
     * or [com.medtroniclabs.microcoaching.ai.translation.TranslationModelState.Failed]
     * — see `TranslationModelStateChip` in the UI layer. Hosts can also observe
     * this directly to show a top-level banner if desired.
     */
    val translationModelState: StateFlow<TranslationModelState>
        get() = translator.state

    /**
     * Speech-to-text controller for the chat mic button.
     *
     * When [Builder.enableVoice] is `true`, this is auto-populated with
     * [com.medtroniclabs.microcoaching.ai.voice.AndroidSpeechRecognizerEngine] —
     * a wrapper around the platform `SpeechRecognizer` that handles both English
     * and Bengali (on-device when a pack is installed, cloud Google otherwise).
     *
     * Hosts can override with a custom impl at build time via
     * [Builder.voiceInputController], or replace it later by reassigning this
     * field directly (e.g. for the offline-Bengali sherpa fallback documented
     * in `docs/v3/chat/sherpa.md`).
     */
    @Volatile
    var voiceInputController: VoiceInputController? = null

    /**
     * Pull-pattern data access interface.
     * SPICE can inject this via Hilt to query coaching data on demand.
     *
     * Alternative push pattern: register [MicroCoachingDataCallback] in [Builder.dataCallback].
     */
    val dataRepository: CoachingDataRepository by lazy {
        val chatRepo = ChatRepositoryImpl(database.chatMessageDao())
        val eventRepo = CoachingEventRepositoryImpl(database.coachingEventDao(), config)
        object : CoachingDataRepository {
            override suspend fun getChatHistory(sessionId: String) =
                chatRepo.getHistory(sessionId)
            override suspend fun getAllSessionIds() =
                chatRepo.getAllSessionIds()
            override suspend fun getPendingCoachingEvents() =
                eventRepo.getPendingCoachingEvents()
            override suspend fun markEventsSynced(eventIds: List<String>) =
                eventRepo.markEventsSynced(eventIds)
            override suspend fun exportAllData() = SdkDataExport(
                chatMessages = chatRepo.getAllMessages(),
                coachingEvents = eventRepo.getPendingCoachingEvents(),
            )
        }
    }

    // ── SPICE Workflow Event Hooks (Phase 1) ──────────────────────────────────
    // Stubs — implementations arrive in Phase 1 when SPICE hook signatures are confirmed.

    /**
     * Call when the CHW opens the SPICE home screen. Stores [chwId], immediately
     * surfaces modules from the local cache (fast path), then kicks off a
     * background morning-cards fetch and refreshes the list when it lands.
     *
     * **3-tier resolution:**
     * 1. If `morning_card_cache` has rows → join with `module_cache` in rank order.
     * 2. Else if `TriggerEvaluator` returns rows → use those (local gap/trigger logic).
     * 3. Else → empty list.
     */
    fun onHomeScreenShown(chwId: String) {
        // Detect a CHW switch (different user signed in on this device) before we
        // overwrite currentCHWId — used to isolate the previous user's morning cards.
        val switchedUser = currentCHWId != null && currentCHWId != chwId
        currentCHWId = chwId
        _morningRefresherDismissed.value = false  // reset banner visibility each time the screen opens
        // Load this CHW's persisted skipped-refresher set (survives restart / re-login).
        // Skips are per CHW, so this also swaps the set on a user switch.
        loadSkippedRefreshers(chwId)
        // currentCHWId is now set — kick the store so its first real compute
        // lands immediately from the local cache (it emits an empty list until a
        // chwId is known), before the network refresh returns.
        coachingModuleStore.invalidate()
        sdkScope.launch {
            // On a user switch, drop the previous CHW's cached morning cards so a
            // different user never sees them before the re-sync below repopulates.
            // (morning_card_cache has no chw_id column; clearing is the small,
            // scalable isolation — the cards are fully re-derivable.)
            if (switchedUser) {
                database.morningCardCacheDao().clearAll()
            }
            morningModuleResolver.refresh(chwId)
            // refresh() rewrote morning_card_cache (backend /morning/cards pull +
            // on-device generate). Recompute the store AGAIN so the refresher list
            // and featured pick reflect the freshly-resolved cards. The early
            // invalidate above ran against the pre-refresh cache, and the Room-flow
            // observation alone has proven unreliable on this path (a referral
            // action-gap card landed in the cache yet the pool stayed stale). This
            // mirrors the refresh()→invalidate() order already used by onMorningOpen.
            coachingModuleStore.invalidate()
        }
    }

    /** Returns a lightweight health snapshot — useful for startup logging. */
    /**
     * Returns the highest-priority morning module to surface on the home screen
     * and modules screen. Priority: gap-sourced items (already ranked first by
     * the backend in [morningCardsItems]) → first item in [morningModules].
     *
     * Both [MorningCard] (home screen) and [QuizRefresherCard] (modules screen)
     * derive from this same store pick so they always show the same module.
     *
     * Back-compat synchronous accessor — now backed by [selectedMorningModule]
     * (the unified [coachingModuleStore] pick). Prefer collecting the
     * [selectedMorningModule] flow so the card advances live on skip / progress.
     */
    fun getSelectedMorningModule(): ModuleEntity? = selectedMorningModule.value

    fun checkHealth(): SdkHealthReport {
        val model = modelManager.findLocalModel()
        return SdkHealthReport(
            isModelPresent = model != null,
            modelFileSizeBytes = model?.length() ?: 0L,
            modelStateName = modelManager.state.value::class.simpleName ?: "Unknown",
            morningCardCount = _morningModules.value.size,
        )
    }

    /**
     * Call after a successful assessment submission.
     * Primary UC-2 Apply trigger point — resolves the best coaching card for the patient
     * and pushes the result to [latestCardState] for [CoachingCardFragment] to observe.
     *
     * @param encounterId SPICE assessment/encounter ID for telemetry tracing. Pass `""` until Q9 is confirmed.
     * @param patientId Raw SPICE patient ID (will be SHA-256 hashed before any backend call).
     * @param assessmentData Optional map of patient vitals and clinical flags from SPICE ViewModel.
     *   Supported keys (TEAM-CONFIRM): `patient_track_id`, `age`, `gender`, `avg_systolic`,
     *   `avg_diastolic`, `bmi`, `cvd_risk_level`, `fbs_value`, `is_pregnant`,
     *   `is_htn_diagnosis`, `is_diabetes_diagnosis`, `upazila_id`.
     *   When empty the SDK will fall back to a condition-agnostic cached card.
     */
    fun onAssessmentSubmitted(
        encounterId: String,
        patientId: String,
        assessmentData: Map<String, Any> = emptyMap(),
    ) {
        val chwId = currentCHWId ?: return
        // Test-loop diagnostic: the key set lands here so we can grep
        // logcat in docs/gaps/GAPS_TEST.md and confirm SPICE actually
        // wrote `referralFacilityType` / `referred_site_id` etc. Values
        // are intentionally omitted — they may contain de-identified
        // clinical data that doesn't belong in logcat.
        Log.i(
            TAG,
            "onAssessmentSubmitted — encounterId='${encounterId}' " +
                "assessmentKeys=${assessmentData.keys}",
        )
        val payload = assessmentData.mapValues { it.value.toString() } +
            ("encounter_id" to encounterId) +
            ("patient_id" to patientId)
        evaluateWorkflowSignal(chwId, "assessment_submitted", payload)

        // UC-2 / UC-3: emit the assessment-level telemetry the backend expects
        // (conditional `risk_flag_observed`, stub `card_shown`). Referral
        // correctness (`spice_action_observed`) is deliberately NOT emitted
        // here — at assessment-save the CHW has not yet picked a destination
        // facility, so any referral verdict would be fabricated. That row is
        // owned entirely by [onReferralSubmitted], which sees the actual pick.
        // The render path is not yet built — `card_shown` is a placeholder
        // until the post-assessment counselling card surface exists.
        sdkScope.launch {
            try {
                val recorder = newSdkHookRecorder(chwId)
                val patientIdHash = PatientIdHasher.hash(patientId)
                val visitId = encounterId.takeIf { it.isNotBlank() }
                val villageId = assessmentData["village_id"] as? String
                val upazilaId = assessmentData["upazila_id"] as? String
                val patientTrackId = assessmentData["patient_track_id"] as? String
                val gapId = assessmentData["behavioural_gap_id"] as? String
                val riskLevel = (assessmentData["risk_level"] as? String).orEmpty()
                val networkState = if (isNetworkAvailable()) "online" else "offline"

                // Referral-compliance gaps are evaluated only at
                // [onReferralSubmitted] (the moment the `actual.*` pick exists);
                // the assessment-submit payload is flat, so no gap can fire here
                // and no `spice_action_observed` row is written. This keeps
                // assessment-save free of any (necessarily fabricated) referral
                // verdict and avoids double-counting a gap across both hooks.

                if (riskLevel.equals("HIGH", ignoreCase = true) ||
                    riskLevel.equals("EMERGENCY", ignoreCase = true)
                ) {
                    recorder.recordRiskFlagObserved(
                        riskLevel = riskLevel,
                        patientIdHash = patientIdHash,
                        patientVisitId = visitId,
                        patientTrackId = patientTrackId,
                        villageId = villageId,
                        upazilaId = upazilaId,
                        behaviouralGapId = gapId,
                        networkState = networkState,
                    )
                }

                // STUB: post-assessment coaching surface "fires" — replace
                // with a render-path-driven emission once the visit card UI
                // is built. Marked `inferenceMode = "cached"` because that's
                // the only resolution path in scope this week.
                recorder.recordCardShown(
                    cardType = "action",
                    triggerType = "workflow_event",
                    inferenceMode = "cached",
                    patientIdHash = patientIdHash,
                    patientVisitId = visitId,
                    patientTrackId = patientTrackId,
                    villageId = villageId,
                    upazilaId = upazilaId,
                    behaviouralGapId = gapId,
                    networkState = networkState,
                )
            } catch (e: Exception) {
                Log.w(TAG, "onAssessmentSubmitted event emission failed: ${e.message}")
            }

            // Push the freshly-written `clinical_observed` rows immediately
            // instead of waiting for the 15-min WorkManager tick. Assessment
            // submit is a meaningful milestone (the supervisor dashboard's
            // "correct referral" tile depends on these), and a CHW with
            // network connectivity should see their action surface server-
            // side within seconds.
            //
            // Offline-safe: `flushTelemetryNow` enqueues a WorkManager job
            // with a `NetworkType.CONNECTED` constraint, so an offline
            // device queues the work and it fires the moment connectivity
            // is restored. No need to gate on `isNetworkAvailable()` here.
            flushTelemetryNow()
        }
    }

    /**
     * Call when the CHW **commits a referral** — picks a destination facility and
     * confirms it (e.g. BD NCD / RMNCH summary "Done"). This is the moment the
     * `actual.*` side of a referral exists, so it's where `spice_referral_compliance`
     * gaps are evaluated — [onAssessmentSubmitted] fires earlier (at save), before
     * the CHW has picked, so it cannot evaluate referral compliance.
     *
     * @param referralData the compliance state — a map carrying both the
     *   `recommended.*` branch (rule-engine output) and the `actual.*` branch
     *   (the CHW's pick). SPICE assembles this; see
     *   docs/gaps/COMPLIANCE_TEST_SPEC.md §2.
     *
     * Telemetry: emits **one gap-tagged** `spice_action_observed` per fired gap
     * (`behavioural_gap_id` set, `outcome="incorrect"`). It does **not** emit a
     * generic fallback row — the single generic "observed" row was already written
     * by [onAssessmentSubmitted]. So per referred assessment the backend sees: one
     * generic row (gap_id = null) from the assessment hook + N gap-tagged rows from
     * here. The backend counts by `(chw_id, behavioural_gap_id)`, so these don't
     * collide. **Compliance gaps must fire at this hook only** (not also at
     * assessment-submit) or a gap would be counted twice; today that holds because
     * the assessment-submit payload is flat (no `recommended.*`/`actual.*`).
     */
    fun onReferralSubmitted(
        encounterId: String,
        patientId: String,
        referralData: Map<String, Any> = emptyMap(),
    ) {
        val chwId = currentCHWId ?: return
        // Log the tier comparison inputs *in words* (not just correct=true/false)
        // so referral_location_* (Upazila vs Community Clinic) can be validated
        // from logcat. Facility tier is not PII.
        val recommendedTier = (referralData["recommended"] as? Map<*, *>)?.get("referralFacilityType")
        val actualTier = (referralData["actual"] as? Map<*, *>)?.get("destinationTier")
        Log.i(
            TAG,
            "onReferralSubmitted — encounterId='${encounterId}' " +
                "recommendedTier='${recommendedTier}' actualTier='${actualTier}' " +
                "referralKeys=${referralData.keys}",
        )
        if (!config.enableGapDetection) {
            Log.d(TAG, "onReferralSubmitted: gap detection disabled — skipping")
            return
        }

        sdkScope.launch {
            try {
                val recorder = newSdkHookRecorder(chwId)
                val patientIdHash = PatientIdHasher.hash(patientId)
                val visitId = encounterId.takeIf { it.isNotBlank() }
                val villageId = referralData["village_id"] as? String
                val upazilaId = referralData["upazila_id"] as? String
                val patientTrackId = referralData["patient_track_id"] as? String
                val networkState = if (isNetworkAvailable()) "online" else "offline"

                val spiceEventCode = (referralData["spice_event_code"] as? String)
                    ?: "referral_submitted"
                val assessmentType = referralData["assessment_type"] as? String
                val firedGaps =
                    gapRuleDispatcher.evaluate(referralData, spiceEventCode, assessmentType)

                for (result in firedGaps) {
                    // Every row here is a *fired* compliance gap, i.e. the CHW's
                    // referral diverged from the recommendation → incorrect. The
                    // legacy three-axis `correctReferral*` is reason-based and
                    // tier-blind, so it would contradict `outcome=incorrect` (it
                    // returns true when only the facility tier is wrong). Report
                    // false uniformly; the precise dimension is in `evidence`
                    // (e.g. tier="location"). The generic "correct" baseline is
                    // still emitted by onAssessmentSubmitted at save time.
                    recorder.recordSpiceActionObserved(
                        patientIdHash = patientIdHash,
                        patientVisitId = visitId,
                        patientTrackId = patientTrackId,
                        villageId = villageId,
                        upazilaId = upazilaId,
                        behaviouralGapId = result.gapId,
                        correctReferral = false,
                        correctReferralLocation = false,
                        correctReferralType = false,
                        networkState = networkState,
                        outcome = result.outcome,
                        ruleType = result.ruleType,
                        evidenceJson = EvidenceBuilder.toJsonString(result.evidence),
                    )
                }
                if (firedGaps.isEmpty()) {
                    // No gap fired. Before claiming a CORRECT referral, confirm the
                    // tier comparison could actually run. If the CHW referred and a
                    // recommended tier exists but the ACTUAL destination tier is
                    // missing/blank (e.g. the picked facility's tier isn't populated
                    // locally — the known facility-tier data gap), the
                    // referral_location_* rules log "no signal" and don't fire. That
                    // is NOT the same as "correct": recording correct here turns
                    // missing data into a false positive (the wrong-facility-referral
                    // bug). Skip the positive emission and warn instead — never assert
                    // a correct referral the SDK couldn't actually verify.
                    val actualMap = referralData["actual"] as? Map<*, *>
                    val didRefer = actualMap?.get("didRefer") == true
                    val actualTierPresent =
                        (actualMap?.get("destinationTier") as? String)?.isNotBlank() == true
                    val recommendedTierPresent =
                        ((referralData["recommended"] as? Map<*, *>)?.get("referralFacilityType") as? String)
                            ?.isNotBlank() == true

                    if (didRefer && recommendedTierPresent && !actualTierPresent) {
                        Log.w(
                            TAG,
                            "onReferralSubmitted: referral made and a recommended tier exists, but " +
                                "actual.destinationTier is missing/blank — NOT recording a correct " +
                                "referral (tier comparison could not run; facility tier likely " +
                                "unpopulated). recommendedTier='$recommendedTier' actualTier='$actualTier'",
                        )
                    } else {
                        // Genuinely no divergence to report → emit the single positive
                        // `spice_action_observed` row (gap_id = null) so the supervisor
                        // "correct referral" tile sees a correct data point. This is the
                        // only place a `correctReferral = true` row originates now that
                        // the assessment hook no longer fabricates one.
                        recorder.recordSpiceActionObserved(
                            patientIdHash = patientIdHash,
                            patientVisitId = visitId,
                            patientTrackId = patientTrackId,
                            villageId = villageId,
                            upazilaId = upazilaId,
                            behaviouralGapId = null,
                            correctReferral = true,
                            correctReferralLocation = true,
                            correctReferralType = true,
                            networkState = networkState,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onReferralSubmitted event emission failed: ${e.message}")
            }
            flushTelemetryNow()

            // The referral just changed this CHW's compliance-gap state — a wrong
            // referral opens a new action-gap refresher (e.g. "Maternal & Neonatal
            // Referral Process"), a correct one can resolve a prior one. Re-run the
            // morning resolution NOW so the on-device generator folds in the
            // freshly-recorded `spice_action_observed` row and the refresher list /
            // featured card update immediately — instead of waiting for the next
            // onHomeScreenShown / sync tick.
            morningModuleResolver.refresh(chwId)
            coachingModuleStore.invalidate()
        }
    }

    /**
     * Call when the CHW finishes a patient visit in SPICE (TP-7).
     *
     * Backfills `patient_visit_id` on every still-pending coaching_event row
     * this visit produced (these events were written by [onAssessmentSubmitted]
     * before the encounterId was final — see BUG-5), emits a `session_end`
     * system event, and triggers an immediate sync push.
     *
     * @param encounterId SPICE assessment/encounter ID. Pass the real value
     *   from SPICE — blank values are skipped.
     */
    fun onVisitCompleted(encounterId: String) {
        val chwId = currentCHWId ?: return
        sdkScope.launch {
            val recorder = newSdkHookRecorder(chwId)
            visitCompletedHandler.handle(
                chwId = chwId,
                encounterId = encounterId,
                recorder = recorder,
                flush = { flushTelemetryNow() },
            )
        }
    }

    /** Call when SPICE finalises a form submission (Phase 8 workflow hook). */
    fun onFormSubmitted(formId: String, payload: Map<String, String> = emptyMap()) {
        val chwId = currentCHWId ?: return
        evaluateWorkflowSignal(chwId, "form_submitted", payload + ("form_id" to formId))
    }

    /** Call when a SPICE rule fires (Phase 8 workflow hook). */
    fun onRuleFired(ruleId: String, payload: Map<String, String> = emptyMap()) {
        val chwId = currentCHWId ?: return
        evaluateWorkflowSignal(chwId, "rule_fired", payload + ("rule_id" to ruleId))
    }

    /**
     * Call when the CHW opens the morning routine. Re-runs the 3-tier morning
     * resolution (same as [onHomeScreenShown]) and updates [latestModule].
     */
    fun onMorningOpen() {
        val chwId = currentCHWId ?: return
        sdkScope.launch {
            morningModuleResolver.refresh(chwId)
            _latestModule.value = _morningModules.value.firstOrNull()
            // morning_card_cache changed → re-read it in the store's mapping.
            coachingModuleStore.invalidate()
        }
    }

    /**
     * Force a refresh of the morning refreshers. Pushes any pending telemetry (so
     * the backend can act on a just-finished quiz), re-pulls `GET /morning/cards`
     * (the backend re-runs its gap algorithm), re-runs the on-device generator
     * (which reflects local progress immediately, even before the push round-trips),
     * and recomputes the shared store.
     *
     * Exposed for:
     *  - the SPICE host's **pull-to-refresh** on the home coaching surface, and
     *  - quiz completion ([com.medtroniclabs.microcoaching.ui.quiz.QuizResultScreen]),
     *    so a finished module's refresher state updates without leaving the screen.
     *
     * No-op until a CHW id is known. Safe to call repeatedly (the network pull is
     * best-effort; offline it falls back to the on-device generator).
     */
    fun refreshRefreshers() {
        val chwId = currentCHWId ?: return
        sdkScope.launch {
            flushTelemetryNow()
            morningModuleResolver.refresh(chwId)
            coachingModuleStore.invalidate()
        }
    }

    /**
     * 4-tier morning resolution, extracted into [MorningModuleResolver]. Re-exposed
     * for `InboundSyncWorker` / `RefresherBottomSheet`, which re-filter the morning
     * list against freshly-synced progress. Delegates to the same resolver instance
     * so it mutates the SDK's [morningModules] / [morningCardsItems] flows.
     */
    internal suspend fun refilterMorningModules(chwId: String) {
        morningModuleResolver.refilter(chwId)
        // morning_card_cache may have changed → re-read it in the store's mapping
        // so refresher list / featured pick reflect freshly-synced progress.
        coachingModuleStore.invalidate()
    }

    /** Call when SPICE surfaces a risk flag for the active patient. */
    fun onRiskFlagObserved(riskLevel: String, patientId: String? = null) {
        val chwId = currentCHWId ?: return
        val payload = mutableMapOf("risk_level" to riskLevel)
        if (patientId != null) payload["patient_id"] = patientId
        evaluateWorkflowSignal(chwId, "risk_flag_observed", payload)

        // UC-3: emit the wire-level `risk_flag_observed` row so the backend's
        // clinical_observed feed picks it up. Sub-scenario C of UC-2 (mid-
        // visit escalation) fires through this same hook — recording the
        // event here means SPICE only has to call one method.
        sdkScope.launch {
            try {
                val recorder = newSdkHookRecorder(chwId)
                val patientIdHash = patientId?.let { PatientIdHasher.hash(it) }
                recorder.recordRiskFlagObserved(
                    riskLevel = riskLevel,
                    patientIdHash = patientIdHash,
                    networkState = if (isNetworkAvailable()) "online" else "offline",
                )
            } catch (e: Exception) {
                Log.w(TAG, "onRiskFlagObserved event emission failed: ${e.message}")
            }
            // Risk-flag is arguably more time-sensitive than a regular
            // assessment submit — push the row immediately. Offline-safe:
            // WorkManager queues with NetworkType.CONNECTED.
            flushTelemetryNow()
        }
    }

    /** Call when SPICE reports an equipment anomaly (BP cuff, glucometer, …). */
    fun onEquipmentAnomaly(detail: String) {
        val chwId = currentCHWId ?: return
        evaluateWorkflowSignal(chwId, "equipment_anomaly", mapOf("detail" to detail))
    }

    /**
     * Persist a module-quiz outcome locally and update [ChwModuleCompletionEntity].
     * Telemetry is emitted by the caller (the Learn flow already records
     * `quiz_completed` via [EventRecorder]).
     */
    fun onModuleQuizCompleted(
        moduleFamilyId: String,
        moduleId: String?,
        scoreFraction: Float,
        passed: Boolean,
    ) {
        val chwId = currentCHWId ?: return
        sdkScope.launch {
            try {
                val dao = database.chwModuleCompletionDao()
                val previous = dao.get(chwId, moduleFamilyId)
                val updated = buildModuleCompletion(
                    previous = previous,
                    chwId = chwId,
                    moduleFamilyId = moduleFamilyId,
                    moduleId = moduleId,
                    scoreFraction = scoreFraction,
                    passed = passed,
                    reinforcementDays = config.periodicRefreshDays,
                )
                dao.upsert(updated)
            } catch (e: Exception) {
                Log.w(TAG, "onModuleQuizCompleted persist failed: ${e.message}")
            }
        }
    }

    private fun evaluateWorkflowSignal(
        chwId: String,
        spiceEventCode: String,
        payload: Map<String, String>,
    ) {
        sdkScope.launch {
            try {
                val module = triggerEvaluator.evaluate(
                    chwId,
                    TriggerEvaluator.Signal.WorkflowEvent(
                        spiceEventCode = spiceEventCode,
                        payload = payload,
                    ),
                )
                _latestModule.value = module
            } catch (e: Exception) {
                Log.w(TAG, "Workflow trigger evaluation failed for '$spiceEventCode': ${e.message}")
            }
        }
    }

    internal fun isNetworkAvailable(): Boolean {
        val cm = config.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /**
     * Idempotent helper called from the BANGLA-gated `modelManager.state`
     * collector in `init` whenever the AI model lands in `Ready`. Triggers the
     * Bengali sherpa STT download unless it's already on disk or in flight.
     * Logs the decision so the auto-chain is observable via `adb logcat -s
     * MicroCoachingSDK`.
     */
    private fun ensureBengaliSttDownloadKickedOff() {
        val current = sttModelManager.state.value
        when (current) {
            is com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState.Ready,
            is com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState.Downloading,
            is com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState.Extracting -> {
                Log.d(TAG, "Bengali STT download already ${current::class.simpleName} — skipping auto-trigger")
                return
            }
            else -> {
                Log.i(TAG, "Auto-triggering Bengali STT download (lang=BN, AI ready)")
                sttModelManager.triggerBengaliDownload()
            }
        }
    }

    private fun registerConnectivityCallback() {
        val cm = config.context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // If the SDK initialises while OFFLINE, pre-set `networkLost = true`
        // so the next `onAvailable` callback triggers a restore. The previous
        // implementation defaulted to false → the first `onAvailable` (which
        // always fires shortly after registration to report the current
        // network) was silently ignored, and pending events shipped from
        // yesterday's session sat in Room until the 15-min periodic worker
        // fired.
        if (cm.activeNetwork == null) {
            networkLost = true
            _networkAvailable.value = false
            Log.d(TAG, "Network callback registering while offline — primed for restore.")
        } else {
            _networkAvailable.value = true
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable(network=$network) networkLost=$networkLost")
                _networkAvailable.value = true
                if (networkLost) {
                    Log.i(TAG, "Connectivity restored — flushing pending telemetry.")
                    networkLost = false
                    onConnectivityRestored()
                }
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "Connectivity lost — pending events will sync when online.")
                networkLost = true
                _networkAvailable.value = false
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.d(TAG, "Network callback registered. activeNetwork=${cm.activeNetwork}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /** Releases the network callback and cancels all pending sync workers. Call before replacing the SDK instance. */
    fun shutdown() {
        val cm = config.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            networkCallback = null
        }
        syncCoordinator.cancelAll()
        Log.d(TAG, "SDK shutdown — network callback unregistered, sync cancelled.")
    }

    /**
     * Call when connectivity is restored after an offline period.
     * Triggers OTel span flush and queues an immediate outbound + inbound sync cycle.
     * Also called automatically by the SDK's internal NetworkCallback.
     */
    fun onConnectivityRestored() {
        Log.i(TAG, "onConnectivityRestored — flushing spans and triggering sync.")
        if (config.enableTelemetry) telemetry.flushPendingSpans()
        if (config.backendUrl.isNotBlank()) {
            // Outbound flush first — gets pending telemetry to the backend
            // promptly even if the inbound bundle pull stalls or fails.
            // triggerNow() then chains outbound+inbound for full freshness.
            // WorkManager dedupes / coalesces the two outbound workers, so
            // there's no double-POST risk.
            syncCoordinator.triggerOutboundNow()
            syncCoordinator.triggerNow()
        }
    }

    /**
     * Force an immediate outbound telemetry flush — bypasses the 15-min
     * WorkManager periodic interval. Intended for SDK-internal use when a
     * quiz answer or module completion has just been recorded, and as a
     * developer hook for verifying backend ingestion during integration.
     *
     * No-op when [MicroCoachingConfig.backendUrl] is blank.
     */
    fun flushTelemetryNow() {
        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "flushTelemetryNow skipped — backendUrl is blank")
            return
        }
        Log.i(TAG, "flushTelemetryNow — enqueuing outbound sync.")
        syncCoordinator.triggerOutboundNow()
    }

    // ── CHW Context Storage ───────────────────────────────────────────────────

    private val chwPrefs by lazy {
        config.context.getSharedPreferences("micro_coaching_chw_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Call whenever the CHW's patient list is available (home screen or patient list screen).
     * Persists an anonymised summary of recent screenings so the AI chat can answer
     * questions like "আমার আজকের স্ক্রিনিং কারা?".
     *
     * @param chwWorkContext Anonymised summary — no patient names or IDs.
     */
    fun onCHWContextUpdated(chwWorkContext: CHWWorkContext) {
        Log.d(TAG, "CHWContext [SDK] onCHWContextUpdated — count=${chwWorkContext.screenedTodayCount}, recentPatients=${chwWorkContext.recentPatients.size}")
        sdkScope.launch { storeCHWContext(chwWorkContext) }
    }

    private fun storeCHWContext(ctx: CHWWorkContext) {
        val json = Json.encodeToString(ctx)
        chwPrefs.edit().putString("chw_work_context", json).apply()
    }

    /** Returns the last CHW work context pushed via [onCHWContextUpdated], or null if none stored. */
    fun loadCHWContext(): CHWWorkContext? {
        val json = chwPrefs.getString("chw_work_context", null) ?: return null
        return try {
            Json.decodeFromString<CHWWorkContext>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode CHWWorkContext: ${e.message}")
            null
        }
    }

    /**
     * Call at home/morning open with the CHW's patient visits due **today** (the
     * host's `FollowUp` rows filtered to today). Used as a cold-start refresher
     * source: when there are no behavioural-gap picks and no backend morning cards,
     * the on-device generator matches these visits to modules via the synced
     * `assessment_due` `workflow_event` trigger bindings. Push an empty list to clear.
     *
     * No patient identifiers — only [TodaysVisit.encounterType] / [TodaysVisit.isPregnant]
     * and the due date are used.
     */
    fun onTodaysVisitsUpdated(visits: List<TodaysVisit>) {
        Log.d(TAG, "CHWContext [SDK] onTodaysVisitsUpdated — visits=${visits.size}")
        sdkScope.launch { storeTodaysVisits(visits) }
    }

    private fun storeTodaysVisits(visits: List<TodaysVisit>) {
        val json = Json.encodeToString(visits)
        chwPrefs.edit().putString("todays_visits", json).apply()
    }

    /** Returns the visits last pushed via [onTodaysVisitsUpdated], or empty if none/undecodable. */
    fun loadTodaysVisits(): List<TodaysVisit> {
        val json = chwPrefs.getString("todays_visits", null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<TodaysVisit>>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode todays_visits: ${e.message}")
            emptyList()
        }
    }

    private fun storeLastPatientSnapshot(snapshot: PatientSnapshot) {
        val json = Json.encodeToString(snapshot)
        chwPrefs.edit().putString("last_patient_snapshot", json).apply()
        Log.d(TAG, "CHWContext [SDK] Persisted PatientSnapshot: conditions=${snapshot.conditions}, risk=${snapshot.riskLevel}")
    }

    /** Returns the snapshot from the most recent assessment, or null if none stored. */
    fun loadLastPatientSnapshot(): PatientSnapshot? {
        val json = chwPrefs.getString("last_patient_snapshot", null) ?: return null
        return try {
            Json.decodeFromString<PatientSnapshot>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode PatientSnapshot: ${e.message}")
            null
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    class Builder(private val context: Context) {
        private var language: Language = Language.BANGLA
        private var tenantId: String = ""
        private var backendUrl: String = ""
        private var authToken: String = ""
        private var connectionTimeoutSeconds: Int = 30
        private var readTimeoutSeconds: Int = 60
        private var enableTelemetry: Boolean = false
        private var otelEndpoint: String = ""
        private var otelServiceName: String = "micro-coaching-android"
        private var otelHeaders: Map<String, String> = emptyMap()
        private var otelSamplingRate: Double = 1.0
        private var otelBatchExportIntervalMs: Long = 5_000L
        private var otelMaxBatchSize: Int = 512
        private var enableOtelDebugLogging: Boolean = false
        private var modelPath: String = ""
        private var modelDownloadStrategy: ModelDownloadStrategy = ModelDownloadStrategy.ON_FIRST_USE
        private var wifiOnlyModelDownload: Boolean = false
        private var modelProviders: List<ModelProvider> = ModelProvider.DEFAULT_ORDER
        private var huggingFaceToken: String = ""
        private var huggingFaceModelUrl: String = ""
        private var selectedModelId: String = ModelCatalog.DEFAULT_ID
        // Keep in sync with MicroCoachingConfig defaults — see the KDoc there for
        // why maxInferenceTokens is a TOTAL (input+output) window, not an output cap.
        private var maxInferenceTokens: Int = 1536
        private var inferenceTemperature: Float = 0.3f
        private var chatScopeStrictness: ChatScopeStrictness = ChatScopeStrictness.ExtendedClinical
        private var chatTuning: ChatTuning = ChatTuning()
        private var refresherTuning: RefresherTuning = RefresherTuning()
        private var forceLowEndMode: Boolean? = null
        private var enableChat: Boolean = true
        private var enableRetrievalHintFixtureOverlay: Boolean = false
        private var enableVoice: Boolean = false
        private var enableLearnModule: Boolean = false
        private var enableApplyModule: Boolean = false
        private var enableMeasureModule: Boolean = false
        private var dataCallback: MicroCoachingDataCallback? = null
        private var uiTheme: CoachingUiTheme = CoachingUiTheme.SYSTEM
        private var forcedMode: CoachingMode? = null
        private var voiceInputController: VoiceInputController? = null
        private var offlineSttEngineFactory:
            ((android.content.Context, java.io.File) -> OfflineSttEngine)? = null

        fun language(l: Language) = apply { language = l }
        fun tenantId(id: String) = apply { tenantId = id }
        fun backendUrl(url: String) = apply { backendUrl = url }
        /** Pass the SPICE JWT here. SDK forwards it as `Authorization: Bearer <token>`. */
        fun authToken(token: String) = apply { authToken = token }
        fun connectionTimeout(seconds: Int) = apply { connectionTimeoutSeconds = seconds }
        fun readTimeout(seconds: Int) = apply { readTimeoutSeconds = seconds }
        fun enableTelemetry(enabled: Boolean) = apply { enableTelemetry = enabled }
        fun otelEndpoint(url: String) = apply { otelEndpoint = url }
        fun otelServiceName(name: String) = apply { otelServiceName = name }
        /** HTTP headers for OTLP export. SigNoz: `"signoz-access-token"`. Grafana: `"Authorization"`. */
        fun otelHeaders(headers: Map<String, String>) = apply { otelHeaders = headers }
        fun otelSamplingRate(rate: Double) = apply { otelSamplingRate = rate.coerceIn(0.0, 1.0) }
        fun otelBatchExportIntervalMs(ms: Long) = apply { otelBatchExportIntervalMs = ms }
        fun otelMaxBatchSize(size: Int) = apply { otelMaxBatchSize = size }
        fun enableOtelDebugLogging(enabled: Boolean) = apply { enableOtelDebugLogging = enabled }
        fun modelPath(path: String) = apply { modelPath = path }
        fun modelDownloadStrategy(strategy: ModelDownloadStrategy) = apply { modelDownloadStrategy = strategy }
        fun wifiOnlyModelDownload(wifiOnly: Boolean) = apply { wifiOnlyModelDownload = wifiOnly }
        /** Override provider priority or disable a provider entirely. Default: Backend → HuggingFace → Kaggle. */
        fun modelProviders(providers: List<ModelProvider>) = apply { modelProviders = providers }
        /** HuggingFace Hub token for gated model downloads. Set HUGGING_FACE_TOKEN in local.properties for dev. */
        fun huggingFaceToken(token: String) = apply { huggingFaceToken = token }
        /**
         * Optional override of the model download URL. Leave unset to use the
         * [selectedModel]'s catalog URL. Set only to point at a file not in the
         * allowlist — the on-disk filename still comes from the selected variant.
         */
        fun huggingFaceModelUrl(url: String) = apply { huggingFaceModelUrl = url }
        /**
         * Pick which on-device model to download/load — an `id` from
         * [com.medtroniclabs.microcoaching.ai.model.ModelCatalog.ALLOWLIST]
         * (e.g. `"gemma3-270m-it-q8-task"`, `"gemma3-1b-it-int4-task"`).
         * Default: [com.medtroniclabs.microcoaching.ai.model.ModelCatalog.DEFAULT_ID].
         * An unknown id falls back to the default; a non-MediaPipe variant is
         * accepted but warns (it can't load until the LiteRT-LM runtime is re-added).
         */
        fun selectedModel(id: String) = apply {
            val variant = ModelCatalog.byId(id)
            when {
                variant == null ->
                    Log.w(TAG, "selectedModel('$id') is not in the allowlist — using default '${ModelCatalog.DEFAULT_ID}'")
                !ModelCatalog.isRunnable(variant) ->
                    Log.w(TAG, "selectedModel('$id') runtime=${variant.runtime} is not bundled — it won't load until the LiteRT-LM runtime is re-added")
            }
            selectedModelId = variant?.id ?: ModelCatalog.DEFAULT_ID
        }
        fun maxInferenceTokens(tokens: Int) = apply { maxInferenceTokens = tokens }
        fun inferenceTemperature(temp: Float) = apply { inferenceTemperature = temp }
        /**
         * Pick the chat refusal strictness. Default is [ChatScopeStrictness.ExtendedClinical] —
         * the on-device LLM is consulted for retrieval-miss questions instead of a hard
         * keyword-classifier refusal. Use [ChatScopeStrictness.Strict] to preserve the
         * legacy keyword-only behaviour.
         */
        fun chatScopeStrictness(strictness: ChatScopeStrictness) = apply { chatScopeStrictness = strictness }
        /**
         * Tune the offline-chat retrieval and refusal gates (BM25 threshold,
         * groundedness floor, strong-retrieval bypass, stream cap, length cap,
         * drug/dosage guards). See [ChatTuning] for each knob. Leave unset to use
         * the SDK defaults, which favour showing the model's answer when BM25
         * retrieval is confident.
         */
        fun chatTuning(tuning: ChatTuning) = apply { chatTuning = tuning }
        /**
         * Tune the daily refresher quiz-subset nudge — the sample ratio and the
         * min/max bounds on how many of a module's questions a refresher presents.
         * See [RefresherTuning]. Leave unset for the defaults (~40%, 2–6, sweet 3–4).
         */
        fun refresherTuning(tuning: RefresherTuning) = apply { refresherTuning = tuning }
        /**
         * Override automatic low-end-device detection. Default `null` lets the
         * SDK probe total RAM (< 3 GB → low-end, retrieval-only chat). Pass
         * `true` to force the retrieval-only path on capable hardware (QA), or
         * `false` to force the LLM path on a low-RAM device (only safe when an
         * external runtime guarantees the Gemma model can load).
         */
        fun forceLowEndMode(force: Boolean?) = apply { forceLowEndMode = force }
        fun enableChat(enabled: Boolean) = apply { enableChat = enabled }
        /**
         * Merge hand-authored per-card hints from `assets/retrieval/overlays/`
         * before building the chat index. Benchmark / QA only — off in production.
         */
        fun enableRetrievalHintFixtureOverlay(enabled: Boolean) =
            apply { enableRetrievalHintFixtureOverlay = enabled }
        fun enableVoice(enabled: Boolean) = apply { enableVoice = enabled }
        fun enableLearnModule(enabled: Boolean) = apply { enableLearnModule = enabled }
        fun enableApplyModule(enabled: Boolean) = apply { enableApplyModule = enabled }
        fun enableMeasureModule(enabled: Boolean) = apply { enableMeasureModule = enabled }
        fun dataCallback(callback: MicroCoachingDataCallback) = apply { dataCallback = callback }
        /** Set the colour scheme for SDK-owned screens. Default: [CoachingUiTheme.SYSTEM]. */
        fun uiTheme(theme: CoachingUiTheme) = apply { uiTheme = theme }
        /**
         * Force a specific coaching mode regardless of network or RAM state.
         * Dev/test only — leave unset in production.
         */
        fun forceMode(mode: CoachingMode) = apply { forcedMode = mode }

        /**
         * Supply a custom speech-to-text controller for the chat mic.
         *
         * When unset, the SDK auto-installs
         * [com.medtroniclabs.microcoaching.ai.voice.AndroidSpeechRecognizerEngine]
         * if [enableVoice] is `true`. Use this hook when you need a different
         * engine (e.g. an offline Bengali sherpa-onnx impl).
         */
        fun voiceInputController(
            controller: VoiceInputController,
        ) = apply { voiceInputController = controller }

        /**
         * Supply a factory that builds the offline Bengali STT engine when needed.
         *
         * The SDK's default voice controller routes Bengali through the platform
         * `SpeechRecognizer` when online, and falls back to this engine only
         * when the device is offline AND the sherpa-onnx model files are on
         * disk. The factory is called lazily — no sherpa-onnx classes are
         * touched until the first offline-Bengali dictation.
         *
         * Hosts that include the optional `:sdk-android-sherpa` module just
         * pass [com.medtroniclabs.microcoaching.sherpa.SherpaOnnxStt.factory].
         * Hosts that skip the module get the platform engine only — same
         * online behaviour for both languages, no offline Bengali.
         */
        fun offlineSttEngineFactory(
            factory: (android.content.Context, java.io.File) -> OfflineSttEngine,
        ) = apply { offlineSttEngineFactory = factory }

        fun build(): MicroCoachingSDK {
            val config = MicroCoachingConfig(
                context = context.applicationContext,
                language = language,
                tenantId = tenantId,
                backendUrl = backendUrl,
                authToken = authToken,
                connectionTimeoutSeconds = connectionTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds,
                enableTelemetry = enableTelemetry,
                otelEndpoint = otelEndpoint,
                otelServiceName = otelServiceName,
                otelHeaders = otelHeaders,
                otelSamplingRate = otelSamplingRate,
                otelBatchExportIntervalMs = otelBatchExportIntervalMs,
                otelMaxBatchSize = otelMaxBatchSize,
                enableOtelDebugLogging = enableOtelDebugLogging,
                modelPath = modelPath,
                modelDownloadStrategy = modelDownloadStrategy,
                wifiOnlyModelDownload = wifiOnlyModelDownload,
                modelProviders = modelProviders,
                huggingFaceToken = huggingFaceToken,
                huggingFaceModelUrl = huggingFaceModelUrl,
                selectedModelId = selectedModelId,
                maxInferenceTokens = maxInferenceTokens,
                inferenceTemperature = inferenceTemperature,
                chatScopeStrictness = chatScopeStrictness,
                chatTuning = chatTuning,
                refresherTuning = refresherTuning,
                forceLowEndMode = forceLowEndMode,
                enableChat = enableChat,
                enableRetrievalHintFixtureOverlay = enableRetrievalHintFixtureOverlay,
                enableVoice = enableVoice,
                enableLearnModule = enableLearnModule,
                enableApplyModule = enableApplyModule,
                enableMeasureModule = enableMeasureModule,
                dataCallback = dataCallback,
                uiTheme = uiTheme,
                forcedMode = forcedMode,
            )
            val sdk = MicroCoachingSDK(config)
            synchronized(Companion) {
                instance?.shutdown()
                instance = sdk
            }

            // Wire the chat mic. Host-supplied controller wins; otherwise install
            // ChatVoiceInputController, which routes between the platform
            // SpeechRecognizer (English / online Bengali) and an optional
            // offline sherpa engine (offline Bengali). The offline engine
            // factory is supplied by hosts that include :sdk-android-sherpa via
            // Builder.offlineSttEngineFactory(SherpaOnnxStt.factory). Without
            // it, the orchestrator falls back to an "offline voice model
            // missing" error in the no-network Bengali case.
            sdk.voiceInputController = voiceInputController
                ?: if (config.enableVoice) {
                    val androidEngine =
                        AndroidSpeechRecognizerEngine(
                            config.context,
                        )
                    val offlineFactory = offlineSttEngineFactory?.let { hostFactory ->
                        { modelDir: java.io.File -> hostFactory(config.context, modelDir) }
                    }
                    ChatVoiceInputController(
                        appContext = config.context,
                        androidEngine = androidEngine,
                        sttModelManager = sdk.sttModelManager,
                        offlineEngineFactory = offlineFactory,
                    )
                } else null

            Log.i(TAG, "SDK initialized — backendUrl=${config.backendUrl.isNotBlank()} " +
                "modelPath=${config.modelPath.isNotBlank()} " +
                "forcedMode=${config.forcedMode ?: "auto"}")

            // Kick off model download if configured to do so at init.
            // Skip entirely on low-end devices — they never use the AI model,
            // so queuing the ~1 GB download would waste bandwidth and storage.
            if (config.modelDownloadStrategy == ModelDownloadStrategy.ON_SDK_INIT &&
                !sdk.isLowEndDevice
            ) {
                sdk.modelManager.scheduleDownloadIfNeeded()
            }

            return sdk
        }
    }

    companion object {
        private const val TAG = "MicroCoachingSDK"

        @Volatile
        private var instance: MicroCoachingSDK? = null

        /**
         * Returns the initialized SDK singleton.
         * @throws IllegalStateException if [Builder.build] has not been called yet.
         */
        fun getInstance(): MicroCoachingSDK =
            instance ?: error(
                "MicroCoachingSDK is not initialized. " +
                    "Call MicroCoachingSDK.Builder(context).build() in Application.onCreate()."
            )

        /** Returns true if the SDK has been initialized. */
        fun isInitialized(): Boolean = instance != null
    }
}
