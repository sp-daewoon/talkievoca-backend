package com.talkievoca.backend.knowledge

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:pdf;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "talkievoca.opensearch.enabled=false", "talkievoca.storage-dir=build/test-media"
])
class KnowledgeIngestionTest {
    @Autowired lateinit var service: KnowledgeIngestionService

    @Test
    fun `real PDF is persisted and listed with searchable chunks`() {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(); document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                stream.newLineAtOffset(50f, 730f)
                listOf("TalkieVoca vocabulary lesson", "squid: a sea animal with eight arms and two longer tentacles.",
                    "green: the color of fresh leaves.", "lantern: a portable lamp with a protective case.").forEach { line ->
                    stream.showText(line); stream.newLineAtOffset(0f, -30f)
                }
                stream.endText()
            }
            document.save(output)
        }
        val bytes = output.toByteArray()
        Files.createDirectories(Path.of("build/fixtures"))
        Files.write(Path.of("build/fixtures/vocabulary-lesson.pdf"), bytes)
        val result = service.ingestPdf(MockMultipartFile("file", "lesson.pdf", "application/pdf", bytes), "Vocabulary lesson")
        assertEquals(1, result.chunks)
        assertTrue(service.sources().any { it.sourceId == result.sourceId && it.chunks == 1 })
        assertTrue(Files.exists(Path.of("build/test-media/knowledge/${result.sourceId}.pdf")))
    }

    @Test
    fun `chunks preserve overlap and the final text`() {
        val text = (1..500).joinToString("\n") { "Sentence $it describes a word." }
        val chunks = KnowledgeIngestionService.chunkText(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 1600 })
        assertTrue(chunks.last().endsWith("Sentence 500 describes a word."))
        assertTrue(chunks[1].contains(chunks[0].takeLast(100)))
    }
}
