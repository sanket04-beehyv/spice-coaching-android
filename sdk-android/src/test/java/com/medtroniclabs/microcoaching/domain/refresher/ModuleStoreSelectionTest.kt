package com.medtroniclabs.microcoaching.domain.refresher

import com.medtroniclabs.microcoaching.domain.gaps.ondevice.ActionGapLink
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure derivations [CoachingModuleStore] is built on:
 *
 *  - [selectFeatured] = first non-skipped refresher that carries a quiz; skipping
 *    advances to the next, and the slot is null only when every refresher is
 *    skipped (skip = advance, not hide-to-null).
 *  - [isActionGapStillActive] = the wrong-referral re-drill gate.
 *
 * Refresher membership itself is now **selector-authoritative** (a
 * `morning_card_cache` row exists → `fromMorningCard`). The old mastery
 * `dropFullyMastered` pass was removed (see
 * docs/retire_bm25_and_surface_all_refreshers_plan.md), so there is no mastery-drop
 * derivation left to pin; the end-to-end cases below instead pin that a selector
 * card surfaces and a non-selector module does not.
 */
class ModuleStoreSelectionTest {

    private val quiz = listOf(QuizQuestion("q1", "?", listOf("a", "b"), 0))

    private fun refresher(
        family: String,
        hasQuiz: Boolean = true,
        fromMorningCard: Boolean = true,
        source: String? = "gap",
        status: String = "assigned",
        moduleType: String = "refresher",
    ): LearnModule = LearnModule(
        moduleFamilyId = family,
        title = "title-$family",
        body = "body",
        clinicalDomain = "hypertension",
        status = status,
        source = source,
        moduleType = moduleType,
        inlineQuestions = if (hasQuiz) quiz else null,
        fromMorningCard = fromMorningCard,
    )

    // ── selectFeatured ─────────────────────────────────────────────────────────

    @Test
    fun `featured is the first non-skipped refresher that has a quiz`() {
        val refreshers = listOf(
            refresher("a", hasQuiz = false), // no quiz → skipped over
            refresher("b"), // first eligible
            refresher("c"),
        )
        assertEquals("b", selectFeatured(refreshers, emptySet())?.moduleFamilyId)
    }

    @Test
    fun `skip advances featured to the next eligible refresher`() {
        val refreshers = listOf(refresher("b"), refresher("c"))
        assertEquals("c", selectFeatured(refreshers, setOf("b"))?.moduleFamilyId)
    }

    @Test
    fun `featured is null when every refresher is skipped`() {
        val refreshers = listOf(refresher("b"), refresher("c"))
        assertNull(selectFeatured(refreshers, setOf("b", "c")))
    }

    @Test
    fun `featured is null when no refresher carries a quiz`() {
        val refreshers = listOf(
            refresher("a", hasQuiz = false),
            refresher("b", hasQuiz = false),
        )
        assertNull(selectFeatured(refreshers, emptySet()))
    }

    // ── End-to-end: refresher membership is selector-authoritative ───────────────

    @Test
    fun `a selector card is a refresher and can be featured (even fallback-sourced)`() {
        val card = refresher("fb", source = "fallback", fromMorningCard = true)
        val refreshers = ModuleCategorizer.categorize(listOf(card)).refreshers
        assertEquals(listOf("fb"), refreshers.map { it.moduleFamilyId })
        assertEquals("fb", selectFeatured(refreshers, emptySet())?.moduleFamilyId)
    }

    @Test
    fun `a non-selector module is not a refresher so cannot be featured`() {
        val notSurfaced = refresher("x", fromMorningCard = false, source = null)
        val refreshers = ModuleCategorizer.categorize(listOf(notSurfaced)).refreshers
        assertEquals(emptyList<String>(), refreshers.map { it.moduleFamilyId })
        assertNull(selectFeatured(refreshers, emptySet()))
    }

    // ── isActionGapStillActive (re-drill gate) ──────────────────────────────────

    private val gap = "referral_location_upazila"
    private val link = ActionGapLink(gapId = gap, lastWrongReferralAt = 1000L)
    private val twoQuestions = setOf("q1", "q2")

    @Test
    fun `action gap stays active until every question is passed since the mistake`() {
        assertEquals(
            true,
            isActionGapStillActive(link, gap, setOf(gap), twoQuestions, passedSinceMistake = setOf("q1")),
        )
    }

    @Test
    fun `action gap is dismissed once all questions are passed since the mistake`() {
        assertEquals(
            false,
            isActionGapStillActive(link, gap, setOf(gap), twoQuestions, passedSinceMistake = setOf("q1", "q2")),
        )
    }

    @Test
    fun `action gap is active when nothing has been re-drilled since the mistake`() {
        assertEquals(
            true,
            isActionGapStillActive(link, gap, setOf(gap), twoQuestions, passedSinceMistake = emptySet()),
        )
    }

    @Test
    fun `action gap is active (fail-open) when the mistake time is unknown`() {
        val noTime = ActionGapLink(gapId = gap, lastWrongReferralAt = null)
        assertEquals(
            true,
            isActionGapStillActive(noTime, gap, setOf(gap), twoQuestions, passedSinceMistake = setOf("q1", "q2")),
        )
    }

    @Test
    fun `action gap with no quiz cannot be drilled away so stays active`() {
        assertEquals(
            true,
            isActionGapStillActive(link, gap, setOf(gap), questionIds = emptySet(), passedSinceMistake = emptySet()),
        )
    }

    @Test
    fun `without a link, a card gap in the catalogue is an ungated action gap`() {
        assertEquals(
            true,
            isActionGapStillActive(
                link = null, cardGapId = gap, actionGapIds = setOf(gap),
                questionIds = twoQuestions, passedSinceMistake = emptySet(),
            ),
        )
    }

    @Test
    fun `without a link, a non-catalogue card gap is not an action gap`() {
        assertEquals(
            false,
            isActionGapStillActive(
                link = null, cardGapId = "module_primary_gap_x", actionGapIds = setOf(gap),
                questionIds = twoQuestions, passedSinceMistake = emptySet(),
            ),
        )
    }
}
