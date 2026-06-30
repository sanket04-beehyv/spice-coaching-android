package com.medtroniclabs.microcoaching.domain.refresher

import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import kotlin.math.roundToInt

/**
 * Refresher quiz-subset selection.
 *
 * A refresher's *membership* on the morning list is decided upstream and is
 * selector-authoritative (a `morning_card_cache` row exists — backend or
 * on-device; see [com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer]).
 * This file decides only WHICH of the module's questions to present once a refresher
 * is shown — instead of re-testing the whole module.
 *
 * Policy (quiz-level model): **every question the CHW most recently got wrong is
 * resurfaced, uncapped** — 3 wrong → all 3 come back. The `k = round(N*ratio)` count
 * applies ONLY as the no-wrong-history nudge (never-attempted / fully-mastered module),
 * so the sheet still has content there. Pure (no DB / no clock) so it is unit-testable;
 * the caller supplies the per-day seed.
 */

private const val TAG = "RefresherQuestionSelector"

/**
 * Target number of questions to present: `round(total * ratio)` clamped to
 * `[min, max]`, and never more than [total]. Depends only on the module size, so
 * the count is stable regardless of attempt history.
 */
internal fun refresherQuestionCount(total: Int, ratio: Float, min: Int, max: Int): Int {
    if (total <= 0) return 0
    val lo = min.coerceAtLeast(1)
    val hi = max.coerceAtLeast(lo)
    return (total * ratio).roundToInt().coerceIn(lo, hi).coerceAtMost(total)
}

/**
 * The questions to present for today's refresher of [all].
 *
 * - **If the CHW has wrong questions** ([wrong] non-empty): return **all of them**, in
 *   authored order, **ignoring [k]** — the whole point of the refresher is to re-drill
 *   exactly what was missed (3 wrong → 3 questions). This is the [k]-cap fix: the count
 *   no longer collapses to ~2 regardless of how many were wrong.
 * - **Otherwise** (never-attempted / fully-mastered — no wrong history): present a
 *   [k]-sized, [daySeed]-rotating nudge so the sheet still has content. Stable within a
 *   day; rotates across days.
 *
 * @param all module questions in their authored order
 * @param wrong ids whose latest local attempt was wrong (all surfaced when present)
 * @param k nudge size used ONLY when [wrong] is empty (see [refresherQuestionCount])
 * @param daySeed a per-module, per-day seed (e.g. `moduleId.hashCode() xor epochDay`)
 */
internal fun selectRefresherQuestions(
    all: List<QuizQuestion>,
    wrong: Set<String>,
    k: Int,
    daySeed: Long,
): List<QuizQuestion> {
    if (all.isEmpty()) return emptyList()

    // Resurface EVERY incorrect question, uncapped (authored order).
    val weak = all.filter { it.id in wrong }
    if (weak.isNotEmpty()) {
        android.util.Log.d(TAG, "selectRefresherQuestions: surfacing all ${weak.size}/${all.size} incorrect question(s)")
        return weak
    }

    // No wrong history → small day-rotating nudge.
    android.util.Log.d(TAG, "selectRefresherQuestions: no wrong history → k-nudge all=${all.size} k=$k daySeed=$daySeed")
    if (k <= 0) return emptyList()
    if (k >= all.size) return all
    return rotate(all, daySeed).take(k)
}

/** Deterministic rotation: a day-seeded start offset; relative order preserved. */
private fun rotate(items: List<QuizQuestion>, daySeed: Long): List<QuizQuestion> {
    if (items.size <= 1) return items
    val offset = (((daySeed % items.size) + items.size) % items.size).toInt()
    return items.drop(offset) + items.take(offset)
}
