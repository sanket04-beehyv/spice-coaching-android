package com.medtroniclabs.microcoaching.ui.learn

/**
 * UI model for a learning module (backed by [ModuleEntity]).
 *
 * @param moduleFamilyId Stable cross-version family key matching [ModuleEntity.moduleFamilyId].
 * @param title Display title from bangla_card.title.
 * @param body Module description from bangla_card.body.
 * @param clinicalDomain Used for domain colour/chip display.
 * @param warningSigns List of Bangla warning sign strings.
 * @param nextStep CHW action text in Bangla.
 * @param referralDestination Referral facility name in Bangla, or null.
 * @param quizIds IDs of quiz questions linked to this module.
 * @param status Learning path status: assigned | in_progress | completed.
 */
data class LearnModule(
    val moduleFamilyId: String,
    val title: String,
    val body: String,
    val clinicalDomain: String,
    val warningSigns: List<String> = emptyList(),
    val nextStep: String = "",
    val referralDestination: String? = null,
    val quizIds: List<String> = emptyList(),
    val status: String = "assigned",
    /**
     * Quiz questions inlined with the module bundle (v3.3 module_cache origin).
     *
     * When non-null, [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel.startQuiz]
     * uses these directly instead of querying [quizIds] against the legacy
     * `quiz_question_cache`. Modules from the v3.3 pipeline ship cards and quiz
     * together — there's no separate quiz table.
     */
    val inlineQuestions: List<QuizQuestion>? = null,
    /** Version-specific module UUID (Module.id). Null until backend exposes it in the sync bundle. */
    val moduleId: String? = null,
    /** Module content version at the time this module was synced. */
    val moduleVersion: Int? = null,
    /** card_family_id of the first (walkthrough) card. Used for card_shown telemetry. */
    val cardFamilyId: String? = null,
    /**
     * Backend `module_type` enum value: "refresher" | "content_update" |
     * "digital_proficiency". Drives section assignment on the v0.3.2 modules
     * screen and determines whether the tap path goes directly to quiz
     * (refresher) or through the lesson-content flow.
     */
    val moduleType: String = "refresher",
    /** Estimated minutes for non-refresher modules. Drives the Training-card meta line. */
    val estimatedMinutes: Int? = null,
    /** content_update fields — populated only when [moduleType] == "content_update". */
    val previousPracticeBn: String? = null,
    val currentPracticeBn: String? = null,
    val rationaleForChangeBn: String? = null,
    val nextActionBn: String? = null,
    /**
     * Gap ID carried from the morning-cards response when this module was surfaced because of
     * a behavioural gap. Forwarded into [TelemetryEventPayload.payloadJson] on
     * `module_quiz_attempted` events so the backend can resolve the gap state.
     * Null for modules opened outside the gap-driven morning surface.
     */
    val behaviouralGapId: String? = null,
    /**
     * Surface source from the morning-cards response: "gap" | "fallback" | null.
     * Used to display the GAP badge on the refresher tile.
     */
    val source: String? = null,
    /**
     * True when the morning-card selector emitted this module — it has a row in
     * `morning_card_cache` (backend `GET /morning/cards` OR the on-device
     * [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator]).
     * This is the SOLE gate for refresher membership (see
     * [com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer]): if a
     * selector surfaced it, it lands on the refresher list regardless of
     * completion/mastery.
     */
    val fromMorningCard: Boolean = false,
    /**
     * Default severity of the behavioural gap that surfaced this module:
     * "low" | "moderate" | "high" (from `behavioural_gap_cache.severity_default`).
     * Resolved in [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel.mapModules]
     * via [behaviouralGapId]. Null when the module isn't gap-sourced — the
     * refresher tile then shows no severity chip.
     */
    val severity: String? = null,
    /**
     * True when this module is surfaced by an **action/compliance gap** — a
     * behavioural gap that carries a `detection_rule` and is driven by a
     * real-world mistake (e.g. a wrong facility referral), not by quiz history.
     * Such refreshers are exempt from the "all questions mastered → drop" filter
     * so a fresh field mistake re-engages the CHW even after a past perfect quiz.
     */
    val isActionGap: Boolean = false,
    /**
     * Raw `cards_json` from [ModuleEntity] — forwarded so [LessonPlayerScreen] and
     * [ModuleDetailScreen] can parse the card list without a DB round-trip.
     * Defaults to `"[]"` when not available (e.g. QuickLearn minimal mapping).
     */
    val cardsJson: String = "[]",
    /**
     * Actual quiz score from the CHW's last attempt (0.0–1.0).
     * Seeded from [ChwModuleCompletionEntity.latestQuizScore] on every
     * `observeModules` call. Null when the module has never been attempted.
     * Drives the progress bar in [TrainingCard] instead of the coarse 3-step
     * status mapping.
     */
    val quizScorePct: Float? = null,
    /**
     * Number of questions whose **latest local attempt was wrong** — the CHW's
     * "local gap". A never-attempted module reports 0 here (not the question
     * total): the name means *answered wrong*, nothing else. Drives the
     * Refresher-section membership in [com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer].
     *
     * NOTE: this is NOT the count to display on the tile — for that, use
     * [reinforceQuestionCount] (the drill size, which also counts never-answered
     * questions). See [LearnViewModel] mapModules for how the two are derived.
     *
     * Null when the count hasn't been computed yet (legacy callers).
     */
    val wrongQuestionCount: Int? = null,
    /**
     * Number of questions still "to reinforce" — either never answered or whose
     * latest answer was wrong. This equals the length of the refresher drill that
     * [com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel.primeRefresherQuiz]
     * presents, so it's the count shown on the refresher tile in [RefresherList].
     *
     * Null when not yet computed; callers fall back to [inlineQuestions]?.size.
     * Recomputed on every [LearnViewModel.observeModules] emission (incl. after
     * any `coaching_event` change) so the tile updates immediately.
     */
    val reinforceQuestionCount: Int? = null,
    /**
     * Number of distinct quiz questions the CHW has attempted at least once,
     * regardless of whether they got them right or wrong. Drives the training-card
     * progress bar per PM direction (DM.txt, 2026-06): the visual progress is
     * attempted / total — every attempt counts toward 100%, even all-wrong attempts.
     *
     * **Visual only.** Not linked to backend `chw_module_completion.completedAt`,
     * which still tracks the passing-attempt semantic.
     *
     * Null when the count hasn't been computed yet (legacy callers).
     */
    val attemptedQuestionCount: Int? = null,
    /**
     * Millis-since-epoch when this module version was published, parsed from
     * `ModuleEntity.publishedAtIso` via `parseIsoMillis`. Null when the backend
     * didn't send a `published_at` or when parsing failed.
     *
     * **Used only by [com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate].**
     * The gate closes the "Start Quiz" CTA once the module is older than
     * 7 days (the retry window) AND the CHW has attempted every question at
     * least once. Within the 7-day window CHWs can keep retrying; first-time
     * attempts are always allowed (a never-attempted module is not a retry).
     *
     * **To remove the retry-window feature**: delete [QuizRetryGate], remove
     * the OR clause in [com.medtroniclabs.microcoaching.ui.flow.CoachingNavGraph]'s
     * `readOnly` decision, and drop this field + its assignment in
     * `LearnViewModel.mapModules`. No UI changes are needed — the read-only
     * "Back to modules" CTA path already exists for completed modules.
     */
    val publishedAtMs: Long? = null,
    /**
     * Cached presigned URL for the module thumbnail, carried from
     * [com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity.thumbnailUrl].
     * Null when the module has no thumbnail or sync hasn't resolved one yet —
     * the tile / detail header simply omits the image in that case.
     */
    val thumbnailUrl: String? = null,
)

/**
 * UI model for a single quiz question (backed by [QuizQuestionCacheEntity]).
 *
 * Replaces [StubQuiz].
 *
 * @param id Stable question ID from the backend.
 * @param questionText The question shown to the CHW (Bangla).
 * @param answers List of 3–4 Bangla answer option strings.
 * @param correctIndex Zero-based index of the correct answer.
 * @param explanation Bangla explanation shown after the CHW answers.
 * @param pointValue Score points for a correct answer.
 */
data class QuizQuestion(
    val id: String,
    val questionText: String,
    val answers: List<String>,
    val correctIndex: Int,
    val explanation: String = "",
    val caseSetup: String = "",
    val pointValue: Int = 10,
)
