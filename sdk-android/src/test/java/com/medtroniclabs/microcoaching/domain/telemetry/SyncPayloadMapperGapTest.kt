package com.medtroniclabs.microcoaching.domain.telemetry

import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [CoachingEventEntity.toPayload] serialises [CoachingEventEntity.behaviouralGapId]
 * into [TelemetryEventPayload.payloadJson] per the backend contract for `module_quiz_attempted`.
 */
class SyncPayloadMapperGapTest {

    private fun minimalEntity(
        eventType: String = "module_quiz_attempted",
        cardType: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        payloadJson: String? = null,
        behaviouralGapId: String? = null,
        timestampLocal: Long = 1_700_000_000_000L,
        timestampUtc: Long? = null,
    ) = CoachingEventEntity(
        eventId = "evt-1",
        sdkVersion = "0.3.x",
        eventFamily = "learning",
        sessionId = "sess-1",
        chwId = "chw-1",
        eventType = eventType,
        cardType = cardType,
        triggerType = triggerType,
        inferenceMode = inferenceMode,
        payloadJson = payloadJson,
        behaviouralGapId = behaviouralGapId,
        timestampLocal = timestampLocal,
        timestampUtc = timestampUtc,
    )

    @Test
    fun `behaviouralGapId is written to payload_json when set`() {
        val gapId = "00000000-0000-0000-0000-000000000301"
        val payload = minimalEntity(behaviouralGapId = gapId).toPayload()
        val json = payload.payloadJson

        assertEquals(gapId, json["behavioural_gap_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `card_type trigger_type and inference_mode are echoed into payload_json alongside gap id`() {
        val gapId = "gap-uuid"
        val payload = minimalEntity(
            cardType = "info",
            triggerType = "action",
            inferenceMode = "online",
            behaviouralGapId = gapId,
        ).toPayload()
        val json = payload.payloadJson

        assertEquals("info", json["card_type"]?.jsonPrimitive?.content)
        assertEquals("action", json["trigger_type"]?.jsonPrimitive?.content)
        assertEquals("online", json["inference_mode"]?.jsonPrimitive?.content)
        assertEquals(gapId, json["behavioural_gap_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `null optional fields are omitted from payload_json rather than written as null`() {
        // card_type, trigger_type, inference_mode all null — only gap id should appear
        val payload = minimalEntity(behaviouralGapId = "gap-uuid").toPayload()
        val json = payload.payloadJson

        assertNull(json["card_type"])
        assertNull(json["trigger_type"])
        assertNull(json["inference_mode"])
        assertEquals("gap-uuid", json["behavioural_gap_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `no behaviouralGapId and no payloadJson produces empty payload_json`() {
        val payload = minimalEntity().toPayload()
        assertTrue(payload.payloadJson.isEmpty())
    }

    @Test
    fun `no behaviouralGapId but legacy payloadJson string is wrapped under raw key`() {
        val payload = minimalEntity(payloadJson = "some-legacy-value").toPayload()
        assertEquals("some-legacy-value", payload.payloadJson["raw"]?.jsonPrimitive?.content)
    }

    @Test
    fun `behaviouralGapId takes precedence over legacy payloadJson string`() {
        // When both are present, the structured gap payload wins (raw is ignored).
        val payload = minimalEntity(
            payloadJson = "old-raw",
            behaviouralGapId = "gap-wins",
        ).toPayload()
        val json = payload.payloadJson

        assertEquals("gap-wins", json["behavioural_gap_id"]?.jsonPrimitive?.content)
        assertNull(json["raw"])
    }

    @Test
    fun `timestamp_utc is never null - falls back to timestamp_local`() {
        // The backend coaching_events insert rejects a null timestamp_utc; the
        // entity never captures a separate UTC value, so it must default to the
        // (UTC epoch) timestamp_local.
        val payload = minimalEntity(timestampLocal = 1_700_000_000_000L, timestampUtc = null).toPayload()
        assertEquals(1_700_000_000_000L, payload.timestampUtc)
        assertEquals(payload.timestampLocal, payload.timestampUtc)
    }

    @Test
    fun `explicit timestamp_utc is preserved`() {
        val payload = minimalEntity(timestampLocal = 1_700_000_000_000L, timestampUtc = 1_699_999_999_000L).toPayload()
        assertEquals(1_699_999_999_000L, payload.timestampUtc)
    }

    @Test
    fun `a structured payload string is emitted flat, not wrapped under raw`() {
        // recordSpiceActionObserved stores a flat JSON object as the payload
        // string; it must be flattened into payload_json (Events-Modelling §70),
        // not double-encoded under "raw".
        val payload = minimalEntity(
            eventType = "spice_action_observed",
            behaviouralGapId = "gap-7",
            payloadJson = """{"behavioural_gap_id":"gap-7","correctReferral":false,""" +
                """"correctReferralLocation":false,"correctReferralType":true,""" +
                """"rule_type":"spice_referral_compliance"}""",
        ).toPayload()
        val json = payload.payloadJson

        assertNull(json["raw"])
        assertEquals("gap-7", json["behavioural_gap_id"]?.jsonPrimitive?.content)
        assertEquals("false", json["correctReferral"]?.jsonPrimitive?.content)
        assertEquals("true", json["correctReferralType"]?.jsonPrimitive?.content)
        assertEquals("spice_referral_compliance", json["rule_type"]?.jsonPrimitive?.content)
    }
}
