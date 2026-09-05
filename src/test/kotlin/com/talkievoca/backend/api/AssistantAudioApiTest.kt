package com.talkievoca.backend.api

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:assistant-audio;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "talkievoca.opensearch.enabled=false",
        "talkievoca.asr.enabled=true",
        "talkievoca.slm.enabled=false"
    ]
)
class AssistantAudioApiTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `Qwen transcript passes through the shared vocabulary resolver`() {
        val boundary = "TalkieVocaAudioTest"
        val body = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"word.m4a\"\r\n" +
            "Content-Type: audio/mp4\r\n\r\n" +
            "audio\r\n" +
            "--$boundary--\r\n"
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/assistant/audio"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, response.statusCode())
        val json = objectMapper.readTree(response.body())
        assertEquals("apple", json.path("query").asString())
        assertEquals("apple", json.path("word").asString())
        assertEquals("qwen_exact", json.path("matched_by").asString())
        assertEquals("English", json.path("transcript_language").asString())
    }

    companion object {
        private val asrServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/audio/transcriptions") { exchange ->
                exchange.requestBody.readAllBytes()
                val response = """{"text":"apple","language":"English"}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun asrProperties(registry: DynamicPropertyRegistry) {
            registry.add("talkievoca.asr.url") { "http://localhost:${asrServer.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopAsrServer() {
            asrServer.stop(0)
        }
    }
}
