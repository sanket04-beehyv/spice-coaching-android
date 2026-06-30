package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [OnDeviceMorningSelector.selectQuizDriven] — strict backend parity:
 * active quizzes with failures, ranked `(failedAttemptsCount DESC, lastFailedAttemptAt DESC)`,
 * one module per family, capped, `source = "quiz"` carrying the quiz_id.
 */
class SelectQuizDrivenTest {

    private fun module(moduleId: String, family: String) = moduleEntityFixture(
        moduleId = moduleId,
        moduleFamilyId = family,
        titleBn = "title",
        moduleType = "refresher",
        estimatedMinutes = 5,
        difficultyLevel = "easy",
    )

    private fun quiz(
        id: String,
        moduleId: String,
        failed: Int,
        lastFailed: Long? = 1L,
        status: GapStatus = GapStatus.ACTIVE,
    ) = QuizState(
        quizId = id, moduleId = moduleId, failedAttemptsCount = failed,
        lastFailedAttemptAt = lastFailed, status = status,
    )

    private val modulesById = mapOf(
        "mA" to module("mA", "famA"),
        "mB" to module("mB", "famB"),
        "mC" to module("mC", "famC"),
    )

    @Test
    fun `ranks by failure count then recency`() {
        val out = OnDeviceMorningSelector.selectQuizDriven(
            quizStates = listOf(
                quiz("q1", "mA", failed = 1, lastFailed = 500),
                quiz("q2", "mB", failed = 3, lastFailed = 100),
                quiz("q3", "mC", failed = 1, lastFailed = 900),
            ),
            modulesById = modulesById, limit = 5, nowMillis = 1_000,
        )
        // q2 (3 failures) first; then q1 vs q3 both 1 failure → q3 more recent (900 > 500).
        assertEquals(listOf("q2", "q3", "q1"), out.map { it.quizId })
        assertEquals(listOf("quiz", "quiz", "quiz"), out.map { it.source })
        assertNull(out.first().behaviouralGapId)
    }

    @Test
    fun `excludes resolved and zero-failure quizzes`() {
        val out = OnDeviceMorningSelector.selectQuizDriven(
            quizStates = listOf(
                quiz("q1", "mA", failed = 0),
                quiz("q2", "mB", failed = 2, status = GapStatus.RESOLVED),
                quiz("q3", "mC", failed = 1),
            ),
            modulesById = modulesById, limit = 5, nowMillis = 1_000,
        )
        assertEquals(listOf("q3"), out.map { it.quizId })
    }

    @Test
    fun `one module per family — higher-ranked quiz wins`() {
        val out = OnDeviceMorningSelector.selectQuizDriven(
            quizStates = listOf(
                quiz("q1", "mA", failed = 1),
                quiz("q2", "mA", failed = 3), // same module/family, more failures → kept
            ),
            modulesById = modulesById, limit = 5, nowMillis = 1_000,
        )
        assertEquals(listOf("q2"), out.map { it.quizId })
    }

    @Test
    fun `caps at limit`() {
        val states = (1..8).map { quiz("q$it", "m$it", failed = it) }
        val modules = (1..8).associate { "m$it" to module("m$it", "fam$it") }
        val out = OnDeviceMorningSelector.selectQuizDriven(states, modules, limit = 5, nowMillis = 1_000)
        assertEquals(5, out.size)
        // Highest failure counts win the 5 slots.
        assertEquals(listOf("q8", "q7", "q6", "q5", "q4"), out.map { it.quizId })
    }

    @Test
    fun `quiz with no synced module is skipped`() {
        val out = OnDeviceMorningSelector.selectQuizDriven(
            quizStates = listOf(quiz("q1", "missing", failed = 2)),
            modulesById = modulesById, limit = 5, nowMillis = 1_000,
        )
        assertTrue(out.isEmpty())
    }
}
