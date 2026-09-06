package com.talkievoca.backend.api

import com.talkievoca.backend.vocabulary.GeneratedVocabulary
import com.talkievoca.backend.vocabulary.VocabularyService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "talkievoca.opensearch.enabled=false"
    ]
)
class VocabularyAdminApiTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var vocabulary: VocabularyService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val client = HttpClient.newHttpClient()

    @Test
    fun `operator corrects a pending generated translation and repoints its Korean alias`() {
        vocabulary.saveGenerated(
            "오징어",
            GeneratedVocabulary("octopus", "오징어", "An octopus has eight arms.", "문어는 팔이 여덟 개다.")
        )
        val sourceId = UUID.randomUUID()
        jdbc.update("insert into knowledge_sources (id, title, original_filename, content_type, storage_path, extracted_text) values (?, 'Lesson', 'lesson.pdf', 'application/pdf', 'fixture.pdf', 'octopus')", sourceId)
        jdbc.update("insert into vocabulary_sources (word, source_id) values ('octopus', ?)", sourceId)

        val pending = get("/v1/admin/vocabulary?status=pending")
        assertEquals(200, pending.statusCode())
        assertTrue(objectMapper.readTree(pending.body()).any { it.path("word").asString() == "octopus" })

        val corrected = post(
            "/v1/admin/vocabulary/corrections",
            """{
                "original_word":"octopus",
                "word":"squid",
                "meaning_ko":"오징어",
                "example_en":"The squid swims through the sea.",
                "example_ko":"그 오징어는 바다를 헤엄쳐 다닌다.",
                "aliases":["오징어"],
                "questions":["오징어를 영어로 뭐라고 해?"]
            }""".trimIndent()
        )

        assertEquals(200, corrected.statusCode())
        val saved = objectMapper.readTree(corrected.body())
        assertEquals("squid", saved.path("word").asString())
        assertEquals("trusted", saved.path("verification_status").asString())
        assertEquals("manual", saved.path("origin").asString())
        assertEquals("오징어를 영어로 뭐라고 해?", saved.path("questions")[0].asString())
        assertEquals("squid", vocabulary.feedbackFor("오징어").word)
        assertEquals(sourceId, jdbc.queryForObject("select source_id from vocabulary_sources where word = 'squid'", UUID::class.java))
        assertFalse(vocabulary.reviewItems("pending").any { it.word == "octopus" })
    }

    @Test
    fun `operator creates a new card before attaching pronunciation`() {
        val response = post("/v1/admin/vocabulary/corrections", """{
            "original_word":"", "word":"lamp", "meaning_ko":"등", "example_en":"The lamp is on.",
            "example_ko":"등이 켜져 있다.", "aliases":["등"], "questions":["등을 영어로 뭐라고 해?"]
        }""")
        assertEquals(200, response.statusCode())
        assertEquals("lamp", vocabulary.feedbackFor("등").word)
        assertEquals(422, post("/v1/admin/vocabulary/corrections", """{
            "original_word":"", "word":"lamp", "meaning_ko":"다른 뜻", "example_en":"The lamp is on.","example_ko":"등이 켜져 있다."
        }""").statusCode())
    }

    @Test
    fun `admin page is served by the backend`() {
        val response = get("/admin/")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("TalkieVoca"))
        assertTrue(response.body().contains("검수 목록"))
    }

    private fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    )

    private fun post(path: String, body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString()
    )
}
