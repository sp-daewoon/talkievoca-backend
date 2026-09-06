package com.talkievoca.backend.search

import com.talkievoca.backend.api.InvalidWordException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

data class MeaningHit(val id: String, val kind: String, val word: String?, val content: String, val similarity: Double)
data class EmbeddingRebuild(val indexed: Int, val failed: Int, val model: String)

@Service
class MeaningSearchService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    @param:Value("\${talkievoca.embedding.enabled:false}") private val enabled: Boolean,
    @param:Value("\${talkievoca.embedding.url:http://localhost:11434}") private val ollamaUrl: String,
    @param:Value("\${talkievoca.opensearch.url}") private val searchUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val index = "talkievoca-meaning-bge-m3-v1"
    private val model = "bge-m3"

    fun rebuild(): EmbeddingRebuild {
        var indexed = 0
        var failed = 0
        jdbc.queryForList("select word from vocabulary_entries where verification_status = 'trusted'", String::class.java)
            .forEach { if (indexVocabulary(it)) indexed++ else failed++ }
        jdbc.queryForList("select id from knowledge_chunks", UUID::class.java)
            .forEach { if (indexChunk(it)) indexed++ else failed++ }
        return EmbeddingRebuild(indexed, failed, model)
    }

    fun indexVocabulary(word: String): Boolean = safely(false) {
        val content = vocabularyContent(word) ?: return@safely false
        put("vocabulary-$word", "vocabulary", word, content)
        true
    }

    fun indexChunk(id: UUID): Boolean = safely(false) {
        val content = chunkContent(id) ?: return@safely false
        put("knowledge-$id", "knowledge", null, content)
        true
    }

    fun search(query: String): List<MeaningHit> {
        if (query.isBlank() || query.length > 300) throw InvalidWordException("q must contain 1 to 300 characters.")
        return safely(emptyList()) {
            val vector = embed(query)
            ensureIndex()
            // Personal corpus: exact cosine scan. Use ANN when corpus size makes this expensive.
            val body = mapOf(
                "size" to 10,
                "_source" to listOf("kind", "word", "content"),
                "query" to mapOf("script_score" to mapOf(
                    "query" to mapOf("match_all" to emptyMap<String, String>()),
                    "script" to mapOf("lang" to "painless",
                        "source" to "1.0 + cosineSimilarity(params.query_vector, doc['embedding'])",
                        "params" to mapOf("query_vector" to vector))
                ))
            )
            mapper.readTree(http(searchUrl, "POST", "/$index/_search", body)).path("hits").path("hits")
                .mapNotNull { hit ->
                    val source = hit.path("_source")
                    val kind = source.path("kind").asString()
                    val id = hit.path("_id").asString()
                    val word = if (kind == "vocabulary") source.path("word").asString() else null
                    val content = source.path("content").asString()
                    val current = if (word != null) vocabularyContent(word)
                        else chunkContent(UUID.fromString(id.removePrefix("knowledge-")))
                    val cosine = hit.path("_score").asDouble() - 1.0
                    if (current != content || cosine < 0.45) null else MeaningHit(id, kind, word, content, cosine)
                }.take(3)
        }
    }

    private fun vocabularyContent(word: String): String? = jdbc.query(
        "select meaning_ko, example_en, example_ko from vocabulary_entries where word = ? and verification_status = 'trusted'",
        { rs, _ ->
            val questions = jdbc.queryForList("select question from vocabulary_questions where word = ? order by question", String::class.java, word)
            meaningDocument(rs.getString("meaning_ko"), rs.getString("example_en"), rs.getString("example_ko"), questions)
        }, word
    ).firstOrNull()

    private fun chunkContent(id: UUID): String? = jdbc.query(
        "select s.title, c.content from knowledge_chunks c join knowledge_sources s on s.id = c.source_id where c.id = ?",
        { rs, _ -> "${rs.getString("title")}\n${rs.getString("content")}" }, id
    ).firstOrNull()

    private fun put(id: String, kind: String, word: String?, content: String) {
        val vector = embed(content)
        ensureIndex()
        val encodedId = java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")
        http(searchUrl, "PUT", "/$index/_doc/$encodedId?refresh=wait_for", mapOf(
            "kind" to kind, "word" to word, "content" to content, "model" to model, "embedding" to vector
        ))
    }

    private fun embed(text: String): List<Double> {
        val node = mapper.readTree(http(ollamaUrl, "POST", "/api/embed", mapOf(
            "model" to model, "input" to text, "truncate" to false, "keep_alive" to "10m"
        ))).path("embeddings")
        require(node.size() == 1) { "Embedding provider returned an invalid batch." }
        val vector = node[0].iterator().asSequence().map { it.asDouble() }.toList()
        require(vector.size == 1024 && vector.all(Double::isFinite) && vector.any { it != 0.0 }) {
            "Expected a finite nonzero 1024-dimensional embedding."
        }
        return vector
    }

    @Synchronized
    private fun ensureIndex() {
        val response = client.send(HttpRequest.newBuilder(URI.create("$searchUrl/$index"))
            .timeout(Duration.ofSeconds(3)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 200) return
        require(response.statusCode() == 404) { "Semantic index is unavailable." }
        http(searchUrl, "PUT", "/$index", mapOf("mappings" to mapOf("properties" to mapOf(
            "embedding" to mapOf("type" to "knn_vector", "dimension" to 1024),
            "kind" to mapOf("type" to "keyword"), "model" to mapOf("type" to "keyword"),
            "word" to mapOf("type" to "keyword"), "content" to mapOf("type" to "text")
        ))))
    }

    private fun http(base: String, method: String, path: String, body: Any): String {
        val response = client.send(HttpRequest.newBuilder(URI.create(base.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Embedding/search HTTP ${response.statusCode()}" }
        return response.body()
    }

    private fun <T> safely(fallback: T, action: () -> T): T {
        if (!enabled) return fallback
        return runCatching(action).onFailure { log.warn("Meaning retrieval unavailable: {}", it.message) }.getOrDefault(fallback)
    }

    companion object {
        fun meaningDocument(meaning: String, exampleEn: String, exampleKo: String, questions: List<String>) =
            (listOf("뜻: $meaning", "문맥: $exampleKo", "Example: $exampleEn") + questions.map { "질문: $it" }).joinToString("\n")
    }
}
