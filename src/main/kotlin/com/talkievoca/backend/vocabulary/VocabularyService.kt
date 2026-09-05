package com.talkievoca.backend.vocabulary

import com.talkievoca.backend.api.WordNotFoundException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

data class WordFeedback(
    val word: String,
    val meaningKo: String,
    val exampleEn: String,
    val exampleKo: String,
    val audioUrl: String? = null
)

@Service
class VocabularyService(private val jdbc: JdbcTemplate) {
    fun feedbackFor(rawWord: String): WordFeedback {
        val normalized = rawWord.trim().lowercase()
        val word = jdbc.queryForList(
            "select word from vocabulary_aliases where alias = ?",
            String::class.java,
            normalized
        ).firstOrNull() ?: normalized

        return jdbc.query(
            """
            select word, meaning_ko, example_en, example_ko, audio_path
            from vocabulary_entries
            where word = ?
            """.trimIndent(),
            { resultSet, _ ->
                WordFeedback(
                    word = resultSet.getString("word"),
                    meaningKo = resultSet.getString("meaning_ko"),
                    exampleEn = resultSet.getString("example_en"),
                    exampleKo = resultSet.getString("example_ko"),
                    audioUrl = resultSet.getString("audio_path")
                )
            },
            word
        ).firstOrNull() ?: throw WordNotFoundException(rawWord.trim())
    }
}
