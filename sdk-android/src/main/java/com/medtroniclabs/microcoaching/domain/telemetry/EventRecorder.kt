package com.medtroniclabs.microcoaching.domain.telemetry

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private const val TAG = "EventRecorder"

/**
 * Lenient Json instance used to parse evidence payloads from gap evaluators
 * before merging them into the event's `payload_json`. Lenient because the
 * envelope is constructed in-process by SDK code, not received over the wire.
 */
private val EVIDENCE_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Map a backend-canonical event_type to its event_family bucket. Values match
 * the deployed `CoachingEventType` / `DigitalEventType` / `EventFamily` enums
 * exactly so the backend ingestion path can deserialise into typed enums for
 * aggregation rather than falling back to `string`.
 */
fun eventFamilyFor(eventType: String): String = when (eventType) {
    // Coaching surface events. `module_quiz_attempted` lives here per the v3
    // E2E contract — see docs/v3/coaching-platform-e2e-backend.md.
    "card_shown", "card_skipped", "card_accepted", "counselling_used",
    "audio_played", "quiz_started", "module_quiz_attempted" -> "coaching"
    "module_delivered", "module_card_viewed", "module_completed" -> "learning"
    "risk_flag_observed", "spice_action_observed",
    "equipment_anomaly_observed" -> "clinical_observed"
    "sync_attempt", "sync_started", "sync_completed",
    "form_submit", "login_attempt", "digital_help_used" -> "digital"
    else -> "system"  // session_start, session_end, llm_inference, unknown
}

/**
 * Maps a [com.medtroniclabs.microcoaching.ui.learn.LearnModule.source] value to
 * the wire `trigger_type` per the v1.1 Events-Modelling spec:
 *  - `"gap"` (gap-driven refresher) → `"gap"`
 *  - `"fallback"` (server fallback recommendation) → `"fallback"`
 *  - null (regular training-row quiz, no morning surface) → `"workflow_event"`
 *
 * Free function (not a method) so both [EventRecorder] callers and the
 * `CoachingModuleStore` can resolve the trigger type without an instance.
 */
fun triggerTypeFor(source: String?): String = when (source) {
    "gap" -> "gap"
    "fallback" -> "fallback"
    else -> "workflow_event"
}

/**
 * Append-only coaching event recorder. The single write path for all CHW interaction events.
 *
 * Replaces [TelemetryManager] for coaching-domain events. Writes directly to Room;
 * the OutboundSyncWorker (Phase B) batches pending rows to POST /telemetry/events.
 *
 * One instance per coaching session — constructed with the session ID and CHW ID
 * that remain constant for the session's lifetime.
 *
 * No batching, no in-memory buffering — each call is an immediate DB insert on the
 * caller's coroutine. Callers are responsible for calling from an IO dispatcher.
 */
class EventRecorder(
    private val dao: CoachingEventDao,
    val sessionId: String,
    private val chwId: String,
    private val sdkVersion: String = BuildConfig.SDK_VERSION,
    /**
     * Optional host-app version string. When non-null it is written to the
     * `sdk_version` column instead of [sdkVersion] — preserving the exact value
     * `LearnViewModel.recordEvent` historically wrote (the SPICE host
     * `versionName`, not the SDK's BuildConfig). Null for every other recorder,
     * which keep emitting [BuildConfig.SDK_VERSION].
     */
    private val appVersionName: String? = null,
) {

    suspend fun recordSessionStart() {
        dao.insert(build(eventType = "session_start"))
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[$sessionId] session_start saved — chw=${chwId.sha256Short()}")
        }
    }

    suspend fun recordSessionEnd() {
        dao.insert(build(eventType = "session_end"))
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[$sessionId] session_end saved — chw=${chwId.sha256Short()}")
        }
    }

    suspend fun recordCardViewed(
        moduleFamilyId: String,
        clinicalDomain: String,
        cardType: String,
        triggerType: String,
        inferenceMode: String,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        cardFamilyId: String? = null,
    ) {
        // Backend canonical for v3 module-sourced cards.
        dao.insert(
            build(
                eventType = "module_card_viewed",
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                triggerType = triggerType,
                inferenceMode = inferenceMode,
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                cardFamilyId = cardFamilyId,
            )
        )
        Log.d(TAG, "[$sessionId] module_card_viewed saved — module=$moduleFamilyId domain=$clinicalDomain mode=$inferenceMode card=$cardFamilyId")
    }

    suspend fun recordCardDismissed(
        moduleFamilyId: String,
        clinicalDomain: String,
        cardType: String,
        triggerType: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        // Backend uses `card_skipped` as the canonical dismissed-from-surface event.
        dao.insert(
            build(
                eventType = "card_skipped",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                triggerType = triggerType,
                outcome = "skip",
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            )
        )
        Log.d(TAG, "[$sessionId] card_skipped saved — module=$moduleFamilyId")
    }

    suspend fun recordCardAccepted(
        moduleFamilyId: String,
        clinicalDomain: String,
        cardType: String,
        inferenceMode: String,
        triggerType: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "card_accepted",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                inferenceMode = inferenceMode,
                triggerType = triggerType,
                outcome = "accepted",
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            )
        )
        Log.d(TAG, "[$sessionId] card_accepted saved — module=$moduleFamilyId")
    }

    suspend fun recordCounsellingUsed(
        moduleFamilyId: String,
        clinicalDomain: String,
        fallbackUsed: Boolean = false,
        validatorStatus: String? = null,
        triggerType: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "counselling_used",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
                triggerType = triggerType,
                outcome = "used",
                validatorStatus = validatorStatus,
                fallbackUsed = fallbackUsed,
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            )
        )
        Log.d(TAG, "[$sessionId] counselling_used saved — module=$moduleFamilyId fallback=$fallbackUsed validator=$validatorStatus")
    }

    suspend fun recordQuizStarted(
        moduleFamilyId: String,
        clinicalDomain: String,
    ) {
        dao.insert(
            build(
                eventType = "quiz_started",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
                cardType = "QUIZ",
            )
        )
        Log.d(TAG, "[$sessionId] quiz_started saved — module=$moduleFamilyId")
    }

    /**
     * Records one row per chat response generation, per the v1.1 Events
     * Modelling spec (`event_family: "digital"`, `event_type: "digital_help_used"`,
     * `trigger_type: "workflow_event"`).
     *
     * Emitted on every assistant turn — both happy path and refusal path. On
     * refusals, [payloadJson] carries the structured detail (refusal_outcome,
     * top retrieval score, chunk IDs) so downstream tuning can mine refusal
     * patterns without a separate event family.
     *
     * @param inferenceMode `"edge"` for on-device Gemma, `"online"` for the
     *   backend RAG path (currently dormant), `"cached"` for pre-authored fallback.
     * @param validatorStatus `"pass"` | `"fail"` | `null` — output of the B4
     *   validator. Null when validation didn't run (e.g. L1 scope refusal).
     * @param fallbackUsed `true` when L4 fell back to a quiz `explanation_bn`
     *   or any other pre-authored Bangla string in place of LLM output.
     * @param networkState `"online"` | `"offline"` | `"restored"` — ConnectivityManager
     *   snapshot at emission time.
     * @param payloadJson Optional structured detail for refusal rows. Pass
     *   `null` on the happy path.
     * @param moduleId Version-specific UUID of the module that grounded the
     *   served response (backend `module.id`). Events-Modelling v1.2 made this
     *   mandatory for served turns — it is the module "used for forming the
     *   response of the query" (the top cited module from the RAG response, or
     *   the dominant grounding chunk's module on the on-device path). Left
     *   `null` on refusal / inference-error / empty-response turns, where no
     *   module formed a response. `module_family_id` stays null per the spec.
     */
    suspend fun recordDigitalHelpUsed(
        inferenceMode: String,
        validatorStatus: String? = null,
        fallbackUsed: Boolean = false,
        networkState: String? = null,
        payloadJson: String? = null,
        moduleId: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "digital_help_used",
                triggerType = "workflow_event",
                inferenceMode = inferenceMode,
                validatorStatus = validatorStatus,
                fallbackUsed = fallbackUsed,
                networkState = networkState,
                payloadJson = payloadJson,
                moduleId = moduleId,
            )
        )
        Log.d(TAG, "[$sessionId] digital_help_used saved — module=$moduleId mode=$inferenceMode validator=$validatorStatus fallback=$fallbackUsed network=$networkState")
    }

    suspend fun recordModuleStarted(
        moduleFamilyId: String,
        clinicalDomain: String,
    ) {
        // Backend canonical event for module surfacing is `module_delivered`.
        dao.insert(
            build(
                eventType = "module_delivered",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
            )
        )
        Log.d(TAG, "[$sessionId] module_delivered saved — module=$moduleFamilyId")
    }

    suspend fun recordModuleCompleted(
        moduleFamilyId: String,
        clinicalDomain: String,
    ) {
        dao.insert(
            build(
                eventType = "module_completed",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
            )
        )
        Log.d(TAG, "[$sessionId] module_completed saved — module=$moduleFamilyId")
    }

    /**
     * Distinct from [recordModuleCompleted]: emitted when the CHW finishes the
     * quiz portion of a module regardless of whether the module is fully
     * completed. Carries the score so the backend can update
     * `chw_module_completion`.
     */
    suspend fun recordQuizCompleted(
        moduleFamilyId: String,
        moduleId: String?,
        moduleVersion: Int?,
        quizScorePct: Float,
        passed: Boolean,
        behaviouralGapId: String? = null,
    ) {
        // Backend canonical event for a finished quiz attempt is
        // `module_quiz_attempted`; the outcome carries pass/fail.
        dao.insert(
            build(
                eventType = "module_quiz_attempted",
                cardType = "quiz",
                outcome = if (passed) "correct" else "wrong",
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                quizScorePct = quizScorePct,
                behaviouralGapId = behaviouralGapId,
            )
        )
        Log.d(TAG, "[$sessionId] module_quiz_attempted saved — module=$moduleFamilyId score=$quizScorePct passed=$passed gap=$behaviouralGapId")
    }

    // ── Patient-interaction events (UC-2 / clinical_observed family) ──────────

    /**
     * Records the `spice_action_observed` event fired after a SPICE patient
     * assessment submits successfully. Backend family: `clinical_observed`.
     *
     * Per v1.1 events spec, this row captures whether the CHW referred the
     * patient correctly along three axes — `correctReferral` (headline),
     * `correctReferralLocation`, `correctReferralType`. The headline value
     * is also written to the top-level `outcome` column as `"correct"` /
     * `"wrong"` for fast aggregation.
     *
     * `payload_json` is built as a strict object here (rather than the
     * `raw`-wrapping branch in [SyncPayloadMapper]) so the backend ingests
     * the expected key shape directly.
     */
    suspend fun recordSpiceActionObserved(
        patientIdHash: String,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        correctReferral: Boolean,
        correctReferralLocation: Boolean,
        correctReferralType: Boolean,
        networkState: String? = null,
        /**
         * Optional override of the top-level `outcome` column. When null, falls
         * back to "correct"/"wrong" derived from [correctReferral]. The gap
         * dispatcher passes "incorrect" per design §1.
         */
        outcome: String? = null,
        /**
         * The `rule_type` of the fired gap evaluator (e.g. `wrong_facility_tier`).
         * Included in `payload_json` for QA replay per GAP_DETECTION_SDK.md §1
         * "non-state fields ship to ClickHouse".
         */
        ruleType: String? = null,
        /**
         * JSON-serialised evidence map from the evaluator's `buildEvidence`,
         * merged into `payload_json` under the `evidence` key. PII must already
         * be hashed by the caller — see [com.medtroniclabs.microcoaching.domain.gaps.evidence.EvidenceBuilder].
         */
        evidenceJson: String? = null,
    ) {
        val evidenceObject = evidenceJson
            ?.takeIf { it.isNotBlank() && it != "{}" }
            ?.let { raw ->
                runCatching { EVIDENCE_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            }
        val payload = buildJsonObject {
            if (behaviouralGapId != null) put("behavioural_gap_id", behaviouralGapId)
            put("correctReferral", correctReferral)
            put("correctReferralLocation", correctReferralLocation)
            put("correctReferralType", correctReferralType)
            if (ruleType != null) put("rule_type", ruleType)
            if (evidenceObject != null) put("evidence", evidenceObject)
        }.toString()
        val resolvedOutcome = outcome ?: if (correctReferral) "correct" else "wrong"
        dao.insert(
            build(
                eventType = "spice_action_observed",
                triggerType = "workflow_event",
                outcome = resolvedOutcome,
                networkState = networkState,
                payloadJson = payload,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            ),
        )
        Log.d(
            TAG,
            "[$sessionId] spice_action_observed saved — outcome=$resolvedOutcome rule=$ruleType gap=$behaviouralGapId",
        )
    }

    /**
     * Records the `risk_flag_observed` event fired when SPICE surfaces a
     * high-risk threshold (BP crisis, etc.). Backend family: `clinical_observed`.
     *
     * Emitted both from the post-assessment path (when `risk_level` lands as
     * HIGH/EMERGENCY) and from the mid-visit hook
     * ([com.medtroniclabs.microcoaching.MicroCoachingSDK.onRiskFlagObserved]).
     */
    suspend fun recordRiskFlagObserved(
        riskLevel: String,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        val payload = buildJsonObject {
            put("risk_level", riskLevel)
            if (behaviouralGapId != null) put("behavioural_gap_id", behaviouralGapId)
        }.toString()
        dao.insert(
            build(
                eventType = "risk_flag_observed",
                triggerType = "workflow_event",
                networkState = networkState,
                payloadJson = payload,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            ),
        )
        Log.d(TAG, "[$sessionId] risk_flag_observed saved — risk=$riskLevel gap=$behaviouralGapId")
    }

    /**
     * Records the `card_shown` event when a coaching surface fires on the
     * CHW's device. Backend family: `coaching`. Used by the UC-2 Apply
     * post-assessment card surface. While the render path is not yet built,
     * the SDK stub-fires this on every `onAssessmentSubmitted` so the
     * downstream "card shown" metric is non-empty.
     */
    suspend fun recordCardShown(
        cardType: String,
        triggerType: String,
        inferenceMode: String? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        clinicalDomain: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "card_shown",
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                triggerType = triggerType,
                inferenceMode = inferenceMode,
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            ),
        )
        Log.d(
            TAG,
            "[$sessionId] card_shown saved — cardType=$cardType trigger=$triggerType mode=$inferenceMode",
        )
    }

    /**
     * Generic coaching/learning event write — the single path the learn flow
     * (module delivery, lesson cards, quiz attempts) records through. Mirrors
     * the parameter surface, outcome derivation, and network-state fallback of
     * the former `LearnViewModel.recordEvent`, so rows are byte-identical.
     *
     * `outcomeOverride` wins; otherwise a `module_quiz_attempted` row with a
     * known [isCorrect] derives `"correct"`/`"wrong"` (per-question rows stay in
     * sync with the aggregate finishQuiz path). When [networkState] is null it
     * defaults to the SDK's ConnectivityManager snapshot so every event family
     * shares one vocabulary. Wrapped in try/catch — a telemetry failure must
     * never break the learn flow.
     */
    suspend fun recordCoachingEvent(
        eventType: String,
        clinicalDomain: String? = null,
        cardType: String? = null,
        quizQuestionId: String? = null,
        selectedOption: Int? = null,
        isCorrect: Boolean? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        cardFamilyId: String? = null,
        quizFamilyId: String? = null,
        quizScorePct: Float? = null,
        outcomeOverride: String? = null,
        behaviouralGapId: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        networkState: String? = null,
    ) {
        try {
            val outcome = outcomeOverride ?: when {
                eventType == "module_quiz_attempted" && isCorrect != null ->
                    if (isCorrect) "correct" else "wrong"
                else -> null
            }
            val resolvedNetworkState = networkState
                ?: if (MicroCoachingSDK.getInstance().isNetworkAvailable()) "online" else "offline"

            dao.insert(
                build(
                    eventType = eventType,
                    clinicalDomain = clinicalDomain,
                    cardType = cardType,
                    triggerType = triggerType,
                    inferenceMode = inferenceMode,
                    quizQuestionId = quizQuestionId,
                    selectedOption = selectedOption,
                    isCorrect = isCorrect,
                    outcome = outcome,
                    networkState = resolvedNetworkState,
                    moduleFamilyId = moduleFamilyId,
                    moduleId = moduleId,
                    cardFamilyId = cardFamilyId,
                    quizFamilyId = quizFamilyId,
                    moduleVersion = moduleVersion,
                    quizScorePct = quizScorePct,
                    behaviouralGapId = behaviouralGapId,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record event '$eventType': ${e.message}")
        }
    }

    // ── Private builder ───────────────────────────────────────────────────────

    private fun build(
        eventType: String,
        clinicalDomain: String? = null,
        cardType: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        quizQuestionId: String? = null,
        selectedOption: Int? = null,
        isCorrect: Boolean? = null,
        outcome: String? = null,
        validatorStatus: String? = null,
        fallbackUsed: Boolean? = null,
        networkState: String? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        cardFamilyId: String? = null,
        quizFamilyId: String? = null,
        moduleVersion: Int? = null,
        quizScorePct: Float? = null,
        behaviouralGapId: String? = null,
        payloadJson: String? = null,
        // ── Patient-interaction context (UC-2 / clinical_observed) ─────────
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
    ) = CoachingEventEntity(
        eventId = UUID.randomUUID().toString(),
        sdkVersion = appVersionName ?: sdkVersion,
        eventFamily = eventFamilyFor(eventType),
        sessionId = sessionId,
        chwId = chwId,
        eventType = eventType,
        clinicalDomain = clinicalDomain,
        cardType = cardType,
        triggerType = triggerType,
        inferenceMode = inferenceMode,
        quizQuestionId = quizQuestionId,
        selectedOption = selectedOption,
        isCorrect = isCorrect,
        outcome = outcome,
        validatorStatus = validatorStatus,
        fallbackUsed = fallbackUsed,
        networkState = networkState,
        moduleFamilyId = moduleFamilyId,
        moduleId = moduleId,
        cardFamilyId = cardFamilyId,
        quizFamilyId = quizFamilyId,
        moduleVersion = moduleVersion,
        quizScorePct = quizScorePct,
        behaviouralGapId = behaviouralGapId,
        payloadJson = payloadJson,
        patientIdHash = patientIdHash,
        patientVisitId = patientVisitId,
        patientTrackId = patientTrackId,
        villageId = villageId,
        upazilaId = upazilaId,
    )
}
