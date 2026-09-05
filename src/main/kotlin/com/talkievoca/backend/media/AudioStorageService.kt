package com.talkievoca.backend.media

import com.talkievoca.backend.api.InvalidUploadException
import com.talkievoca.backend.api.StoredFileNotFoundException
import com.talkievoca.backend.api.WordNotFoundException
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class StoredAudio(val id: UUID, val word: String, val contentType: String, val filename: String, val resource: FileSystemResource)

@Service
class AudioStorageService(
    private val jdbc: JdbcTemplate,
    @param:Value("\${talkievoca.storage-dir}") private val storageDir: String
) {
    @Transactional
    fun store(wordInput: String, file: MultipartFile): StoredAudio {
        if (file.isEmpty) throw InvalidUploadException("audio file must not be empty.")
        if (!file.contentType.orEmpty().startsWith("audio/")) throw InvalidUploadException("file must be audio.")
        val word = resolveWord(wordInput)
        val id = UUID.randomUUID()
        val filename = safeFilename(file.originalFilename, "audio")
        val extension = filename.substringAfterLast('.', "bin").lowercase().takeIf { it in setOf("mp3", "m4a", "wav", "aac") } ?: "bin"
        val relativePath = "audio/$id.$extension"
        val target = Path.of(storageDir).resolve(relativePath)
        Files.createDirectories(target.parent)
        file.transferTo(target)
        jdbc.update(
            "insert into media_assets (id, word, original_filename, content_type, storage_path) values (?, ?, ?, ?, ?)",
            id, word, filename, file.contentType, relativePath
        )
        jdbc.update("update vocabulary_entries set audio_path = ? where word = ?", "/v1/media/$id", word)
        return StoredAudio(id, word, file.contentType!!, filename, FileSystemResource(target))
    }

    fun load(id: UUID): StoredAudio {
        val row = jdbc.query(
            "select word, original_filename, content_type, storage_path from media_assets where id = ?",
            { resultSet, _ ->
                StoredAudio(
                    id,
                    resultSet.getString("word"),
                    resultSet.getString("content_type"),
                    resultSet.getString("original_filename"),
                    FileSystemResource(Path.of(storageDir).resolve(resultSet.getString("storage_path")))
                )
            },
            id
        ).firstOrNull() ?: throw StoredFileNotFoundException("Media '$id' was not found.")
        if (!row.resource.exists()) throw StoredFileNotFoundException("Media '$id' is unavailable.")
        return row
    }

    private fun resolveWord(rawWord: String): String {
        val normalized = rawWord.trim().lowercase()
        if (normalized.isBlank()) throw InvalidUploadException("word must not be blank.")
        val word = jdbc.queryForList("select word from vocabulary_aliases where alias = ?", String::class.java, normalized).firstOrNull() ?: normalized
        if (jdbc.queryForList("select word from vocabulary_entries where word = ?", String::class.java, word).isEmpty()) {
            throw WordNotFoundException(rawWord.trim())
        }
        return word
    }

    private fun safeFilename(filename: String?, fallback: String): String =
        filename?.substringAfterLast('/').orEmpty().substringAfterLast('\\').ifBlank { fallback }
}
