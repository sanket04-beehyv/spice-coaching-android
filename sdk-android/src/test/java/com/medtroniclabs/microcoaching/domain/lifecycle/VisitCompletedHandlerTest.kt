package com.medtroniclabs.microcoaching.domain.lifecycle

import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.RetryCountRow
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitCompletedHandlerTest {

    @Test
    fun `backfills patient_visit_id, emits session_end, and flushes`() = runBlocking {
        val dao = FakeCoachingEventDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sdk-hook", chwId = "chw-1")
        val handler = VisitCompletedHandler(coachingEventDao = dao)

        var flushed = false
        handler.handle(
            chwId = "chw-1",
            encounterId = "visit-42",
            recorder = recorder,
            flush = { flushed = true },
        )

        assertEquals("sdk-hook", dao.lastBackfillSessionId)
        assertEquals("chw-1", dao.lastBackfillChwId)
        assertEquals("visit-42", dao.lastBackfillEncounterId)
        val sessionEnd = dao.inserted.find { it.eventType == "session_end" }
        assertNotNull("expected a session_end event to be inserted", sessionEnd)
        assertTrue("expected flush() to be invoked", flushed)
    }

    @Test
    fun `skips work entirely when encounterId is blank`() = runBlocking {
        val dao = FakeCoachingEventDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sdk-hook", chwId = "chw-1")
        val handler = VisitCompletedHandler(coachingEventDao = dao)

        var flushed = false
        handler.handle(
            chwId = "chw-1",
            encounterId = "",
            recorder = recorder,
            flush = { flushed = true },
        )

        assertNull(dao.lastBackfillEncounterId)
        assertTrue(dao.inserted.isEmpty())
        // Blank encounter — no work, no flush needed.
        assertEquals(false, flushed)
    }

    /**
     * Captures the args to [backfillPatientVisitId] and any inserts. Only the
     * methods the handler/recorder touch are implemented meaningfully.
     */
    private class FakeCoachingEventDao : CoachingEventDao {
        var lastBackfillSessionId: String? = null
        var lastBackfillChwId: String? = null
        var lastBackfillEncounterId: String? = null
        val inserted = mutableListOf<CoachingEventEntity>()

        override suspend fun insert(event: CoachingEventEntity) {
            inserted += event
        }

        override suspend fun backfillPatientVisitId(
            sessionId: String,
            chwId: String,
            encounterId: String,
        ): Int {
            lastBackfillSessionId = sessionId
            lastBackfillChwId = chwId
            lastBackfillEncounterId = encounterId
            return 0
        }

        // ── Unused by the handler/recorder paths under test ───────────────────
        override suspend fun getPending(): List<CoachingEventEntity> = emptyList()
        override suspend fun getLatestCorrectQuestionIds(chwId: String, moduleFamilyId: String): List<String> =
            emptyList()
        override suspend fun getLatestWrongQuestionIds(chwId: String, moduleFamilyId: String): List<String> =
            emptyList()
        override suspend fun getLatestCorrectQuestionIdsSince(
            chwId: String,
            moduleFamilyId: String,
            sinceMillis: Long,
        ): List<String> = emptyList()
        override suspend fun getReplayableForGapState(chwId: String): List<CoachingEventEntity> = emptyList()
        override suspend fun getUnsyncedQuizAttempts(chwId: String): List<CoachingEventEntity> = emptyList()
        override fun getEventCountFlow(): Flow<Int> = flowOf(0)
        override suspend fun markSynced(eventIds: List<String>, syncedAt: Long) = Unit
        override suspend fun markFailed(eventIds: List<String>) = Unit
        override suspend fun incrementRetryCount(eventIds: List<String>) = Unit
        override suspend fun getRetryCounts(eventIds: List<String>): List<RetryCountRow> = emptyList()
        override suspend fun getBySession(sessionId: String): List<CoachingEventEntity> = emptyList()
        override suspend fun getAll(): List<CoachingEventEntity> = emptyList()
        override suspend fun deleteSynced() = Unit
        override suspend fun deleteAll() = Unit
    }
}
