package com.talkievoca.backend.asr

import com.sun.net.httpserver.HttpServer
import com.talkievoca.backend.api.InvalidAudioException
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import tools.jackson.databind.ObjectMapper

class QwenAsrClientTest {
    @Test
    fun `sends an OpenAI-compatible audio transcription request`() {
        val requestBody = AtomicReference("")
        val upgradeHeader = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/audio/transcriptions") { exchange ->
                upgradeHeader.set(exchange.requestHeaders.getFirst("Upgrade"))
                requestBody.set(exchange.requestBody.readAllBytes().decodeToString())
                val response = """{"text":"apple","language":"English"}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val client = QwenAsrClient(
                enabled = true,
                baseUrl = "http://localhost:${server.address.port}",
                model = "Qwen/Qwen3-ASR-0.6B",
                apiKey = "",
                timeoutSeconds = 2,
                maxBytes = 1024,
                objectMapper = ObjectMapper()
            )
            val result = client.transcribe("word.m4a", "audio/mp4", byteArrayOf(1, 2, 3))

            assertEquals("apple", result.text)
            assertEquals("English", result.language)
            assertContains(requestBody.get(), "Qwen/Qwen3-ASR-0.6B")
            assertContains(requestBody.get(), "filename=\"word.m4a\"")
            assertContains(requestBody.get(), "enable_speaker_diarization")
            assertContains(requestBody.get(), "false")
            assertNull(upgradeHeader.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `maps a provider audio rejection to invalid audio`() {
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/audio/transcriptions") { exchange ->
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(422, -1)
                exchange.close()
            }
            start()
        }

        try {
            val client = QwenAsrClient(
                enabled = true,
                baseUrl = "http://localhost:${server.address.port}",
                model = "qwen3-asr-0.6b",
                apiKey = "",
                timeoutSeconds = 2,
                maxBytes = 1024,
                objectMapper = ObjectMapper()
            )

            assertFailsWith<InvalidAudioException> {
                client.transcribe("bad.m4a", "audio/mp4", byteArrayOf(1, 2, 3))
            }
        } finally {
            server.stop(0)
        }
    }
}
