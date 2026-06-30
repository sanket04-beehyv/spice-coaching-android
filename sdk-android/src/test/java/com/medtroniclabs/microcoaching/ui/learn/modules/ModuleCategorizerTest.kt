package com.medtroniclabs.microcoaching.ui.learn.modules

import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Refresher / Knowledge / Training partition contract used by
 * [ModulesScreen].
 *
 *  - **Training** = `moduleType == "initial_training"` OR `"digital_proficiency"`
 *    (always — completed modules still render here).
 *  - **Knowledge** = no longer a module partition (renders source documents now);
 *    `sections.knowledge` is always empty.
 *  - **Refresher** = **selector-authoritative**: `moduleType != "content_update"
 *    AND fromMorningCard`. A module is a refresher iff the morning-card selector
 *    emitted it (backend `/morning/cards` OR the on-device generator). There is NO
 *    mastery/completion/source/wrong-count gating — a completed or fully-mastered
 *    selector card still surfaces (it just sorts last), and a `fallback`-sourced
 *    card qualifies just like a `gap` one. `content_update` never qualifies.
 *  - **No section** = a non-selector, non-training module. This is a categorisation
 *    outcome, NOT a "dropper": a selector card always lands in Refresher.
 *
 * Sections may **overlap** by design (a selector-surfaced `initial_training` module
 * is in both Refresher and Training).
 *
 * If a contributor changes a predicate without updating the tests below, the
 * rule-level cases fail and force a conscious decision.
 */
class ModuleCategorizerTest {

    private fun module(
        family: String,
        fromMorningCard: Boolean = false,
        status: String = "assigned",
        moduleType: String = "initial_training",
        source: String? = null,
    ): LearnModule = LearnModule(
        moduleFamilyId = family,
        title = "title-$family",
        body = "body",
        clinicalDomain = "hypertension",
        status = status,
        source = source,
        moduleType = moduleType,
        fromMorningCard = fromMorningCard,
    )

    // ── Training rule (moduleType only) ───────────────────────────────────────

    @Test
    fun `Training when moduleType is initial_training assigned`() {
        val m = module("a", moduleType = "initial_training", status = "assigned")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.training.map { it.moduleFamilyId })
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.refreshers.isEmpty())
    }

    @Test
    fun `Training keeps completed initial_training modules`() {
        val m = module("a", moduleType = "initial_training", status = "completed")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.training.map { it.moduleFamilyId })
        assertTrue(sections.knowledge.isEmpty())
    }

    @Test
    fun `digital_proficiency modules land in Training (Knowledge empty)`() {
        val rows = listOf(
            module("dp-assigned", moduleType = "digital_proficiency", status = "assigned"),
            module("dp-in-prog", moduleType = "digital_proficiency", status = "in_progress"),
            module("dp-completed", moduleType = "digital_proficiency", status = "completed"),
        )
        val sections = ModuleCategorizer.categorize(rows)
        assertEquals(
            listOf("dp-assigned", "dp-in-prog", "dp-completed"),
            sections.training.map { it.moduleFamilyId },
        )
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.refreshers.isEmpty())
    }

    @Test
    fun `Knowledge partition is always empty`() {
        val rows = listOf(
            module("a", moduleType = "initial_training", status = "completed"),
            module("b", moduleType = "digital_proficiency", status = "assigned"),
            module("c", moduleType = "content_update", status = "assigned"),
        )
        assertTrue(ModuleCategorizer.categorize(rows).knowledge.isEmpty())
    }

    // ── Refresher rule: selector-authoritative (fromMorningCard) ──────────────

    @Test
    fun `Refresher when the selector emitted it (fromMorningCard)`() {
        val m = module("a", fromMorningCard = true, source = "gap", moduleType = "refresher")
        assertEquals(listOf("a"), ModuleCategorizer.categorize(listOf(m)).refreshers.map { it.moduleFamilyId })
    }

    @Test
    fun `Refresher includes a fallback-sourced selector card`() {
        // A "fallback" morning card is a refresher just like a "gap" one — the old
        // source == "gap"-only gate is gone.
        val m = module("a", fromMorningCard = true, source = "fallback", moduleType = "refresher")
        assertTrue(ModuleCategorizer.categorize(listOf(m)).refreshers.any { it.moduleFamilyId == "a" })
    }

    @Test
    fun `Refresher includes a COMPLETED selector card (no completion drop)`() {
        // Completion/mastery no longer hides a selector-provided module.
        val m = module("a", fromMorningCard = true, source = "gap", status = "completed", moduleType = "refresher")
        assertTrue(ModuleCategorizer.categorize(listOf(m)).refreshers.any { it.moduleFamilyId == "a" })
    }

    @Test
    fun `NOT a refresher when no selector emitted it`() {
        // Selector-only: a module the CHW may have gotten wrong locally but that no
        // selector surfaced is not a refresher.
        val m = module("a", fromMorningCard = false, source = null, status = "in_progress", moduleType = "refresher")
        assertTrue(ModuleCategorizer.categorize(listOf(m)).refreshers.isEmpty())
    }

    @Test
    fun `content_update is never a refresher even if the selector emitted it`() {
        val m = module("a", fromMorningCard = true, source = "gap", moduleType = "content_update")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.isEmpty())
        assertTrue(sections.training.isEmpty())
    }

    // ── Overlap — sections may share modules by design ────────────────────────

    @Test
    fun `selector-surfaced initial_training lands in BOTH Refresher and Training`() {
        val m = module("a", fromMorningCard = true, source = "gap", status = "in_progress", moduleType = "initial_training")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.refreshers.map { it.moduleFamilyId })
        assertEquals(listOf("a"), sections.training.map { it.moduleFamilyId })
        assertTrue(sections.knowledge.isEmpty())
    }

    // ── No-section (categorisation outcome, not a dropper) ────────────────────

    @Test
    fun `non-selector non-training module has no section`() {
        val m = module("a", fromMorningCard = false, moduleType = "refresher", status = "assigned")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.isEmpty())
        assertTrue(sections.training.isEmpty())
        assertTrue(sections.knowledge.isEmpty())
    }

    @Test
    fun `content_update with no selector card has no section`() {
        val m = module("a", fromMorningCard = false, moduleType = "content_update", status = "in_progress")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.isEmpty())
        assertTrue(sections.training.isEmpty())
        assertTrue(sections.knowledge.isEmpty())
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `Empty input yields three empty sections`() {
        val sections = ModuleCategorizer.categorize(emptyList())
        assertTrue(sections.refreshers.isEmpty())
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.training.isEmpty())
    }

    // ── Order preservation ────────────────────────────────────────────────────

    @Test
    fun `input order is preserved within each section`() {
        val catalogue = listOf(
            module("T-first", moduleType = "initial_training", status = "assigned"),
            module("K-first", moduleType = "digital_proficiency", status = "assigned"),
            module("R-first", fromMorningCard = true, source = "gap", moduleType = "refresher", status = "in_progress"),
            module("T-second", moduleType = "initial_training", status = "in_progress"),
            module("K-second", moduleType = "digital_proficiency", status = "completed"),
            module("R-second", fromMorningCard = true, source = "fallback", moduleType = "refresher", status = "in_progress"),
        )
        val sections = ModuleCategorizer.categorize(catalogue)
        // digital_proficiency (K-*) joins Training, interleaved in input order.
        assertEquals(
            listOf("T-first", "K-first", "T-second", "K-second"),
            sections.training.map { it.moduleFamilyId },
        )
        assertTrue(sections.knowledge.isEmpty())
        assertEquals(listOf("R-first", "R-second"), sections.refreshers.map { it.moduleFamilyId })
    }
}
