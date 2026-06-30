package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleKnowledgeIndexTest {

    private fun moduleWith(
        cardsJson: String,
        searchMetadataJson: String = "{}",
        moduleId: String = "m1",
        moduleFamilyId: String = "fam1",
    ) = moduleEntityFixture(
        moduleId = moduleId,
        moduleFamilyId = moduleFamilyId,
        cardsJson = cardsJson,
        searchMetadataJson = searchMetadataJson,
    )

    @Test
    fun `tiptap array body is indexed and retrievable`() {
        val cardsJson = """
            [
              {
                "title_en": "Dehydration signs",
                "body_en": [
                  {"type":"paragraph","content":[
                    {"type":"text","text":"Signs of dehydration include dry mouth and sunken eyes"}
                  ]},
                  {"type":"image","attrs":{"object_name":"media/uuid_pic.png"}}
                ]
              }
            ]
        """.trimIndent()

        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        // scoreThreshold=0 isolates "was the body text indexed?" from production
        // BM25 tuning (the default 3.0 is calibrated for the full on-device corpus).
        val hits = index.search("dehydration sunken eyes", k = 2, scoreThreshold = 0f)
        assertTrue("expected a hit on the array body", hits.isNotEmpty())

        // The reference text fed to the LLM must be clean prose, not JSON.
        val reference = hits.first().referenceText()
        assertTrue(reference.contains("dehydration"))
        assertFalse(reference.contains("object_name"))
        assertFalse(reference.contains("\"type\""))
        assertFalse(reference.contains("media/"))
    }

    @Test
    fun `markdown string body still indexes (regression)`() {
        val cardsJson = """[{"title_en":"Treatment","body_en":"Use **ORS** to rehydrate the child"}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("ORS rehydrate child", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
    }

    // ── Change 2: bare JsonObject body guard ─────────────────────────────────

    @Test
    fun `bare JsonObject body_en is indexed and referenceText is clean`() {
        val cardsJson = """
            [
              {
                "title_en": "Malnutrition signs",
                "body_en": {"type":"paragraph","content":[{"type":"text","text":"Signs of severe malnutrition include wasting"}]}
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("severe malnutrition wasting", k = 2, scoreThreshold = 0f)
        assertTrue("bare object body must be indexed", hits.isNotEmpty())
        val ref = hits.first().referenceText()
        assertFalse("referenceText must not contain raw JSON keys", ref.contains("\"type\""))
        assertFalse("referenceText must not contain raw JSON keys", ref.contains("\"content\""))
        assertTrue(ref.contains("malnutrition"))
    }

    // ── Phase 0: quiz JSON is not indexed ─────────────────────────────────────

    @Test
    fun `quiz vocabulary does not surface when absent from card bodies`() {
        val mod = moduleEntityFixture(
            moduleId = "m2",
            moduleFamilyId = "fam2",
            cardsJson = """[{"title_en":"Newborn care","body_en":"Keep baby warm."}]""",
            quizJson = """[{"question_en":"What is the hypertension threshold?","options_en":["BP 140 per 90 or above"],"correct_indices":[0],"explanation_en":"BP threshold 140 90."}]""",
        )
        val index = ModuleKnowledgeIndex.build(listOf(mod))
        val hits = index.search("hypertension threshold", k = 2, scoreThreshold = 0f)
        assertTrue("quiz vocabulary must not surface as a chunk", hits.isEmpty())
    }

    @Test
    fun `retrieval_metadata keywords make a card discoverable by mined vocabulary`() {
        val cardsJson = """
            [{
              "title_en": "ANC risk assessment",
              "body_en": "Watch for high-risk signs during antenatal visits.",
              "retrieval_metadata": {
                "keywords": ["PW", "pregnant woman", "low blood pressure", "90/60"],
                "aliases": ["high risk mother"],
                "concepts": ["ANC risk assessment"]
              }
            }]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("PW with low BP 90/60", k = 2, scoreThreshold = 0f)
        assertTrue("retrieval_metadata keywords must make the card discoverable", hits.isNotEmpty())
        assertEquals("ANC risk assessment", hits.first().titleEn)
    }

    @Test
    fun `card with no retrieval_metadata still indexes from title and body`() {
        val cardsJson = """[{"title_en":"Dehydration signs","body_en":"Dry mouth and sunken eyes."}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("dehydration sunken", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun `malformed retrieval_metadata is tolerated (degrades to empty)`() {
        // Wrong shape (array instead of object) must not break the index build.
        val cardsJson = """
            [{
              "title_en": "Anaemia",
              "body_en": "Severe anaemia haemoglobin below 7.5",
              "retrieval_metadata": ["not", "an", "object"]
            }]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("severe anaemia", k = 2, scoreThreshold = 0f)
        assertTrue("malformed metadata must not break the index", hits.isNotEmpty())
    }

    // ── Bangla recall: symmetric bigrams match inflected forms (F2) ──────────

    @Test
    fun `inflected bangla query retrieves base-form bangla chunk`() {
        // Card body is in Bangla ("টিকা" = vaccine). Query is the inflected form
        // "টিকার" (of the vaccine). Whole-word tokens differ; the symmetric
        // character bigrams indexed on both sides bridge the inflection.
        val cardsJson = """[{"title_bn":"টিকা","body_bn":"শিশুকে টিকা দিতে হবে নির্ধারিত সময়ে।"}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search(
            "টিকার",
            k = 2,
            scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.BN,
        )
        assertTrue("inflected Bangla query must surface the base-form chunk", hits.isNotEmpty())
    }

    // ── Per-language scoring keeps EN and BN bags separate (F4/F7) ───────────

    @Test
    fun `english query against BN index does not match english-only content`() {
        // English-only card. Searching the BN index must not return it — the per
        // language split keeps English tokens out of the Bangla bag.
        val cardsJson = """[{"title_en":"Dehydration signs","body_en":"dry mouth and sunken eyes"}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val bnHits = index.search("dehydration sunken eyes", k = 2, scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.BN)
        val enHits = index.search("dehydration sunken eyes", k = 2, scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.EN)
        assertTrue("BN index must not match English-only content", bnHits.isEmpty())
        assertTrue("EN index must match English content", enHits.isNotEmpty())
    }

    // ── Change 4: title boost ranks specific card above overview ─────────────

    @Test
    fun `title-boosted card outranks overview card for specific topic query`() {
        // Card 0 is the module overview — it mentions "danger signs" once in passing
        // among many other keywords. Card 1 is titled "Danger Signs" specifically.
        val cardsJson = """
            [
              {
                "title_en": "Module Overview",
                "body_en": "This module covers risk factors, treatment, danger signs, and referral."
              },
              {
                "title_en": "Danger Signs",
                "body_en": "Key danger signs include severe headache and visual disturbance."
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("danger signs", k = 2, scoreThreshold = 0f)
        assertTrue("must return at least one hit", hits.isNotEmpty())
        // The card titled "Danger Signs" (positionalId=1) must rank above the overview (positionalId=0).
        assertEquals(
            "Danger Signs card (positionalId=1) must be the top hit",
            1,
            hits.first().positionalId,
        )
    }

    // ── source_pages: object-array shape parses into page numbers ────────────

    @Test
    fun `object-shaped source_pages parses into page numbers`() {
        // The live backend ships source_pages as objects, not bare ints. The
        // chunk's sourcePages must carry the page_number so the chat PDF deep-link
        // can open the source document on the right page.
        val cardsJson = """
            [{
              "title_en": "What is Tuberculosis",
              "body_en": "Tuberculosis is caused by a bacterium.",
              "source_pages": [
                {"source_document_id": "doc-1", "page_number": 159, "start_ms": null}
              ]
            }]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("tuberculosis bacterium", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
        val sp = hits.first().sourcePages
        assertEquals(listOf(159), sp?.map { it.pageNumber })
        // The document id must be retained so chat attribution can cite the exact doc.
        assertEquals("doc-1", sp?.first()?.sourceDocumentId)
    }

    @Test
    fun `legacy int-array source_pages still parses (back-compat)`() {
        val cardsJson = """[{"title_en":"TB","body_en":"Tuberculosis bacterium.","source_pages":[161, 162]}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("tuberculosis bacterium", k = 2, scoreThreshold = 0f)
        val sp = hits.first().sourcePages
        assertEquals(listOf(161, 162), sp?.map { it.pageNumber })
        assertEquals("legacy payload carries no document id", null, sp?.first()?.sourceDocumentId)
    }

    @Test
    fun `non-positive source_pages entries are dropped`() {
        val cardsJson =
            """[{"title_en":"TB","body_en":"Tuberculosis bacterium.","source_pages":[{"page_number":0},{"page_number":162}]}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("tuberculosis bacterium", k = 2, scoreThreshold = 0f)
        assertEquals(listOf(162), hits.first().sourcePages?.map { it.pageNumber })
    }

    // ── Module-level search_metadata feeds the per-language BM25 index ────────

    private val tbSearchMetadata = """
        {
          "topic_tags": ["infectious_diseases", "respiratory"],
          "keywords_en": ["sputum test", "DOTS"],
          "keywords_bn": ["কফ পরীক্ষা"],
          "synonyms_en": {"DOTS": "Directly Observed Treatment, Short-course"},
          "search_phrases_en": ["How to conduct a sputum test for TB?"],
          "clinical_conditions": ["tuberculosis"]
        }
    """.trimIndent()

    @Test
    fun `english keyword from search_metadata makes the module discoverable`() {
        // Card body mentions neither "sputum" nor "DOTS"; only the module-level
        // search_metadata carries them. An EN query in that vocabulary must hit.
        val cardsJson = """[{"title_en":"TB management","body_en":"Guidance for treating patients."}]"""
        val index = ModuleKnowledgeIndex.build(
            listOf(moduleWith(cardsJson, searchMetadataJson = tbSearchMetadata)),
        )
        val hits = index.search("sputum test", k = 2, scoreThreshold = 0f)
        assertTrue("keywords_en must make the module discoverable", hits.isNotEmpty())
    }

    @Test
    fun `search_metadata synonym bridges the abbreviation to its expansion at query time`() {
        // synonyms_en is NO LONGER indexed into the document bag (that dumped
        // undifferentiated tokens onto every card and flattened ranking). Instead it
        // drives QUERY EXPANSION: the expansion phrase lives in the card body, and a
        // query using only the ABBREVIATION must bridge to it.
        val meta = """{"synonyms_en":{"DOTS":"directly observed treatment"}}"""
        val cardsJson =
            """[{"title_en":"TB therapy","body_en":"Use directly observed treatment to ensure adherence."}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson, searchMetadataJson = meta)))
        val hits = index.search("DOTS", k = 2, scoreThreshold = 0f)
        assertTrue("the synonym bridge must surface the card describing the expansion", hits.isNotEmpty())
    }

    @Test
    fun `bangla keyword from search_metadata hits via the BN index`() {
        val cardsJson = """[{"title_bn":"যক্ষ্মা ব্যবস্থাপনা","body_bn":"রোগীদের চিকিৎসার নির্দেশনা।"}]"""
        val index = ModuleKnowledgeIndex.build(
            listOf(moduleWith(cardsJson, searchMetadataJson = tbSearchMetadata)),
        )
        val hits = index.search(
            "কফ পরীক্ষা",
            k = 2,
            scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.BN,
        )
        assertTrue("keywords_bn must hit via the BN index", hits.isNotEmpty())
    }

    @Test
    fun `search_metadata is module-scoped — does not surface a different module`() {
        // Module A carries the TB metadata; module B is unrelated newborn content
        // with no overlap. A "sputum test" query must surface A only, never B.
        val tb = moduleWith(
            cardsJson = """[{"title_en":"TB management","body_en":"Guidance for treating patients."}]""",
            searchMetadataJson = tbSearchMetadata,
            moduleId = "mA",
            moduleFamilyId = "famA",
        )
        val newborn = moduleWith(
            cardsJson = """[{"title_en":"Newborn warmth","body_en":"Keep the baby warm and dry."}]""",
            moduleId = "mB",
            moduleFamilyId = "famB",
        )
        val index = ModuleKnowledgeIndex.build(listOf(tb, newborn))
        val hits = index.search("sputum test", k = 5, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
        assertTrue(
            "a module's metadata must never surface a different module",
            hits.none { it.moduleFamilyId == "famB" },
        )
        assertEquals("famA", hits.first().moduleFamilyId)
    }

    @Test
    fun `malformed search_metadata is tolerated (degrades to empty)`() {
        val cardsJson = """[{"title_en":"TB","body_en":"Tuberculosis bacterium."}]"""
        val index = ModuleKnowledgeIndex.build(
            listOf(moduleWith(cardsJson, searchMetadataJson = """{"keywords_en": "not-an-array"}""")),
        )
        // Build must not throw; card still indexes from body.
        val hits = index.search("tuberculosis bacterium", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
    }

    // ── Phase 2 prep: per-card retrieval_hints + retired families ────────────

    @Test
    fun `per-card retrieval hints discriminate sibling cards`() {
        // Mirrors Q30 sibling confusion: overview card vs disease-prevention card.
        // Module-level search_phrases must NOT flatten ranking; per-card hints on
        // the expected sibling must make it rank #1.
        val cardsJson = """
            [
              {
                "title_en": "Benefits of Using Sanitary Latrines",
                "body_en": "Sanitary latrines improve dignity and reduce odor in the home."
              },
              {
                "title_en": "Prevention of Waterborne and Feces-borne Diseases",
                "body_en": "Proper latrine use blocks fecal contamination of water sources.",
                "retrieval_hints_en": [
                  "how do sanitary latrines help prevent disease spread"
                ]
              }
            ]
        """.trimIndent()
        val sharedPhrases = """
            {
              "search_phrases_en": [
                "how do sanitary latrines help prevent disease spread",
                "benefits of sanitary latrines"
              ]
            }
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(
            listOf(moduleWith(cardsJson, searchMetadataJson = sharedPhrases)),
        )
        val hits = index.search(
            "How do sanitary latrines help prevent disease spread?",
            k = 2,
            scoreThreshold = 0f,
        )
        assertTrue(hits.isNotEmpty())
        assertEquals(
            "per-card hint must rank the disease-prevention sibling first",
            1,
            hits.first().positionalId,
        )
    }

    @Test
    fun `nested search_metadata retrieval hints are indexed when top-level absent`() {
        // Backend ships per-card hints under cards[i].search_metadata (§11.1).
        val cardsJson = """
            [
              {
                "title_en": "Benefits of Using Sanitary Latrines",
                "body_en": "Sanitary latrines improve dignity and reduce odor in the home."
              },
              {
                "title_en": "Prevention of Waterborne and Feces-borne Diseases",
                "body_en": "Proper latrine use blocks fecal contamination of water sources.",
                "search_metadata": {
                  "schema_version": 2,
                  "retrieval_hints_en": [
                    "how do sanitary latrines help prevent disease spread"
                  ],
                  "retrieval_hints_bn": [
                    "পায়খানা রোগ ছড়ানো রোধ করে কীভাবে"
                  ]
                }
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search(
            "how do sanitary latrines help prevent disease spread",
            k = 2,
            scoreThreshold = 0f,
        )
        assertEquals(1, hits.first().positionalId)
    }

    @Test
    fun `top-level retrieval hints take precedence over nested search_metadata`() {
        val cardsJson = """
            [
              {
                "title_en": "Card A",
                "body_en": "Body A.",
                "retrieval_hints_en": ["top level hint for card a"],
                "search_metadata": {
                  "retrieval_hints_en": ["nested hint should lose"]
                }
              },
              {
                "title_en": "Card B",
                "body_en": "Body B.",
                "search_metadata": {
                  "retrieval_hints_en": ["nested hint for card b"]
                }
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("top level hint for card a", k = 2, scoreThreshold = 0f)
        assertEquals(0, hits.first().positionalId)
        val nestedHits = index.search("nested hint for card b", k = 2, scoreThreshold = 0f)
        assertEquals(1, nestedHits.first().positionalId)
    }

    @Test
    fun `per-card search_metadata keywords discriminate sibling cards`() {
        val cardsJson = """
            [
              {"title_en": "Overview", "body_en": "General introduction."},
              {
                "title_en": "Specific Topic",
                "body_en": "Detailed guidance.",
                "search_metadata": {
                  "keywords_en": ["unique-card-keyword-xyz"]
                }
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("unique-card-keyword-xyz", k = 2, scoreThreshold = 0f)
        assertEquals(1, hits.first().positionalId)
    }

    @Test
    fun `per-card search_metadata questions index into question field`() {
        val cardsJson = """
            [
              {"title_en": "Overview", "body_en": "General introduction."},
              {
                "title_en": "ORS guidance",
                "body_en": "Rehydration steps.",
                "search_metadata": {
                  "questions_en": ["how much ORS should a child drink per day"]
                }
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("how much ORS should a child drink per day", k = 2, scoreThreshold = 0f)
        assertEquals(1, hits.first().positionalId)
    }

    @Test
    fun `per-card search_metadata synonyms_en feed query expansion`() {
        val cardsJson = """
            [
              {
                "title_en": "TB therapy",
                "body_en": "Use directly observed treatment.",
                "search_metadata": {
                  "synonyms_en": {"DOTS": "directly observed treatment"}
                }
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("DOTS", k = 2, scoreThreshold = 0f)
        assertTrue("card synonyms_en must bridge to indexed body vocabulary", hits.isNotEmpty())
        assertEquals(0, hits.first().positionalId)
    }

    @Test
    fun `full backend card search_metadata shape is indexed per field`() {
        val cardsJson = """
            [
              {"title_en": "Overview", "body_en": "General introduction."},
              {
                "title_en": "Disease prevention",
                "body_en": "Latrine use blocks contamination.",
                "search_metadata": {
                  "schema_version": 1,
                  "retrieval_hints_en": ["how do sanitary latrines help prevent disease spread"],
                  "retrieval_hints_bn": ["পায়খানা রোগ ছড়ানো রোধ করে কীভাবে"],
                  "keywords_en": ["fecal contamination"],
                  "keywords_bn": ["মল দূষণ"],
                  "questions_en": ["how do latrines prevent waterborne illness"],
                  "questions_bn": ["পানিবাহিত রোগ কীভাবে রোধ হয়"],
                  "synonyms_en": {"WASH": "water sanitation hygiene"}
                }
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        assertEquals(1, index.search("how do sanitary latrines help prevent disease spread", scoreThreshold = 0f).first().positionalId)
        assertEquals(1, index.search("fecal contamination", scoreThreshold = 0f).first().positionalId)
        assertEquals(1, index.search("how do latrines prevent waterborne illness", scoreThreshold = 0f).first().positionalId)
    }

    @Test
    fun `module search_phrases demoted to keyword do not duplicate on every card question field`() {
        val cardsJson = """
            [
              {"title_en": "Overview", "body_en": "General module introduction."},
              {"title_en": "Specific Topic", "body_en": "Detailed guidance on the topic."}
            ]
        """.trimIndent()
        val meta = """{"search_phrases_en": ["unique phrase only in metadata"]}"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson, searchMetadataJson = meta)))
        // The phrase is only in KEYWORD now — a query matching ONLY that phrase should
        // still hit (via KEYWORD field) but both cards get equal phrase weight, not
        // inflated QUESTION duplication.
        val hits = index.search("unique phrase only in metadata", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun `retired family ids are excluded from the index`() {
        val active = moduleWith(
            cardsJson = """[{"title_en":"Active module","body_en":"Still published."}]""",
            moduleFamilyId = "fam-active",
        )
        val retired = moduleWith(
            cardsJson = """[{"title_en":"Retired module","body_en":"Should not appear."}]""",
            moduleId = "m-retired",
            moduleFamilyId = "fam-retired",
        )
        val index = ModuleKnowledgeIndex.build(
            modules = listOf(active, retired),
            retiredFamilyIds = setOf("fam-retired"),
        )
        assertEquals(1, index.size)
        val hits = index.search("Should not appear", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `localized search_metadata keywords make module discoverable`() {
        val meta = """
            {
              "keywords": {"en": ["neonatal sepsis"], "bn": ["নিওনেটাল সেপসিস"]},
              "search_phrases": {"en": ["signs of neonatal sepsis"]}
            }
        """.trimIndent()
        val cardsJson = """[{"title": {"en": "Sepsis"}, "body": {"en": "General care."}}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson, searchMetadataJson = meta)))
        val hits = index.search("neonatal sepsis", k = 2, scoreThreshold = 0f)
        assertTrue("localized keywords must be indexed", hits.isNotEmpty())
    }
}
