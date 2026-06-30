package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins `ModuleGapIndex.buildFromModules` — the gap↔module index built from
 * `module.primary_gap_id` + `module.behavioural_gap_ids` (the backend's
 * `module_behavioural_gap` data embedded on the module). `module_primary_gap_*`
 * placeholder gaps are kept like any other gap (the placeholder is the module's
 * quiz signal), matching the backend, which never filters by `gap_code`.
 */
class ModuleGapIndexTest {

    private fun module(
        family: String,
        primaryGap: String?,
        gaps: List<String>,
        version: Int = 1,
    ): ModuleEntity = moduleEntityFixture(
        moduleId = "$family-v$version",
        moduleFamilyId = family,
        version = version,
        titleBn = "title",
        moduleType = "refresher",
        estimatedMinutes = 5,
        difficultyLevel = "easy",
        primaryGapId = primaryGap,
        behaviouralGapIdsJson = gaps.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" },
    )

    @Test
    fun `maps primary gap per family and inverts behavioural_gap_ids`() {
        val index = ModuleGapIndex.buildFromModules(
            modules = listOf(
                module("famA", primaryGap = "gA", gaps = listOf("gA", "gB")), // gB = secondary link
                module("famB", primaryGap = "gC", gaps = listOf("gC")),
            ),
        )

        assertEquals(mapOf("famA" to "gA", "famB" to "gC"), index.familyToPrimaryGap)
        assertEquals(listOf("famA"), index.gapToFamilies["gA"])
        assertEquals(listOf("famA"), index.gapToFamilies["gB"])
        assertEquals(listOf("famB"), index.gapToFamilies["gC"])
    }

    @Test
    fun `placeholder primary gap is kept so a failed module quiz still surfaces`() {
        // A module_primary_gap_* placeholder is the module's quiz signal — it must
        // index exactly like a real gap (backend never filters by gap_code).
        val index = ModuleGapIndex.buildFromModules(
            modules = listOf(
                module(
                    "famP",
                    primaryGap = "module_primary_gap_famP",
                    gaps = listOf("module_primary_gap_famP", "gReal"),
                ),
            ),
        )

        assertEquals("module_primary_gap_famP", index.familyToPrimaryGap["famP"])      // primary kept
        assertEquals(listOf("famP"), index.gapToFamilies["module_primary_gap_famP"])    // placeholder link kept
        assertEquals(listOf("famP"), index.gapToFamilies["gReal"])                      // real secondary link kept
    }

    @Test
    fun `a gap shared by two modules lists both families`() {
        val index = ModuleGapIndex.buildFromModules(
            modules = listOf(
                module("famA", primaryGap = "gShared", gaps = listOf("gShared")),
                module("famB", primaryGap = "gShared", gaps = listOf("gShared")),
            ),
        )

        assertEquals(listOf("famA", "famB"), index.gapToFamilies["gShared"])
    }

    @Test
    fun `null primary gap yields no primary mapping but secondary links still index`() {
        val index = ModuleGapIndex.buildFromModules(
            modules = listOf(module("famN", primaryGap = null, gaps = listOf("gX"))),
        )

        assertTrue(index.familyToPrimaryGap.isEmpty())
        assertEquals(listOf("famN"), index.gapToFamilies["gX"])
    }

    @Test
    fun `empty modules yields empty maps`() {
        val index = ModuleGapIndex.buildFromModules(modules = emptyList())
        assertTrue(index.familyToPrimaryGap.isEmpty())
        assertTrue(index.gapToFamilies.isEmpty())
    }
}
