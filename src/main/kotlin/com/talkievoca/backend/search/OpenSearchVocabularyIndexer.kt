package com.talkievoca.backend.search

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import tools.jackson.databind.ObjectMapper

@Component
class OpenSearchVocabularyIndexer(
    @param:Value("\${talkievoca.opensearch.enabled:true}") private val enabled: Boolean,
    @param:Value("\${talkievoca.opensearch.url}") private val baseUrl: String,
    @param:Value("\${talkievoca.opensearch.index}") private val index: String,
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override fun run(args: ApplicationArguments) {
        if (!enabled) return
        runCatching {
            ensureIndex()
            jdbc.query(
                "select word, meaning_ko, example_en, example_ko from vocabulary_entries"
            ) { resultSet, _ ->
                mapOf(
                    "word" to resultSet.getString("word"),
                    "meaning_ko" to resultSet.getString("meaning_ko"),
                    "example_en" to resultSet.getString("example_en"),
                    "example_ko" to resultSet.getString("example_ko")
                )
            }.forEach { entry -> index(entry) }
            log.info("OpenSearch vocabulary index is ready: {}", index)
        }.onFailure { log.warn("OpenSearch indexing skipped: {}", it.message) }
    }

    private fun ensureIndex() {
        if (status("HEAD", "/$index") == 200) return
        request(
            "PUT",
            "/$index",
            """{"mappings":{"properties":{"word":{"type":"text"},"meaning_ko":{"type":"text"},"example_en":{"type":"text"},"example_ko":{"type":"text"}}}}"""
        )
    }

    private fun index(entry: Map<String, String>) =
        request("PUT", "/$index/_doc/${entry.getValue("word")}", objectMapper.writeValueAsString(entry))

    private fun status(method: String, path: String): Int =
        client.send(
            HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding()
        ).statusCode()

    private fun request(method: String, path: String, body: String): String {
        val response = client.send(
            HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        require(response.statusCode() in 200..299) { "OpenSearch HTTP ${response.statusCode()}: ${response.body()}" }
        return response.body()
    }

    private fun uri(path: String) = URI.create(baseUrl.trimEnd('/') + path)
}
