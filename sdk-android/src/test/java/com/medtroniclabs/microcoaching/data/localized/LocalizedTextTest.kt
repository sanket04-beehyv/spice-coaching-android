package com.medtroniclabs.microcoaching.data.localized

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedTextTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `readLocalized prefers nested locale map`() {
        val obj = json.parseToJsonElement(
            """{"title": {"bn": "বাংলা", "en": "English"}}""",
        ).let { it as kotlinx.serialization.json.JsonObject }

        val text = obj.readLocalized("title")
        assertEquals("বাংলা", text.bn)
        assertEquals("English", text.en)
    }

    @Test
    fun `readLocalized falls back to legacy flat keys`() {
        val obj = json.parseToJsonElement(
            """{"title_bn": "বাংলা", "title_en": "English"}""",
        ).let { it as kotlinx.serialization.json.JsonObject }

        val text = obj.readLocalized("title")
        assertEquals("বাংলা", text.bn)
        assertEquals("English", text.en)
    }

    @Test
    fun `readLocalized nested wins over legacy when both present`() {
        val obj = json.parseToJsonElement(
            """{
              "title": {"bn": "nested"},
              "title_bn": "legacy"
            }""",
        ).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals("nested", obj.readLocalized("title").bn)
    }

    @Test
    fun `readLocalizedArray reads nested options map`() {
        val obj = json.parseToJsonElement(
            """{"options": {"bn": ["এ", "বি"], "en": ["A", "B"]}}""",
        ).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals(listOf("A", "B"), obj.readLocalizedArray("options", "en"))
        assertEquals(listOf("এ", "বি"), obj.readLocalizedArray("options", "bn"))
    }

    @Test
    fun `readLocalizedArray falls back to options_bn`() {
        val obj = json.parseToJsonElement(
            """{"options_bn": ["এ", "বি"]}""",
        ).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals(listOf("এ", "বি"), obj.readLocalizedArray("options", "bn"))
    }

    @Test
    fun `readLocalizedBody reads TipTap array under body bn`() {
        val obj = json.parseToJsonElement(
            """{"body": {"bn": [{"type": "paragraph", "content": []}]}}""",
        ).let { it as kotlinx.serialization.json.JsonObject }

        val body = obj.readLocalizedBody("body", "bn")
        assertTrue(body!!.contains("paragraph"))
    }

    @Test
    fun `readLocalizedBody does not cross-fallback from bn to en`() {
        val obj = json.parseToJsonElement(
            """{"body_bn": "plain markdown"}""",
        ).let { it as kotlinx.serialization.json.JsonObject }

        assertNull(obj.readLocalizedBody("body", "en"))
        assertEquals("plain markdown", obj.readLocalizedBody("body", "bn"))
    }

    @Test
    fun `toJsonString round trips`() {
        val original = LocalizedText(bn = "বাংলা", en = "English")
        val decoded = LocalizedText.decode(original.toJsonString())
        assertEquals(original, decoded)
    }

    @Test
    fun `fromBnEn strips blank strings`() {
        val text = LocalizedText.fromBnEn(bn = "বাংলা", en = "  ")
        assertEquals("বাংলা", text.bn)
        assertNull(text.en)
    }

    @Test
    fun `readLocalizedArray on search_metadata keywords map`() {
        val obj = buildJsonObject {
            put(
                "keywords",
                buildJsonObject {
                    put("en", json.parseToJsonElement("""["neonatal", "sepsis"]"""))
                    put("bn", json.parseToJsonElement("""["নিওনেটাল"]"""))
                },
            )
        }
        assertEquals(listOf("neonatal", "sepsis"), obj.readLocalizedArray("keywords", "en"))
    }
}
