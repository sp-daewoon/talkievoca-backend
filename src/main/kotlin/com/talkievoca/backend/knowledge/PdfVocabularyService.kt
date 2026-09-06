package com.talkievoca.backend.knowledge

import com.talkievoca.backend.api.InvalidUploadException
import com.talkievoca.backend.api.StoredFileNotFoundException
import com.talkievoca.backend.slm.OllamaVocabularyClient
import com.talkievoca.backend.vocabulary.VocabularyService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

data class PdfVocabularyResult(val words: List<String>, val examinedCharacters: Int, val truncated: Boolean)

@Service
class PdfVocabularyService(
    private val jdbc: JdbcTemplate,
    private val slm: OllamaVocabularyClient,
    private val vocabulary: VocabularyService
) {
    fun extract(sourceId: UUID): PdfVocabularyResult {
        val text = jdbc.queryForList("select extracted_text from knowledge_sources where id = ?", String::class.java, sourceId)
            .firstOrNull() ?: throw StoredFileNotFoundException("PDF source not found.")
        val excerpt = text.take(12000)
        val cards = slm.extractVocabulary(excerpt)
            ?: throw InvalidUploadException("단어 추출을 완료하지 못했습니다. PDF는 저장되어 있습니다. SLM 상태를 확인하고 다시 시도해 주세요.")
        val words = cards.map { card ->
            // Model-proposed meanings must not create exact Korean aliases before review.
            val saved = vocabulary.saveGenerated(card.word, card)
            jdbc.update("insert into vocabulary_sources (word, source_id) values (?, ?) on conflict do nothing", saved.word, sourceId)
            saved.word
        }.distinct()
        return PdfVocabularyResult(words, excerpt.length, text.length > excerpt.length)
    }
}
