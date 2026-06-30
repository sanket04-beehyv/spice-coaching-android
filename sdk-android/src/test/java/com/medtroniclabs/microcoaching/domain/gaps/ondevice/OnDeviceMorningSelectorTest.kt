package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins gap-driven selection and the visit-driven stand-in builder. Pure. */
class OnDeviceMorningSelectorTest {

    private fun module(family: String, version: Int = 1) = moduleEntityFixture(
        moduleId = "$family-v$version",
        moduleFamilyId = family,
        version = version,
        titleBn = "title",
        domain = "domain",
        moduleType = "refresher",
        estimatedMinutes = 5,
        difficultyLevel = "easy",
    )

    private fun gap(id: String, severity: String, status: String = "active") =
        BehaviouralGapEntity(gapId = id, gapCode = id, severityDefault = severity, status = status)

    private fun activeState(id: String, occurrence: Int, lastObserved: Long = 100L) =
        GapState(id, occurrenceCount = occurrence, status = GapStatus.ACTIVE, lastObservedAt = lastObserved)

    private val recencyByVersion: (ModuleEntity) -> Long = { it.version.toLong() }

    // ── Gap-driven selection (select) ───────────────────────────────────────────

    @Test
    fun `higher severity outranks higher occurrence`() {
        val states = mapOf(
            "gH" to activeState("gH", occurrence = 1),
            "gL" to activeState("gL", occurrence = 5),
        )
        val gaps = mapOf("gH" to gap("gH", "high"), "gL" to gap("gL", "low"))
        val index = ModuleGapIndex(
            familyToPrimaryGap = mapOf("famH" to "gH", "famL" to "gL"),
            gapToFamilies = mapOf("gH" to listOf("famH"), "gL" to listOf("famL")),
        )
        val modules = mapOf("famH" to module("famH"), "famL" to module("famL"))

        val result = OnDeviceMorningSelector.select(states, gaps, index, modules, limit = 5, nowMillis = 0L, recencyOf = recencyByVersion)

        assertEquals(listOf("famH", "famL"), result.map { it.moduleFamilyId })
        assertEquals(listOf("gap", "gap"), result.map { it.source })
        assertEquals(listOf(0, 1), result.map { it.rank })
    }

    @Test
    fun `same severity falls back to higher occurrence first`() {
        val states = mapOf(
            "gA" to activeState("gA", occurrence = 2),
            "gB" to activeState("gB", occurrence = 5),
        )
        val gaps = mapOf("gA" to gap("gA", "moderate"), "gB" to gap("gB", "moderate"))
        val index = ModuleGapIndex(
            familyToPrimaryGap = mapOf("famA" to "gA", "famB" to "gB"),
            gapToFamilies = mapOf("gA" to listOf("famA"), "gB" to listOf("famB")),
        )
        val modules = mapOf("famA" to module("famA"), "famB" to module("famB"))

        val result = OnDeviceMorningSelector.select(states, gaps, index, modules, limit = 5, nowMillis = 0L, recencyOf = recencyByVersion)

        assertEquals(listOf("famB", "famA"), result.map { it.moduleFamilyId })
    }

    @Test
    fun `one family per gap - a family already taken is not picked twice`() {
        val states = mapOf(
            "g1" to activeState("g1", occurrence = 9),
            "g2" to activeState("g2", occurrence = 1),
        )
        val gaps = mapOf("g1" to gap("g1", "high"), "g2" to gap("g2", "moderate"))
        val index = ModuleGapIndex(
            familyToPrimaryGap = mapOf("famX" to "g1"),
            gapToFamilies = mapOf("g1" to listOf("famX"), "g2" to listOf("famX")),
        )
        val modules = mapOf("famX" to module("famX"))

        val result = OnDeviceMorningSelector.select(states, gaps, index, modules, limit = 5, nowMillis = 0L, recencyOf = recencyByVersion)

        assertEquals(1, result.size)
        assertEquals("famX", result.first().moduleFamilyId)
    }

    @Test
    fun `limit caps the number of gap picks`() {
        val states = mapOf(
            "gH" to activeState("gH", occurrence = 1),
            "gL" to activeState("gL", occurrence = 1),
        )
        val gaps = mapOf("gH" to gap("gH", "high"), "gL" to gap("gL", "low"))
        val index = ModuleGapIndex(
            familyToPrimaryGap = mapOf("famH" to "gH", "famL" to "gL"),
            gapToFamilies = mapOf("gH" to listOf("famH"), "gL" to listOf("famL")),
        )
        val modules = mapOf("famH" to module("famH"), "famL" to module("famL"))

        val result = OnDeviceMorningSelector.select(states, gaps, index, modules, limit = 1, nowMillis = 0L, recencyOf = recencyByVersion)

        assertEquals(1, result.size)
        assertEquals("famH", result.first().moduleFamilyId)
    }

    @Test
    fun `no active gaps yields no gap picks`() {
        val states = mapOf("gR" to GapState("gR", status = GapStatus.RESOLVED))
        val gaps = mapOf("gR" to gap("gR", "high"))
        val index = ModuleGapIndex(familyToPrimaryGap = emptyMap(), gapToFamilies = emptyMap())
        val modules = mapOf("famA" to module("famA"))

        val result = OnDeviceMorningSelector.select(states, gaps, index, modules, limit = 5, nowMillis = 0L, recencyOf = recencyByVersion)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `deprecated catalogue gap is excluded from gap-driven selection`() {
        val states = mapOf("gD" to activeState("gD", occurrence = 5))
        val gaps = mapOf("gD" to gap("gD", "high", status = "deprecated"))
        val index = ModuleGapIndex(
            familyToPrimaryGap = mapOf("famD" to "gD"),
            gapToFamilies = mapOf("gD" to listOf("famD")),
        )
        val modules = mapOf("famD" to module("famD"))

        val result = OnDeviceMorningSelector.select(states, gaps, index, modules, limit = 5, nowMillis = 0L, recencyOf = recencyByVersion)

        assertTrue(result.isEmpty())
    }

    // ── Visit-driven stand-in (selectFromTodaysAppointments) ─────────────────────

    @Test
    fun `visit cards built from candidates - highest priority first, one per family`() {
        val modules = mapOf("famA" to module("famA"), "famB" to module("famB"))
        val candidates = listOf(VisitCandidate("famA", 5), VisitCandidate("famB", 9), VisitCandidate("famA", 1))

        val out = OnDeviceMorningSelector.selectFromTodaysAppointments(candidates, modules, limit = 5, nowMillis = 0L)

        assertEquals(listOf("famB", "famA"), out.map { it.moduleFamilyId }) // weight 9 before 5; famA deduped
        assertEquals(listOf("visit", "visit"), out.map { it.source })
        assertEquals(listOf(null, null), out.map { it.behaviouralGapId })
        assertEquals(listOf(0, 1), out.map { it.rank })
    }

    @Test
    fun `visit candidate with no synced module is skipped`() {
        val modules = mapOf("famA" to module("famA"))
        val candidates = listOf(VisitCandidate("famMissing", 9), VisitCandidate("famA", 5))

        val out = OnDeviceMorningSelector.selectFromTodaysAppointments(candidates, modules, limit = 5, nowMillis = 0L)

        assertEquals(listOf("famA"), out.map { it.moduleFamilyId })
    }

    @Test
    fun `visit limit caps the cards`() {
        val modules = mapOf("famA" to module("famA"), "famB" to module("famB"))
        val candidates = listOf(VisitCandidate("famA", 9), VisitCandidate("famB", 5))

        val out = OnDeviceMorningSelector.selectFromTodaysAppointments(candidates, modules, limit = 1, nowMillis = 0L)

        assertEquals(1, out.size)
        assertEquals("famA", out.first().moduleFamilyId)
    }

    @Test
    fun `no visit candidates yields empty`() {
        assertTrue(OnDeviceMorningSelector.selectFromTodaysAppointments(emptyList(), emptyMap(), limit = 5, nowMillis = 0L).isEmpty())
    }
}
