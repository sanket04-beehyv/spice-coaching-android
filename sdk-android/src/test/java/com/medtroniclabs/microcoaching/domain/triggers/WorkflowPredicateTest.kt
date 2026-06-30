package com.medtroniclabs.microcoaching.domain.triggers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the legacy `workflow_event` predicate match (`spice_event_code` +
 * `payload_filters` equality) used by [TriggerEvaluator] for host SPICE callbacks
 * (`form_submitted`, `rule_fired`, …). Visit refreshers use the separate
 * `matchesVisitTrigger` (the `assessment_due` `filter_predicate.match` shape).
 */
class WorkflowPredicateTest {

    private val formSubmitted =
        """{"spice_event_code":"form_submitted","payload_filters":{"form_id":"anc","region":"north"}}"""

    @Test
    fun `matches when code and all payload filters match`() {
        assertTrue(
            matchesWorkflowPredicate(formSubmitted, "form_submitted", mapOf("form_id" to "anc", "region" to "north")),
        )
    }

    @Test
    fun `rejects on code mismatch`() {
        assertFalse(
            matchesWorkflowPredicate(formSubmitted, "rule_fired", mapOf("form_id" to "anc", "region" to "north")),
        )
    }

    @Test
    fun `rejects when a filter value differs`() {
        assertFalse(
            matchesWorkflowPredicate(formSubmitted, "form_submitted", mapOf("form_id" to "anc", "region" to "south")),
        )
    }

    @Test
    fun `rejects when a required filter key is absent from the payload`() {
        assertFalse(matchesWorkflowPredicate(formSubmitted, "form_submitted", mapOf("form_id" to "anc")))
    }

    @Test
    fun `matches on code alone when predicate has no payload_filters`() {
        assertTrue(matchesWorkflowPredicate("""{"spice_event_code":"form_submitted"}""", "form_submitted", emptyMap()))
    }

    @Test
    fun `never matches an unparseable predicate or one missing the code`() {
        assertFalse(matchesWorkflowPredicate("not-json", "form_submitted", emptyMap()))
        assertFalse(matchesWorkflowPredicate("""{"payload_filters":{}}""", "form_submitted", emptyMap()))
    }
}
