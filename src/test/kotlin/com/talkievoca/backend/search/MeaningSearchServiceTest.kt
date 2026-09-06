package com.talkievoca.backend.search

import com.sun.net.httpserver.HttpServer
import com.talkievoca.backend.vocabulary.GeneratedVocabulary
import com.talkievoca.backend.vocabulary.VocabularyService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import kotlin.test.*

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:meaning;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "talkievoca.opensearch.enabled=false"
])
class MeaningSearchServiceTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var vocabulary: VocabularyService
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `embeds contextual questions excludes pending and rejects stale retrieval`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var input = ""
        var stored = ""
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.bufferedReader().readText()
            val result = when {
                path == "/api/embed" -> {
                    input = mapper.readTree(body).path("input").asString()
                    mapper.writeValueAsString(mapOf("embeddings" to listOf(List(1024) { 0.1 })))
                }
                exchange.requestMethod == "PUT" -> { stored = body; "{}" }
                path.endsWith("/_search") -> """{"hits":{"hits":[{"_id":"vocabulary-apple","_score":1.8,"_source":$stored}]}}"""
                else -> "{}"
            }
            if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1)
            else {
                val bytes = result.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        server.start()
        try {
            val url = "http://localhost:${server.address.port}"
            val service = MeaningSearchService(jdbc, mapper, true, url, url)
            jdbc.update("insert into vocabulary_questions values ('apple', '사과를 영어로 뭐라고 해?')")
            assertTrue(service.indexVocabulary("apple"))
            assertContains(input, "뜻: 사과")
            assertContains(input, "질문: 사과를 영어로 뭐라고 해?")
            assertNotEquals("apple", input)
            assertEquals(1024, mapper.readTree(stored).path("embedding").size())
            assertEquals("apple", service.search("사과의 영어 표현").single().word)
            assertEquals("사과의 영어 표현", input)
            jdbc.update("update vocabulary_entries set meaning_ko = '수정된 뜻' where word = 'apple'")
            assertTrue(service.search("사과의 영어 표현").isEmpty())
            vocabulary.saveGenerated("검수전", GeneratedVocabulary("pending", "대기", "It is pending.", "대기 중이다."))
            assertFalse(service.indexVocabulary("pending"))
        } finally { server.stop(0) }
    }
}
