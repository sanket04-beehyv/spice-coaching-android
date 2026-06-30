package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [matchesVisitTrigger]: the minimal `assessment_due` visit predicate matcher.
 * Only `encounter_type_any` + `is_pregnant` are modelled; everything else is ignored,
 * and a trigger that constrains only unmodelled fields must NOT vacuously match.
 */
class VisitTriggerPredicateTest {

    /** Helper to build a `filter_predicate.match` predicate JSON. */
    private fun predicate(match: String): String =
        """{"spice_event_code":"assessment_due","filter_predicate":{"match":$match}}"""

    @Test
    fun `encounter_type matches case and space-insensitively`() {
        val p = predicate("""{"encounter_type_any":["MALARIA","ANC"]}""")
        assertTrue(matchesVisitTrigger(p, encounterType = "malaria", isPregnant = null))
        assertTrue(matchesVisitTrigger(p, encounterType = "ANC", isPregnant = null))
    }

    @Test
    fun `encounter_type mismatch fails`() {
        val p = predicate("""{"encounter_type_any":["ANC"]}""")
        assertFalse(matchesVisitTrigger(p, encounterType = "MALARIA", isPregnant = null))
    }

    @Test
    fun `is_pregnant true requires a pregnant visit`() {
        val p = predicate("""{"encounter_type_any":["ANC"],"is_pregnant":true}""")
        assertTrue(matchesVisitTrigger(p, encounterType = "ANC", isPregnant = true))
        assertFalse(matchesVisitTrigger(p, encounterType = "ANC", isPregnant = false))
    }

    @Test
    fun `unknown is_pregnant skips the constraint (not a failure)`() {
        val p = predicate("""{"encounter_type_any":["ANC"],"is_pregnant":true}""")
        // encounter_type still matches and is_pregnant is skipped → overall match.
        assertTrue(matchesVisitTrigger(p, encounterType = "ANC", isPregnant = null))
    }

    @Test
    fun `trigger constraining only unmodelled fields does not match`() {
        // diagnosis_any/reason_display_any are not modelled → no field checked → no match.
        val p = predicate("""{"diagnosis_any":["ANEMIA"],"reason_display_any":["Anemia"]}""")
        assertFalse(matchesVisitTrigger(p, encounterType = "ANC", isPregnant = true))
    }

    @Test
    fun `empty match never matches`() {
        assertFalse(matchesVisitTrigger(predicate("{}"), encounterType = "ANC", isPregnant = true))
    }

    @Test
    fun `empty encounter_type_any list is not a constraint`() {
        val p = predicate("""{"encounter_type_any":[],"is_pregnant":true}""")
        // only is_pregnant is constrained; it matches → overall match.
        assertTrue(matchesVisitTrigger(p, encounterType = "ANC", isPregnant = true))
        // is_pregnant is the only checked field and it fails.
        assertFalse(matchesVisitTrigger(p, encounterType = "ANC", isPregnant = false))
    }

    @Test
    fun `visit without an encounterType skips that constraint`() {
        val p = predicate("""{"encounter_type_any":["ANC"]}""")
        // no encounterType provided and nothing else constrained → no field checked → no match.
        assertFalse(matchesVisitTrigger(p, encounterType = null, isPregnant = null))
    }

    @Test
    fun `unparseable predicate never matches`() {
        assertFalse(matchesVisitTrigger("not json", encounterType = "ANC", isPregnant = true))
    }
}
