package com.talkievoca.backend.vocabulary

import com.talkievoca.backend.api.WordNotFoundException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:talkievoca;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "talkievoca.opensearch.enabled=false"
    ]
)
class VocabularyServiceTest {
    @Autowired
    private lateinit var service: VocabularyService

    @Test
    fun `resolves Korean aliases from the database`() {
        assertEquals("apple", service.feedbackFor("애플").word)
        assertEquals("green", service.feedbackFor(" 녹색 ").word)
    }

    @Test
    fun `rejects unknown words`() {
        assertFailsWith<WordNotFoundException> { service.feedbackFor("unknown") }
    }

    @Test
    fun `stores SLM vocabulary as pending data`() {
        val feedback = service.saveGenerated(
            "강아지",
            GeneratedVocabulary("dog", "개, 강아지", "The dog is friendly.", "그 개는 친근하다.")
        )

        assertEquals("dog", service.feedbackFor("강아지").word)
        assertEquals("slm", feedback.origin)
        assertFalse(feedback.verified)
    }

    @Test
    fun `does not attach an untrusted generated alias to a trusted word`() {
        service.saveGenerated(
            "잘못 들은 사과",
            GeneratedVocabulary("apple", "사과", "I ate an apple.", "나는 사과를 먹었다.")
        )

        assertFailsWith<WordNotFoundException> { service.feedbackFor("잘못 들은 사과") }
    }
}
