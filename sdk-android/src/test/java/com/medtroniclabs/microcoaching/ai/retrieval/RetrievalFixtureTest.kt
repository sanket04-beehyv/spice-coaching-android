package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parameterised harness over `src/test/resources/retrieval/` fixture JSON files.
 * Proves per-card hint consumption ranks the expected sibling **before** backend
 * sync ships real metadata — flip overlays on in benchmark APKs via
 * [com.medtroniclabs.microcoaching.ai.retrieval.RetrievalHintOverlay].
 */
@RunWith(Parameterized::class)
class RetrievalFixtureTest(
    private val fixtureName: String,
    private val fixture: RetrievalFixture,
) {

    @Test
    fun `fixture queries rank expected card first`() {
        val index = ModuleKnowledgeIndex.build(listOf(RetrievalFixtureLoader.toModuleEntity(fixture)))
        fixture.queries.forEach { q ->
            val hits = index.search(
                query = q.query,
                k = 3,
                scoreThreshold = 0f,
                language = RetrievalFixtureLoader.lang(q),
            )
            val ref = q.benchmarkRef?.let { " ($it)" } ?: ""
            assertTrue(
                "fixture=${fixture.id} query='${q.query}'$ref must return hits",
                hits.isNotEmpty(),
            )
            assertEquals(
                "fixture=${fixture.id} query='${q.query}'$ref",
                q.expectedCardIndex,
                hits.first().positionalId,
            )
        }
    }

    companion object {
        private val FIXTURE_FILES = listOf(
            "diarrhea_prevention_fixture.json",
            "first_aid_unconscious_fixture.json",
            "hypertension_threshold_fixture.json",
            "newborn_heat_loss_fixture.json",
            "sanitary_latrines_sibling_fixture.json",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> {
            val classLoader = RetrievalFixtureTest::class.java.classLoader
                ?: error("no classloader")
            return FIXTURE_FILES.map { name ->
                val text = classLoader.getResourceAsStream("retrieval/$name")?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("missing fixture resource: retrieval/$name")
                val fixture = RetrievalFixtureLoader.parse(text)
                arrayOf(name, fixture)
            }
        }
    }
}
