package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.domain.context.TodaysVisit
import java.util.TimeZone

/**
 * On-device companion to `GET /morning/cards`: computes gap state from cached
 * data, selects the refresher set the backend would, and writes it into
 * `morning_card_cache` so the existing morning/refresher pipeline
 * ([com.medtroniclabs.microcoaching.domain.morning.MorningModuleResolver] →
 * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore]) consumes
 * it unchanged.
 *
 * Runs on every morning refresh (online and offline). It COEXISTS with the
 * backend's cards — it writes only its own rows (`on_device = 1`) and never
 * overwrites the backend's — so on-device gap cards (e.g. referral compliance
 * computed from still-unsynced events) merge on top of the backend selection.
 *
 * Gap→module links come solely from the synced [ModuleGapIndex] (trigger
 * bindings today; the backend's `module_behavioural_gap` relationship once it
 * ships). There is intentionally NO fuzzy fallback: a gap with no link simply
 * does not surface until the real link arrives. A missing link is a data gap to
 * fix at the source, not to guess at on-device — so until that relationship is
 * synced, expect this generator to under-produce, which is acceptable and known.
 */
internal class OnDeviceMorningGenerator(
    private val database: MicroCoachingDatabase,
    private val config: GapStateConfig,
    private val limit: Int = DEFAULT_LIMIT,
    /** Which morning-card sources are active. Default = quiz-level only (backend parity). */
    private val sources: MorningSourcesConfig = MorningSourcesConfig(),
    /**
     * Publishes `moduleFamilyId → real action-gap id` for every action/compliance
     * gap this run resolved (e.g. a wrong-referral module linked to
     * `referral_location_upazila`). Emitted even for cards the coexistence filter
     * drops, so the store can recognise the true action gap regardless of which
     * gap id the cached morning-card row ends up carrying. Defaults to a no-op.
     */
    private val publishActionGapLinks: (Map<String, ActionGapLink>) -> Unit = {},
    /**
     * Supplies the CHW's patient visits due today, pushed by the SPICE host via
     * [com.medtroniclabs.microcoaching.MicroCoachingSDK.onTodaysVisitsUpdated]. Used
     * only as the no-gaps / no-backend stand-in. Defaults to none (ship-safe before
     * the host plumbing lands).
     */
    private val loadTodaysVisits: () -> List<TodaysVisit> = { emptyList() },
) {
    private val resolver = ModuleGapResolver(
        moduleDao = database.moduleDao(),
    )
    private val engine = OnDeviceGapStateEngine(
        gapProfileDao = database.chwGapProfileDao(),
        eventDao = database.coachingEventDao(),
        config = config,
    )
    private val quizEngine = OnDeviceQuizStateEngine(
        quizStateDao = database.chwQuizQuestionStateDao(),
        eventDao = database.coachingEventDao(),
        config = config,
    )

    /**
     * Generate and persist the morning-card set for [chwId]. Returns the number
     * of cards written (0 when nothing could be selected — the caller keeps its
     * existing fallback behaviour).
     */
    suspend fun generate(chwId: String, nowMillis: Long): Int {
        Log.i(
            TAG,
            "generate: chw=$chwId — computing offline morning cards " +
                "(sources: quiz=${sources.quiz} gap=${sources.gap} referral=${sources.referral} visit=${sources.visit})",
        )

        val allModules = database.moduleDao().getAllOrderedOnce()
        val modulesByFamily = allModules
            .groupBy { it.moduleFamilyId }
            .mapValues { (_, versions) -> versions.maxByOrNull { it.version }!! }
        val modulesById = allModules.associateBy { it.moduleId }

        // Gaps carrying a detection_rule are action/compliance (referral) gaps.
        val actionGapIds = database.behaviouralGapDao().getActiveWithRules().map { it.gapId }.toSet()

        // Backend's current morning cards (read before selection): parity log + coexistence.
        val backendCards = database.morningCardCacheDao().getAllOrderedOnce().filterNot { it.onDevice }
        val backendFamilies = backendCards.map { it.moduleFamilyId }.toSet()

        // ── 1. Quiz-level refreshers (backend default) ──
        val quizCards = if (sources.quiz) {
            val quizStates = quizEngine.computeStates(chwId)
            OnDeviceMorningSelector.selectQuizDriven(quizStates.values, modulesById, limit, nowMillis)
        } else {
            emptyList()
        }

        // ── 2. Legacy behavioural-gap level + referral action gaps (both off by default) ──
        // The gap engine is only run when one of these is on; its assessment (referral)
        // branch is gated by `sources.referral`. Referral-only keeps just the action gaps.
        var gapStates: Map<String, GapState> = emptyMap()
        val gapCards: List<MorningCardCacheEntity> = if (sources.gap || sources.referral) {
            val index = resolver.loadIndex()
            Log.i(
                TAG,
                "index: gapBindings=${index.gapToFamilies.size} " +
                    "boundGaps=${index.gapToFamilies.keys} (from synced gap↔module links)",
            )
            gapStates = engine.computeStates(chwId, index, enableAssessment = sources.referral)
            val gapsById = database.behaviouralGapDao().getAllActiveOnce().associateBy { it.gapId }
            val all = OnDeviceMorningSelector.select(
                states = gapStates,
                gapsById = gapsById,
                index = index,
                modulesByFamily = modulesByFamily,
                limit = limit,
                nowMillis = nowMillis,
                recencyOf = ::recencyOf,
            )
            if (sources.gap) all else all.filter { it.behaviouralGapId in actionGapIds }
        } else {
            emptyList()
        }

        // ── 3. Merge primary picks (quiz first), one card per family, capped ──
        val primary = (quizCards + gapCards).distinctBy { it.moduleFamilyId }.take(limit)

        // ── 4. Gate: primary → backend authoritative → today's-visit stand-in ──
        val selected = when {
            primary.isNotEmpty() -> {
                Log.i(
                    TAG,
                    "source=GAP_DRIVEN picks=${primary.size} (quiz=${quizCards.size} gap=${gapCards.size}; " +
                        "visits/backend not consulted)",
                )
                primary
            }
            backendCards.isNotEmpty() -> {
                Log.i(TAG, "source=BACKEND_AUTHORITATIVE backendCards=${backendCards.size} (on-device adds nothing)")
                emptyList()
            }
            sources.visit -> {
                // No quiz/gap picks + no backend → today's-visit stand-in (synced
                // `assessment_due` trigger bindings; see VisitModuleResolver).
                val loaded = loadTodaysVisits()
                val dueToday = loaded.filter { isToday(it.dueDateIso, nowMillis) }
                val triggers = database.triggerDefinitionDao().getByKind(WORKFLOW_EVENT_KIND)
                val bindingsByTrigger = database.moduleTriggerBindingDao().getAll()
                    .groupBy { it.triggerDefinitionId }
                val candidates = VisitModuleResolver.resolve(dueToday, triggers, bindingsByTrigger, modulesByFamily)
                val visitCards = OnDeviceMorningSelector.selectFromTodaysAppointments(
                    candidates = candidates,
                    modulesByFamily = modulesByFamily,
                    limit = limit,
                    nowMillis = nowMillis,
                )
                Log.i(
                    TAG,
                    "source=VISITS loadedFromHost=${loaded.size} dueToday=${dueToday.size} " +
                        "triggers=${triggers.size} candidates=${candidates.size} cards=${visitCards.size} " +
                        "families=${visitCards.map { it.moduleFamilyId }}",
                )
                visitCards
            }
            else -> {
                Log.i(TAG, "source=NONE (no quiz/gap picks, no backend cards, visit source off)")
                emptyList()
            }
        }

        // Stable 0-based rank after the merge.
        val cards = selected.mapIndexed { i, c -> c.copy(rank = i) }

        trace(chwId, gapStates, cards, actionGapIds)

        // Publish family → real action-gap id for every action/compliance card we
        // resolved — BEFORE the coexistence filter below, so it covers families the
        // backend already surfaced (which it often does via a module_primary_gap_*
        // placeholder that carries no detection rule). The store overlays this so a
        // completed referral module is still recognised as an action gap and kept in
        // the refresher list; a wrong referral is too important to hide. Keyed by
        // family so it's independent of the cached row's gap id. An empty map clears
        // a stale link once the gap resolves.
        val actionGapLinks = if (sources.referral) {
            cards
                .filter { it.behaviouralGapId != null && it.behaviouralGapId in actionGapIds }
                .associate { card ->
                    val gapId = card.behaviouralGapId!!
                    // lastFailedAttemptAt is the timestamp the engine stamped from the
                    // latest incorrect referral — the mistake the store gates the
                    // re-drill against.
                    card.moduleFamilyId to ActionGapLink(
                        gapId = gapId,
                        lastWrongReferralAt = gapStates[gapId]?.lastFailedAttemptAt,
                    )
                }
        } else {
            emptyMap()
        }
        publishActionGapLinks(actionGapLinks)
        if (actionGapLinks.isNotEmpty()) {
            Log.i(TAG, "actionGapLinks: published ${actionGapLinks.size} family→gap link(s) $actionGapLinks")
        }

        // ── Parity check: on-device selection vs the backend's GET /morning/cards ──
        // This selector mirrors the backend `module_suggestion_service`, so for the
        // same CHW the two SHOULD pick the same module families. Log them side-by-side
        // so any divergence is visible in logcat. Compared BY FAMILY: both sides pick
        // one canonical module per family, so the family id is the stable key (the
        // exact module version may differ). When offline the backend rows are stale or
        // absent, so backend=0 here just means "no live fetch this run", not a mismatch.
        // (backendCards / backendFamilies were read above, before selection.)
        val onDeviceFamilies = cards.map { it.moduleFamilyId }.toSet()
        val onlyBackend = backendFamilies - onDeviceFamilies
        val onlyOnDevice = onDeviceFamilies - backendFamilies
        val onDeviceActionGaps = cards.count { it.behaviouralGapId?.let { g -> g in actionGapIds } == true }
        Log.i(
            TAG,
            "compare: onDevice=${cards.size} backend=${backendFamilies.size} " +
                "match=${onlyBackend.isEmpty() && onlyOnDevice.isEmpty()} " +
                "overlap=${(backendFamilies intersect onDeviceFamilies).size} " +
                "onlyBackend=$onlyBackend onlyOnDevice=$onlyOnDevice " +
                "onDeviceActionGaps=$onDeviceActionGaps (referral/compliance the backend doesn't compute)",
        )

        // COEXIST with the backend's morning cards instead of replacing them.
        // Drop any family the backend already surfaced (no duplicates / PK churn),
        // then replace ONLY the on-device rows (`on_device = 1`). The backend's
        // `replaceBackend` and this `replaceOnDevice` each own their own rows, so the
        // two writers no longer wipe each other (the cause of the card flapping
        // between a backend pick and the referral card). replaceOnDevice with an
        // empty list still clears stale on-device rows (e.g. a resolved gap).
        val onDeviceCards = cards.filterNot { it.moduleFamilyId in backendFamilies }
        database.morningCardCacheDao().replaceOnDevice(onDeviceCards)
        return onDeviceCards.size
    }

    /** Publication time for recency ordering; falls back to the cache sync time. */
    private fun recencyOf(module: ModuleEntity): Long =
        parseIsoMillis(module.publishedAtIso) ?: module.lastSynced

    /**
     * True when [dueDateIso] falls on the same **device-local** calendar day as
     * [nowMillis]. Unparseable dates are excluded. (Local-day per the team's plan;
     * switch to a SPICE-supplied reference date if one is ever passed in.)
     */
    private fun isToday(dueDateIso: String, nowMillis: Long): Boolean {
        val dueMillis = parseIsoMillis(dueDateIso) ?: return false
        val zone = TimeZone.getDefault()
        return localEpochDay(dueMillis, zone) == localEpochDay(nowMillis, zone)
    }

    private fun localEpochDay(millis: Long, zone: TimeZone): Long =
        Math.floorDiv(millis + zone.getOffset(millis), DAY_MS)

    private fun trace(
        chwId: String,
        gapStates: Map<String, GapState>,
        cards: List<MorningCardCacheEntity>,
        actionGapIds: Set<String>,
    ) {
        if (gapStates.isNotEmpty()) {
            val active = gapStates.values.count { it.status == GapStatus.ACTIVE || it.status == GapStatus.MONITORING }
            Log.i(TAG, "states: chw=$chwId gapStates=${gapStates.size} gapActive=$active")
        }

        // Per-card provenance: the source (quiz-level, legacy gap-driven, today's-visit,
        // or fallback), the gap/quiz id it carries, and — for source="gap" — whether
        // it's a detection-rule action gap (the referral path) vs a quiz-gap.
        cards.forEach { c ->
            val gap = c.behaviouralGapId
            val origin = when (c.source) {
                "quiz" -> "quiz-level"
                "gap" -> if (gap != null && gap in actionGapIds) "gap-driven(assessment/referral)" else "gap-driven(quiz-gap)"
                "visit" -> "today-visit"
                else -> "fallback"
            }
            Log.i(
                TAG,
                "card: family=${c.moduleFamilyId} gap=${gap ?: "none"} quiz=${c.quizId ?: "none"} " +
                    "source=${c.source} origin=$origin",
            )
        }

        val quizCount = cards.count { it.source == "quiz" }
        val gapDriven = cards.count { it.source == "gap" }
        val actionCount = cards.count { it.behaviouralGapId?.let { g -> g in actionGapIds } == true }
        Log.i(
            TAG,
            "picks: count=${cards.size} quiz=$quizCount gap=$gapDriven (assessment/referral=$actionCount) " +
                "visit=${cards.count { it.source == "visit" }} families=${cards.map { it.moduleFamilyId }}",
        )
    }

    companion object {
        private const val TAG = "OnDeviceMorningTrace"
        private const val DEFAULT_LIMIT = 5
        private const val DAY_MS = 86_400_000L
        // Visit triggers (`wf:assessment_due:*`) are `workflow_event`s.
        private const val WORKFLOW_EVENT_KIND = "workflow_event"
    }
}
