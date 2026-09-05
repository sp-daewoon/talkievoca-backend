package com.talkievoca.backend.knowledge

import com.talkievoca.backend.api.InvalidUploadException
import com.talkievoca.backend.search.KnowledgeChunk
import com.talkievoca.backend.search.KnowledgeSearchHit
import com.talkievoca.backend.search.OpenSearchVocabularyIndexer
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class PdfIngestResponse(
    val sourceId: UUID,
    val title: String,
    val chunks: Int
)

@Service
class KnowledgeIngestionService(
    private val jdbc: JdbcTemplate,
    private val searchIndex: OpenSearchVocabularyIndexer,
    @param:Value("\${talkievoca.storage-dir}") private val storageDir: String
) {
    @Transactional
    fun ingestPdf(file: MultipartFile, requestedTitle: String?): PdfIngestResponse {
        validatePdf(file)
        val bytes = file.bytes
        val text = runCatching {
            Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
        }.getOrElse { throw InvalidUploadException("PDF text could not be extracted.") }
            .replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) throw InvalidUploadException("PDF contains no extractable text.")

        val sourceId = UUID.randomUUID()
        val title = requestedTitle?.trim()?.takeIf { it.isNotBlank() } ?: safeFilename(file.originalFilename, "document.pdf")
        val relativePath = "knowledge/$sourceId.pdf"
        write(relativePath, bytes)
        jdbc.update(
            "insert into knowledge_sources (id, title, original_filename, content_type, storage_path, extracted_text) values (?, ?, ?, ?, ?, ?)",
            sourceId,
            title,
            safeFilename(file.originalFilename, "document.pdf"),
            file.contentType ?: "application/pdf",
            relativePath,
            text
        )

        val chunks = chunks(text).mapIndexed { ordinal, content ->
            KnowledgeChunk(UUID.randomUUID(), sourceId, ordinal, content, title)
        }
        chunks.forEach { chunk ->
            jdbc.update(
                "insert into knowledge_chunks (id, source_id, ordinal, content) values (?, ?, ?, ?)",
                chunk.id, chunk.sourceId, chunk.ordinal, chunk.content
            )
            searchIndex.indexKnowledgeChunk(chunk)
        }
        return PdfIngestResponse(sourceId, title, chunks.size)
    }

    fun search(query: String): List<KnowledgeSearchHit> =
        query.trim().takeIf { it.isNotBlank() }?.let(searchIndex::searchKnowledge)
            ?: throw InvalidUploadException("q must not be blank.")

    private fun validatePdf(file: MultipartFile) {
        if (file.isEmpty) throw InvalidUploadException("PDF file must not be empty.")
        val filename = file.originalFilename?.lowercase().orEmpty()
        if (file.contentType != "application/pdf" && !filename.endsWith(".pdf")) {
            throw InvalidUploadException("file must be a PDF.")
        }
    }

    private fun chunks(text: String): List<String> = text.chunked(1_000)

    private fun write(relativePath: String, bytes: ByteArray) {
        val target = Path.of(storageDir).resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    private fun safeFilename(filename: String?, fallback: String): String =
        filename?.substringAfterLast('/').orEmpty().substringAfterLast('\\').ifBlank { fallback }
}
