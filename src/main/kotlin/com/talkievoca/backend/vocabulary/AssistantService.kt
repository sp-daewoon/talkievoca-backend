package com.talkievoca.backend.vocabulary

import com.talkievoca.backend.api.InvalidWordException
import com.talkievoca.backend.api.WordNotFoundException
import com.talkievoca.backend.search.OpenSearchVocabularyIndexer
import com.talkievoca.backend.slm.OllamaVocabularyClient
import org.springframework.stereotype.Service

data class AssistantResolution(val feedback: WordFeedback, val matchedBy: String)

@Service
class AssistantService(
    private val vocabulary: VocabularyService,
    private val search: OpenSearchVocabularyIndexer,
    private val slm: OllamaVocabularyClient
) {
    fun slmStatus() = slm.status()

    fun resolve(rawQuery: String): AssistantResolution {
        val query = vocabulary.normalize(rawQuery)
        if (query.isBlank()) throw InvalidWordException("text must not be blank.")
        if (query.length > 100) throw InvalidWordException("text must be 100 characters or fewer.")

        vocabulary.findFeedback(query)?.let { feedback ->
            val match = when {
                feedback.origin == "slm" -> "cache"
                query == feedback.word -> "exact"
                else -> "alias"
            }
            return AssistantResolution(feedback, match)
        }

        val references = search.searchKnowledge(query)
            .take(3)
            .map { "${it.title}: ${it.content}" }
        val generated = slm.generate(query, references) ?: throw WordNotFoundException(rawQuery.trim())
        return AssistantResolution(vocabulary.saveGenerated(query, generated), "slm")
    }
}
