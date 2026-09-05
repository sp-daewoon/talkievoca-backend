package com.talkievoca.backend.vocabulary

import com.talkievoca.backend.api.InvalidWordException
import com.talkievoca.backend.api.WordNotFoundException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

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

data class VocabularyReviewItem(
    val word: String,
    val meaningKo: String,
    val exampleEn: String,
    val exampleKo: String,
    val aliases: List<String>,
    val origin: String,
    val verificationStatus: String,
    val createdAt: OffsetDateTime
)

data class VocabularyCorrection(
    val originalWord: String,
    val word: String,
    val meaningKo: String,
    val exampleEn: String,
    val exampleKo: String,
    val aliases: List<String> = emptyList()
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

        return exactFeedback(word)
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

    fun reviewItems(status: String): List<VocabularyReviewItem> {
        val normalizedStatus = status.lowercase()
        if (normalizedStatus !in setOf("pending", "trusted", "all")) {
            throw InvalidWordException("status must be pending, trusted, or all.")
        }
        val where = if (normalizedStatus == "all") "" else "where verification_status = ?"
        val mapper = { resultSet: java.sql.ResultSet, _: Int ->
            reviewItem(
                word = resultSet.getString("word"),
                meaningKo = resultSet.getString("meaning_ko"),
                exampleEn = resultSet.getString("example_en"),
                exampleKo = resultSet.getString("example_ko"),
                origin = resultSet.getString("origin"),
                verificationStatus = resultSet.getString("verification_status"),
                createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java)
            )
        }
        val sql = """
            select word, meaning_ko, example_en, example_ko, origin, verification_status, created_at
            from vocabulary_entries
            $where
            order by created_at desc, word
        """.trimIndent()
        return if (normalizedStatus == "all") jdbc.query(sql, mapper) else jdbc.query(sql, mapper, normalizedStatus)
    }

    @Transactional
    fun correct(correction: VocabularyCorrection): VocabularyReviewItem {
        val originalWord = normalize(correction.originalWord)
        val word = normalize(correction.word)
        validateEnglishWord(originalWord, "original_word")
        validateEnglishWord(word, "word")
        validateText(correction.meaningKo, "meaning_ko", 200)
        validateText(correction.exampleEn, "example_en", 300)
        validateText(correction.exampleKo, "example_ko", 300)

        val original = exactFeedback(originalWord) ?: throw WordNotFoundException(originalWord)
        if (originalWord != word && original.audioUrl != null) {
            throw InvalidWordException("A word with pronunciation audio cannot be renamed.")
        }
        val aliases = (correction.aliases + word)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
        aliases.forEach { alias ->
            if (alias.length > 100) throw InvalidWordException("aliases must be 100 characters or fewer.")
            val conflictingWord = jdbc.queryForList(
                "select word from vocabulary_entries where word = ? and word <> ?",
                String::class.java,
                alias,
                word
            ).firstOrNull()
            if (conflictingWord != null) {
                throw InvalidWordException("Alias '$alias' is already a vocabulary word.")
            }
        }

        if (originalWord != word) {
            jdbc.update("delete from vocabulary_entries where word = ?", originalWord)
        }
        val updated = jdbc.update(
            """
            update vocabulary_entries
            set meaning_ko = ?, example_en = ?, example_ko = ?, origin = 'manual', verification_status = 'trusted'
            where word = ?
            """.trimIndent(),
            correction.meaningKo.trim(),
            correction.exampleEn.trim(),
            correction.exampleKo.trim(),
            word
        )
        if (updated == 0) {
            jdbc.update(
                """
                insert into vocabulary_entries
                    (word, meaning_ko, example_en, example_ko, audio_path, origin, verification_status)
                values (?, ?, ?, ?, null, 'manual', 'trusted')
                """.trimIndent(),
                word,
                correction.meaningKo.trim(),
                correction.exampleEn.trim(),
                correction.exampleKo.trim()
            )
        }

        jdbc.update("delete from vocabulary_aliases where word = ?", word)
        aliases.forEach { alias ->
            jdbc.update("delete from vocabulary_aliases where alias = ?", alias)
            jdbc.update("insert into vocabulary_aliases (alias, word) values (?, ?)", alias, word)
        }
        return requireNotNull(reviewItemFor(word))
    }

    @Transactional
    fun rejectPending(rawWord: String) {
        val word = normalize(rawWord)
        validateEnglishWord(word, "word")
        if (exactFeedback(word)?.audioUrl != null) {
            throw InvalidWordException("A word with pronunciation audio cannot be removed.")
        }
        if (jdbc.update("delete from vocabulary_entries where word = ? and verification_status = 'pending'", word) == 0) {
            throw WordNotFoundException(word)
        }
    }

    fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .trim { !it.isLetter() && it != '\'' && it != '-' }
        .replace(WHITESPACE, " ")

    private fun exactFeedback(word: String): WordFeedback? = jdbc.query(
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

    private fun reviewItemFor(word: String): VocabularyReviewItem? = jdbc.query(
        """
        select word, meaning_ko, example_en, example_ko, origin, verification_status, created_at
        from vocabulary_entries
        where word = ?
        """.trimIndent(),
        { resultSet, _ ->
            reviewItem(
                word = resultSet.getString("word"),
                meaningKo = resultSet.getString("meaning_ko"),
                exampleEn = resultSet.getString("example_en"),
                exampleKo = resultSet.getString("example_ko"),
                origin = resultSet.getString("origin"),
                verificationStatus = resultSet.getString("verification_status"),
                createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java)
            )
        },
        word
    ).firstOrNull()

    private fun reviewItem(
        word: String,
        meaningKo: String,
        exampleEn: String,
        exampleKo: String,
        origin: String,
        verificationStatus: String,
        createdAt: OffsetDateTime
    ) = VocabularyReviewItem(
        word = word,
        meaningKo = meaningKo,
        exampleEn = exampleEn,
        exampleKo = exampleKo,
        aliases = jdbc.queryForList(
            "select alias from vocabulary_aliases where word = ? order by alias",
            String::class.java,
            word
        ),
        origin = origin,
        verificationStatus = verificationStatus,
        createdAt = createdAt
    )

    private fun validateEnglishWord(value: String, field: String) {
        if (!value.matches(ENGLISH_TERM)) {
            throw InvalidWordException("$field must be an English word or phrase.")
        }
    }

    private fun validateText(value: String, field: String, maxLength: Int) {
        if (value.isBlank()) throw InvalidWordException("$field must not be blank.")
        if (value.length > maxLength) {
            throw InvalidWordException("$field must be $maxLength characters or fewer.")
        }
    }

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
