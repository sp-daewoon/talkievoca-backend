package com.talkievoca.backend.api

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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:assistant;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "talkievoca.opensearch.enabled=false"
    ]
)
class AssistantQueryApiTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val client = HttpClient.newHttpClient()

    @Test
    fun `Korean input resolves through the single assistant endpoint`() {
        val response = post("""{"text":"사과"}""")

        assertEquals(200, response.statusCode())
        val json = objectMapper.readTree(response.body())
        assertEquals("사과", json.path("query").asString())
        assertEquals("apple", json.path("word").asString())
        assertEquals("사과", json.path("meaning_ko").asString())
        assertEquals("alias", json.path("matched_by").asString())
        assertEquals("http://localhost:$port/audio/apple.m4a", json.path("audio_url").asString())
    }

    @Test
    fun `blank input returns the standard validation error`() {
        val response = post("""{"text":"  "}""")

        assertEquals(422, response.statusCode())
        val json = objectMapper.readTree(response.body())
        assertEquals("invalid_word", json.path("error").path("code").asString())
    }

    @Test
    fun `missing text returns the standard validation error`() {
        val response = post("{}")

        assertEquals(422, response.statusCode())
        val json = objectMapper.readTree(response.body())
        assertEquals("invalid_word", json.path("error").path("code").asString())
        assertEquals("text is required.", json.path("error").path("message").asString())
    }

    private fun post(body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/assistant/query"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString()
    )
}
