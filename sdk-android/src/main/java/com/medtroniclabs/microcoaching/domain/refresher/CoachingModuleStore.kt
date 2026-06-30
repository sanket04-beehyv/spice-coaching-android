package com.medtroniclabs.microcoaching.domain.refresher

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.sortedForDisplay
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedBody
import com.medtroniclabs.microcoaching.data.mapper.decodeIncompleteQuizIds
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.data.repository.GapProfileRepositoryImpl
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.ActionGapLink
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer
import com.medtroniclabs.microcoaching.ui.learn.modules.ModuleSections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Single, SDK-owned source of truth for the categorised module lists and the
 * **one** featured refresher pick that every surface shares.
 *
 * Why it lives in the SDK singleton (on `sdkScope`) rather than a ViewModel:
 * the home [com.medtroniclabs.microcoaching.ui.components.MorningCard] is hosted
 * by the SPICE host's Activity, while the modules screen
 * ([com.medtroniclabs.microcoaching.ui.learn.modules.ModulesScreen]) lives in the
 * SDK's `CoachingFlowActivity`. Those are different Android Activities, so the
 * only state both can observe is the process-singleton. Before this store the
 * home card derived its pick from the morning-API subset while the modules
 * screen categorised from the full module list — they could disagree. Now both
 * read [selectedMorningCard] / [refresherModules] from here.
 *
 * Pipeline (all reactive, all on [scope]):
 *  1. [allModules]    — every active module enriched into a [LearnModule]
 *                       (the former `LearnViewModel.mapModules`), recomputed on
 *                       any module / coaching_event change or [invalidate].
 *  2. [refresherModules] / [trainingModules] — [ModuleCategorizer.categorize]
 *                       partitions. Refresher membership is selector-authoritative
 *                       (every morning-card module surfaces; no mastery/completion
 *                       drop — a mastered card just sorts last).
 *  3. [selectedMorningCard] — the first non-skipped refresher that carries a
 *                       quiz; advances to the next as the CHW skips, hides only
 *                       when every refresher is skipped.
 *
 * [com.medtroniclabs.microcoaching.MicroCoachingSDK.morningModules] is left
 * untouched (the chat ScopeClassifier still consumes it); this store is purely
 * additive.
 */
internal class CoachingModuleStore(
    private val database: MicroCoachingDatabase,
    private val scope: CoroutineScope,
    private val chwIdProvider: () -> String?,
    private val langProvider: () -> String,
    private val skippedIds: StateFlow<Set<String>>,
    /**
     * `moduleFamilyId → real action-gap id` published by
     * [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator].
     * Overlaid in [map] so an action/compliance module (e.g. a wrong-referral
     * refresher) is recognised by its true gap even when the cached morning-card
     * row carries only the backend's `module_primary_gap_*` placeholder. Empty
     * until the generator runs.
     */
    private val onDeviceActionGapLinks: StateFlow<Map<String, ActionGapLink>>,
) {

    private val gapRepo = GapProfileRepositoryImpl(database.chwGapProfileDao())

    /**
     * In-session module-status overlay (moduleFamilyId → "in_progress"/"completed"),
     * moved here from `LearnViewModel` so the mapping that reads it and the quiz
     * flow that writes it share one map — and so a completion on the modules
     * screen is reflected on the home card. The persisted truth still lives in
     * `chw_module_completion`; this is the optimistic overlay applied on top.
     */
    private val statusByModule = ConcurrentHashMap<String, String>()

    /**
     * Bumped to force a recompute that a Room flow wouldn't otherwise trigger:
     * after a `/morning/cards` pull updates `morning_card_cache`, after a
     * [setInSessionStatus] overlay change, and once `currentCHWId` is known.
     */
    private val invalidateTick = MutableStateFlow(0L)

    /** Force the pipeline to recompute (see [invalidateTick]). */
    fun invalidate() = invalidateTick.update { it + 1 }

    /**
     * Apply an optimistic in-session status for [moduleFamilyId] and recompute,
     * so the UI reflects "in_progress"/"completed" before the DB write
     * propagates through the coaching_event flow. Called by the `LearnViewModel`
     * quiz/lesson flow.
     */
    fun setInSessionStatus(moduleFamilyId: String, status: String) {
        if (statusByModule[moduleFamilyId] == status) return
        statusByModule[moduleFamilyId] = status
        invalidate()
    }

    /**
     * Every active module enriched into a [LearnModule] — the data behind every
     * downstream list. Recomputes on module-cache changes, coaching_event inserts
     * (so quiz-progress counts update live), **morning_card_cache changes**, and
     * [invalidate].
     *
     * Observing `morning_card_cache` is essential: [map] reads it for each module's
     * `source` / `behaviouralGapId` (→ `isActionGap`), so when [OnDeviceMorningGenerator]
     * rewrites the cache the store MUST recompute — otherwise it keeps a stale view
     * where a freshly gap-surfaced (and possibly completed/action-gap) module isn't a
     * refresher, and the featured pick flaps to an unrelated module. (`invalidate` alone
     * can't cover this: it fires before the generator's async write lands.)
     *
     * `Eagerly` so the featured pick stays consistent across the home ⇄ modules
     * Activities even when neither is actively collecting (mirrors
     * `skippedRefresherCount`). Emits an empty list until `currentCHWId` is set.
     */
    val allModules: StateFlow<List<LearnModule>> =
        combine(
            database.moduleDao().getAllActive().map { it.sortedForDisplay() },
            database.coachingEventDao().getEventCountFlow(),
            database.morningCardCacheDao().getAllOrdered(),
            onDeviceActionGapLinks,
            invalidateTick,
        ) { modules, _, _, _, _ -> modules }
            .map { modules ->
                val chw = chwIdProvider() ?: return@map emptyList<LearnModule>()
                map(modules, chw)
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Categorised partitions. Refresher membership is selector-authoritative
     * ([ModuleCategorizer.isRefresher] = a `morning_card_cache` row exists); there is
     * no mastery/completion drop — a fully-mastered selector card still surfaces.
     */
    private val sections: StateFlow<ModuleSections> =
        allModules
            .map { all ->
                val s = ModuleCategorizer.categorize(all)
                traceSections(all, s)
                s
            }
            .stateIn(scope, SharingStarted.Eagerly, EMPTY_SECTIONS)

    /** Active "drill this today" queue — shared by the banner and the list. */
    val refresherModules: StateFlow<List<LearnModule>> =
        sections.map { it.refreshers }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Modules assigned to the current CHW (from the `assigned_module` join table,
     * populated by the "assigned" `/sync/modules` call). Drives the
     * training-library filter below. Empty until the assigned pull lands —
     * `flatMapLatest` re-subscribes when `currentCHWId` is set so the set isn't
     * pinned to a null/blank id at construction time.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val assignedModules: StateFlow<List<AssignedModuleEntity>> =
        invalidateTick
            .flatMapLatest {
                val chw = chwIdProvider()
                if (chw.isNullOrBlank()) flowOf(emptyList())
                else database.assignedModuleDao().getAssignedForUser(chw)
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The Training screen's list.
     *
     * **With assignments:** every module assigned to the CHW that has a learnable
     * tile — i.e. ANY type except `content_update` (which has no quiz/lesson and is
     * excluded from Training/Refresher everywhere). Crucially this means an
     * **assigned `refresher`** shows here too, even when the morning-card selector
     * didn't surface it (so it'd otherwise be unreachable). A module matches by
     * EITHER its `moduleId` (the backend's assignment key, always present) or its
     * `moduleFamilyId` (when the payload carries it), so the filter still works if a
     * future dedicated endpoint returns only `module_id`.
     *
     * **Without assignments (fallback):** the type-gated training catalogue
     * ([ModuleCategorizer]'s `initial_training` / `digital_proficiency`). A fresh
     * device, a wiped cache, or a failed assigned-sync must never show an empty
     * Training screen — it degrades to the catalogue until `assigned_module` is
     * populated. The BM25 path is unaffected.
     */
    val trainingModules: StateFlow<List<LearnModule>> =
        combine(sections.map { it.training }, assignedModules, allModules) { training, assigned, allMapped ->
            val onScreen = if (assigned.isEmpty()) {
                training
            } else {
                val assignedModuleIds = assigned.map { it.moduleId }.toSet()
                val assignedFamilyIds = assigned.mapNotNull { it.moduleFamilyId }.toSet()
                allMapped.filter {
                    (it.moduleId in assignedModuleIds || it.moduleFamilyId in assignedFamilyIds) &&
                        it.moduleType != "content_update"
                }
            }
            traceAssignedFilter(assigned, allMapped, training, onScreen)
            onScreen
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The single featured pick shared by the home [MorningCard] and the modules
     * [QuizRefresherCard]: the first refresher whose family the CHW hasn't
     * skipped this session and that carries a quiz. Skipping advances to the
     * next; null only when every refresher is skipped (or none qualify).
     */
    val selectedMorningCard: StateFlow<LearnModule?> =
        combine(refresherModules, skippedIds) { refs, skip ->
            val pick = selectFeatured(refs, skip)
            traceSelected(pick, refs.size, skip)
            pick
        }.stateIn(scope, SharingStarted.Eagerly, null)

    // ── Mapping (moved verbatim from LearnViewModel.mapModules) ─────────────────

    private suspend fun map(
        modules: List<ModuleEntity>,
        chwId: String,
    ): List<LearnModule> = withContext(Dispatchers.Default) {
        // Runs off the main thread: per-module JSON parsing (cards + quiz) and
        // several DB reads.
        val gapEntries = gapRepo.getAllForChw(chwId)
        val activeGapKeys = gapEntries.filter { it.gapActive }.map { it.behaviouralGapId }.toSet()

        // Morning-card enrichment (source / gap id for refresher list + telemetry).
        val morningCardsByModuleId = database.morningCardCacheDao()
            .getAllOrderedOnce()
            .associateBy { it.moduleId }

        // gapId → severity_default ("low"|"moderate"|"high") for the refresher
        // tile's severity chip. Resolved once per emission.
        val gapSeverityById = database.behaviouralGapDao()
            .getAllActiveOnce()
            .associate { it.gapId to it.severityDefault }

        // Action/compliance gaps (those carrying a detection_rule) drive refreshers
        // from real-world mistakes, so their modules are exempt from the
        // fully-mastered drop. Resolved once per emission.
        val actionGapIds = database.behaviouralGapDao()
            .getActiveWithRules()
            .map { it.gapId }
            .toSet()

        // Family → real action-gap id resolved on-device (see [onDeviceActionGapLinks]).
        // Lets a module's true action gap win over the backend's placeholder gap.
        val actionGapLinks = onDeviceActionGapLinks.value

        // Seed progress from persisted chw_module_completion + partial-completion
        // rows. In-session statusByModule always takes priority; DB rows fill the
        // gaps on first open after a restart. Partials are server-authoritative
        // for cross-device recovery.
        val completions = database.chwModuleCompletionDao()
            .getAllForChw(chwId)
            .associateBy { it.moduleFamilyId }
        val partials = database.chwModulePartialCompletionDao()
            .getAllForChw(chwId)
            .associateBy { it.moduleFamilyId }

        val mapped = modules.mapNotNull { entity ->
            val completion = completions[entity.moduleFamilyId]
            val partial = partials[entity.moduleFamilyId]
            // "completed" is sticky: `completedAt` is stamped once the CHW passes
            // and carried forward across later fails. A completion row with
            // `completedAt == null` means attempted-but-never-passed → in_progress.
            val persistedStatus = when {
                completion?.completedAt != null -> "completed"
                completion != null || partial != null -> "in_progress"
                else -> "assigned"
            }
            // Prefer in-session value; fall back to DB-derived status.
            val status = statusByModule[entity.moduleFamilyId] ?: persistedStatus
            // Sync in-memory map so future recomputes are consistent.
            if (!statusByModule.containsKey(entity.moduleFamilyId) && persistedStatus != "assigned") {
                statusByModule[entity.moduleFamilyId] = persistedStatus
            }

            val card = morningCardsByModuleId[entity.moduleId]
            // Prefer the on-device-resolved REAL action gap over the cached card's
            // gap. The backend's morning card for a referral module often carries
            // only its module_primary_gap_* placeholder (empty detection rule),
            // which would make a completed referral look like an ordinary mastered
            // module and get dropped at the "completed" check. The resolved link
            // (e.g. referral_location_upazila) keeps isActionGap true and surfaces
            // the real gap's (higher) severity.
            val actionGapLink = actionGapLinks[entity.moduleFamilyId]
            val resolvedGapId = actionGapLink?.gapId ?: card?.behaviouralGapId

            // Build the module shell first so we can use its inlineQuestions to
            // compute both wrongQuestionCount and the merged quizScorePct.
            val shell = entity.toLearnModule(
                status = status,
                gapCode = null,
                behaviouralGapId = resolvedGapId,
                source = card?.source ?: resolvedGapId?.let { "gap" },
                quizScorePct = null,
            ) ?: run {
                // Fail loud: a module with no resolvable title is a content/data bug.
                // We can't render a titleless card, but it must never vanish silently —
                // FIX AT SOURCE (module content), don't paper over it.
                Log.e(
                    TAG,
                    "dropTitleless: module=${entity.moduleId} family=${entity.moduleFamilyId} " +
                        "fromMorningCard=${card != null} — no resolvable title, FIX AT SOURCE",
                )
                return@mapNotNull null
            }

            val totalQ = shell.inlineQuestions?.size ?: 0
            val questionIds = shell.inlineQuestions?.map { it.id }?.toSet().orEmpty()

            // Local attempt history, fetched ONCE per module: the latest-attempt-
            // per-question outcome from coaching_event, bounded by [questionIds].
            val dao = database.coachingEventDao()
            val localCorrectIds: Set<String> = if (totalQ == 0) emptySet()
                else dao.getLatestCorrectQuestionIds(chwId, entity.moduleFamilyId).toSet().intersect(questionIds)
            val localWrongIds: Set<String> = if (totalQ == 0) emptySet()
                else dao.getLatestWrongQuestionIds(chwId, entity.moduleFamilyId).toSet().intersect(questionIds)

            // "To reinforce" = questions not yet mastered, INCLUDING never-attempted
            // ones — the right notion for the progress bar. Computed via the pure
            // overload so it adds NO extra per-module DB queries.
            val toReinforceCount: Int = if (totalQ == 0) {
                0
            } else {
                toReinforceQuestionIds(
                    allQuestionIds = questionIds,
                    localCorrect = localCorrectIds,
                    localWrong = localWrongIds,
                    serverIncomplete = partial?.decodeIncompleteQuizIds()?.toSet(),
                ).size
            }

            // wrongQuestionCount = questions whose LATEST local attempt was wrong —
            // the CHW's local gap. A never-attempted module reports 0.
            val wrongCount = localWrongIds.size

            // Distinct quiz questions ever attempted (right OR wrong). Cache-first:
            // a backend completion (`completedAt != null`) clamps to "all attempted"
            // even when the local coaching_event log is empty (fresh device / wipe).
            val attemptedCount: Int = when {
                totalQ == 0 -> 0
                completion?.completedAt != null -> totalQ
                else -> (localCorrectIds + localWrongIds).size
            }

            // Progress prioritization:
            //   passed-completion > partial > failed-completion > assigned
            val quizScore: Float? = when {
                completion?.completedAt != null -> 1f
                partial != null && totalQ > 0 ->
                    ((totalQ - toReinforceCount).coerceAtLeast(0)).toFloat() / totalQ
                completion != null -> completion.latestQuizScore
                else -> null
            }

            // Publication time — see QuizRetryGate. ISO 8601 from the backend
            // module sync, parsed via the existing helper.
            val publishedAtMs: Long? = parseIsoMillis(entity.publishedAtIso)

            // Action/compliance gaps re-engage the CHW on a real-world mistake, but a
            // wrong-referral refresher must be CLEARED BY A PASSING RE-DRILL (see
            // [isActionGapStillActive]). Only query the per-question "passed since the
            // mistake" set when it can actually matter — an on-device-linked gap with a
            // known mistake time and a quiz to re-pass.
            val lastWrong = actionGapLink?.lastWrongReferralAt
            val passedSinceMistake: Set<String> =
                if (lastWrong != null && questionIds.isNotEmpty()) {
                    dao.getLatestCorrectQuestionIdsSince(chwId, entity.moduleFamilyId, lastWrong)
                        .toSet()
                } else {
                    emptySet()
                }
            val isActionGap = isActionGapStillActive(
                link = actionGapLink,
                cardGapId = card?.behaviouralGapId,
                actionGapIds = actionGapIds,
                questionIds = questionIds,
                passedSinceMistake = passedSinceMistake,
            )

            shell.copy(
                quizScorePct = quizScore,
                wrongQuestionCount = wrongCount,
                reinforceQuestionCount = toReinforceCount,
                attemptedQuestionCount = attemptedCount,
                publishedAtMs = publishedAtMs,
                severity = shell.behaviouralGapId?.let { gapSeverityById[it] },
                isActionGap = isActionGap,
                // Selector provenance: a morning_card_cache row (backend or on-device)
                // exists for this exact module version → it's a refresher, full stop.
                fromMorningCard = card != null,
            )
        }.sortedWith(
            compareBy(
                // Severity takes the top, when present: high → moderate → low, then
                // any module with no resolved severity. Drives the featured pick
                // (selectFeatured takes the first refresher with a quiz), so a
                // higher-severity gap surfaces ahead of a lower one.
                {
                    when (it.severity?.lowercase()) {
                        "high" -> 0
                        "moderate" -> 1
                        "low" -> 2
                        else -> 3 // unknown / no severity → after every ranked one
                    }
                },
                // Then the prior ordering: active gaps first, …
                { if (it.behaviouralGapId != null && activeGapKeys.contains(it.behaviouralGapId)) 0 else 1 },
                // … then by progress status.
                {
                    when (it.status) {
                        "in_progress" -> 0
                        "assigned" -> 1
                        "completed" -> 2
                        else -> 3
                    }
                },
            ),
        )

        mapped
    }

    // ── LearnModule construction (moved verbatim from LearnViewModel) ───────────

    private fun ModuleEntity.toLearnModule(
        status: String,
        gapCode: String?,
        behaviouralGapId: String? = null,
        source: String? = null,
        quizScorePct: Float? = null,
    ): LearnModule? {
        val l = langProvider()
        val firstCard = try {
            Json.parseToJsonElement(cardsJson).jsonArray.firstOrNull()?.jsonObject
        } catch (_: Exception) { null }

        // Prefer the module-level title — the first-card title is only a fallback
        // for legacy modules that don't carry their own title.
        val moduleTitle = title.forLang(l)
        val title = moduleTitle
            ?: firstCard?.readLocalized("title")?.forLang(l)
            ?: return null
        // `body_*` in the v3.5+ schema may be a JSON array of rich-content blocks,
        // not a string; fall through to module-level description in that case.
        val body = firstCard?.readLocalizedBody("body", l)
            ?: firstCard?.readLocalizedBody("body", "bn")
            ?: description.forLang(l).orEmpty()
        val nextStep = firstCard?.readLocalized("next_action")?.forLang(l).orEmpty()

        val inlineQuestions = parseInlineQuiz(quizJson, l)
        val quizIds = inlineQuestions.map { it.id }

        val firstCardFamilyId = firstCard?.primitiveOrNull("card_family_id")

        // content_update fields — present only on content_update modules.
        val previousPracticeBn = firstCard?.readLocalized("previous_practice")?.bn
        val currentPracticeBn = firstCard?.readLocalized("current_practice")?.bn
        val rationaleForChangeBn = firstCard?.readLocalized("rationale_for_change")?.bn
        val nextActionBn = firstCard?.readLocalized("next_action")?.bn

        return LearnModule(
            moduleFamilyId = moduleFamilyId,
            title = title,
            body = body,
            clinicalDomain = gapCode ?: domain ?: "general",
            warningSigns = emptyList(),
            nextStep = nextStep,
            referralDestination = null,
            quizIds = quizIds,
            status = status,
            inlineQuestions = inlineQuestions.takeIf { it.isNotEmpty() },
            moduleId = moduleId,
            moduleVersion = version,
            cardFamilyId = firstCardFamilyId,
            moduleType = moduleType,
            estimatedMinutes = estimatedMinutes,
            previousPracticeBn = previousPracticeBn,
            currentPracticeBn = currentPracticeBn,
            rationaleForChangeBn = rationaleForChangeBn,
            nextActionBn = nextActionBn,
            behaviouralGapId = behaviouralGapId,
            source = source,
            cardsJson = cardsJson,
            quizScorePct = quizScorePct,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content

    private fun JsonObject.primitiveOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    // ── Tracing (greppable: `adb logcat -s ModuleStoreTrace:I`) ─────────────────

    @Volatile private var lastFeaturedId: String? = null

    private fun traceSections(all: List<LearnModule>, s: ModuleSections) {
        val sourceGap = all.count { it.source == "gap" }
        val sourceFallback = all.count { it.source == "fallback" }
        val sourceNull = all.count { it.source == null }
        Log.i(
            TAG,
            "counts: total=${all.size} refreshers=${s.refreshers.size} training=${s.training.size} " +
                "| source: gap=$sourceGap fallback=$sourceFallback null=$sourceNull",
        )
        Log.i(TAG, "pool: refreshers=${s.refreshers.map { it.moduleFamilyId }}")
        // Visibility: modules that land in no section. A non-selector, non-training
        // module legitimately has no home here. A `content_update` that arrived via
        // the selector is a contract violation — flag it loudly (FIX AT SOURCE) rather
        // than dropping a selector-provided module silently.
        all.filter {
            !it.fromMorningCard &&
                it.moduleType != "initial_training" &&
                it.moduleType != "digital_proficiency"
        }.forEach {
            Log.i(TAG, "noSection: family=${it.moduleFamilyId} type=${it.moduleType} (not selector-surfaced, not training)")
        }
        all.filter { it.fromMorningCard && it.moduleType == "content_update" }.forEach {
            Log.e(TAG, "contractViolation: content_update ${it.moduleFamilyId} arrived as a morning card — FIX AT SOURCE")
        }
    }

    /**
     * Greppable trace of the assigned-module → training-tile route, so a "missing"
     * assigned module can be pinned to its drop point:
     *   A. not in module_cache (unsynced / pruned / titleless-dropped in [map])
     *   B. in cache but moduleType == content_update (no learnable tile)
     *   C. cached & eligible but the assignment-key filter still excluded it
     *
     * Logcat: `adb logcat -s AssignedModuleTrace:I`
     */
    private fun traceAssignedFilter(
        assigned: List<AssignedModuleEntity>,
        allMapped: List<LearnModule>,
        training: List<LearnModule>,
        onScreen: List<LearnModule>,
    ) {
        fun short(s: String?) = s?.take(8) ?: "null"
        // 1. Raw rows straight from the DB (assigned_module).
        Log.i(
            TAG_ASSIGNED,
            "assignedFromDb: count=${assigned.size} " +
                "rows=${assigned.map { "mid=${short(it.moduleId)} fam=${short(it.moduleFamilyId)}" }}",
        )
        // 2. Training partition BEFORE the assignment filter, and the on-screen result AFTER.
        Log.i(
            TAG_ASSIGNED,
            "trainingPartition(pre-filter): count=${training.size} " +
                "families=${training.map { short(it.moduleFamilyId) }}",
        )
        Log.i(
            TAG_ASSIGNED,
            "onScreen(post-filter): count=${onScreen.size} " +
                "families=${onScreen.map { short(it.moduleFamilyId) }}",
        )
        if (assigned.isEmpty()) {
            Log.i(TAG_ASSIGNED, "filter SKIPPED — no assigned rows for this CHW → showing full training catalogue (fallback)")
            return
        }
        // 3. Per-assigned verdict + drop reason (the route each row took).
        val onScreenKeys = onScreen.flatMap { listOfNotNull(it.moduleId, it.moduleFamilyId) }.toSet()
        for (a in assigned) {
            val cached = allMapped.firstOrNull { it.moduleId == a.moduleId || it.moduleFamilyId == a.moduleFamilyId }
            val shown = a.moduleId in onScreenKeys || (a.moduleFamilyId != null && a.moduleFamilyId in onScreenKeys)
            val verdict = when {
                shown -> "ON_SCREEN"
                cached == null -> "DROPPED(A): not in module_cache (unsynced/pruned/titleless)"
                cached.moduleType == "content_update" ->
                    "DROPPED(B): content_update (no learnable tile on Training)"
                else -> "DROPPED(C): cached & eligible but assignment-key filter excluded it (key mismatch — investigate)"
            }
            Log.i(
                TAG_ASSIGNED,
                "assigned mid=${short(a.moduleId)} fam=${short(a.moduleFamilyId)} " +
                    "title=${cached?.title ?: "<not-cached>"} type=${cached?.moduleType ?: "?"} → $verdict",
            )
        }
    }

    private fun traceSelected(pick: LearnModule?, poolSize: Int, skipped: Set<String>) {
        val prev = lastFeaturedId
        val now = pick?.moduleFamilyId
        if (prev != null && prev != now && prev in skipped) {
            Log.i(TAG, "skip→advance: skipped=$prev nextFeatured=${now ?: "null"} remaining=$poolSize")
        }
        lastFeaturedId = now
        Log.i(
            TAG,
            "selectedCard: familyId=${now ?: "null"} source=${pick?.source} " +
                "reason=${if (pick != null) "topNonSkippedWithQuiz" else "allSkippedOrNoQuiz"}",
        )
    }

    companion object {
        private const val TAG = "ModuleStoreTrace"
        private const val TAG_ASSIGNED = "AssignedModuleTrace"
        private val EMPTY_SECTIONS = ModuleSections(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The featured-pick rule, extracted as a pure function so it is unit-testable
 * without constructing the store (the test source set has no coroutine/Room
 * harness). The first refresher the CHW hasn't skipped this session that also
 * carries a quiz — mirrors the former `ModulesScreen.featured`. Skipping a
 * family advances to the next; null when every refresher is skipped.
 */
internal fun selectFeatured(refreshers: List<LearnModule>, skipped: Set<String>): LearnModule? =
    refreshers.firstOrNull { it.moduleFamilyId !in skipped && !it.inlineQuestions.isNullOrEmpty() }

/**
 * Whether a module should be treated as a live action/compliance gap (and thus
 * bypass the mastery/completed drops), extracted as a pure function so the
 * re-drill gate is unit-testable without a DB.
 *
 * Semantics:
 *  - **On-device-linked gap** ([link] non-null — e.g. a wrong referral resolved to
 *    `referral_location_upazila`): the gap is "cleared" only once the CHW re-passes
 *    EVERY quiz question *after* the mistake. So it stays active while any question
 *    is not in [passedSinceMistake] (questions whose latest correct attempt is newer
 *    than [ActionGapLink.lastWrongReferralAt]). Fail-open when the mistake time is
 *    unknown or the module ships no quiz to re-pass — never silently hide a live
 *    mistake.
 *  - **No link**: plain catalogue check (a gap bound directly to the module's morning
 *    card, e.g. once `/sync/triggers` ships the binding) — ungated, prior behaviour.
 */
internal fun isActionGapStillActive(
    link: ActionGapLink?,
    cardGapId: String?,
    actionGapIds: Set<String>,
    questionIds: Set<String>,
    passedSinceMistake: Set<String>,
): Boolean = when {
    link != null -> when {
        link.lastWrongReferralAt == null -> true   // mistake time unknown → keep showing
        questionIds.isEmpty() -> true              // no quiz to re-pass → can't be drilled away
        else -> questionIds.any { it !in passedSinceMistake } // active until ALL passed since
    }
    else -> cardGapId != null && cardGapId in actionGapIds
}

