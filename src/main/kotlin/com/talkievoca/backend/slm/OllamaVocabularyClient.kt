package com.talkievoca.backend.slm

import com.talkievoca.backend.vocabulary.GeneratedVocabulary
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import tools.jackson.databind.ObjectMapper

@Component
class OllamaVocabularyClient(
    @param:Value("\${talkievoca.slm.enabled:false}") val enabled: Boolean,
    @param:Value("\${talkievoca.slm.url:http://localhost:11434}") private val baseUrl: String,
    @param:Value("\${talkievoca.slm.model:qwen3:4b}") private val model: String,
    @Value("\${talkievoca.slm.timeout-seconds:12}") timeoutSeconds: Long,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun status(): String {
        if (!enabled) return "disabled"
        return runCatching {
            client.send(
                HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/api/tags"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            ).statusCode().let { if (it in 200..299) "ready" else "unavailable" }
        }.getOrDefault("unavailable")
    }

    fun generate(query: String, knowledge: List<String>): GeneratedVocabulary? {
        if (!enabled) return null
        return runCatching {
            val prompt = buildString {
                appendLine("Create a card for: $query")
                if (knowledge.isNotEmpty()) {
                    appendLine("Reference material (use only when relevant):")
                    knowledge.forEach { appendLine(it.take(900)) }
                }
            }
            val payload = mapOf(
                "model" to model,
                "stream" to false,
                "think" to false,
                "keep_alive" to "10m",
                "format" to RESPONSE_SCHEMA,
                "options" to mapOf("temperature" to 0, "num_predict" to 180),
                "messages" to listOf(
                    mapOf(
                        "role" to "system",
                        "content" to """
                            Create one English vocabulary card for a Korean learner. For a recognizable Korean or English word,
                            found MUST be true. word MUST be lowercase ASCII English. Never put Korean in word. meaning_ko MUST
                            be Korean. Use an extremely simple 4-7 word sentence with no names or occupations, then translate it
                            literally into Korean with no English characters. Good example: word dog, meaning_ko 강아지,
                            example_en The dog is very friendly., example_ko 그 개는 매우 친근하다. For gibberish only, found is false.
                        """.trimIndent()
                    ),
                    mapOf("role" to "user", "content" to prompt)
                )
            )
            val response = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            require(response.statusCode() in 200..299) { "Ollama HTTP ${response.statusCode()}" }
            val content = objectMapper.readTree(response.body()).path("message").path("content").asString()
            val result = objectMapper.readTree(content)
            if (!result.path("found").asBoolean()) return null
            val generated = GeneratedVocabulary(
                word = result.path("word").asString(),
                meaningKo = result.path("meaning_ko").asString(),
                exampleEn = result.path("example_en").asString(),
                exampleKo = result.path("example_ko").asString()
            )
            require(generated.word.matches(ENGLISH_TERM)) { "SLM returned a non-English headword." }
            require(
                generated.meaningKo.contains(HANGUL) &&
                    generated.exampleKo.contains(HANGUL) &&
                    !generated.exampleKo.contains(ENGLISH_LETTER)
            ) {
                "SLM returned incomplete Korean text."
            }
            require(generated.exampleEn.contains(ENGLISH_LETTER)) { "SLM returned an invalid English example." }
            generated
        }.onFailure { log.warn("SLM vocabulary resolution failed: {}", it.message) }.getOrNull()
    }

    private companion object {
        val RESPONSE_SCHEMA = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "found" to mapOf("type" to "boolean"),
                "word" to mapOf("type" to "string", "pattern" to "^[a-z]+(?:[ '-][a-z]+)*$"),
                "meaning_ko" to mapOf("type" to "string"),
                "example_en" to mapOf("type" to "string"),
                "example_ko" to mapOf("type" to "string")
            ),
            "required" to listOf("found", "word", "meaning_ko", "example_en", "example_ko")
        )
        val ENGLISH_TERM = Regex("^[a-z]+(?:[ '-][a-z]+)*$")
        val ENGLISH_LETTER = Regex("[A-Za-z]")
        val HANGUL = Regex("[가-힣]")
    }
}
