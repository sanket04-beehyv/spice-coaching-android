package com.medtroniclabs.microcoaching.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.ChatScopeStrictness
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import com.medtroniclabs.microcoaching.ui.chat.MessageSource
import com.medtroniclabs.microcoaching.data.repository.ChatRepositoryImpl
import com.medtroniclabs.microcoaching.ai.model.ModelState
import com.medtroniclabs.microcoaching.ai.inference.InferenceRouter
import com.medtroniclabs.microcoaching.ai.retrieval.ChatRefusal
import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.ai.retrieval.OffTopicGuard
import com.medtroniclabs.microcoaching.ai.retrieval.ScopeClassifier
import com.medtroniclabs.microcoaching.ai.voice.CoachingTtsHelper
import com.medtroniclabs.microcoaching.network.RagQueryRequest
import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.domain.validation.OutputValidator
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.R
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the AI coaching chat.
 *
 * Responsibilities:
 *   - Initializing [InferenceRouter] on first use
 *   - Sending user messages and streaming LLM responses
 *   - Persisting messages to MicroCoachingDatabase via [ChatRepositoryImpl]
 *   - Emitting OTel spans via TelemetryManager
 *   - Exposing [ChatUiState] to [ChatScreen]
 *
 * Created via [Factory] — no Hilt required (SDK is DI-framework-agnostic).
 * SPICE can inject [MicroCoachingSDK.getInstance().dataRepository] via its own Hilt AppModule.
 */
class ChatViewModel(
    application: Application,
    private val patientId: String,
    private val systemContext: String,
) : AndroidViewModel(application) {

    private val sdk = MicroCoachingSDK.getInstance()
    private val config = sdk.config
    private val telemetry = sdk.telemetry
    private val db = sdk.database
    private val chatRepo = ChatRepositoryImpl(db.chatMessageDao())
    private val inferenceRouter = InferenceRouter(config)
    private val tts = CoachingTtsHelper(application.applicationContext, Locale("bn", "BD"))
    // Lazy because it depends on `session` which is declared below.
    private val eventRecorder: EventRecorder by lazy {
        EventRecorder(
            dao = db.coachingEventDao(),
            sessionId = session.sessionId,
            chwId = sdk.currentCHWId.orEmpty(),
        )
    }
    private val outputValidator = OutputValidator()
    private val suggestionsRepository = ChatSuggestionsRepository(
        appContext = application.applicationContext,
        moduleDao = sdk.database.moduleDao(),
    )

    /**
     * Resolves a string resource through the SDK-configured locale rather than
     * the host's device locale, so error states surface in Bangla regardless
     * of where this VM is instantiated.
     */
    private fun localizedString(@androidx.annotation.StringRes resId: Int): String {
        val ctx = SdkLocaleHelper.wrap(
            getApplication<android.app.Application>(),
            sdk.language,
        )
        return ctx.getString(resId)
    }

    /**
     * Truncated, single-line preview of user/model text for the [TRACE_TAG]
     * pipeline logs. Content previews can contain CHW-typed text — keep these
     * at a level you can strip from production builds, and never log full
     * untruncated message bodies. The metadata-only trace lines (scores, ids,
     * lengths, booleans) carry no such risk.
     */
    private fun tracePreview(s: String?, max: Int = 120): String {
        if (s.isNullOrEmpty()) return "∅"
        val oneLine = s.replace('\n', '⏎').replace("\r", "")
        return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…(${oneLine.length} chars)"
    }

    /** One-line [TRACE_TAG] description of a BM25 grounding candidate. */
    private fun traceChunk(label: String, i: Int, c: GroundingChunk): String =
        "$label[$i] score=%.2f src=%s chunk=%s family=%s title=\"%s\"".format(
            Locale.US,
            c.score,
            c.source,
            c.chunkId,
            c.moduleFamilyId,
            tracePreview(c.titleEn ?: c.titleBn, 60),
        )

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val session = ChatSession(
        systemContext = systemContext,
    )

    private val sessionSpan = telemetry.startChatSession(session.sessionId)
    private var inferenceJob: Job? = null

    init {
        viewModelScope.launch { initializeModel() }
        observeModelState()
    }

    /**
     * Observes [ModelManager.state] so the UI reacts to download progress, failure, and success
     * without the user having to manually refresh.
     *
     * - [ModelState.Downloading]     → update progress bar while download is in flight
     * - [ModelState.DownloadFailed]  → clear the spinner so the user can retry
     * - [ModelState.Ready]           → model just landed on device; auto-init the inference engine
     */
    private fun observeModelState() {
        viewModelScope.launch {
            sdk.modelManager.state.collect { modelState ->
                when (modelState) {
                    is ModelState.Downloading -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.ModelNotReady -> it.copy(
                                    isDownloading = true,
                                    isPaused = false,
                                    downloadProgress = modelState.progressPercent,
                                    downloadBytesDownloaded = modelState.bytesDownloaded,
                                    downloadTotalBytes = modelState.totalBytes,
                                )
                                is ChatUiState.Ready -> it.copy(
                                    isModelDownloading = true,
                                    modelDownloadProgress = modelState.progressPercent,
                                    modelDownloadBytesDownloaded = modelState.bytesDownloaded,
                                    modelDownloadTotalBytes = modelState.totalBytes,
                                )
                                else -> it
                            }
                        }
                    }
                    is ModelState.Paused -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.ModelNotReady -> it.copy(
                                    isDownloading = false,
                                    isPaused = true,
                                    downloadProgress = modelState.progressPercent,
                                )
                                else -> it
                            }
                        }
                    }
                    is ModelState.DownloadFailed -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.ModelNotReady -> it.copy(
                                    isDownloading = false,
                                    isPaused = false,
                                    downloadProgress = -1,
                                )
                                is ChatUiState.Ready -> it.copy(isModelDownloading = false, modelDownloadProgress = -1)
                                else -> it
                            }
                        }
                    }
                    is ModelState.Ready -> {
                        when (val currentState = _uiState.value) {
                            is ChatUiState.Ready -> {
                                // Model just finished downloading while chat is already open.
                                // Must call initializeIfModelPresent() to actually load the inference
                                // engine — just setting modelPresent = true leaves activeService null,
                                // causing "No response available" on the next message send.
                                inferenceRouter.initializeIfModelPresent()
                                _uiState.update {
                                    (it as? ChatUiState.Ready)?.copy(
                                        modelPresent = inferenceRouter.isModelAvailable,
                                        isModelDownloading = false,
                                        modelDownloadProgress = -1,
                                    ) ?: it
                                }
                            }
                            is ChatUiState.Loading -> {
                                // initializeModel() is already in flight (it set
                                // _uiState to Loading on entry and the launched
                                // coroutine hasn't completed yet). Calling
                                // initializeModel() again here would race the
                                // first call and cause MediaPipe's LLM engine to
                                // load the same .task file twice on different
                                // threads — that's the native crash we hit in
                                // libllm_inference_engine_jni.so. Skip; the
                                // existing init will set Ready when it finishes.
                                @Suppress("UNUSED_VARIABLE")
                                val ignored = currentState
                            }
                            else -> initializeModel()
                        }
                    }
                    is ModelState.LoadFailed -> {
                        // Model file was corrupt and has been deleted; prompt re-download.
                        _uiState.value = ChatUiState.ModelNotReady()
                    }
                    is ModelState.Idle -> {
                        // Hit by cancelDownload — partial file is gone, reset the UI
                        // back to the initial "Download AI Model" CTA.
                        _uiState.update {
                            when (it) {
                                is ChatUiState.ModelNotReady -> ChatUiState.ModelNotReady()
                                else -> it
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Tap handler for the seed-suggestion chips. Persists the suggestion as
     * "used" so it never re-offers, refreshes the chip row from the remaining
     * pool, then sends the message via the normal inference path.
     */
    fun sendSuggestion(suggestion: SuggestedQuestion) {
        suggestionsRepository.markUsed(suggestion)
        // Refresh the chip row asynchronously — nextBatch() now reads from
        // Room (cached modules + quiz JSON). Running on viewModelScope keeps
        // the tap responsive; the UI updates as soon as the new batch is
        // computed.
        viewModelScope.launch {
            val next = loadSuggestions()
            _uiState.update { state ->
                (state as? ChatUiState.Ready)?.copy(suggestedQuestions = next) ?: state
            }
        }
        val text = when (sdk.language) {
            Language.BANGLA ->
                suggestion.banglaQuestion.ifBlank { suggestion.question }
            Language.ENGLISH ->
                suggestion.question.ifBlank { suggestion.banglaQuestion }
        }
        sendMessage(text, moduleFamilyId = suggestion.moduleFamilyId)
    }

    private suspend fun initializeModel() {
        _uiState.value = ChatUiState.Loading

        // Low-end devices skip the inference engine entirely. The chat opens
        // straight to Ready and every message routes through BM25 → bodyBn
        // (see sendMessage below). modelPresent=false signals downstream UI
        // surfaces that we're in retrieval-only mode.
        if (sdk.isLowEndDevice) {
            Log.i(TAG, "Low-end device — initialising chat in retrieval-only mode")
            val history = chatRepo.getRecentHistory(
                chwId = sdk.currentCHWId.orEmpty(),
                limit = HISTORY_LIMIT,
            )
            _uiState.value = ChatUiState.Ready(
                messages = history,
                modelPresent = false,
                suggestedQuestions = loadSuggestions(),
            )
            return
        }

        val service = inferenceRouter.initializeIfModelPresent()

        // No working local inference engine → show the dedicated download / progress
        // surface, regardless of whether the host configured a backend URL. The
        // online RAG fallback that justified a Ready(modelPresent=false) branch was
        // removed in Phase 2.4 (backend `/rag/answer` not implemented — see comment
        // in sendMessage), so the chat surface with input + suggestions chips would
        // otherwise render over a non-functional model and let the CHW type
        // questions that go nowhere. Collapsing both null-service paths keeps the
        // UX honest: chat is only "ready" when the LLM can actually answer.
        if (service == null) {
            if (sdk.modelManager.isModelPresent()) {
                // File on disk but engine couldn't load — let the manager decide
                // whether to wipe it (truncated) or keep it for retry (size OK).
                sdk.modelManager.onModelLoadFailed()
            }
            _uiState.value = currentModelNotReadyState()
            return
        }

        // History is keyed on CHW, not session — sessionId is fresh per VM
        // (it drives EventRecorder.sessionId for telemetry bucketing) and would
        // always return an empty list. See [ChatRepositoryImpl.getRecentHistory].
        val history = chatRepo.getRecentHistory(
            chwId = sdk.currentCHWId.orEmpty(),
            limit = HISTORY_LIMIT,
        )
        val seededQuestions = loadSuggestions()
        _uiState.value = ChatUiState.Ready(
            messages = history,
            modelPresent = true,
            suggestedQuestions = seededQuestions,
        )
    }

    /**
     * Send a user message and stream the LLM response.
     * Cancels any in-progress generation before starting a new one.
     *
     * @param text User message.
     * @param moduleFamilyId Optional anchored module family — set when the user tapped a
     *   suggested question. When null, no module is anchored. The resolved value (or null)
     *   is injected into the prompt by [ChatSession.buildPrompt].
     */
    fun sendMessage(text: String, moduleFamilyId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val readyState = _uiState.value as? ChatUiState.Ready ?: return
        if (readyState.isGenerating) return

        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            val currentState = _uiState.value as? ChatUiState.Ready ?: return@launch

            // Persist user message — capture the DB-assigned ID so LazyColumn keys are unique.
            val userMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.USER,
                text = trimmed,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
            val updatedMessages = currentState.messages + userMsg
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    messages = updatedMessages,
                    isGenerating = true,
                    streamingText = "",
                    error = null,
                ) ?: it
            }

            // ── Routing ──────────────────────────────────────────────────────
            // Online (any device): backend RAG — higher quality, no local LLM needed.
            // Offline: low-end uses BM25-only; normal device uses on-device Gemma + BM25.
            val online = sdk.isNetworkAvailable()
            val route = when {
                online -> "ONLINE → backend RAG (POST /coaching/rag-query) — no on-device BM25/translation"
                sdk.isLowEndDevice -> "OFFLINE low-end → BM25-only (no LLM)"
                else -> "OFFLINE → on-device Gemma + BM25"
            }
            // The one line that tells you which pipeline actually answered. NOTE the
            // "Online" badge in the chat header is a static UI label, NOT this value —
            // only `net` below reflects real connectivity / the route taken.
            Log.i(
                TRACE_TAG,
                "──── turn ──── route=[$route] net=$online lowEnd=${sdk.isLowEndDevice} " +
                    "lang=${sdk.language} strictness=${config.chatScopeStrictness} " +
                    "modelLoaded=${inferenceRouter.isModelAvailable} " +
                    "moduleFamilyId=${moduleFamilyId ?: "∅"} q=\"${tracePreview(trimmed)}\"",
            )
            if (online) {
                val handled = handleBackendRagMessage(trimmed)
                if (handled) return@launch
                // Online, but the backend RAG call failed for an infrastructure
                // reason (network drop, non-2xx, timeout). Don't dead-end with
                // "no response available" — fall through to the on-device pipeline
                // and answer from the offline BM25 index. (A 2xx blank answer is
                // handled inside handleBackendRagMessage as final — never reaches here.)
                Log.i(TRACE_TAG, "backend-rag failed → on-device fallback (offline pipeline)")
            }
            if (sdk.isLowEndDevice) {
                handleLowEndMessage(trimmed)
                return@launch
            }
            handleLocalGemmaMessage(trimmed, moduleFamilyId, currentState)
        }
    }

    /**
     * Snapshot of `ConnectivityManager` state at telemetry-emission time. Mirrors
     * the canonical values used by [sync.SyncApi.recordSyncAttempt] so the
     * dashboard sees a consistent vocabulary across event families.
     */
    private fun currentNetworkState(): String =
        if (sdk.isNetworkAvailable()) "online" else "offline"

    /**
     * Build a [ChatUiState.ModelNotReady] seeded from the *current* [ModelState]
     * snapshot — closes the race where a chat fragment opens mid-download and
     * the StateFlow's first emission lands while `_uiState` is still [Loading],
     * leaving the UI showing a "Download" button while a worker is actually in
     * flight. Reading the state at the same moment we transition to ModelNotReady
     * guarantees the very first frame reflects reality.
     */
    private fun currentModelNotReadyState(): ChatUiState.ModelNotReady {
        return when (val s = sdk.modelManager.state.value) {
            is ModelState.Downloading -> ChatUiState.ModelNotReady(
                isDownloading = true,
                isPaused = false,
                downloadProgress = s.progressPercent,
                downloadBytesDownloaded = s.bytesDownloaded,
                downloadTotalBytes = s.totalBytes,
            )
            is ModelState.Paused -> ChatUiState.ModelNotReady(
                isDownloading = false,
                isPaused = true,
                downloadProgress = s.progressPercent,
            )
            is ModelState.DownloadFailed -> ChatUiState.ModelNotReady(
                isDownloading = false,
                isPaused = false,
                downloadProgress = -1,
            )
            else -> ChatUiState.ModelNotReady()
        }
    }

    /**
     * Serve a canned refusal message (chat_plan.md §B4 L1/L2/L4 paths). Persists an
     * assistant ChatMessage with the refusal copy, stamps `meta.outcome` so the
     * downstream TTS layer (Phase 6) can choose a distinctive voice, and emits one
     * IT-help telemetry row with the refusal detail in `payload_json`.
     */
    private suspend fun serveRefusal(
        refusal: ChatRefusal,
        groundedFrom: List<String>,
        topScore: Float?,
        validatorReason: String? = null,
    ) {
        Log.i(
            TRACE_TAG,
            "OUTCOME=REFUSAL key=${refusal.outcomeKey} topScore=$topScore " +
                "groundedFrom=$groundedFrom reason=${validatorReason ?: "∅"}",
        )
        // Use the SDK-locale-wrapped context so the refusal copy follows
        // `MicroCoachingSDK.language` regardless of the host app's device locale —
        // SPICE running in English would otherwise resolve every refusal through
        // its own `Resources` and emit English text inside a Bangla-mode chat.
        val ctx = SdkLocaleHelper.wrap(
            getApplication<android.app.Application>(),
            sdk.language,
        )
        val message = refusal.message(ctx)
        val assistantMsg = ChatMessage(
            sessionId = session.sessionId,
            role = ChatRole.ASSISTANT,
            text = message,
            source = MessageSource.LOCAL_MODEL,
            meta = ChatMessageMeta(outcome = refusal.outcomeKey, groundedFrom = groundedFrom),
        ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
        _uiState.update {
            (it as? ChatUiState.Ready)?.copy(
                messages = (it as ChatUiState.Ready).messages + assistantMsg,
                isGenerating = false,
                streamingText = "",
            ) ?: it
        }
        eventRecorder.recordDigitalHelpUsed(
            inferenceMode = "edge",
            validatorStatus = "fail",
            fallbackUsed = false,
            networkState = currentNetworkState(),
            payloadJson = buildRefusalPayload(
                outcome = refusal.outcomeKey,
                topScore = topScore,
                chunkIds = groundedFrom,
                validatorReason = validatorReason,
            ),
        )
    }

    /**
     * Serve the BM25-selected clinician content when the model's own answer is
     * rejected by a post-stream gate (the groundedness floor or the L4 validator).
     * BM25 already surfaced relevant cards, so the CHW gets the authoritative
     * answer instead of an "I don't have this" refusal. Order matters for tone:
     *   1) a linked quiz EXPLANATION (concise, already answer-shaped) — far better
     *      than a long third-person card body; served in the CHW's language directly.
     *   2) else the retrieved CARD body, clipped to a complete sentence so it never
     *      ends mid-sentence.
     *   3) else (no usable text at all) an honest Unsafe refusal.
     * Shared by the L3c groundedness gate and the L4 validator.
     */
    private suspend fun serveGroundingFallbackOrRefuse(
        grounding: List<GroundingChunk>,
        isBangla: Boolean,
        validatorReason: String?,
    ) {
        val fbAttribution = resolveSourceAttribution(grounding)
        val explanationChunk = grounding.firstOrNull { !explanationFor(it, isBangla).isNullOrBlank() }
        val cardFallback = grounding.firstOrNull {
            it.source == GroundingChunk.Source.CARD &&
                (!it.bodyBn.isNullOrBlank() || !it.bodyEn.isNullOrBlank())
        }
        when {
            explanationChunk != null -> serveFallback(
                bodyBn = explanationFor(explanationChunk, isBangla).orEmpty(),
                groundedFrom = listOf(explanationChunk.chunkId),
                validatorReason = validatorReason,
                fallbackKind = "fallback_quiz_explanation",
                sourceDocuments = fbAttribution.docs,
                groundingModuleFamilyId = fbAttribution.familyId,
                groundingModuleId = fbAttribution.moduleId,
                startPage = fbAttribution.startPage,
            )
            cardFallback != null -> serveFallback(
                bodyBn = clipToCompleteSentence(resolveCardBody(cardFallback, isBangla)),
                groundedFrom = listOf(cardFallback.chunkId),
                validatorReason = validatorReason,
                fallbackKind = "fallback_card_body",
                sourceDocuments = fbAttribution.docs,
                groundingModuleFamilyId = fbAttribution.familyId,
                groundingModuleId = fbAttribution.moduleId,
                startPage = fbAttribution.startPage,
            )
            else -> serveRefusal(
                ChatRefusal.Unsafe,
                groundedFrom = grounding.map { it.chunkId },
                topScore = grounding.firstOrNull()?.score,
                validatorReason = validatorReason,
            )
        }
    }

    /**
     * Serve clinician-authored module text as the chat reply (L4 fallback).
     * Used when the validator rejects Gemma's free-form answer but a retrieved
     * grounding chunk carries trustworthy source text. [fallbackKind] is the
     * `ChatMessageMeta.outcome` key — `fallback_quiz_explanation` for QUIZ
     * chunks, `fallback_card_body` for CARD chunks.
     */
    private suspend fun serveFallback(
        bodyBn: String,
        groundedFrom: List<String>,
        validatorReason: String?,
        fallbackKind: String = "fallback_quiz_explanation",
        sourceDocuments: List<SourceDocumentRef> = emptyList(),
        groundingModuleFamilyId: String? = null,
        groundingModuleId: String? = null,
        startPage: Int? = null,
    ) {
        // The LLM answer was rejected (or skipped on low-end) and we are serving
        // clinician-authored module text verbatim instead. Two identical questions
        // taking different branches — one served the LLM answer, one fell back here —
        // is itself a source of the "different answer each time" report.
        Log.i(
            TRACE_TAG,
            "OUTCOME=FALLBACK kind=$fallbackKind groundedFrom=$groundedFrom " +
                "reason=${validatorReason ?: "∅"} bodyLen=${bodyBn.length} " +
                "body=\"${tracePreview(bodyBn)}\"",
        )
        val assistantMsg = ChatMessage(
            sessionId = session.sessionId,
            role = ChatRole.ASSISTANT,
            text = bodyBn,
            source = MessageSource.LOCAL_MODEL,
            meta = ChatMessageMeta(outcome = fallbackKind, groundedFrom = groundedFrom),
            sourceDocuments = sourceDocuments,
            groundingModuleFamilyId = groundingModuleFamilyId,
            startPage = startPage,
        ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
        _uiState.update {
            (it as? ChatUiState.Ready)?.copy(
                messages = (it as ChatUiState.Ready).messages + assistantMsg,
                isGenerating = false,
                streamingText = "",
            ) ?: it
        }
        eventRecorder.recordDigitalHelpUsed(
            inferenceMode = "edge",
            validatorStatus = "fail",
            fallbackUsed = true,
            networkState = currentNetworkState(),
            // A clinician-authored module body IS the served response here, so
            // module_id is the module that formed it (Events-Modelling v1.2).
            moduleId = groundingModuleId,
            payloadJson = buildRefusalPayload(
                outcome = fallbackKind,
                topScore = null,
                chunkIds = groundedFrom,
                validatorReason = validatorReason,
            ),
        )
    }

    /**
     * Returns the suggestions to display above the chat input.
     *
     * Currently serves [ChatSuggestionDefaults.all] — a curated, open-question
     * list in EN + BN. To switch back to module-sourced dynamic suggestions
     * (quiz questions sampled from the cached corpus), change the body to:
     *   return suggestionsRepository.nextBatch()
     */
    private suspend fun loadSuggestions(): List<SuggestedQuestion> =
        ChatSuggestionDefaults.all

    /**
     * Backend RAG path — used when the device is online (any device class).
     * Sends [trimmed] to `POST /coaching/rag-query`, maps the response to a
     * [ChatMessage], persists it, and updates [_uiState]. No local scope-gate
     * or LLM; the backend handles retrieval and generation.
     *
     * @return `true` when the turn was **handled** here — either a grounded
     *   answer was served, or the backend returned a 2xx with a deliberately
     *   blank answer (a content decision: shown as
     *   [R.string.chat_error_no_response_available], no fallback). Returns
     *   `false` on an **infrastructure** failure (network error / thrown
     *   exception, non-2xx, empty body) **without** setting an error, so the
     *   caller can fall back to the on-device pipeline — the device can still
     *   answer from the offline BM25 index. Connectivity is never the excuse.
     */
    private suspend fun handleBackendRagMessage(trimmed: String): Boolean {
        val responseLanguage = if (sdk.language == Language.BANGLA) "bn" else "en"
        Log.i(
            TRACE_TAG,
            "backend-rag → request lang=$responseLanguage moduleLimit=5 q=\"${tracePreview(trimmed)}\"",
        )
        try {
            val response = sdk.apiService.ragQuery(
                RagQueryRequest(
                    question = trimmed,
                    moduleLimit = 5,
                    responseLanguage = responseLanguage,
                ),
            )
            val body = response.body()
            // Infrastructure failure (non-2xx, empty body) — NOT a content
            // decision. Return false WITHOUT setting an error so sendMessage falls
            // back to on-device retrieval instead of dead-ending.
            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "handleBackendRagMessage: non-success/empty body — HTTP ${response.code()} → on-device fallback")
                Log.i(
                    TRACE_TAG,
                    "backend-rag ← FAIL http=${response.code()} success=${response.isSuccessful} " +
                        "bodyNull=${body == null} → fallback",
                )
                return false
            }
            // 2xx with a deliberately blank answer: the backend retrieved nothing
            // groundable and chose to say nothing. Final — show the message and do
            // NOT fall back; a local BM25 guess would undercut that decision.
            if (body.answer.isBlank()) {
                Log.i(TRACE_TAG, "backend-rag ← 2xx blank answer — final (no fallback)")
                _uiState.update {
                    (it as? ChatUiState.Ready)?.copy(
                        isGenerating = false,
                        error = localizedString(R.string.chat_error_no_response_available),
                    ) ?: it
                }
                return true
            }

            // The backend re-embeds + retrieves on every call. Logging the retrieved
            // set (module + cosine_distance) and the cited ids across identical
            // questions tells you whether inconsistency is a *retrieval* problem
            // (different modules surface each time) or a *generation* problem (same
            // modules, different answer → server LLM sampling).
            Log.i(
                TRACE_TAG,
                "backend-rag ← HTTP ${response.code()} model=${body.model} " +
                    "answerLen=${body.answer.length} citedModuleIds=${body.citedModuleIds} " +
                    "answer=\"${tracePreview(body.answer)}\"",
            )
            body.retrievedModules.forEachIndexed { i, m ->
                Log.i(
                    TRACE_TAG,
                    "  retrieved[$i] module=${m.moduleId} cosineDist=${m.cosineDistance} " +
                        "domain=${m.domain} title=\"${tracePreview(m.titleEn ?: m.titleBn, 60)}\"",
                )
            }

            val sourceDocs = body.sourceDocuments.map { doc ->
                SourceDocumentRef(
                    id = doc.sourceDocumentId,
                    title = doc.title,
                    originalFilename = doc.originalFilename,
                )
            }

            // First positive page from source_pages, then from page_numbers fallback.
            val startPage = body.sourceDocuments.firstOrNull()?.let { doc ->
                doc.sourcePages.firstOrNull { it.pageNumber > 0 }?.pageNumber
                    ?: doc.pageNumbers.firstOrNull { it > 0 }
            }

            // cited_module_ids are version UUIDs — look up the family UUID in local DB for
            // the chip-label fallback. Null if the module hasn't been synced yet.
            val familyId = body.citedModuleIds.firstOrNull()?.let { versionId ->
                runCatching { sdk.database.moduleDao().getById(versionId)?.moduleFamilyId }.getOrNull()
            }

            val assistantMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.ASSISTANT,
                text = body.answer,
                source = MessageSource.RAG_API,
                meta = ChatMessageMeta(
                    outcome = "served_grounded",
                    groundedFrom = body.citedModuleIds,
                ),
                sourceDocuments = sourceDocs,
                groundingModuleFamilyId = familyId,
                startPage = startPage,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }

            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    messages = (it as ChatUiState.Ready).messages + assistantMsg,
                    isGenerating = false,
                    streamingText = "",
                ) ?: it
            }

            eventRecorder.recordDigitalHelpUsed(
                inferenceMode = "online",
                validatorStatus = "pass",
                fallbackUsed = false,
                networkState = currentNetworkState(),
                // Events-Modelling v1.2: the module that formed the response is
                // the top cited module version straight from the RAG response.
                moduleId = body.citedModuleIds.firstOrNull(),
            )
            return true
        } catch (e: Exception) {
            // Network drop mid-request, timeout, deserialization — infrastructure,
            // not content. Don't set an error; fall back to on-device retrieval.
            Log.w(TAG, "handleBackendRagMessage failed: ${e.message}", e)
            Log.i(TRACE_TAG, "backend-rag ← EXCEPTION ${e.javaClass.simpleName} → on-device fallback")
            return false
        }
    }

    /**
     * On-device Gemma path — used when offline on a capable (≥ 3 GB RAM) device.
     * Runs the full L0→L5 pipeline: deny-list, scope gate, BM25 retrieval,
     * Gemma generation, L3/L4 validators, and BN↔EN translation round-trip.
     *
     * [currentState] is the [ChatUiState.Ready] snapshot captured at the start of
     * [sendMessage] (before the user message was appended) so the prompt history
     * excludes the current turn — the current message is passed separately to
     * [ChatSession.buildPrompt].
     */
    private suspend fun handleLocalGemmaMessage(
        trimmed: String,
        moduleFamilyId: String?,
        currentState: ChatUiState.Ready,
    ) {
        // On-device Gemma. When the model isn't loaded — e.g. an always-online
        // device that never downloaded it, now falling back from a failed backend
        // call — degrade to the BM25-only path rather than erroring. Clinician-
        // authored content is still served from the offline index.
        val llm = inferenceRouter.activeService ?: run {
            Log.i(TRACE_TAG, "Gemma model unavailable → degrading to BM25-only")
            handleLowEndMessage(trimmed)
            return
        }

        // BN→EN→AI→EN→BN round-trip: Gemma 3 1B is English-dominant, so when
        // the SDK is configured for Bangla we pre-translate the user's question
        // to English before prompting (the response is post-translated below).
        // This keeps the UI bubble showing the original Bangla input but lets
        // the model actually understand the question.
        // Track BN↔EN passthrough across both pivots for F5 telemetry.
        var translationPassthrough = false
        val englishCurrent = if (sdk.language == Language.BANGLA) {
            val result = sdk.translator.translateBnToEnResult(trimmed)
            if (!result.translated) {
                translationPassthrough = true
                Log.w(TAG, "BN→EN input passthrough — untranslated Bangla sent to the LLM")
            }
            // translated=false means MLKit fell back to passthrough (pack not ready /
            // translate threw) → the LLM receives raw Bangla. altered=true means the
            // text actually changed, i.e. the model saw something different from what
            // the CHW typed — inspect `out` when an answer looks off-topic.
            Log.i(
                TRACE_TAG,
                "BN→EN translate: translated=${result.translated} altered=${result.text != trimmed} " +
                    "in=\"${tracePreview(trimmed)}\" out=\"${tracePreview(result.text)}\"",
            )
            result.text
        } else {
            Log.i(
                TRACE_TAG,
                "BN→EN translate: SKIPPED (SDK language=${sdk.language}) — query goes to the LLM verbatim",
            )
            trimmed
        }

        // L0 — Hard deny-list. Catches obvious out-of-scope topics (coding,
        // sports, weather, entertainment, etc.) before any LLM call. Applies
        // in BOTH Strict and ExtendedClinical modes because the 1B model has
        // proven unreliable at refusing these on its own even with a tight
        // open-scope prompt. Cheap (substring match against ~50 terms).
        val scopeClassifier = ScopeClassifier.buildFrom(sdk.morningModules.value)
        if (scopeClassifier.isOutOfScope(trimmed) || scopeClassifier.isOutOfScope(englishCurrent)) {
            Log.d(TAG, "L0 deny-list: hard out-of-scope match — refusing without LLM call")
            serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
            return
        }

        // L1 — Scope allow-list (chat_plan.md §B4). Advisory only: a keyword miss no
        // longer hard-refuses before retrieval — that pre-search gate caused
        // false refusals on legitimate clinical questions the gazetteer hadn't seen.
        // The real backstops are L2 retrieval (no grounding → honest refusal below)
        // and the OffTopicGuard clinical-overlap check. L0 (deny-list) still blocks
        // obvious out-of-scope topics hard, before any of this.
        val l1InScope = scopeClassifier.isInScope(trimmed) || scopeClassifier.isInScope(englishCurrent)
        if (!l1InScope) {
            Log.d(TAG, "L1 advisory: scope keyword miss — deferring to retrieval + OffTopicGuard")
        }

        // L2 — Retrieval threshold gate. Score like-for-like: the user's-language
        // query against that language's index, plus (only when we translated for
        // the LLM) the English query against the English index. BM25 scores are not
        // calibrated across languages, so we bias toward the user's actual language:
        // a translated-English hit only overrides a native hit when it wins by a
        // clear margin, or when the native search found nothing.
        val knowledgeIndex = sdk.chatKnowledgeIndex.value
        val nativeLang =
            if (sdk.language == Language.BANGLA) ModuleKnowledgeIndex.Lang.BN
            else ModuleKnowledgeIndex.Lang.EN
        // k=3: the verified "Low BP 90/60" failure had the clinically-correct card
        // at rank 3 — k=2 cut it before the LLM ever saw it. Three reference cards
        // fit comfortably in the prompt budget now that the session window is 1536.
        val bm25Threshold = config.chatTuning.bm25ScoreThreshold
        val nativeHits = knowledgeIndex.search(
            trimmed, k = GROUNDING_K, scoreThreshold = bm25Threshold, language = nativeLang,
        )
        val translatedHits = if (englishCurrent != trimmed) {
            knowledgeIndex.search(
                englishCurrent, k = GROUNDING_K, scoreThreshold = bm25Threshold,
                language = ModuleKnowledgeIndex.Lang.EN,
            )
        } else emptyList()
        val nativeTop = nativeHits.firstOrNull()?.score ?: 0f
        val translatedTop = translatedHits.firstOrNull()?.score ?: 0f
        val grounding = when {
            nativeHits.isEmpty() -> translatedHits
            translatedTop > nativeTop * CROSS_LANGUAGE_OVERRIDE_MARGIN -> translatedHits
            else -> nativeHits
        }

        // BM25 is deterministic for a given query+corpus, so identical questions
        // should produce identical candidate sets here. If the served answers differ
        // anyway, the divergence is downstream (LLM sampling / validator branch), not
        // retrieval. Watch for an on-topic question grounding to an off-topic module
        // (e.g. a "low BP / hypotension" question matching a "hypertension" card — the
        // tokens overlap but the clinical meaning is opposite).
        Log.i(
            TRACE_TAG,
            "BM25 native[$nativeLang] hits=${nativeHits.size} topScore=%.2f".format(Locale.US, nativeTop),
        )
        nativeHits.forEachIndexed { i, h -> Log.i(TRACE_TAG, traceChunk("  native", i, h)) }
        if (englishCurrent != trimmed) {
            Log.i(
                TRACE_TAG,
                "BM25 translated[EN] hits=${translatedHits.size} topScore=%.2f".format(Locale.US, translatedTop),
            )
            translatedHits.forEachIndexed { i, h -> Log.i(TRACE_TAG, traceChunk("  translated", i, h)) }
        }
        val chosenLabel = when {
            nativeHits.isEmpty() -> "translated (native empty)"
            grounding === translatedHits -> "translated (beat native by >${CROSS_LANGUAGE_OVERRIDE_MARGIN}×)"
            else -> "native"
        }
        Log.i(TRACE_TAG, "BM25 grounding chosen=$chosenLabel size=${grounding.size}")

        // Phase-0 garbage guard. Refuses only when the top hit shares ZERO clinical
        // tokens with the query — the "BM25 latched onto a stop-word" failure that
        // the prior synonym-map bug also enabled (e.g. "low BP 90/60" returning a
        // diarrhoea card). The tuned refusal floor (Phase 2) replaces this once the
        // benchmark gives us in- vs out-of-corpus score distributions to calibrate.
        val guardQuery = if (englishCurrent != trimmed) "$trimmed $englishCurrent" else trimmed
        if (OffTopicGuard.isClearlyUnanswerable(
                query = guardQuery,
                topHit = grounding.firstOrNull(),
                clinicalTerms = scopeClassifier.scopeTerms(),
            )
        ) {
            Log.i(TRACE_TAG, "Phase-0 garbage guard: zero clinical-token overlap with top hit — refusing")
            serveRefusal(
                ChatRefusal.NoGround,
                groundedFrom = emptyList(),
                topScore = grounding.firstOrNull()?.score,
            )
            return
        }

        // Routing — honest-refusal policy: we never let the 1B model answer
        // ungrounded clinical content. No grounding → honest refusal. With grounding
        // present the model answers from it (and may answer the part it covers — see
        // the hardened prompt). The open-scope general-knowledge path was removed.
        if (grounding.isEmpty()) {
            serveRefusal(ChatRefusal.NoGround, groundedFrom = emptyList(), topScore = null)
            return
        }
        val promptMode = PromptMode.Grounded
        Log.d(TAG, "promptMode=$promptMode, grounding=${grounding.size} chunks, topScore=${grounding.firstOrNull()?.score}")

        // Build prompt — pass previous messages only; currentMessage is appended by buildPrompt.
        // Force the English system-prompt variant when round-tripping so all model-facing
        // instructions are in the language the model handles best.
        val promptLanguage = if (sdk.language == Language.BANGLA) "en-US" else sdk.language.bcp47
        val scopeTermsForPrompt = if (promptMode == PromptMode.OpenScope) {
            (CLINICAL_SCOPE_FLOOR + scopeClassifier.scopeTerms()).distinct()
        } else emptyList()
        // History is deliberately NOT replayed to the model — every turn is
        // independent. Verified 2026-06-11: with prior exchanges in the prompt,
        // the model imitates its own earlier free-form answers (which were
        // grounded on *different* references) and answers from pre-training
        // instead of the current reference block — the breastfeeding turn scored
        // groundedness 0.14 with history vs 0.50 without, identical retrieval.
        // The conversation stays visible in the UI; this only affects model
        // context. When follow-up support is needed, re-introduce history as a
        // standalone-question rewrite (use the last topic to rewrite the query
        // BEFORE retrieval) rather than verbatim turn replay.
        val prompt = session.buildPrompt(
            currentMessage = englishCurrent,
            history = emptyList(),
            language = promptLanguage,
            grounding = grounding,
            mode = promptMode,
            scopeTerms = scopeTermsForPrompt,
        )
        Log.d(TAG, "Prompt: $prompt")

        // OTel span
        val modelName = config.modelPath.substringAfterLast("/").ifBlank { "gemma.task" }
        val engineName = "mediapipe"
        val inferenceSpan = telemetry.startInferenceStream(
            modelName = modelName,
            engineName = engineName,
            sessionId = session.sessionId,
        )

        val startMs = System.currentTimeMillis()
        val responseBuilder = StringBuilder()

        // Guard against endInferenceStream being called twice: once from .catch (on error)
        // and once from the success path below. The span must be ended exactly once.
        var inferenceSpanEnded = false

        val isBangla = sdk.language == Language.BANGLA

        llm.generateResponseStream(prompt)
            .catch { cause ->
                val errMsg = cause.message ?: "Generation failed"
                inferenceSpanEnded = true
                telemetry.endInferenceStream(
                    span = inferenceSpan,
                    estimatedInputTokens = (prompt.length / 4).toLong(),
                    estimatedOutputTokens = (responseBuilder.length / 4).toLong(),
                    latencyMs = System.currentTimeMillis() - startMs,
                    success = false,
                    errorMessage = errMsg,
                )
                // IT-help telemetry — inference threw. validator_status is left null
                // because no output reached the validator. payload_json carries the
                // error message for debug aggregation.
                eventRecorder.recordDigitalHelpUsed(
                    inferenceMode = "edge",
                    validatorStatus = null,
                    fallbackUsed = false,
                    networkState = currentNetworkState(),
                    payloadJson = buildJsonObject { put("error", errMsg) }.toString(),
                )
                _uiState.update {
                    (it as? ChatUiState.Ready)?.copy(isGenerating = false, error = errMsg) ?: it
                }
            }
            .takeWhile {
                // Stream cap — every chat mode mandates a 2–4 sentence answer. A
                // stream blowing far past that is the model ignoring the prompt
                // and free-styling from pre-training (verified 2026-06-11: a 28 s,
                // 1.3 k-char low-BP essay that the groundedness gate then refused
                // anyway). Cancelling early reaches the same outcome in a fraction
                // of the latency; the partial text still runs the normal gates.
                val streamCap = config.chatTuning.streamCapChars
                val withinCap = responseBuilder.length < streamCap
                if (!withinCap) {
                    Log.i(
                        TRACE_TAG,
                        "stream-cap: aborted generation at ${responseBuilder.length} chars " +
                            "(cap=$streamCap — model ignored the 2–4 sentence rule)",
                    )
                }
                withinCap
            }
            .collect { token ->
                responseBuilder.append(token)
                // Tokens are buffered, never streamed raw to the UI. The raw
                // English still has to pass the validation gates (groundedness,
                // question-echo, L4 block-list), any of which can replace it with
                // a refusal or card fallback — streaming it live means the CHW
                // watches an answer appear and then vanish (verified UX failure).
                // StreamingBubble shows "●●●" while we collect; the validated
                // text is typewritten afterwards, in every language.
            }

        val latencyMs = System.currentTimeMillis() - startMs
        // Strip <end_of_turn> and anything after it — Gemma appends it after its response.
        val untrimmedResponse = responseBuilder.toString()
            .substringBefore("<end_of_turn>")
            .trim()

        // The model's raw English output, pre-validation/translation.
        // sawEndOfTurn=false means generation stopped because the SESSION token
        // window (`maxInferenceTokens` = input + output, see MicroCoachingConfig)
        // was exhausted, not because the model finished — the reply is cut
        // mid-sentence. We salvage it by trimming back to the last complete
        // sentence ('.', '!', '?', or the Bangla danda '।') so the CHW never
        // sees a dangling fragment like "Pain can be alleviated by".
        val sawEndOfTurn = responseBuilder.contains("<end_of_turn>")
        val rawResponse =
            if (sawEndOfTurn) untrimmedResponse else trimToCompleteSentence(untrimmedResponse)
        Log.i(
            TRACE_TAG,
            "LLM raw: len=${untrimmedResponse.length} latencyMs=$latencyMs sawEndOfTurn=$sawEndOfTurn " +
                "temp=${config.inferenceTemperature} maxTokens=${config.maxInferenceTokens} " +
                "text=\"${tracePreview(untrimmedResponse, 220)}\"",
        )
        if (rawResponse.length != untrimmedResponse.length) {
            Log.i(
                TRACE_TAG,
                "truncation-trim: dropped ${untrimmedResponse.length - rawResponse.length} trailing chars " +
                    "(window exhausted mid-sentence) kept=\"${tracePreview(rawResponse, 120)}\"",
            )
        }

        // L3 — Intercept the REFUSE_NO_GROUND sentinel before it reaches the user.
        // Treat the same as L2 (no grounding) but emit a distinct telemetry signal.
        if (outputValidator.isNoGroundSentinel(rawResponse)) {
            serveRefusal(
                ChatRefusal.NoGround,
                groundedFrom = grounding.map { it.chunkId },
                topScore = grounding.firstOrNull()?.score,
            )
            return
        }

        // L3b — Open-scope sentinel: the LLM judged the question off-topic. Serve
        // the same canned scope-refusal copy used by L1 in Strict mode.
        if (promptMode == PromptMode.OpenScope && outputValidator.isOutOfScopeSentinel(rawResponse)) {
            serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
            return
        }

        // L3c — Groundedness gate (grounded mode only). The [[REFUSE_NO_GROUND]]
        // sentinel relies on the model NOTICING the references don't cover the
        // question; when the references are merely adjacent (newborn-warmth cards
        // for a breast-engorgement question — the verified failure) a 1B model
        // answers fluently from pre-training instead. Reference vocabulary
        // survives honest paraphrase, so a near-zero content-word overlap means
        // the answer did not come from the references. Refuse rather than serve
        // confident pre-training content as if it were clinician-reviewed. Score
        // is traced on every grounded turn so the floor can be tuned from logs.
        if (promptMode == PromptMode.Grounded && rawResponse.isNotBlank()) {
            val tuning = config.chatTuning
            val topScore = grounding.firstOrNull()?.score
            val groundedness = outputValidator.groundednessScore(rawResponse, grounding)
            // Two-tier groundedness gate — always on, leniency scaled by retrieval
            // confidence. Reference vocabulary survives honest paraphrase, so a
            // near-zero content-word overlap means the answer did not come from the
            // references (the model free-styled from pre-training). When BM25 found a
            // strong match (top score ≥ strongRetrievalScore) we trust the right
            // references are present and apply the lenient floor so a paraphrase that
            // doesn't literally match still serves; a weak match uses the stricter
            // floor. Both floors are tunable via ChatTuning. Score is traced on every
            // grounded turn so the floors can be tuned from logs.
            // PHASE3_RETIRE: delete score-based bypass when OffTopicGuard is out-of-corpus-only.
            val strongRetrieval = (topScore ?: 0f) >= tuning.strongRetrievalScore
            val floor =
                if (strongRetrieval) tuning.strongRetrievalGroundednessFloor
                else tuning.groundednessFloor
            Log.i(
                TRACE_TAG,
                "groundedness=%.2f floor=%.2f strongRetrieval=%b topScore=%.2f grounded=%s".format(
                    Locale.US, groundedness, floor, strongRetrieval,
                    topScore ?: 0f, grounding.map { it.chunkId },
                ),
            )
            if (groundedness < floor) {
                // The model's own answer isn't grounded enough — but BM25 DID select
                // relevant cards, so instead of telling the CHW "I don't have this"
                // we serve the clinician-authored card content (the BM25 result) so
                // they still get the full, authoritative answer. Same graceful
                // fallback the L4 validator uses.
                Log.i(
                    TRACE_TAG,
                    "groundedness %.2f < floor %.2f → serving BM25 card fallback"
                        .format(Locale.US, groundedness, floor),
                )
                serveGroundingFallbackOrRefuse(
                    grounding = grounding,
                    isBangla = isBangla,
                    validatorReason = "groundedness:%.2f".format(Locale.US, groundedness),
                )
                return
            }
        }

        // L4 — Output validator. Reject responses that introduce drugs or dosages
        // not present in the retrieved candidates, or that exceed the length cap.
        // In the open-scope path there are no candidates by definition, so drop the
        // drug/dosage block-list — the safety caveat in the system prompt does the
        // work the block-list was meant to do.
        val validation = outputValidator.validateChatResponse(
            rawResponse,
            grounding,
            maxWords = config.chatTuning.maxResponseWords,
            allowFreeText = (promptMode == PromptMode.OpenScope),
            // English text as it appeared in the prompt — covers Bangla mode too,
            // where the question is pre-translated before reaching the LLM.
            userQuestion = englishCurrent,
            enableDrugGuard = config.chatTuning.enableDrugGuard,
            enableDosageGuard = config.chatTuning.enableDosageGuard,
        )
        if (!validation.isValid) {
            Log.w(TAG, "L4 validator rejected: ${validation.failureReason}")
            serveGroundingFallbackOrRefuse(
                grounding = grounding,
                isBangla = isBangla,
                validatorReason = validation.failureReason,
            )
            return
        }

        val responseText = if (isBangla && rawResponse.isNotBlank()) {
            val outResult = sdk.translator.translateEnToBnResult(rawResponse)
            if (!outResult.translated) {
                translationPassthrough = true
                Log.w(TAG, "EN→BN output passthrough — untranslated English shown to the CHW")
            }
            val bn = outResult.text.ifBlank { rawResponse }
            Log.d(TAG, "Post-translated EN→BN (${rawResponse.length} → ${bn.length} chars)")
            // L5 — translation fidelity guard. If MLKit hands back an empty string
            // or a response that's still > 30% Latin chars, prefix the EN body with
            // the "(translation unavailable)" string so the CHW gets *something*
            // actionable rather than a blank Bangla bubble.
            val latinShare = bn.count { it.code in 0x41..0x7A }.toFloat() / bn.length.coerceAtLeast(1).toFloat()
            if (latinShare > 0.3f) {
                Log.w(TAG, "L5 translation degraded — latinShare=$latinShare; falling back to EN+prefix")
                localizedString(R.string.chat_translation_degraded)
                    .format(rawResponse)
            } else {
                bn
            }
        } else rawResponse

        // No typewriter reveal: the assistant bubble renders markdown (**bold**,
        // bullet/numbered lists), and progressively revealing half-typed markdown
        // reflows and flashes raw markers. Instead the StreamingBubble shows only
        // the "●●●" typing dots (streamingText stays blank) for the whole
        // generation, then snaps to the fully-rendered message committed below.

        if (!inferenceSpanEnded) {
            telemetry.endInferenceStream(
                span = inferenceSpan,
                estimatedInputTokens = (prompt.length / 4).toLong(),
                estimatedOutputTokens = (responseText.length / 4).toLong(),
                latencyMs = latencyMs,
                success = responseText.isNotBlank(),
            )
        }
        telemetry.chatMessageCounter.add(1)

        if (responseText.isNotBlank()) {
            val happyOutcome = if (promptMode == PromptMode.OpenScope) "served_open_scope" else "served_grounded"
            Log.i(
                TRACE_TAG,
                "OUTCOME=$happyOutcome served len=${responseText.length} " +
                    "grounded=${grounding.map { it.chunkId }} text=\"${tracePreview(responseText)}\"",
            )
            val attribution = resolveSourceAttribution(grounding)
            val assistantMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.ASSISTANT,
                text = responseText,
                traceId = inferenceSpan.spanContext.traceId,
                source = MessageSource.LOCAL_MODEL,
                meta = ChatMessageMeta(
                    outcome = happyOutcome,
                    groundedFrom = grounding.map { it.chunkId },
                ),
                sourceDocuments = attribution.docs,
                groundingModuleFamilyId = attribution.familyId,
                startPage = attribution.startPage,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    messages = (it as ChatUiState.Ready).messages + assistantMsg,
                    isGenerating = false,
                    streamingText = "",
                ) ?: it
            }
            // IT-help telemetry — happy path. `served_grounded` when retrieval surfaced
            // the answer; `served_open_scope` when the LLM judged scope itself.
            val topScore = grounding.firstOrNull()?.score
            val chunkIds = grounding.map { it.chunkId }
            eventRecorder.recordDigitalHelpUsed(
                inferenceMode = "edge",
                validatorStatus = "pass",
                fallbackUsed = false,
                networkState = currentNetworkState(),
                // Events-Modelling v1.2: the dominant grounding chunk's module
                // version is what grounded this served answer.
                moduleId = attribution.moduleId,
                payloadJson = buildRefusalPayload(
                    outcome = happyOutcome,
                    topScore = topScore,
                    chunkIds = chunkIds,
                    validatorReason = null,
                    translationPassthrough = if (isBangla) translationPassthrough else null,
                ),
            )
        } else {
            Log.i(TRACE_TAG, "OUTCOME=empty_response — LLM/translation produced no text; nothing served")
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    isGenerating = false,
                    streamingText = "",
                    error = localizedString(R.string.chat_error_no_response_generated),
                ) ?: it
            }
            // IT-help telemetry — empty-response branch. Tracked separately so we can
            // distinguish silent failures from thrown ones in the dashboard.
            eventRecorder.recordDigitalHelpUsed(
                inferenceMode = "edge",
                validatorStatus = "fail",
                fallbackUsed = false,
                networkState = currentNetworkState(),
                payloadJson = "{\"reason\":\"empty_response\"}",
            )
        }
    }

    /**
     * Retrieval-only path used on low-end (< 3 GB RAM) devices. Mirrors the
     * scope filters from the capable-device path (L0 deny-list, L1 allow-list
     * in Strict mode) but skips the LLM, the L3 sentinels, and the L4
     * validator entirely — the BM25 result is served verbatim.
     */
    private suspend fun handleLowEndMessage(trimmed: String) {
        val isBangla = sdk.language == Language.BANGLA
        val scopeClassifier = ScopeClassifier.buildFrom(sdk.morningModules.value)

        // L0 — hard deny-list. Cheap; runs on the original (untranslated) text.
        if (scopeClassifier.isOutOfScope(trimmed)) {
            Log.d(TAG, "Low-end L0 deny-list match — refusing without retrieval")
            serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
            return
        }

        // L1 — allow-list (Strict only; Extended skips and lets BM25 decide).
        val strictMode = config.chatScopeStrictness == ChatScopeStrictness.Strict
        if (strictMode && !scopeClassifier.isInScope(trimmed)) {
            serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
            return
        }

        // L2 — BM25 retrieval. Single search on the input query against the index
        // for the CHW's configured language; no BN→EN pre-translation since we
        // never feed an LLM here.
        val knowledgeIndex = sdk.chatKnowledgeIndex.value
        val searchLang =
            if (isBangla) ModuleKnowledgeIndex.Lang.BN else ModuleKnowledgeIndex.Lang.EN
        val grounding = knowledgeIndex.search(
            trimmed, k = 2, scoreThreshold = config.chatTuning.bm25ScoreThreshold, language = searchLang,
        )
        Log.i(TRACE_TAG, "BM25 low-end[$searchLang] hits=${grounding.size}")
        grounding.forEachIndexed { i, h -> Log.i(TRACE_TAG, traceChunk("  hit", i, h)) }
        val top = grounding.firstOrNull {
            !it.bodyBn.isNullOrBlank() || !it.bodyEn.isNullOrBlank()
        }
        if (top == null) {
            Log.d(TAG, "Low-end retrieval miss — no usable grounding chunk")
            serveRefusal(ChatRefusal.NoGround, groundedFrom = emptyList(), topScore = null)
            return
        }
        // Phase-0 garbage guard. On the low-end path the retrieved chunk IS the
        // user-visible answer, so a wrong-card miss is louder than on the Gemma
        // path. Refuses only when query and top hit share NO clinical tokens.
        if (OffTopicGuard.isClearlyUnanswerable(
                query = trimmed,
                topHit = top,
                clinicalTerms = scopeClassifier.scopeTerms(),
            )
        ) {
            Log.i(TRACE_TAG, "Phase-0 garbage guard (low-end): zero clinical-token overlap — refusing")
            serveRefusal(ChatRefusal.NoGround, groundedFrom = emptyList(), topScore = top.score)
            return
        }
        Log.d(TAG, "Low-end serving retrieval-only — chunkId=${top.chunkId} score=${top.score}")
        val attribution = resolveSourceAttribution(listOf(top))
        // Prefer the concise linked quiz explanation; else the card body, clipped so
        // it never ends mid-sentence. Both are clinician-authored — safe to serve
        // verbatim on the LLM-less low-end path.
        val explanation = explanationFor(top, isBangla)
        serveFallback(
            bodyBn = explanation ?: clipToCompleteSentence(resolveCardBody(top, isBangla)),
            groundedFrom = listOf(top.chunkId),
            validatorReason = null,
            fallbackKind = if (explanation != null) "fallback_quiz_explanation" else "served_retrieval_only",
            sourceDocuments = attribution.docs,
            groundingModuleFamilyId = attribution.familyId,
            groundingModuleId = attribution.moduleId,
            startPage = attribution.startPage,
        )
    }

    /**
     * Resolve which side of a CARD chunk to surface as the L4 fallback message.
     * In Bangla mode prefer `bodyBn` (no translator round-trip needed); fall
     * back to translating `bodyEn` when only English is present. In English
     * mode the order is reversed.
     */
    private suspend fun resolveCardBody(
        chunk: GroundingChunk,
        isBangla: Boolean,
    ): String {
        val primary = if (isBangla) chunk.bodyBn else chunk.bodyEn
        if (!primary.isNullOrBlank()) return primary
        val secondary = if (isBangla) chunk.bodyEn else chunk.bodyBn
        if (secondary.isNullOrBlank()) return ""
        return if (isBangla) sdk.translator.translateEnToBn(secondary).ifBlank { secondary } else secondary
    }

    /**
     * The linked quiz explanation in the CHW's language, or null. We only use the
     * same-language side — serving an English explanation inside a Bangla chat reads
     * worse than falling through to the card body (which [resolveCardBody] can
     * translate). Real content ships both EN + BN explanations, so this usually fires.
     */
    private fun explanationFor(chunk: GroundingChunk, isBangla: Boolean): String? =
        (if (isBangla) chunk.explanationBn else chunk.explanationEn)?.takeIf { it.isNotBlank() }

    /**
     * Clip a fallback body to its last complete sentence so a served card body never
     * ends mid-sentence (the "…in the tablet. Each" failure). Falls back to the
     * trimmed raw text when the body carries no sentence terminator at all.
     */
    private fun clipToCompleteSentence(text: String): String =
        trimToCompleteSentence(text).ifBlank { text.trim() }

    /**
     * Pull the source-document attribution off the dominant (top-1) BM25 chunk
     * for this message. Returns the module's source-document refs, the
     * `moduleFamilyId` (so the chat UI can resolve a label at render time), the
     * resolved version `moduleId` (backend `module.id`, used for the
     * `digital_help_used` telemetry per Events-Modelling v1.2), and the PDF page
     * anchor (first entry of the top chunk's `sourcePages`, used by the in-app
     * document viewer to deep-link). All default to empty/null when the module
     * is no longer in cache or the grounding set is empty / the card has no
     * `source_pages`.
     */
    data class SourceAttribution(
        val docs: List<SourceDocumentRef>,
        val familyId: String?,
        val moduleId: String?,
        val startPage: Int?,
    ) {
        companion object {
            val EMPTY = SourceAttribution(docs = emptyList(), familyId = null, moduleId = null, startPage = null)
        }
    }

    private suspend fun resolveSourceAttribution(
        grounding: List<GroundingChunk>,
    ): SourceAttribution {
        val top = grounding.firstOrNull() ?: return SourceAttribution.EMPTY
        val familyId = top.moduleFamilyId
        // A null here is a legitimate cache miss; a thrown read is a real failure we
        // must not swallow silently — log it so it's observable, then degrade to the
        // family-only attribution (F11).
        val module = try {
            sdk.database.moduleDao().getByFamilyId(familyId)
        } catch (e: Exception) {
            Log.w(TAG, "Source-attribution module read failed for family=$familyId: ${e.message}")
            null
        } ?: return SourceAttribution(
            docs = emptyList(), familyId = familyId, moduleId = null, startPage = top.firstPageNumber,
        )
        // Pick the EXACT document the card cites (by source_document_id) + its page;
        // module-level first doc when the card has no page anchor. See
        // [SourceAttributionResolver] — pure so it is unit-tested without Room.
        val resolved = SourceAttributionResolver.resolve(top, module.sourceDocuments)
        return SourceAttribution(
            docs = resolved.docs,
            familyId = familyId,
            moduleId = module.moduleId,
            startPage = resolved.startPage,
        )
    }

    /**
     * Per-message cache of resolved `moduleFamilyId → title` (locale-aware) so
     * the chat surface doesn't query Room on every recomposition. Populated
     * lazily by [moduleTitleFor]. Keyed by `moduleFamilyId` rather than
     * message id so all messages sharing a module reuse the same title.
     */
    private val moduleTitleCache = mutableMapOf<String, String?>()

    /**
     * Look up the SDK-locale title for a grounding module family, or `null` if
     * the module has been removed from the cache. The lookup is cached for the
     * lifetime of the ViewModel — modules are versioned per sync and titles
     * are stable across versions of the same family.
     */
    suspend fun moduleTitleFor(familyId: String): String? {
        moduleTitleCache[familyId]?.let { return it }
        if (moduleTitleCache.containsKey(familyId)) return null
        val module = try {
            sdk.database.moduleDao().getByFamilyId(familyId)
        } catch (e: Exception) {
            Log.w(TAG, "Module-title read failed for family=$familyId: ${e.message}")
            null
        }
        val title = if (sdk.language == Language.BANGLA) {
            module?.titleBn?.takeIf { it.isNotBlank() } ?: module?.titleEn
        } else {
            module?.titleEn?.takeIf { it.isNotBlank() } ?: module?.titleBn
        }
        moduleTitleCache[familyId] = title
        return title
    }

    /**
     * Tap-handler for a source-document chip. Fetches the short-lived
     * presigned URL from the backend and launches
     * [com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity]
     * to render it. Silently no-ops when network is unavailable — the chip is
     * already greyed-out in that state by
     * [com.medtroniclabs.microcoaching.MicroCoachingSDK.networkAvailable].
     *
     * @param startPage 1-indexed PDF page to deep-link to — sourced from the
     *   BM25-matched card's `source_pages`. Null falls back to page 1 in the
     *   PDF viewer; ignored entirely for image / external formats.
     */
    fun openSourceDocument(sourceDocumentId: String, fallbackTitle: String, startPage: Int? = null) {
        if (sourceDocumentId.isBlank()) return
        val originalFilename = (_uiState.value as? ChatUiState.Ready)?.messages
            ?.flatMap { it.sourceDocuments }
            ?.firstOrNull { it.id == sourceDocumentId }
            ?.originalFilename
        viewModelScope.launch {
            DocumentPreviewActivity.start(
                context = getApplication<android.app.Application>(),
                sourceDocumentId = sourceDocumentId,
                title = fallbackTitle,
                startPage = startPage,
                originalFilename = originalFilename,
            )
        }
    }

    /**
     * Compose the small JSON blob used for `payload_json` on chatbot events.
     * Keys mirror the Events Modelling intent: refusal_outcome, top_score, chunk_ids,
     * and an optional validator_reason for L4 rejects so tuning can debug per-class.
     */
    private fun buildRefusalPayload(
        outcome: String,
        topScore: Float?,
        chunkIds: List<String>,
        validatorReason: String?,
        translationPassthrough: Boolean? = null,
    ): String = buildJsonObject {
        put("refusal_outcome", outcome)
        // Keep the 3-decimal rounding the hand-rolled version emitted.
        if (topScore != null) put("top_score", String.format(Locale.US, "%.3f", topScore).toDouble())
        putJsonArray("chunk_ids") { chunkIds.forEach { add(it) } }
        if (validatorReason != null) put("validator_reason", validatorReason)
        // F5 instrumentation: record when the BN↔EN pivot fell back to passthrough,
        // so grounded-answer fidelity of the LLM path can be measured in the field
        // (untranslated input to the model, or untranslated output to the CHW).
        if (translationPassthrough != null) put("translation_passthrough", translationPassthrough)
    }.toString()

    fun sendQuickAnswer(question: String, answer: String) {
        if (_uiState.value !is ChatUiState.Ready) return
        viewModelScope.launch {
            val userMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.USER,
                text = question,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
            val assistantMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.ASSISTANT,
                text = answer,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(messages = it.messages + userMsg + assistantMsg) ?: it
            }
        }
    }

    fun speakText(text: String) = tts.speak(text)

    fun stopSpeaking() = tts.stop()

    fun requestModelDownload() {
        if (sdk.isLowEndDevice) {
            Log.i(TAG, "requestModelDownload ignored — low-end device runs in retrieval-only mode")
            return
        }
        sdk.modelManager.triggerDownload()
        _uiState.update {
            when (it) {
                is ChatUiState.ModelNotReady -> it.copy(isDownloading = true)
                is ChatUiState.Ready -> it.copy(isModelDownloading = true, modelDownloadProgress = 0)
                else -> it
            }
        }
    }

    /** User pressed Pause on the in-flight model download. */
    fun pauseModelDownload() {
        sdk.modelManager.pauseDownload()
    }

    /** User pressed Resume on a previously-paused download. */
    fun resumeModelDownload() {
        sdk.modelManager.resumeDownload()
    }

    /**
     * User pressed Cancel on the download — wipes the partial file and resets
     * back to the initial CTA. Distinct from [pauseModelDownload] which keeps
     * the partial file so resume can pick up cheaply.
     */
    fun cancelModelDownload() {
        sdk.modelManager.cancelDownload()
    }

    /**
     * Wipe every persisted chat message for the current CHW and reset the
     * visible message list to empty. Backs the header's "Clear chat" action.
     * The DB call is hard delete; there is no undo (the user confirms via
     * AlertDialog in [com.medtroniclabs.microcoaching.ui.screens.ChatScreen]).
     */
    fun clearChatHistory() {
        viewModelScope.launch {
            chatRepo.clearChwHistory(sdk.currentCHWId.orEmpty())
            _uiState.update { current ->
                (current as? ChatUiState.Ready)?.copy(messages = emptyList()) ?: current
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetry.endChatSession(sessionSpan)
        inferenceRouter.release()
        tts.release()
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * Dedicated tag for the end-to-end chat pipeline trace. Filter the whole
         * pipeline for one device with:  `adb logcat -s ChatTrace:I`
         * (add `ChatViewModel:D ModuleKnowledgeIndex:I OnDeviceTranslator:D` for
         * the lower-level operational logs). Every turn opens with a `──── turn ────`
         * line that names the route actually taken, so it is unambiguous whether a
         * message hit the backend RAG endpoint or the on-device Gemma/BM25 pipeline.
         */
        private const val TRACE_TAG = "ChatTrace"

        /**
         * Factor by which a translated-English retrieval hit must beat the
         * user's native-language hit before it is allowed to override it (see the
         * L2 grounding gate). Biases grounding toward the language the CHW actually
         * typed, since BM25 scores are not calibrated across the two indices.
         */
        private const val CROSS_LANGUAGE_OVERRIDE_MARGIN = 1.25f

        /**
         * Grounding chunks retrieved per query and injected as reference cards.
         * 3 (was 2): the verified "Low BP 90/60" failure had the correct card at
         * rank 3. Three ~300-char references fit the prompt budget comfortably
         * within the 1536-token session window.
         */
        private const val GROUNDING_K = 3

        // The groundedness floor and the streamed-response cap are now tunable at
        // runtime via [com.medtroniclabs.microcoaching.ChatTuning] (groundednessFloor /
        // streamCapChars), set through MicroCoachingSDK.Builder.chatTuning(...). They
        // used to be the fixed constants GROUNDEDNESS_FLOOR=0.25 and STREAM_CAP_CHARS=700.

        /** Sentence terminators recognised by [trimToCompleteSentence] — EN + Bangla danda. */
        private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '।')

        /**
         * Cut a window-truncated response back to its last complete sentence.
         * Returns "" when no terminator exists (the whole output is one
         * unfinished sentence) — the L4 validator then routes the turn to the
         * clinician-authored card-body fallback instead of serving a fragment.
         */
        internal fun trimToCompleteSentence(text: String): String {
            val lastEnd = text.lastIndexOfAny(SENTENCE_TERMINATORS)
            return if (lastEnd < 0) "" else text.substring(0, lastEnd + 1).trim()
        }

        /**
         * Maximum number of restored messages on chat reopen. All messages
         * remain persisted; this just caps how many are pushed into the UI on
         * init so a long-lived install doesn't render thousands of bubbles.
         */
        private const val HISTORY_LIMIT = 50

        /**
         * Static floor of clinical domains injected into the open-scope LLM
         * prompt. Always present so the model has a stable scope reference
         * even when the indexed module corpus is empty (fresh install,
         * pre-sync). Combined at call-time with [ScopeClassifier.scopeTerms]
         * to widen scope as new modules ship.
         */
        private val CLINICAL_SCOPE_FLOOR = listOf(
            "hypertension",
            "diabetes",
            "non-communicable diseases",
            "pregnancy and maternal health",
            "eyecare",
            "child health",
            "family planning",
            "newborn care",
            "danger signs",
            "referrals",
        )
    }

    /** Factory for creating [ChatViewModel] without Hilt — SDK is DI-framework-agnostic. */
    class Factory(
        private val application: Application,
        private val patientId: String = "",
        private val systemContext: String = "",
    ) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(application, patientId, systemContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
