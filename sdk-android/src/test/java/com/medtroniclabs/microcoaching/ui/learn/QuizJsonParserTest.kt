package com.medtroniclabs.microcoaching.ui.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizJsonParserTest {

    @Test
    fun `parses backend quiz row with id question options and correct_indices`() {
        val json = """
            [
              {
                "id": "q-1",
                "question_bn": "প্রশ্ন এক",
                "options_bn": ["এ", "বি", "সি", "ডি"],
                "correct_indices": [2],
                "explanation_bn": "ব্যাখ্যা"
              }
            ]
        """.trimIndent()

        val questions = parseInlineQuiz(json)
        assertEquals(1, questions.size)
        val q = questions.first()
        assertEquals("q-1", q.id)
        assertEquals("প্রশ্ন এক", q.questionText)
        assertEquals(listOf("এ", "বি", "সি", "ডি"), q.answers)
        assertEquals(2, q.correctIndex)
        assertEquals("ব্যাখ্যা", q.explanation)
    }

    @Test
    fun `drops rows missing the required id field`() {
        val json = """
            [
              { "question_bn": "no id here", "options_bn": ["a"], "correct_indices": [0] },
              { "id": "q-2", "question_bn": "ok", "options_bn": ["a", "b"], "correct_indices": [0] }
            ]
        """.trimIndent()

        val questions = parseInlineQuiz(json)
        assertEquals(1, questions.size)
        assertEquals("q-2", questions.first().id)
    }

    @Test
    fun `empty or malformed input yields empty list, never throws`() {
        assertTrue(parseInlineQuiz("").isEmpty())
        assertTrue(parseInlineQuiz("not json at all").isEmpty())
        assertTrue(parseInlineQuiz("{}").isEmpty()) // not an array
    }

    @Test
    fun `defaults correctIndex to 0 when correct_indices missing`() {
        val json = """[{"id":"q-3","question_bn":"q","options_bn":["a","b"]}]"""
        val q = parseInlineQuiz(json).first()
        assertEquals(0, q.correctIndex)
    }

    @Test
    fun `parses options_bn shipped as label-value objects (pilot data shape)`() {
        // Real backend pilot data sometimes ships options as objects, not strings.
        val json = """
            [
              {
                "id": "00000000-0000-0000-0000-000000000221",
                "question_bn": "কখন রেফার করবেন?",
                "options_bn": [
                  {"label": "তাৎক্ষণিক", "value": 0},
                  {"label": "কখনই না", "value": 1}
                ],
                "correct_indices": [0],
                "explanation_bn": "বিপদচিহ্ন থাকলে রেফার করুন।"
              }
            ]
        """.trimIndent()

        val questions = parseInlineQuiz(json)
        assertEquals(1, questions.size)
        val q = questions.first()
        assertEquals(listOf("তাৎক্ষণিক", "কখনই না"), q.answers)
        assertEquals(0, q.correctIndex)
        assertEquals("বিপদচিহ্ন থাকলে রেফার করুন।", q.explanation)
    }

    @Test
    fun `string and object option shapes coexist in same payload`() {
        val json = """
            [
              {"id":"q-a","question_bn":"a","options_bn":["plain","string"],"correct_indices":[0]},
              {"id":"q-b","question_bn":"b","options_bn":[{"label":"obj","value":0}],"correct_indices":[0]}
            ]
        """.trimIndent()

        val questions = parseInlineQuiz(json)
        assertEquals(2, questions.size)
        assertEquals(listOf("plain", "string"), questions[0].answers)
        assertEquals(listOf("obj"), questions[1].answers)
    }

    @Test
    fun `lang=en returns _en fields when present`() {
        val json = """
            [
              {
                "id": "q-en",
                "question_bn": "প্রশ্ন বাংলায়",
                "question_en": "Question in English",
                "options_bn": ["ক", "খ"],
                "options_en": ["A", "B"],
                "correct_indices": [1],
                "explanation_bn": "বাংলা ব্যাখ্যা",
                "explanation_en": "English explanation",
                "case_setup_bn": "বাংলা কেস সেটআপ",
                "case_setup_en": "English case setup"
              }
            ]
        """.trimIndent()

        val questions = parseInlineQuiz(json, lang = "en")
        assertEquals(1, questions.size)
        val q = questions.first()
        assertEquals("Question in English", q.questionText)
        assertEquals(listOf("A", "B"), q.answers)
        assertEquals("English explanation", q.explanation)
        assertEquals("English case setup", q.caseSetup)
    }

    @Test
    fun `lang=en falls back to _bn when _en field absent`() {
        val json = """
            [
              {
                "id": "q-fallback",
                "question_bn": "প্রশ্ন বাংলায়",
                "options_bn": ["ক", "খ"],
                "correct_indices": [0],
                "explanation_bn": "বাংলা ব্যাখ্যা"
              }
            ]
        """.trimIndent()

        val questions = parseInlineQuiz(json, lang = "en")
        assertEquals(1, questions.size)
        val q = questions.first()
        assertEquals("প্রশ্ন বাংলায়", q.questionText)
        assertEquals(listOf("ক", "খ"), q.answers)
        assertEquals("বাংলা ব্যাখ্যা", q.explanation)
    }

    @Test
    fun `case_setup is parsed from wire and stored on QuizQuestion`() {
        val json = """
            [
              {
                "id": "q-cs",
                "question_bn": "প্রশ্ন",
                "options_bn": ["ক"],
                "correct_indices": [0],
                "case_setup_bn": "কেস সেটআপ বিবরণ"
              }
            ]
        """.trimIndent()

        val q = parseInlineQuiz(json).first()
        assertEquals("কেস সেটআপ বিবরণ", q.caseSetup)
    }

    @Test
    fun `parses new locale-map quiz row`() {
        val json = """
            [
              {
                "id": "4be36040-4b8c-471f-9515-dcb607a0a68c",
                "question_order": 1,
                "question": {"bn": "Test08", "en": ""},
                "case_setup": {},
                "options": {
                  "bn": ["", "", "", ""],
                  "en": ["Option 1", "Option 2"]
                },
                "correct_indices": [0],
                "explanation": {"en": ""},
                "difficulty": "medium"
              }
            ]
        """.trimIndent()

        val questions = parseInlineQuiz(json, lang = "en")
        assertEquals(1, questions.size)
        val q = questions.first()
        assertEquals("Test08", q.questionText)
        assertEquals(listOf("Option 1", "Option 2"), q.answers)
    }
}
