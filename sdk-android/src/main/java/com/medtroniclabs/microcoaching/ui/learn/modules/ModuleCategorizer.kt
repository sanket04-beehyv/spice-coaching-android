package com.medtroniclabs.microcoaching.ui.learn.modules

import android.util.Log
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Three partitions of the input module list. **Pairwise non-disjoint** —
 * a single module can appear in more than one section (e.g. an
 * `initial_training` module that's also morning-card-surfaced with wrong
 * questions lands in **both** Training and Refresher). This is intentional:
 * the Refresher row exists to push "drill this today" prominence
 * regardless of the underlying module type, while Training/Knowledge are
 * the type-based libraries.
 *
 * Invariant: each list preserves the input order and contains no
 * duplicates among itself.
 *
 * Modules that don't match any rule are silently dropped (logged via
 * [android.util.Log] at debug level). See [ModuleCategorizer] for the
 * rule contract.
 */
data class ModuleSections(
    val refreshers: List<LearnModule>,
    val knowledge: List<LearnModule>,
    val training: List<LearnModule>,
)

/**
 * Partitions [LearnModule]s into the three sections rendered by
 * [com.medtroniclabs.microcoaching.ui.learn.modules.ModulesScreen]:
 *
 *  - **Training** — `moduleType == "initial_training"` OR
 *    `"digital_proficiency"`, always. Even completed modules render here.
 *    (`digital_proficiency` joined Training when the Knowledge section
 *    switched from modules to source documents — see below.)
 *  - **Knowledge** — **no longer a module partition.** The Knowledge
 *    section now shows the deduped *source documents* of all modules
 *    (`KnowledgeDocController.knowledgeDocuments`), not modules.
 *    [ModuleSections.knowledge] is kept (always empty) only to avoid churn in
 *    existing destructuring; no module is ever placed into it.
 *  - **Refresher** — "active drilling" queue. **Selector-authoritative:**
 *      `moduleType != "content_update" AND fromMorningCard`
 *    A module is a refresher iff the morning-card selector emitted it — it has a
 *    `morning_card_cache` row from the backend `GET /morning/cards` OR the on-device
 *    [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator].
 *    There is **no** mastery/completion/source/wrong-count gating: every
 *    selector-provided module lands here — even a completed/fully-mastered one (it
 *    simply sorts last). A `fallback`-sourced card qualifies just like a `gap` one.
 *    A selector-surfaced `initial_training` module appears in **both** Refresher
 *    *and* Training — the sections overlap by design (see [ModuleSections]).
 *
 * Modules matching **none** of the above fall through to no section (logged). This
 * is a categorisation outcome, **not** a "dropper": a selector-provided module
 * always lands in Refresher. Only these legitimately have no home here:
 *   - `content_update` modules (no refresher/training section)
 *   - any non-`initial_training`/`digital_proficiency` module the selector did NOT
 *     emit (no `morning_card_cache` row) — never meant to surface on this screen
 *
 * The contract is pinned by `ModuleCategorizerTest`.
 */
object ModuleCategorizer {

    fun categorize(modules: List<LearnModule>): ModuleSections {
        val refreshers = ArrayList<LearnModule>()
        val knowledge = ArrayList<LearnModule>()
        val training = ArrayList<LearnModule>()

        for (m in modules) {
            // Each module is offered to all three section predicates
            // independently — overlap is allowed (a refresher-by-source
            // initial_training module can land in both Training and
            // Refresher; see ModuleSections docs).
            var placed = false
            if (isTraining(m)) {
                training += m
                placed = true
            }
            if (isRefresher(m)) {
                refreshers += m
                placed = true
            }
            if (!placed) {
                Log.d(
                    "ModuleCategorizer",
                    "Dropped module: ${m.title}, moduleType: ${m.moduleType}",
                )
            }
        }
        return ModuleSections(refreshers, knowledge, training)
    }

    private fun isTraining(m: LearnModule): Boolean =
        m.moduleType == "initial_training" || m.moduleType == "digital_proficiency"

    private fun isRefresher(m: LearnModule): Boolean {
        // content_update modules have no quiz/drill semantics — never refreshers
        // (existing contract). If the backend ever emits one as a morning card that's
        // a contract violation, flagged loudly by CoachingModuleStore.traceSections.
        if (m.moduleType == "content_update") return false
        // Selector-authoritative: a module is a refresher iff the morning-card
        // selector emitted it — i.e. it has a `morning_card_cache` row from the
        // backend `GET /morning/cards` OR the on-device generator. NO mastery,
        // completion, source, or wrong-count gating: every selector-provided module
        // lands on the refresher list, full stop (a completed one simply sorts last).
        return m.fromMorningCard
    }
}
