package com.medtroniclabs.microcoaching.domain.morning

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import com.medtroniclabs.microcoaching.sync.SyncApi
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Resolves the prioritised morning-module list and publishes it into the SDK's
 * [morningModules] / [morningCardsItems] flows. Extracted from `MicroCoachingSDK`
 * (which was a god object) — behaviour is unchanged; this only relocates the
 * 4-tier resolution cluster behind a single collaborator.
 *
 * The tiers, in order:
 *   1. seed from the local `morning_card_cache`,
 *   2. live `GET /morning/cards` refresh (writes the backend's selection),
 *   3. on-device [OnDeviceMorningGenerator] — **always** runs; it merges gap-driven
 *      cards the backend doesn't compute (e.g. referral compliance) on top of the
 *      backend's, and is the sole source when the endpoint is unavailable / offline,
 *   4. fresh-CHW local fallback (first un-mastered, non-`content_update` module).
 *
 * The two state flows are owned by the SDK and passed in by reference so the
 * SDK's public read-only views stay backed by the same instances.
 */
internal class MorningModuleResolver(
    private val database: MicroCoachingDatabase,
    private val config: MicroCoachingConfig,
    private val apiService: CoachingApiService,
    private val onDeviceMorningGenerator: OnDeviceMorningGenerator,
    private val langCode: () -> String,
    private val morningModules: MutableStateFlow<List<ModuleEntity>>,
    private val morningCardsItems: MutableStateFlow<List<MorningCardCacheEntity>>,
) {

    /**
     * Core 4-tier morning resolution used by both `onHomeScreenShown` and
     * `onMorningOpen`.
     *
     * Step 1: seed the list immediately from the local cache (already-loaded from
     * DB or the in-memory [morningCardsItems] state).
     * Step 2: attempt a live `GET /morning/cards` fetch; on success it writes the
     * backend's selection into the cache.
     * Step 3: always run the [OnDeviceMorningGenerator], which merges its gap-driven
     * cards into the cache (assisting the backend, or standing in for it offline).
     */
    suspend fun refresh(chwId: String) {
        try {
            // ── Tier 1 / Tier 2 seed: use whatever is already in the cache ──
            val cached = database.morningCardCacheDao().getAllOrderedOnce()
            if (cached.isNotEmpty()) {
                publish(cached, chwId)
            }

            if (config.backendUrl.isNotBlank()) {
                // ── Live fetch — writes the backend's selection into the cache ──
                val syncApi = SyncApi(
                    apiService = apiService,
                    db = database,
                    sessionId = "morning-refresh",
                    chwId = chwId,
                )
                val result = syncApi.pullMorningCards(
                    chwId = chwId,
                    tenantId = config.tenantId.takeIf { it.isNotBlank() },
                )
                if (result.success) {
                    Log.i(TAG, "Morning cards live fetch OK: ${result.count} items")
                } else {
                    Log.d(TAG, "Morning cards live fetch skipped/failed: ${result.error}")
                }
            }

            // ── On-device generator ALWAYS runs (online and offline) ──
            // It ASSISTS the backend's morning cards — adding gap-driven cards the
            // backend doesn't compute (e.g. referral compliance) — and is the sole
            // source when the endpoint is unavailable. It MERGES with any backend
            // cards from the live fetch (preserving them), so it's no longer gated
            // on the live fetch failing. `publish` then resolves + filters the union.
            val generated = onDeviceMorningGenerator.generate(chwId, System.currentTimeMillis())
            Log.i(TAG, "Morning cards on-device assist: +$generated item(s)")
            publish(database.morningCardCacheDao().getAllOrderedOnce(), chwId)

            // ── Fresh-CHW local fallback ──
            applyLocalFallbackIfEmpty(chwId)
        } catch (e: Exception) {
            Log.w(TAG, "refreshMorningModules failed: ${e.message}")
            if (morningModules.value.isEmpty()) morningModules.value = emptyList()
        }
    }

    /**
     * Re-apply [keepIfHasReinforceQuestions] to the latest morning-card cache.
     * Invoked from the SDK's event-flow collector so the home banner walks to the
     * next unmastered module the moment the CHW finishes the last wrong question
     * of the current one — no need to wait for a fresh `onHomeScreenShown`.
     *
     * Also called by `InboundSyncWorker` after `chw_module_partial_completion`
     * rows land, so a fresh-device CHW sees the morning list re-filter against the
     * server-known progress before they answer anything locally.
     *
     * Falls back to [applyLocalFallbackIfEmpty] so a fresh CHW (no cache) or a CHW
     * who just mastered their last morning-card module still sees a top module.
     */
    suspend fun refilter(chwId: String) {
        val cache = database.morningCardCacheDao().getAllOrderedOnce()
        if (cache.isEmpty()) {
            morningCardsItems.value = emptyList()
            morningModules.value = emptyList()
        } else {
            publish(cache, chwId)
        }
        applyLocalFallbackIfEmpty(chwId)
    }

    /** Joins [MorningCardCacheEntity] items with `module_cache` in rank order. */
    private suspend fun resolveFromCache(cache: List<MorningCardCacheEntity>): List<ModuleEntity> {
        if (cache.isEmpty()) return emptyList()
        val byId = cache.associate { it.moduleId to it.rank }
        val allModules = database.moduleDao().getAllOrderedOnce()
        val matched = allModules.filter { it.moduleId in byId }
        val dropped = byId.keys - matched.map { it.moduleId }.toSet()
        if (dropped.isNotEmpty()) {
            // A selector emitted a card for a module that isn't in module_cache — a
            // sync gap. We can't render content we don't have, but this must be LOUD
            // (FIX AT SOURCE: /sync/modules), never a silent omission of a selector card.
            Log.e(TAG, "resolveFromCache: ${dropped.size} morning-card module(s) MISSING from " +
                "module_cache (not synced) — FIX AT SOURCE. droppedIds=$dropped")
        } else {
            Log.d(TAG, "resolveFromCache: cache=${cache.size} allModules=${allModules.size} matched=${matched.size}")
        }
        return matched.sortedBy { byId[it.moduleId] ?: Int.MAX_VALUE }
    }

    /**
     * Resolves the morning-card cache to modules and publishes both the cache list
     * and the module list together, in backend-priority order.
     *
     * Filtering, in order:
     *  1. [resolveFromCache] drops cards whose module isn't synced locally (logged loudly).
     *  2. [keepIfHasReinforceQuestions] drops modules the CHW has **fully mastered
     *     locally** — every quiz question attempted and its latest attempt correct — so a
     *     refresher disappears once answered correctly, **offline and even over a stale
     *     backend card**. Never-/partially-attempted modules (and quizless content) are
     *     kept, and server-known partials are honoured, so a cross-device gap is never
     *     wrongly dropped. (Quiz-level requirement; supersedes the earlier
     *     selector-authoritative "no droppers" stance for the quiz era.)
     */
    private suspend fun publish(cache: List<MorningCardCacheEntity>, chwId: String) {
        val resolved = resolveFromCache(cache)
        val kept = resolved.filter { keepIfHasReinforceQuestions(it, chwId) }
        val dropped = resolved.size - kept.size
        if (dropped > 0) {
            Log.i(TAG, "publish: dropped $dropped mastered module(s) (no local to-reinforce questions); kept=${kept.size}")
        }
        val keptIds = kept.map { it.moduleId }.toSet()
        morningCardsItems.value = cache.filter { it.moduleId in keptIds }
        morningModules.value = kept
    }

    /**
     * `true` when the module either has no inline quiz, or has at least one
     * question whose latest attempt was wrong / never attempted.
     *
     * Routes through [toReinforceQuestionIds] so a fresh-device CHW with a
     * server-known partial-completion row still sees the morning card surface
     * (even though the local `coaching_event` table is empty).
     */
    private suspend fun keepIfHasReinforceQuestions(entity: ModuleEntity, chwId: String): Boolean {
        val parsed = parseInlineQuiz(entity.quizJson, langCode())
        if (parsed.isEmpty()) return true
        val allIds = parsed.map { it.id }.toSet()
        val toReinforce = toReinforceQuestionIds(
            db = database,
            chwId = chwId,
            moduleFamilyId = entity.moduleFamilyId,
            allQuestionIds = allIds,
        )
        return toReinforce.isNotEmpty()
    }

    /**
     * Tier-4 morning-module fallback. When [morningModules] is empty (fresh CHW
     * with zero observed gaps, empty backend morning-cards response, or every
     * morning-card module just got mastered), surface the first non-`content_update`
     * module with un-mastered questions so the home banner and refresher card still
     * have a useful target. Purely client-side and transient — never persisted.
     */
    private suspend fun applyLocalFallbackIfEmpty(chwId: String) {
        if (morningModules.value.isNotEmpty()) return
        val candidate = database.moduleDao().getAllOrderedOnce()
            .firstOrNull { it.moduleType != "content_update" && keepIfHasReinforceQuestions(it, chwId) }
        if (candidate == null) {
            Log.d(TAG, "Morning modules: no local-fallback candidate available")
            return
        }
        val synthetic = MorningCardCacheEntity(
            moduleId = candidate.moduleId,
            moduleFamilyId = candidate.moduleFamilyId,
            source = "local_fallback",
            behaviouralGapId = null,
            rank = 0,
            fetchedAt = System.currentTimeMillis(),
        )
        morningCardsItems.value = listOf(synthetic)
        morningModules.value = listOf(candidate)
        Log.i(TAG, "Morning modules via local fallback: ${candidate.moduleFamilyId} (source=local_fallback)")
    }

    private companion object {
        private const val TAG = "MorningModuleResolver"
    }
}
