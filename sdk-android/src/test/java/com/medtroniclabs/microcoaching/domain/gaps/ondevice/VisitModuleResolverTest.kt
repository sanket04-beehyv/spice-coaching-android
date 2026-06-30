package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity
import com.medtroniclabs.microcoaching.domain.context.TodaysVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the trigger-based visit→module resolution: a visit matched against an
 * `assessment_due` trigger's `filter_predicate.match` contributes the trigger's
 * bound module families, weighted by the binding's `priority_weight`.
 */
class VisitModuleResolverTest {

    private fun module(family: String) = moduleEntityFixture(
        moduleId = "$family-v1",
        moduleFamilyId = family,
        titleBn = "title",
        moduleType = "refresher",
        estimatedMinutes = 5,
        difficultyLevel = "easy",
    )

    private fun visit(encounterType: String?, isPregnant: Boolean? = null) =
        TodaysVisit(
            type = "HH_VISIT", encounterType = encounterType, isPregnant = isPregnant,
            dueDateIso = "2026-06-23T00:00:00+00:00",
        )

    private fun trigger(id: String, match: String) = TriggerDefinitionEntity(
        triggerId = id, triggerKind = "workflow_event", triggerCode = "wf:assessment_due:$id",
        predicateJson = """{"spice_event_code":"assessment_due","filter_predicate":{"match":$match}}""",
    )

    private fun binding(triggerId: String, family: String, weight: Int, relationship: String = "primary") =
        ModuleTriggerBindingEntity(
            bindingId = "$triggerId-$family", moduleFamilyId = family,
            triggerDefinitionId = triggerId, relationship = relationship, priorityWeight = weight,
        )

    private val modules = mapOf("famAnc" to module("famAnc"), "famMal" to module("famMal"))

    private val ancTrigger = trigger("anc", """{"encounter_type_any":["ANC"],"is_pregnant":true}""")
    private val malariaTrigger = trigger("malaria", """{"encounter_type_any":["MALARIA"]}""")

    private val bindings = mapOf(
        "anc" to listOf(binding("anc", "famAnc", weight = 20)),
        "malaria" to listOf(binding("malaria", "famMal", weight = 20)),
    )

    @Test
    fun `matching visit yields the trigger's bound family with its binding weight`() {
        val out = VisitModuleResolver.resolve(
            visits = listOf(visit("MALARIA")),
            triggers = listOf(ancTrigger, malariaTrigger),
            bindingsByTrigger = bindings,
            modulesByFamily = modules,
        )
        assertEquals(listOf("famMal"), out.map { it.moduleFamilyId })
        assertEquals(20, out.single().priorityWeight)
    }

    @Test
    fun `is_pregnant guard filters the ANC trigger`() {
        val triggers = listOf(ancTrigger)
        // pregnant ANC visit → matches.
        assertEquals(
            listOf("famAnc"),
            VisitModuleResolver.resolve(listOf(visit("ANC", isPregnant = true)), triggers, bindings, modules)
                .map { it.moduleFamilyId },
        )
        // non-pregnant ANC visit → no match.
        assertTrue(
            VisitModuleResolver.resolve(listOf(visit("ANC", isPregnant = false)), triggers, bindings, modules).isEmpty(),
        )
    }

    @Test
    fun `binding whose family has no synced module is dropped`() {
        val orphan = mapOf("malaria" to listOf(binding("malaria", "famGone", weight = 20)))
        assertTrue(
            VisitModuleResolver.resolve(listOf(visit("MALARIA")), listOf(malariaTrigger), orphan, modules).isEmpty(),
        )
    }

    @Test
    fun `a family bound via multiple matches keeps the highest weight`() {
        val twoBindings = mapOf(
            "malaria" to listOf(
                binding("malaria", "famMal", weight = 10, relationship = "secondary"),
                binding("malaria", "famMal", weight = 20, relationship = "primary"),
            ),
        )
        val out = VisitModuleResolver.resolve(listOf(visit("MALARIA")), listOf(malariaTrigger), twoBindings, modules)
        assertEquals(20, out.single().priorityWeight)
    }

    @Test
    fun `no triggers or no visits yields empty`() {
        assertTrue(VisitModuleResolver.resolve(listOf(visit("ANC")), emptyList(), bindings, modules).isEmpty())
        assertTrue(VisitModuleResolver.resolve(emptyList(), listOf(ancTrigger), bindings, modules).isEmpty())
    }
}
