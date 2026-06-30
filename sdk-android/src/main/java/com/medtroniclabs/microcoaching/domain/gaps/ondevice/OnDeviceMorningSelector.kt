package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity

/**
 * Pure morning-card selection, mirroring the backend `module_suggestion_service`:
 *
 *  1. Candidate gaps = effective states with `status IN (active, monitoring)` whose
 *     catalogue gap is `active`.
 *  2. Rank by `(severity_rank, −occurrence_count, −last_observed_at)`.
 *  3. Walk ranked gaps, taking each gap's canonical module (latest version), one
 *     family per gap, `source = "gap"`, until [limit].
 *  4. When there are no gap picks, the **generator** falls back: nothing while
 *     backend cards are present (authoritative), else [selectFromTodaysAppointments]
 *     over the CHW's today's-visit candidates (resolved from the synced `assessment_due`
 *     trigger bindings). Both [select] and [selectFromTodaysAppointments] are pure.
 *
 * Output is [MorningCardCacheEntity] rows so the existing morning/refresher
 * pipeline consumes them unchanged. Pure: recency is supplied via [recencyOf]
 * so the selector needs no date parsing to be unit-tested.
 */
object OnDeviceMorningSelector {

    /** Gap-driven picks for the CHW; empty when no active gap maps to a synced module. */
    fun select(
        states: Map<String, GapState>,
        gapsById: Map<String, BehaviouralGapEntity>,
        index: ModuleGapIndex,
        modulesByFamily: Map<String, ModuleEntity>,
        limit: Int,
        nowMillis: Long,
        recencyOf: (ModuleEntity) -> Long,
    ): List<MorningCardCacheEntity> =
        selectGapDriven(states, gapsById, index, modulesByFamily, limit, nowMillis, recencyOf)

    private fun selectGapDriven(
        states: Map<String, GapState>,
        gapsById: Map<String, BehaviouralGapEntity>,
        index: ModuleGapIndex,
        modulesByFamily: Map<String, ModuleEntity>,
        limit: Int,
        nowMillis: Long,
        recencyOf: (ModuleEntity) -> Long,
    ): List<MorningCardCacheEntity> {
        val rankedGaps = states.values
            .filter { it.status == GapStatus.ACTIVE || it.status == GapStatus.MONITORING }
            .filter { gapsById[it.behaviouralGapId]?.status == "active" }
            .sortedWith(
                compareBy<GapState>(
                    { severityRank(gapsById[it.behaviouralGapId]?.severityDefault) },
                    { -it.occurrenceCount },
                    { -(it.lastObservedAt ?: 0L) },
                ),
            )

        val picks = ArrayList<MorningCardCacheEntity>(limit)
        val usedFamilies = HashSet<String>()
        for (gap in rankedGaps) {
            if (picks.size >= limit) break
            val module = index.gapToFamilies[gap.behaviouralGapId].orEmpty()
                .asSequence()
                .filter { it !in usedFamilies }
                .mapNotNull { modulesByFamily[it] }
                .sortedByDescending { recencyOf(it) }
                .firstOrNull() ?: continue
            picks += MorningCardCacheEntity(
                moduleId = module.moduleId,
                moduleFamilyId = module.moduleFamilyId,
                source = SOURCE_GAP,
                behaviouralGapId = gap.behaviouralGapId,
                rank = picks.size,
                fetchedAt = nowMillis,
            )
            usedFamilies += module.moduleFamilyId
        }
        return picks
    }

    /**
     * Visit-driven stand-in (offline / fresh CHW with no gap picks and no backend
     * cards): turn today's-visit [candidates] — resolved by [VisitModuleResolver]
     * from the synced `assessment_due` trigger bindings — into refresher cards. Highest
     * `priorityWeight` first, one card per family, capped at [limit]; a family with
     * no synced module is skipped. Cards carry `source = "visit"` and no behavioural
     * gap. Pure (the caller does the DB resolution + today filtering).
     */
    fun selectFromTodaysAppointments(
        candidates: List<VisitCandidate>,
        modulesByFamily: Map<String, ModuleEntity>,
        limit: Int,
        nowMillis: Long,
    ): List<MorningCardCacheEntity> {
        val picks = ArrayList<MorningCardCacheEntity>(limit)
        val usedFamilies = HashSet<String>()
        for (candidate in candidates.sortedByDescending { it.priorityWeight }) {
            if (picks.size >= limit) break
            val module = modulesByFamily[candidate.moduleFamilyId] ?: continue
            if (!usedFamilies.add(candidate.moduleFamilyId)) continue
            picks += MorningCardCacheEntity(
                moduleId = module.moduleId,
                moduleFamilyId = module.moduleFamilyId,
                source = SOURCE_VISIT,
                behaviouralGapId = null,
                rank = picks.size,
                fetchedAt = nowMillis,
            )
        }
        return picks
    }

    private fun severityRank(severity: String?): Int = when (severity) {
        "high" -> 0
        "moderate" -> 1
        "low" -> 2
        else -> 3
    }

    /**
     * Quiz-driven picks — the backend's default mode, mirroring
     * `module_suggestion_service._suggest_from_quiz_state`: active quiz states with
     * `failedAttemptsCount > 0`, ranked `(failedAttemptsCount DESC, lastFailedAttemptAt
     * DESC)`, **one module per family, capped at [limit]**, `source = "quiz"` carrying
     * the `quiz_id`. Strict backend parity — no recency reserve. The quiz's module is
     * resolved by `quiz.moduleId` via [modulesById].
     *
     * Comprehensive trace (`OnDeviceMorningTrace`): the ranked active set, what was
     * kept, what was dropped past the cap, deduped by family, or had no synced module —
     * so a buried newly-attempted quiz is visible.
     */
    fun selectQuizDriven(
        quizStates: Collection<QuizState>,
        modulesById: Map<String, ModuleEntity>,
        limit: Int,
        nowMillis: Long,
    ): List<MorningCardCacheEntity> {
        val ranked = quizStates
            .filter { it.status == GapStatus.ACTIVE && it.failedAttemptsCount > 0 }
            .sortedWith(
                compareByDescending<QuizState> { it.failedAttemptsCount }
                    .thenByDescending { it.lastFailedAttemptAt ?: 0L },
            )

        val picks = ArrayList<MorningCardCacheEntity>(limit)
        val usedFamilies = HashSet<String>()
        val droppedPastCap = ArrayList<String>()
        val dedupDropped = ArrayList<String>()
        var unresolvedModule = 0
        for (quiz in ranked) {
            val module = modulesById[quiz.moduleId]
            if (module == null) {
                unresolvedModule++
                continue
            }
            if (picks.size >= limit) {
                droppedPastCap += "${quiz.quizId}/${module.moduleFamilyId}(failed=${quiz.failedAttemptsCount})"
                continue
            }
            if (!usedFamilies.add(module.moduleFamilyId)) {
                dedupDropped += "${quiz.quizId}/${module.moduleFamilyId}"
                continue
            }
            picks += MorningCardCacheEntity(
                moduleId = module.moduleId,
                moduleFamilyId = module.moduleFamilyId,
                source = SOURCE_QUIZ,
                behaviouralGapId = null,
                quizId = quiz.quizId,
                rank = picks.size,
                fetchedAt = nowMillis,
            )
        }
        Log.i(
            TAG,
            "quizRanked: active=${ranked.size} kept=${picks.map { it.quizId to it.moduleFamilyId }} " +
                "droppedPastCap=$droppedPastCap dedupDropped=$dedupDropped unresolvedModule=$unresolvedModule",
        )
        return picks
    }

    private const val SOURCE_GAP = "gap"
    private const val SOURCE_VISIT = "visit"
    private const val SOURCE_QUIZ = "quiz"
    private const val TAG = "OnDeviceMorningTrace"
}
