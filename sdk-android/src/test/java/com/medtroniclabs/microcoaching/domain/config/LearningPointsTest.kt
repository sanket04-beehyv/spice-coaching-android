package com.medtroniclabs.microcoaching.domain.config

import com.medtroniclabs.microcoaching.data.db.entity.ConfigThresholdEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the learning-points resolution + the module-quiz XP formula. Pure, so it
 * runs in the plain JUnit test source set (no Room / coroutines harness).
 */
class LearningPointsTest {

    private fun global(key: String, value: String) =
        ConfigThresholdEntity(moduleFamilyId = ConfigThresholdEntity.GLOBAL_SCOPE, key = key, value = value)

    @Test
    fun `from empty rows yields documented defaults`() {
        val lp = LearningPoints.from(emptyList())
        assertEquals(15, lp.quizScoreMultiplier)
        assertEquals(15, lp.quizAttemptedBase)
        assertEquals(20, lp.moduleCompleted)
        assertEquals(5, lp.moduleDelivered)
        assertEquals(10, lp.moduleCardViewed)
        assertEquals(3, lp.spiceActionObserved)
    }

    @Test
    fun `from rows overrides defaults per key`() {
        val lp = LearningPoints.from(
            listOf(
                global(LearningPoints.KEY_QUIZ_SCORE_MULTIPLIER, "25"),
                global(LearningPoints.KEY_MODULE_COMPLETED, "40"),
            ),
        )
        assertEquals(25, lp.quizScoreMultiplier)
        assertEquals(40, lp.moduleCompleted)
        // Untouched keys keep their defaults.
        assertEquals(15, lp.quizAttemptedBase)
    }

    @Test
    fun `from falls back when a value is not an integer`() {
        val lp = LearningPoints.from(listOf(global(LearningPoints.KEY_QUIZ_SCORE_MULTIPLIER, "abc")))
        assertEquals(15, lp.quizScoreMultiplier)
    }

    @Test
    fun `moduleQuizXp = attempted x base + correct x multiplier + completion`() {
        // Documented config example: base 15, multiplier 15, completed 20.
        val lp = LearningPoints()
        // 0% case (3 attempted, 0 correct): 3*15 + 0*15 + 20 = 65.
        assertEquals(65, lp.moduleQuizXp(questionsAttempted = 3, correctAnswers = 0))
        // Perfect (3 attempted, 3 correct): 3*15 + 3*15 + 20 = 110.
        assertEquals(110, lp.moduleQuizXp(questionsAttempted = 3, correctAnswers = 3))
    }

    @Test
    fun `moduleQuizXp respects synced overrides`() {
        val lp = LearningPoints(quizAttemptedBase = 10, quizScoreMultiplier = 20, moduleCompleted = 30)
        // 2 attempted, 1 correct: 2*10 + 1*20 + 30 = 70.
        assertEquals(70, lp.moduleQuizXp(questionsAttempted = 2, correctAnswers = 1))
    }
}
