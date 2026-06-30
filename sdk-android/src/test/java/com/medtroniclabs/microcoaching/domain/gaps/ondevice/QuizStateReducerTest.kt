package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [QuizStateReducer] — the on-device ±1 quiz model, mirror of the backend
 * `QuizQuestionStateService` (incorrect +1 / correct −1, resolve at 0, window reset).
 */
class QuizStateReducerTest {

    private val config = GapStateConfig(escalationFailureCount = 3, escalationWindowDays = 30)
    private val t0 = 1_000_000_000_000L
    private val dayMs = 86_400_000L

    private fun state() = QuizState(quizId = "q1", moduleId = "m1")

    @Test
    fun `incorrect increments and marks active`() {
        val s1 = QuizStateReducer.reduce(state(), GapOutcome.INCORRECT, config, t0)
        assertEquals(1, s1.failedAttemptsCount)
        assertEquals(GapStatus.ACTIVE, s1.status)
        assertEquals(t0, s1.lastFailedAttemptAt)

        val s2 = QuizStateReducer.reduce(s1, GapOutcome.INCORRECT, config, t0 + 1000)
        assertEquals(2, s2.failedAttemptsCount)
    }

    @Test
    fun `correct decrements and resolves at zero`() {
        val failed = state().copy(failedAttemptsCount = 1, lastFailedAttemptAt = t0)
        val resolved = QuizStateReducer.reduce(failed, GapOutcome.CORRECT, config, t0 + 1000)
        assertEquals(0, resolved.failedAttemptsCount)
        assertEquals(GapStatus.RESOLVED, resolved.status)
    }

    @Test
    fun `correct decrement above zero stays active`() {
        val failed = state().copy(failedAttemptsCount = 2, lastFailedAttemptAt = t0, status = GapStatus.ACTIVE)
        val next = QuizStateReducer.reduce(failed, GapOutcome.CORRECT, config, t0 + 1000)
        assertEquals(1, next.failedAttemptsCount)
        assertEquals(GapStatus.ACTIVE, next.status)
    }

    @Test
    fun `correct never goes below zero`() {
        val next = QuizStateReducer.reduce(state(), GapOutcome.CORRECT, config, t0)
        assertEquals(0, next.failedAttemptsCount)
    }

    @Test
    fun `incorrect outside the window resets the counter to one`() {
        val stale = state().copy(failedAttemptsCount = 4, lastFailedAttemptAt = t0)
        val next = QuizStateReducer.reduce(stale, GapOutcome.INCORRECT, config, t0 + 31 * dayMs)
        assertEquals(1, next.failedAttemptsCount)
    }

    @Test
    fun `escalates at the failure threshold`() {
        var s = state()
        repeat(3) { i -> s = QuizStateReducer.reduce(s, GapOutcome.INCORRECT, config, t0 + i * 1000L) }
        assertEquals(3, s.failedAttemptsCount)
        assertEquals(true, s.escalatedToSupervisor)
    }

    @Test
    fun `unknown records the attempt without changing the failure count`() {
        val failed = state().copy(failedAttemptsCount = 2, lastFailedAttemptAt = t0)
        val next = QuizStateReducer.reduce(failed, GapOutcome.UNKNOWN, config, t0 + 1000)
        assertEquals(2, next.failedAttemptsCount)
        assertEquals(t0 + 1000, next.lastAttemptAt)
    }
}
