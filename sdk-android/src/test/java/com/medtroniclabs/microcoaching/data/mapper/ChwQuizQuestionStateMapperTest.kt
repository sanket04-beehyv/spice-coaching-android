package com.medtroniclabs.microcoaching.data.mapper

import com.medtroniclabs.microcoaching.network.ChwQuizQuestionStateSyncPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins `ChwQuizQuestionStateSyncPayload.toEntity()` — the /sync/gaps quiz-state baseline mapping. */
class ChwQuizQuestionStateMapperTest {

    private fun payload(
        failed: Int = 2,
        status: String = "active",
        lastFailed: String? = "2026-06-20T08:00:00Z",
    ) = ChwQuizQuestionStateSyncPayload(
        chwId = "87",
        quizId = "quiz-1",
        moduleId = "mod-1",
        tenantId = null,
        failedAttemptsCount = failed,
        lastFailedAttemptAt = lastFailed,
        firstAttemptAt = "2026-06-18T08:00:00Z",
        lastAttemptAt = "2026-06-20T08:00:00Z",
        escalatedToSupervisor = false,
        status = status,
        updatedAt = "2026-06-20T08:00:00Z",
    )

    @Test
    fun `maps identity + counters + status`() {
        val e = payload().toEntity()
        assertEquals("87", e.chwId)
        assertEquals("quiz-1", e.quizId)
        assertEquals("mod-1", e.moduleId)
        assertEquals(2, e.failedAttemptsCount)
        assertEquals("active", e.status)
        assertEquals(false, e.escalatedToSupervisor)
    }

    @Test
    fun `parses timestamps (present → non-null, absent → null)`() {
        assertNotNull(payload(lastFailed = "2026-06-20T08:00:00Z").toEntity().lastFailedAttemptAt)
        assertNull(payload(lastFailed = null).toEntity().lastFailedAttemptAt)
    }
}
