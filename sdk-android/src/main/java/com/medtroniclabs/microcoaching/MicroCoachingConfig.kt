package com.medtroniclabs.microcoaching

import android.content.Context
import com.medtroniclabs.microcoaching.ai.model.ModelCatalog
import com.medtroniclabs.microcoaching.ai.model.ModelProvider
import com.medtroniclabs.microcoaching.ai.model.ModelVariant
import com.medtroniclabs.microcoaching.domain.decision.CoachingMode
import com.medtroniclabs.microcoaching.sdk.MicroCoachingDataCallback

/**
 * All client-configurable settings for the MicroCoaching SDK.
 *
 * Constructed exclusively via [MicroCoachingSDK.Builder]. Once built, config is immutable.
 *
 * SPICE integration example:
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
 */
@ConsistentCopyVisibility
data class MicroCoachingConfig internal constructor(
    val context: Context,

    // ── Identity ──────────────────────────────────────────────────────────────
    /** Primary language for coaching content. Default: Bangla. */
    val language: Language = Language.BANGLA,
    /** Tenant identifier forwarded to the SDK backend for multi-tenant isolation. */
    val tenantId: String = "",

    // ── Backend ───────────────────────────────────────────────────────────────
    /** Base URL of the MicroCoaching FastAPI backend. */
    val backendUrl: String = "",
    /**
     * Auth token forwarded to the SDK backend as `Authorization: Bearer <token>`.
     * Pass the SPICE JWT here — single auth source of truth.
     */
    val authToken: String = "",
    val connectionTimeoutSeconds: Int = 30,
    val readTimeoutSeconds: Int = 60,

    // ── OTel Telemetry ────────────────────────────────────────────────────────
    /** Set to true to enable OpenTelemetry span export. Safe default is false. */
    val enableTelemetry: Boolean = false,
    /**
     * OTLP/HTTP endpoint for span export, e.g. `"http://signoz:4318"`.
     * Vendor-neutral: works with SigNoz, Grafana Tempo, Jaeger, Datadog, etc.
     */
    val otelEndpoint: String = "",
    /** `service.name` attribute on all exported spans. */
    val otelServiceName: String = "micro-coaching-android",
    /**
     * HTTP headers sent with every OTLP export request.
     * Examples:
     *   SigNoz:        `mapOf("signoz-access-token" to token)`
     *   Grafana Cloud: `mapOf("Authorization" to "Bearer $token")`
     */
    val otelHeaders: Map<String, String> = emptyMap(),
    /** Sampling rate 0.0–1.0. 1.0 captures every span. Reduce for high-volume prod. */
    val otelSamplingRate: Double = 1.0,
    /** How often the batch processor flushes spans to the endpoint (milliseconds). */
    val otelBatchExportIntervalMs: Long = 5_000L,
    /** Maximum spans per export batch. */
    val otelMaxBatchSize: Int = 512,
    /** When true, spans are also printed to Logcat (debug builds only). */
    val enableOtelDebugLogging: Boolean = false,

    // ── LLM / Inference ──────────────────────────────────────────────────────
    /**
     * Absolute path to a pre-provisioned model file.
     * Used when [modelDownloadStrategy] is [ModelDownloadStrategy.PROVIDED].
     * Must be a `.task` file → MediaPipe Gemma 3 1B via [GemmaService].
     */
    val modelPath: String = "",
    /** Controls when the on-device model is downloaded. */
    val modelDownloadStrategy: ModelDownloadStrategy = ModelDownloadStrategy.ON_FIRST_USE,
    /**
     * When `true`, the WorkManager job that downloads the on-device model
     * runs only on unmetered networks (Wi-Fi). When `false` (default), the
     * job runs as soon as any network is connected — including cellular data.
     *
     * The default is permissive because the host app is expected to surface
     * its own metered-network confirmation dialog before calling
     * [com.medtroniclabs.microcoaching.ai.model.ModelManager.triggerDownload]
     * (SPICE already does — see `LandingActivity.showCoachingMeteredNetworkWarning`).
     * Honour the user's "yes, use mobile data" choice rather than silently
     * queueing the worker against a constraint that can't be met.
     *
     * Hosts that operate in pilot regions where data plans are strict can
     * opt back into Wi-Fi-only via `.wifiOnlyModelDownload(true)` on the
     * builder.
     */
    val wifiOnlyModelDownload: Boolean = false,

    // ── Model Download Providers ──────────────────────────────────────────────
    /**
     * Ordered list of providers tried when downloading the model.
     * The SDK moves to the next provider if the current one fails.
     * Default: Backend → HuggingFace → Kaggle
     *
     * Override to change priority or disable a provider:
     * ```kotlin
     * .modelProviders(listOf(ModelProvider.HuggingFace, ModelProvider.Backend))
     * ```
     */
    val modelProviders: List<ModelProvider> = ModelProvider.DEFAULT_ORDER,
    /**
     * HuggingFace Hub access token for downloading gated models.
     * Required for `litert-community/Gemma3-1B-IT` and similar restricted repos.
     *
     * Obtain from https://huggingface.co/settings/tokens
     * For the sample app, set `HUGGING_FACE_TOKEN` in `local.properties`.
     */
    val huggingFaceToken: String = "",
    /**
     * Which model the SDK downloads and loads — an `id` from
     * [com.medtroniclabs.microcoaching.ai.model.ModelCatalog.ALLOWLIST].
     * Default: [com.medtroniclabs.microcoaching.ai.model.ModelCatalog.DEFAULT_ID]
     * (Gemma 3 270M q8 `.task`). The selected variant is the single source of truth
     * for download URL, on-disk filename, expected size, and runtime — see
     * [selectedModelVariant].
     *
     * The global ≥ 3 GB RAM gate still applies; a 270M variant's lower
     * `minDeviceMemoryGb` is not yet enforced below that cut-off.
     */
    val selectedModelId: String = ModelCatalog.DEFAULT_ID,
    /**
     * **Optional** direct download URL override for the HuggingFace model file.
     * Blank (default) → use [selectedModelVariant]'s `downloadUrl` from the catalog.
     * Set this only to point at a model file not in the allowlist (the on-disk
     * filename still comes from the selected variant).
     */
    val huggingFaceModelUrl: String = "",

    /**
     * TOTAL token window for an inference session — **input prompt + generated
     * output combined**, not an output cap (MediaPipe's `setMaxTokens` sizes the
     * KV cache). The grounded chat prompt (system rules + reference cards +
     * history) alone runs ~450–550 tokens; the previous 512 default left ~15–40
     * tokens for the answer, which is why replies were cut off mid-sentence
     * (`sawEndOfTurn=false` in ChatTrace). 1536 fits the full prompt budget
     * (`ChatSession.MAX_PROMPT_CHARS`) plus a complete 2–4 sentence answer with
     * headroom; KV-cache cost at this length is comfortably within the 3 GB-RAM
     * device floor for Gemma 3 1B INT4.
     */
    val maxInferenceTokens: Int = 1536,
    /**
     * LLM sampling temperature. The chat's grounded mode is an *extractive
     * rephrasing* task — low temperature keeps the model close to the reference
     * text and makes repeated identical questions produce near-identical answers
     * (the 0.6 default was the main source of "same question, different answer"
     * reports). Raise only if responses feel robotic.
     */
    val inferenceTemperature: Float = 0.3f,
    /**
     * Controls how strictly the chat refuses out-of-corpus questions.
     *
     * Default [ChatScopeStrictness.ExtendedClinical] lets the on-device LLM judge
     * scope when retrieval misses, so in-scope clinical questions like "How to
     * manage breast engorgement?" get a natural conversational answer with a
     * "consult your supervisor" caveat instead of the keyword-classifier refusal.
     *
     * Set to [ChatScopeStrictness.Strict] to preserve the legacy hard-refusal
     * behaviour (L1 keyword miss or L2 retrieval miss → canned refusal).
     */
    val chatScopeStrictness: ChatScopeStrictness = ChatScopeStrictness.ExtendedClinical,

    /**
     * Tunable thresholds for the offline-chat pipeline — the BM25 retrieval gate
     * and the post-LLM-stream refusal gates. Defaults preserve current behaviour
     * except the groundedness floor (lowered) and stream cap (raised); see
     * [ChatTuning] for the rationale of each knob. Set via
     * `MicroCoachingSDK.Builder.chatTuning(...)`.
     *
     * Why this exists: on a 270M model the retrieval is usually excellent (the
     * right card scores high) but the model disregards the references and
     * hallucinates, so the old fixed 0.25 groundedness floor hard-refused
     * answerable questions ("I don't have this in my training material"). These
     * knobs let a confident BM25 match show the model's answer, with the narrow
     * drug/dosage guards kept on as the only hard safety net.
     */
    val chatTuning: ChatTuning = ChatTuning(),

    /**
     * Refresher behaviour: the per-source toggles (quiz/gap/referral/visit) and the
     * (now no-op) legacy question-sampling fields — see [RefresherTuning]. A refresher
     * presents its full to-reinforce set. Set via
     * `MicroCoachingSDK.Builder.refresherTuning(...)`.
     */
    val refresherTuning: RefresherTuning = RefresherTuning(),

    /**
     * Overrides the SDK's automatic low-end-device detection (`< 3 GB total RAM`,
     * see [com.medtroniclabs.microcoaching.domain.system.DeviceCapability]).
     *
     * `null` (default) lets the SDK probe `ActivityManager.MemoryInfo.totalMem`
     * at construction. Set to `true` to force the retrieval-only chat path on
     * capable hardware (useful for QA), or to `false` to force the LLM path on
     * a low-RAM device (only do this when an external runtime guarantees the
     * Gemma model can load — otherwise the host process will be OOM-killed
     * mid-inference).
     */
    val forceLowEndMode: Boolean? = null,

    // ── Feature Flags ─────────────────────────────────────────────────────────
    /** Enable the AI chat fragment (UC-2 entry point). */
    val enableChat: Boolean = true,
    /**
     * When `true`, merge per-card `retrieval_hints_*` from APK assets
     * (`assets/retrieval/overlays/`) into synced modules before building the
     * chat knowledge index. **Benchmark / QA only** — use to validate hint
     * consumption before the backend ships v2 metadata on sync cards.
     */
    val enableRetrievalHintFixtureOverlay: Boolean = false,
    /** Enable Bengali voice input/output (Phase 6 — disabled by default). */
    val enableVoice: Boolean = false,
    /** Enable micro-learning module UC-1 (Phase 3 — disabled by default). */
    val enableLearnModule: Boolean = false,
    /** Enable counselling apply module UC-2 (Phase 4 — disabled by default). */
    val enableApplyModule: Boolean = false,
    /** Enable telemetry measure module UC-3 (Phase 5 — disabled by default). */
    val enableMeasureModule: Boolean = false,
    /**
     * Run synced gap-detection rules inside `onAssessmentSubmitted` (GAP_DETECTION_SDK.md).
     * When true, the SDK iterates `behavioural_gap_cache.detection_rule` rows
     * and emits one `spice_action_observed` per fired gap (tagged with
     * `behavioural_gap_id`). When false, the SDK falls back to the legacy
     * single-event referral-only emission — useful as a kill switch if rule
     * evaluators misbehave in the field.
     */
    val enableGapDetection: Boolean = true,

    // ── v3 Behavioural / Trigger thresholds ──────────────────────────────────
    // Defaults sourced from Implementation Plan v3.3 §W-0. All knobs are
    // overridable per-module at runtime via the `config_threshold` sync resource;
    // these values are the fallback when no server-side override is cached.

    /** Quiz pass mark, expressed as percent (0–100). */
    val quizPassThreshold: Int = 70,
    /** Consecutive failed attempts within [escalationWindowDays] that escalate to the supervisor. */
    val escalationFailureCount: Int = 3,
    /** Window over which [escalationFailureCount] is evaluated. */
    val escalationWindowDays: Int = 30,
    /** Days after a passed quiz before the same module is re-surfaced for reinforcement. */
    val periodicRefreshDays: Int = 90,
    /** Gap-trigger occurrences required inside [triggerWindowDays] before firing. */
    val triggerOccurrenceThreshold: Int = 2,
    /** Window over which [triggerOccurrenceThreshold] is evaluated. */
    val triggerWindowDays: Int = 14,

    // ── UI ────────────────────────────────────────────────────────────────────
    /**
     * Controls the colour scheme used by SDK-owned screens (e.g. [CoachingFlowActivity]).
     * Default: follows the system setting.
     */
    val uiTheme: CoachingUiTheme = CoachingUiTheme.SYSTEM,

    // ── Data Access ───────────────────────────────────────────────────────────
    /**
     * Optional push-pattern callback. SPICE registers this to receive coaching
     * events without holding a direct Room dependency on SDK internals.
     *
     * Pull-pattern alternative: inject [CoachingDataRepository] via Hilt:
     * ```kotlin
     * @Provides @Singleton
     * fun provideCoachingDataRepo(): CoachingDataRepository =
     *     MicroCoachingSDK.getInstance().dataRepository
     * ```
     */
    val dataCallback: MicroCoachingDataCallback? = null,

    // ── Testing / Override ────────────────────────────────────────────────────
    /**
     * When set, [ModeSelector] bypasses all dynamic checks and always returns this mode.
     * Use in the SPICE dev build or sample app to test EDGE/ONLINE flows without
     * physically going offline or having a model loaded.
     *
     * Example:
     * ```kotlin
     * .forceMode(CoachingMode.EDGE)  // always runs Gemma even when Wi-Fi is on
     * ```
     * Leave null (default) in production.
     */
    val forcedMode: CoachingMode? = null,
) {
    /**
     * The resolved on-device model for this config. Falls back to
     * [ModelCatalog.default] for an unknown [selectedModelId]. Single source of
     * truth for download URL, on-disk filename, expected size, runtime, and
     * sampling overrides.
     */
    fun selectedModelVariant(): ModelVariant = ModelCatalog.resolve(selectedModelId)
}

/**
 * Tunable thresholds for the capable-device offline-chat pipeline. Every value is
 * a single knob the host can move without an SDK code change; the defaults are the
 * SDK's recommended starting point.
 *
 * The pipeline retrieves grounding cards via BM25, streams an answer from the
 * on-device model, then runs a series of gates. On a small model (Gemma 3 270M) the
 * dominant failure is the model *ignoring* excellent retrieval and answering from
 * pre-training. The groundedness gate (L3c) always runs, with a **two-tier floor**:
 * when BM25 retrieval is confident the answer only has to clear a lenient floor;
 * when retrieval is weak it must clear a stricter floor.
 *
 * Crucially, failing the groundedness floor (or the L4 validator) **does not refuse**
 * — because BM25 already selected relevant cards, the gate falls back to serving the
 * clinician-authored card body / quiz explanation (the BM25 result) with a source
 * chip. So the floors can be set strict: a model answer that doesn't faithfully cover
 * the references is replaced by the authoritative card content rather than shown. The
 * drug/dosage-fabrication guards remain a hard safety net regardless.
 *
 * Observed groundedness scores (from `groundedness=` ChatTrace lines): a fluent
 * answer that ignores the references scores ~0.05–0.10; an honest paraphrase of the
 * reference facts scores ~0.5–0.9. Tune the floors between those bands.
 *
 * @property bm25ScoreThreshold Minimum field-weighted BM25 score for a chunk to be
 *           retrieved at all (the L2 gate). Lower = more recall, more marginal hits.
 * @property strongRetrievalScore Top-chunk BM25 score at/above which retrieval is
 *           treated as *confident*, selecting [strongRetrievalGroundednessFloor]
 *           instead of [groundednessFloor]. BM25 scores are not normalised (field
 *           weights push real matches to ~10–65); tune from the `topScore=` line.
 * @property groundednessFloor Groundedness floor (0..1) applied when retrieval is
 *           **NOT** confident. Stricter of the two. Below it the answer is replaced by
 *           the BM25 card content (not refused), so it can sit high. Lower it toward
 *           0.2 to let more of the model's own wording through.
 * @property strongRetrievalGroundednessFloor Groundedness floor (0..1) applied when
 *           retrieval **IS** confident ([strongRetrievalScore]+). Below it the answer
 *           is replaced by the BM25 card content. Set equal to [groundednessFloor]
 *           for a single uniform floor; set to 0 to always show the model's answer on
 *           strong retrieval.
 * @property streamCapChars Hard cap on streamed response length before generation is
 *           aborted. Raised from 700 so a complete 2–4 sentence answer isn't cut
 *           mid-sentence (the verified breastfeeding-counselling truncation).
 * @property maxResponseWords L4 length cap; responses longer than this are rejected
 *           as a free-styling signal and routed to the card-body fallback.
 * @property enableDosageGuard When true, an answer that introduces a number-adjacent
 *           dosage ("5 mg", "2 tablets") not present in the references is rejected.
 *           The one clinical-safety guard that should normally stay on.
 * @property enableDrugGuard When true, an answer that names a drug not present in the
 *           references is rejected. Keep on for safety.
 */
data class ChatTuning(
    val bm25ScoreThreshold: Float = 1.5f,
    val strongRetrievalScore: Float = 6.0f,
    val groundednessFloor: Float = 0.35f,
    val strongRetrievalGroundednessFloor: Float = 0.25f,
    val streamCapChars: Int = 1100,
    val maxResponseWords: Int = 280,
    val enableDosageGuard: Boolean = true,
    val enableDrugGuard: Boolean = true,
)

/**
 * Refresher tunables.
 *
 * **Note:** the quiz-subset "nudge" ([questionSampleRatio]/[minQuestionsPerRefresher]/
 * [maxQuestionsPerRefresher]) is **no longer applied** — a refresher now presents its
 * **full to-reinforce set** (every question still unanswered-correctly) so it can be
 * completed and disappears once answered, instead of looping on a fixed subset. The
 * three sample fields are retained for source/binary compatibility but have no effect.
 */
data class RefresherTuning(
    @Deprecated("No effect — refreshers present the full to-reinforce set, not a sampled subset.")
    val questionSampleRatio: Float = 0.4f,
    @Deprecated("No effect — see questionSampleRatio.")
    val minQuestionsPerRefresher: Int = 2,
    @Deprecated("No effect — see questionSampleRatio.")
    val maxQuestionsPerRefresher: Int = 6,

    // ── On-device morning-card sources (per-source, independently toggleable) ──
    // The backend's current default is quiz-level only; the other three are kept
    // implemented but off, ready to re-enable by flipping a single flag.

    /** Quiz-level refreshers (`chw_quiz_question_state`) — the backend default. */
    val enableQuizRefreshers: Boolean = true,
    /** Legacy behavioural-gap-level refreshers (the pre-quiz model). */
    val enableGapRefreshers: Boolean = false,
    /** Referral/assessment action-gap refreshers (`spice_action_observed`). */
    val enableReferralRefreshers: Boolean = false,
    /** Today's-visit stand-in refreshers (`assessment_due` trigger bindings). */
    val enableVisitRefreshers: Boolean = false,
)

/** Supported coaching interface languages. */
enum class Language(val bcp47: String) {
    BANGLA("bn-BD"),
    ENGLISH("en-US");

    companion object {
        fun fromBcp47(code: String): Language =
            entries.firstOrNull { it.bcp47.startsWith(code, ignoreCase = true) }
                ?: ENGLISH
    }
}

/** Controls the colour scheme applied to SDK-owned UI screens. */
enum class CoachingUiTheme {
    /** Follow the device system setting (default). */
    SYSTEM,
    /** Always use light theme regardless of system setting. */
    LIGHT,
    /** Always use dark theme regardless of system setting. */
    DARK,
}

/**
 * Strictness of the chat's out-of-scope refusal layer.
 *
 * The default is [ExtendedClinical] — the LLM gets to decide whether a
 * retrieval-miss question is still within SPICE's clinical scope and either
 * answers it freely with a safety caveat or emits an out-of-scope sentinel.
 */
enum class ChatScopeStrictness {
    /**
     * Legacy behaviour: L1 keyword classifier miss OR L2 retrieval miss → hard
     * refusal without ever calling the LLM. Cheapest path; most aggressive at
     * rejecting questions the corpus doesn't already cover.
     */
    Strict,

    /**
     * L1 keyword classifier is advisory only. On retrieval miss, the LLM is
     * called with an open-scope system prompt that lists the clinical domains
     * it may answer about; the model either responds with a "consult your
     * supervisor" caveat or emits a refusal sentinel. One LLM round-trip per
     * message — same as today's grounded path.
     */
    ExtendedClinical,
}

/** Controls when the on-device Gemma model is downloaded to the device. */
enum class ModelDownloadStrategy {
    /**
     * Download as soon as the SDK initializes.
     * Recommended for Bangladesh onboarding flow where Wi-Fi is available upfront.
     */
    ON_SDK_INIT,

    /**
     * Download only when the user first opens a feature requiring inference.
     * Shows a download progress UI before the chat is available.
     */
    ON_FIRST_USE,

    /**
     * The host app or MDM system pre-provisions the model file.
     * SDK reads [MicroCoachingConfig.modelPath] directly — no download.
     */
    PROVIDED,

    /**
     * Download triggered manually by calling [ModelManager.triggerDownload].
     * Use this when the host app controls the onboarding flow.
     */
    MANUAL,
}
