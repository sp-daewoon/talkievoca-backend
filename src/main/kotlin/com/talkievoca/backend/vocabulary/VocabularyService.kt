package com.talkievoca.backend.vocabulary

import com.talkievoca.backend.api.WordNotFoundException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class WordFeedback(
    val word: String,
    val meaningKo: String,
    val exampleEn: String,
    val exampleKo: String,
    val audioUrl: String? = null,
    val origin: String = "seed",
    val verified: Boolean = true
)

data class GeneratedVocabulary(
    val word: String,
    val meaningKo: String,
    val exampleEn: String,
    val exampleKo: String
)

@Service
class VocabularyService(private val jdbc: JdbcTemplate) {
    fun feedbackFor(rawWord: String): WordFeedback =
        findFeedback(rawWord) ?: throw WordNotFoundException(rawWord.trim())

    fun findFeedback(rawWord: String): WordFeedback? {
        val normalized = normalize(rawWord)
        if (normalized.isBlank()) return null
        val word = jdbc.queryForList(
            "select word from vocabulary_aliases where alias = ?",
            String::class.java,
            normalized
        ).firstOrNull() ?: normalized

        return jdbc.query(
            """
            select word, meaning_ko, example_en, example_ko, audio_path, origin, verification_status
            from vocabulary_entries
            where word = ?
            """.trimIndent(),
            { resultSet, _ ->
                WordFeedback(
                    word = resultSet.getString("word"),
                    meaningKo = resultSet.getString("meaning_ko"),
                    exampleEn = resultSet.getString("example_en"),
                    exampleKo = resultSet.getString("example_ko"),
                    audioUrl = resultSet.getString("audio_path"),
                    origin = resultSet.getString("origin"),
                    verified = resultSet.getString("verification_status") == "trusted"
                )
            },
            word
        ).firstOrNull()
    }

    @Transactional
    fun saveGenerated(rawQuery: String, generated: GeneratedVocabulary): WordFeedback {
        val word = normalize(generated.word)
        require(word.matches(ENGLISH_TERM)) { "SLM returned a non-English word." }
        require(generated.meaningKo.isNotBlank() && generated.exampleEn.isNotBlank() && generated.exampleKo.isNotBlank()) {
            "SLM returned incomplete vocabulary data."
        }

        val inserted = try {
            jdbc.update(
                """
                insert into vocabulary_entries
                    (word, meaning_ko, example_en, example_ko, audio_path, origin, verification_status)
                values (?, ?, ?, ?, null, 'slm', 'pending')
                """.trimIndent(),
                word,
                generated.meaningKo.take(200),
                generated.exampleEn.take(300),
                generated.exampleKo.take(300)
            )
        } catch (_: DuplicateKeyException) {
            0
        }
        if (inserted == 1) {
            listOf(word, normalize(rawQuery)).filter { it.isNotBlank() }.distinct().forEach { alias ->
                ignoreDuplicate { jdbc.update("insert into vocabulary_aliases (alias, word) values (?, ?)", alias, word) }
            }
        }
        return requireNotNull(findFeedback(word))
    }

    fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .trim { !it.isLetter() && it != '\'' && it != '-' }
        .replace(WHITESPACE, " ")

    private fun ignoreDuplicate(block: () -> Unit) {
        try {
            block()
        } catch (_: DuplicateKeyException) {
            // A concurrent request already cached the same word or alias.
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val ENGLISH_TERM = Regex("^[a-z]+(?:[ '-][a-z]+)*$")
    }
}
