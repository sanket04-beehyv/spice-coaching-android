package com.medtroniclabs.microcoaching.domain.refresher

import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure refresher quiz-subset nudge (Task 4): the [refresherQuestionCount]
 * formula and the weak-first, day-rotating [selectRefresherQuestions] slice.
 */
class RefresherQuestionSelectorTest {

    private fun qs(n: Int): List<QuizQuestion> =
        (1..n).map { QuizQuestion(id = "q$it", questionText = "?", answers = listOf("a", "b"), correctIndex = 0) }

    private fun ids(list: List<QuizQuestion>) = list.map { it.id }

    // ── refresherQuestionCount (ratio formula) ───────────────────────────────

    @Test
    fun `k is round(N times ratio) clamped to bounds`() {
        // ratio 0.4, bounds [2,6]
        assertEquals(4, refresherQuestionCount(10, 0.4f, 2, 6)) // 4.0
        assertEquals(3, refresherQuestionCount(8, 0.4f, 2, 6))  // 3.2 → 3
        assertEquals(2, refresherQuestionCount(6, 0.4f, 2, 6))  // 2.4 → 2
        assertEquals(6, refresherQuestionCount(20, 0.4f, 2, 6)) // 8 → clamp 6
    }

    @Test
    fun `k never exceeds the module question count`() {
        // min bound 2 but only 1 question → k clamped down to N
        assertEquals(1, refresherQuestionCount(1, 0.4f, 2, 6))
        assertEquals(0, refresherQuestionCount(0, 0.4f, 2, 6))
    }

    // ── selectRefresherQuestions ─────────────────────────────────────────────

    @Test
    fun `k greater or equal to N returns all questions unchanged`() {
        val all = qs(3)
        assertEquals(ids(all), ids(selectRefresherQuestions(all, emptySet(), k = 5, daySeed = 7)))
    }

    @Test
    fun `all wrong questions surface, uncapped (ignoring k)`() {
        val all = qs(6) // q1..q6
        val wrong = setOf("q2", "q4", "q5")
        // k = 2 must NOT clamp — all 3 wrong come back, in authored order.
        val picked = selectRefresherQuestions(all, wrong, k = 2, daySeed = 0)
        assertEquals(listOf("q2", "q4", "q5"), ids(picked))
    }

    @Test
    fun `a single wrong question surfaces alone (not padded to k)`() {
        val all = qs(6)
        val picked = selectRefresherQuestions(all, setOf("q3"), k = 4, daySeed = 0)
        assertEquals(listOf("q3"), ids(picked))
    }

    @Test
    fun `selection is stable within the same day seed`() {
        val all = qs(8)
        val a = selectRefresherQuestions(all, emptySet(), k = 3, daySeed = 42)
        val b = selectRefresherQuestions(all, emptySet(), k = 3, daySeed = 42)
        assertEquals(ids(a), ids(b))
    }

    @Test
    fun `selection rotates across different day seeds`() {
        val all = qs(8)
        val day1 = ids(selectRefresherQuestions(all, emptySet(), k = 3, daySeed = 1))
        val day2 = ids(selectRefresherQuestions(all, emptySet(), k = 3, daySeed = 2))
        // A rotation shifts the window, so consecutive days differ.
        assertTrue("expected different slices across days: $day1 vs $day2", day1 != day2)
    }

    @Test
    fun `over enough days every question gets surfaced`() {
        val all = qs(6)
        val seen = mutableSetOf<String>()
        for (day in 0 until 6) {
            seen += selectRefresherQuestions(all, emptySet(), k = 2, daySeed = day.toLong()).map { it.id }
        }
        assertEquals(ids(all).toSet(), seen)
    }

    @Test
    fun `empty input or non-positive k yields empty`() {
        assertTrue(selectRefresherQuestions(emptyList(), emptySet(), k = 3, daySeed = 1).isEmpty())
        assertTrue(selectRefresherQuestions(qs(4), emptySet(), k = 0, daySeed = 1).isEmpty())
    }
}
