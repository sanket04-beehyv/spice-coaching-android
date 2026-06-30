package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the faithful-replica gap-state arithmetic against the backend
 * `gap_escalation_handler` rules. Pure — no Room/coroutines.
 */
class GapStateReducerTest {

    private val cfg = GapStateConfig(
        passThreshold = 0.70f,
        escalationFailureCount = 3,
        escalationWindowDays = 30,
        occurrenceWindowDays = 14,
    )
    private val day = 24L * 60L * 60L * 1000L
    private val t0 = 1_000_000_000_000L
    private val gap = "gap-1"

    private fun quiz(outcome: GapOutcome, score: Float? = null, ts: Long) =
        NormalizedGapEvent(gap, GapEventKind.QUIZ, outcome, score, ts)

    private fun assessment(outcome: GapOutcome, ts: Long) =
        NormalizedGapEvent(gap, GapEventKind.ASSESSMENT, outcome, null, ts)

    @Test
    fun `quiz incorrect increments failures and observation`() {
        val s = GapStateReducer.reduce(GapState(gap), quiz(GapOutcome.INCORRECT, ts = t0), cfg, t0)
        assertEquals(1, s.failedAttemptsCount)
        assertEquals(1, s.occurrenceCount)
        assertEquals(GapStatus.ACTIVE, s.status)
        assertEquals(t0, s.lastFailedAttemptAt)
    }

    @Test
    fun `quiz correct decrements but does not resolve until zero`() {
        val start = GapState(gap, failedAttemptsCount = 2, lastFailedAttemptAt = t0)
        val s = GapStateReducer.reduce(start, quiz(GapOutcome.CORRECT, ts = t0 + day), cfg, t0 + day)
        assertEquals(1, s.failedAttemptsCount)
        assertEquals(GapStatus.ACTIVE, s.status)
    }

    @Test
    fun `quiz correct resolves the gap at zero`() {
        val start = GapState(gap, failedAttemptsCount = 1, lastFailedAttemptAt = t0)
        val s = GapStateReducer.reduce(start, quiz(GapOutcome.CORRECT, ts = t0 + day), cfg, t0 + day)
        assertEquals(0, s.failedAttemptsCount)
        assertEquals(GapStatus.RESOLVED, s.status)
    }

    @Test
    fun `quiz correct never underflows below zero`() {
        val start = GapState(gap, failedAttemptsCount = 0)
        val s = GapStateReducer.reduce(start, quiz(GapOutcome.CORRECT, ts = t0), cfg, t0)
        assertEquals(0, s.failedAttemptsCount)
        // Observation still recorded.
        assertEquals(1, s.occurrenceCount)
    }

    @Test
    fun `three failures within window escalate to supervisor`() {
        var s = GapState(gap)
        s = GapStateReducer.reduce(s, quiz(GapOutcome.INCORRECT, ts = t0), cfg, t0)
        s = GapStateReducer.reduce(s, quiz(GapOutcome.INCORRECT, ts = t0 + day), cfg, t0 + day)
        assertFalse(s.escalatedToSupervisor)
        s = GapStateReducer.reduce(s, quiz(GapOutcome.INCORRECT, ts = t0 + 2 * day), cfg, t0 + 2 * day)
        assertEquals(3, s.failedAttemptsCount)
        assertTrue(s.escalatedToSupervisor)
    }

    @Test
    fun `failure outside the escalation window resets the counter to one`() {
        val start = GapState(gap, failedAttemptsCount = 2, lastFailedAttemptAt = t0)
        val s = GapStateReducer.reduce(start, quiz(GapOutcome.INCORRECT, ts = t0 + 31 * day), cfg, t0 + 31 * day)
        assertEquals(1, s.failedAttemptsCount)
    }

    @Test
    fun `module-level pass via score resets failures and reinforces`() {
        val start = GapState(gap, failedAttemptsCount = 2, escalatedToSupervisor = true, lastFailedAttemptAt = t0)
        val s = GapStateReducer.reduce(start, quiz(GapOutcome.UNKNOWN, score = 0.8f, ts = t0 + day), cfg, t0 + day)
        assertEquals(0, s.failedAttemptsCount)
        assertFalse(s.escalatedToSupervisor)
        assertEquals(GapStatus.RESOLVED, s.status)
        assertEquals(t0 + day, s.lastReinforcedAt)
    }

    @Test
    fun `module-level fail via score increments failures`() {
        val s = GapStateReducer.reduce(GapState(gap), quiz(GapOutcome.UNKNOWN, score = 0.33f, ts = t0), cfg, t0)
        assertEquals(1, s.failedAttemptsCount)
    }

    @Test
    fun `assessment incorrect increments failures`() {
        val s = GapStateReducer.reduce(GapState(gap), assessment(GapOutcome.INCORRECT, ts = t0), cfg, t0)
        assertEquals(1, s.failedAttemptsCount)
    }

    @Test
    fun `assessment non-incorrect is observation only`() {
        val start = GapState(gap, failedAttemptsCount = 2, lastFailedAttemptAt = t0)
        val s = GapStateReducer.reduce(start, assessment(GapOutcome.CORRECT, ts = t0 + day), cfg, t0 + day)
        // No decrement on assessment correctness (backend has no such path)...
        assertEquals(2, s.failedAttemptsCount)
        // ...but the observation still lands.
        assertEquals(1, s.occurrenceCount)
        assertEquals(t0 + day, s.lastObservedAt)
    }

    @Test
    fun `observation outside its window resets occurrence to one`() {
        val start = GapState(gap, occurrenceCount = 4, lastObservedAt = t0)
        val s = GapStateReducer.reduce(start, assessment(GapOutcome.CORRECT, ts = t0 + 15 * day), cfg, t0 + 15 * day)
        assertEquals(1, s.occurrenceCount)
    }

    @Test
    fun `the 2-wrong-1-right-then-fail sequence matches the backend net`() {
        // Mirrors the verified backend walk-through: 3 per-question events + 1
        // module-level fail summary ⇒ net failedAttemptsCount == 2.
        var s = GapState(gap)
        s = GapStateReducer.reduce(s, quiz(GapOutcome.INCORRECT, ts = t0), cfg, t0)
        s = GapStateReducer.reduce(s, quiz(GapOutcome.INCORRECT, ts = t0 + 1), cfg, t0 + 1)
        s = GapStateReducer.reduce(s, quiz(GapOutcome.CORRECT, ts = t0 + 2), cfg, t0 + 2)
        s = GapStateReducer.reduce(s, quiz(GapOutcome.INCORRECT, ts = t0 + 3), cfg, t0 + 3)
        assertEquals(2, s.failedAttemptsCount)
    }
}
