package com.talkievoca.backend.api

import com.talkievoca.backend.vocabulary.GeneratedVocabulary
import com.talkievoca.backend.vocabulary.VocabularyService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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

    private val client = HttpClient.newHttpClient()

    @Test
    fun `operator corrects a pending generated translation and repoints its Korean alias`() {
        vocabulary.saveGenerated(
            "오징어",
            GeneratedVocabulary("octopus", "오징어", "An octopus has eight arms.", "문어는 팔이 여덟 개다.")
        )

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
                "aliases":["오징어"]
            }""".trimIndent()
        )

        assertEquals(200, corrected.statusCode())
        val saved = objectMapper.readTree(corrected.body())
        assertEquals("squid", saved.path("word").asString())
        assertEquals("trusted", saved.path("verification_status").asString())
        assertEquals("manual", saved.path("origin").asString())
        assertEquals("squid", vocabulary.feedbackFor("오징어").word)
        assertFalse(vocabulary.reviewItems("pending").any { it.word == "octopus" })
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
