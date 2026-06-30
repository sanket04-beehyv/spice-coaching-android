package com.medtroniclabs.microcoaching.domain.gaps

import com.medtroniclabs.microcoaching.data.db.dao.BehaviouralGapDao
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapRuleDispatcherTest {

    @Test
    fun `dispatch fires only evaluators whose rule_type is registered`() = runBlocking {
        val dao = FakeBehaviouralGapDao(
            listOf(
                gap("g-1", "wrong_facility_tier", validRule("wrong_facility_tier")),
                gap("g-2", "missing_paired_action", validRule("missing_paired_action")), // unknown — should skip
            ),
        )
        val dispatcher = GapRuleDispatcher(
            gapDao = dao,
            evaluators = mapOf(
                "wrong_facility_tier" to AlwaysFiresEvaluator("wrong_facility_tier"),
            ),
        )

        val fired = dispatcher.evaluate(
            assessmentData = emptyMap(),
            spiceEventCode = "assessment_submitted",
            assessmentType = null,
        )

        assertEquals(1, fired.size)
        assertEquals("g-1", fired[0].gapId)
        assertEquals("wrong_facility_tier", fired[0].ruleType)
    }

    @Test
    fun `dispatcher skips rules with schema_version greater than 1`() = runBlocking {
        val futureRule = """
            {"schema_version":2,"rule_type":"wrong_facility_tier",
             "params":{},"match":{"spice_event_codes":[],"assessment_types":[]}}
        """.trimIndent()
        val dao = FakeBehaviouralGapDao(
            listOf(gap("g-1", "wrong_facility_tier", futureRule)),
        )
        val dispatcher = GapRuleDispatcher(
            gapDao = dao,
            evaluators = mapOf("wrong_facility_tier" to AlwaysFiresEvaluator("wrong_facility_tier")),
        )

        val fired = dispatcher.evaluate(emptyMap(), "assessment_submitted", null)
        assertTrue("Future-schema rule must be skipped", fired.isEmpty())
    }

    @Test
    fun `dispatcher skips when spice_event_code is not in match list`() = runBlocking {
        val rule = """
            {"schema_version":1,"rule_type":"wrong_facility_tier",
             "params":{},
             "match":{"spice_event_codes":["NCDBloodPressureCreation"],"assessment_types":[]}}
        """.trimIndent()
        val dao = FakeBehaviouralGapDao(listOf(gap("g-1", "wrong_facility_tier", rule)))
        val dispatcher = GapRuleDispatcher(
            gapDao = dao,
            evaluators = mapOf("wrong_facility_tier" to AlwaysFiresEvaluator("wrong_facility_tier")),
        )

        val fired = dispatcher.evaluate(emptyMap(), "assessment_submitted", null)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `dispatcher matches when match lists are empty (no constraint)`() = runBlocking {
        val rule = validRule("wrong_facility_tier")
        val dao = FakeBehaviouralGapDao(listOf(gap("g-1", "wrong_facility_tier", rule)))
        val dispatcher = GapRuleDispatcher(
            gapDao = dao,
            evaluators = mapOf("wrong_facility_tier" to AlwaysFiresEvaluator("wrong_facility_tier")),
        )

        val fired = dispatcher.evaluate(emptyMap(), "any_event", "NCD")
        assertEquals(1, fired.size)
    }

    @Test
    fun `dispatcher returns empty when DAO returns no rules`() = runBlocking {
        val dao = FakeBehaviouralGapDao(emptyList())
        val dispatcher = GapRuleDispatcher(gapDao = dao, evaluators = emptyMap())
        val fired = dispatcher.evaluate(emptyMap(), "assessment_submitted", null)
        assertTrue(fired.isEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun gap(id: String, code: String, rule: String?) = BehaviouralGapEntity(
        gapId = id,
        gapCode = code,
        description = null,
        domain = null,
        severityDefault = null,
        status = "active",
        detectionRule = rule,
        lastSynced = 0L,
    )

    private fun validRule(ruleType: String): String =
        """
        {"schema_version":1,"rule_type":"$ruleType",
         "params":{},
         "match":{"spice_event_codes":[],"assessment_types":[]}}
        """.trimIndent()

    private class AlwaysFiresEvaluator(override val ruleType: String) : GapEvaluator {
        override suspend fun evaluate(
            assessmentData: Map<String, Any>,
            rule: DetectionRuleEnvelope,
            gap: BehaviouralGapEntity,
        ): GapDetectionResult = GapDetectionResult(
            gapId = gap.gapId,
            gapCode = gap.gapCode,
            ruleType = ruleType,
            outcome = "incorrect",
            evidence = emptyMap(),
        )
    }

    /** Minimal in-memory fake — only [getActiveWithRules] is used by the dispatcher. */
    private class FakeBehaviouralGapDao(
        private val rows: List<BehaviouralGapEntity>,
    ) : BehaviouralGapDao {
        override suspend fun getActiveWithRules(): List<BehaviouralGapEntity> = rows
        override fun getAllActive(): Flow<List<BehaviouralGapEntity>> = error("not used")
        override suspend fun getAllActiveOnce(): List<BehaviouralGapEntity> = rows
        override suspend fun getById(gapId: String) = rows.firstOrNull { it.gapId == gapId }
        override suspend fun getByCode(gapCode: String) = rows.firstOrNull { it.gapCode == gapCode }
        override suspend fun getByDomain(domain: String) = rows.filter { it.domain == domain }
        override suspend fun countActive() = rows.size
        override suspend fun upsertAll(gaps: List<BehaviouralGapEntity>) = Unit
        override suspend fun deleteByIds(gapIds: List<String>) = Unit
        override suspend fun deleteAll() = Unit
    }
}
