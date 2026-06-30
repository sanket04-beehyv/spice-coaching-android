package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import com.medtroniclabs.microcoaching.ui.learn.parseLessonCards
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks using canonical module fixtures under
 * `src/test/resources/modules/`.
 */
class ModuleSyncFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy flat module fixture round-trips through sync mapper`() {
        val text = readResource("modules/module_legacy_flat.json")
        val payload = json.decodeFromString<ModuleSyncPayload>(text)
        val entity = payload.toEntity(lastSynced = 0L)

        assertEquals("Management of Neonatal Sepsis", entity.titleEn)
        assertEquals("নিওনেটাল সেপসিস বোঝা", entity.titleBn)
        assertEquals(1, parseLessonCards(entity.cardsJson).size)
        assertEquals(1, parseInlineQuiz(entity.quizJson).size)
    }

    @Test
    fun `localized module fixture round-trips through sync mapper`() {
        val text = readResource("modules/module_localized.json")
        val payload = json.decodeFromString<ModuleSyncPayload>(text)
        val entity = payload.toEntity(lastSynced = 0L)

        assertEquals("Test08 EN", entity.titleEn)
        assertEquals("Test08", entity.titleBn)
        val card = parseLessonCards(entity.cardsJson).single()
        assertEquals("Card title", card.titleEn)
        assertTrue(card.bodyBn.contains("paragraph"))
        assertEquals("Test08 EN", parseInlineQuiz(entity.quizJson, lang = "en").single().questionText)
    }

    private fun readResource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }.bufferedReader().use { it.readText() }
}
