package com.talkievoca.backend.search

import com.talkievoca.backend.vocabulary.WordFeedback
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import tools.jackson.databind.ObjectMapper

data class KnowledgeSearchHit(
    val sourceId: UUID,
    val title: String,
    val content: String,
    val score: Double
)

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
                """
                select word, meaning_ko, example_en, example_ko
                from vocabulary_entries
                where verification_status = 'trusted'
                """.trimIndent()
            ) { resultSet, _ ->
                mapOf(
                    "kind" to "vocabulary",
                    "word" to resultSet.getString("word"),
                    "meaning_ko" to resultSet.getString("meaning_ko"),
                    "example_en" to resultSet.getString("example_en"),
                    "example_ko" to resultSet.getString("example_ko")
                )
            }.forEach { entry -> index("vocabulary-${entry.getValue("word")}", entry) }
            jdbc.query(
                """
                select chunks.id, chunks.source_id, chunks.ordinal, chunks.content, sources.title
                from knowledge_chunks chunks join knowledge_sources sources on sources.id = chunks.source_id
                """.trimIndent()
            ) { resultSet, _ ->
                KnowledgeChunk(
                    id = UUID.fromString(resultSet.getString("id")),
                    sourceId = UUID.fromString(resultSet.getString("source_id")),
                    ordinal = resultSet.getInt("ordinal"),
                    content = resultSet.getString("content"),
                    title = resultSet.getString("title")
                )
            }.forEach(::indexKnowledgeChunk)
            log.info("OpenSearch vocabulary index is ready: {}", index)
        }.onFailure { log.warn("OpenSearch indexing skipped: {}", it.message) }
    }

    private fun ensureIndex() {
        if (status("HEAD", "/$index") == 200) return
        request(
            "PUT",
            "/$index",
            """{"mappings":{"properties":{"kind":{"type":"keyword"},"word":{"type":"text"},"meaning_ko":{"type":"text"},"example_en":{"type":"text"},"example_ko":{"type":"text"},"source_id":{"type":"keyword"},"title":{"type":"text"},"ordinal":{"type":"integer"},"content":{"type":"text"}}}}"""
        )
    }

    fun indexKnowledgeChunk(chunk: KnowledgeChunk) {
        if (!enabled) return
        runCatching {
            ensureIndex()
            index(
                "knowledge-${chunk.id}",
                mapOf(
                    "kind" to "knowledge",
                    "source_id" to chunk.sourceId.toString(),
                    "title" to chunk.title,
                    "ordinal" to chunk.ordinal,
                    "content" to chunk.content
                )
            )
        }.onFailure { log.warn("OpenSearch knowledge indexing skipped: {}", it.message) }
    }

    fun indexVocabulary(feedback: WordFeedback) {
        if (!enabled || !feedback.verified) return
        runCatching {
            ensureIndex()
            index(
                "vocabulary-${feedback.word}",
                mapOf(
                    "kind" to "vocabulary",
                    "word" to feedback.word,
                    "meaning_ko" to feedback.meaningKo,
                    "example_en" to feedback.exampleEn,
                    "example_ko" to feedback.exampleKo
                )
            )
        }.onFailure { log.warn("OpenSearch vocabulary indexing skipped: {}", it.message) }
    }

    fun searchKnowledge(query: String): List<KnowledgeSearchHit> {
        if (!enabled) return emptyList()
        return runCatching {
            ensureIndex()
            val body = objectMapper.writeValueAsString(
                mapOf(
                    "size" to 10,
                    "query" to mapOf(
                        "bool" to mapOf(
                            "filter" to listOf(mapOf("term" to mapOf("kind" to "knowledge"))),
                            "must" to listOf(mapOf("multi_match" to mapOf("query" to query, "fields" to listOf("title^2", "content"))))
                        )
                    )
                )
            )
            val hits = mutableListOf<KnowledgeSearchHit>()
            val nodes = objectMapper.readTree(request("POST", "/$index/_search", body)).path("hits").path("hits")
            val iterator = nodes.iterator()
            while (iterator.hasNext()) {
                val hit = iterator.next()
                val source = hit.path("_source")
                hits += KnowledgeSearchHit(
                    sourceId = UUID.fromString(source.path("source_id").asString()),
                    title = source.path("title").asString(),
                    content = source.path("content").asString(),
                    score = hit.path("_score").asDouble()
                )
            }
            hits
        }.onFailure { log.warn("OpenSearch knowledge search skipped: {}", it.message) }.getOrDefault(emptyList())
    }

    private fun index(id: String, entry: Map<String, Any>) =
        request("PUT", "/$index/_doc/$id?refresh=wait_for", objectMapper.writeValueAsString(entry))

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

data class KnowledgeChunk(
    val id: UUID,
    val sourceId: UUID,
    val ordinal: Int,
    val content: String,
    val title: String
)
