package com.medtroniclabs.microcoaching.ui.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonCardsJsonParserTest {

    @Test
    fun `parses card with both bn and en fields`() {
        val json = """
            [
              {
                "title_bn": "জরুরি রেফারেল",
                "title_en": "Emergency Referral",
                "body_bn": "যোনিপথে রক্তক্ষরণ\nভ্রূণের নড়াচড়া কমে যাওয়া",
                "body_en": "Vaginal bleeding\nReduced fetal movement",
                "card_family_id": "card-uuid-1"
              }
            ]
        """.trimIndent()

        val cards = parseLessonCards(json)
        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("জরুরি রেফারেল", card.titleBn)
        assertEquals("Emergency Referral", card.titleEn)
        assertEquals("যোনিপথে রক্তক্ষরণ\nভ্রূণের নড়াচড়া কমে যাওয়া", card.bodyBn)
        assertEquals("Vaginal bleeding\nReduced fetal movement", card.bodyEn)
        assertEquals("card-uuid-1", card.cardFamilyId)
    }

    @Test
    fun `absent en fields fall back to null not bn value`() {
        val json = """
            [
              {
                "title_bn": "শুধু বাংলা",
                "body_bn": "বাংলা বিষয়বস্তু"
              }
            ]
        """.trimIndent()

        val cards = parseLessonCards(json)
        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("শুধু বাংলা", card.titleBn)
        assertNull(card.titleEn)
        assertEquals("বাংলা বিষয়বস্তু", card.bodyBn)
        assertNull(card.bodyEn)
        assertNull(card.cardFamilyId)
    }

    @Test
    fun `empty or malformed input returns empty list and never throws`() {
        assertTrue(parseLessonCards("").isEmpty())
        assertTrue(parseLessonCards("not json").isEmpty())
        assertTrue(parseLessonCards("{}").isEmpty()) // object not array
        assertTrue(parseLessonCards("null").isEmpty())
    }

    @Test
    fun `missing title_bn defaults to empty string not null`() {
        val json = """[{"body_bn":"বিষয়বস্তু"}]"""
        val cards = parseLessonCards(json)
        assertEquals(1, cards.size)
        assertEquals("", cards.first().titleBn)
        assertEquals("বিষয়বস্তু", cards.first().bodyBn)
    }

    @Test
    fun `multiple cards parsed in order`() {
        val json = """
            [
              {"title_bn": "কার্ড এক", "body_bn": "বিষয় এক"},
              {"title_bn": "কার্ড দুই", "body_bn": "বিষয় দুই"},
              {"title_bn": "কার্ড তিন", "body_bn": "বিষয় তিন"}
            ]
        """.trimIndent()

        val cards = parseLessonCards(json)
        assertEquals(3, cards.size)
        assertEquals("কার্ড এক", cards[0].titleBn)
        assertEquals("কার্ড দুই", cards[1].titleBn)
        assertEquals("কার্ড তিন", cards[2].titleBn)
    }

    @Test
    fun `tiptap array body is preserved as a JSON string for the rich renderer`() {
        val json = """
            [
              {
                "title_bn": "শিরোনাম",
                "body_bn": [
                  {"type":"paragraph","content":[{"type":"text","text":"রিচ বিষয়বস্তু"}]}
                ]
              }
            ]
        """.trimIndent()

        val card = parseLessonCards(json).single()
        // bodyBn round-trips to a JSON array string so RichCardBody can detect + parse it.
        assertTrue(card.bodyBn.trimStart().startsWith("["))
        assertTrue(card.bodyBn.contains("রিচ বিষয়বস্তু"))
    }

    @Test
    fun `extra fields like thresholds and source_block_ids are silently ignored`() {
        val json = """
            [
              {
                "title_bn": "শিরোনাম",
                "body_bn": "বিষয়",
                "thresholds": [{"label": "BP", "value": "120/80"}],
                "source_block_ids": ["abc", "def"]
              }
            ]
        """.trimIndent()

        val cards = parseLessonCards(json)
        assertEquals(1, cards.size)
        assertEquals("শিরোনাম", cards.first().titleBn)
    }

    @Test
    fun `parses locale-map title and body`() {
        val json = """
            [
              {
                "title": {"bn": "বাংলা শিরোনাম", "en": "English title"},
                "body": {
                  "bn": "বাংলা বিষয়",
                  "en": "English body"
                }
              }
            ]
        """.trimIndent()

        val card = parseLessonCards(json).single()
        assertEquals("বাংলা শিরোনাম", card.titleBn)
        assertEquals("English title", card.titleEn)
        assertEquals("বাংলা বিষয়", card.bodyBn)
        assertEquals("English body", card.bodyEn)
    }
}
