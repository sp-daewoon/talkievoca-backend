package com.talkievoca.backend.api

import com.talkievoca.backend.vocabulary.VocabularyService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(val status: String = "ok")
data class AssistantQueryRequest(val text: String)
data class AssistantQueryResponse(
    val query: String,
    val word: String,
    val meaningKo: String,
    val exampleEn: String,
    val exampleKo: String,
    val audioUrl: String?,
    val matchedBy: String
)

@RestController
class WatchFeedbackController(private val vocabularyService: VocabularyService) {
    @GetMapping("/health")
    fun health() = HealthResponse()

    @GetMapping("/v1/watch/word-feedback")
    fun wordFeedback(@RequestParam word: String, request: HttpServletRequest) =
        word.takeIf { it.isNotBlank() }
            ?.let(vocabularyService::feedbackFor)
            ?.let { feedback -> feedback.copy(audioUrl = absoluteUrl(request, feedback.audioUrl)) }
            ?: throw InvalidWordException("word must not be blank.")

    @PostMapping("/v1/assistant/query")
    fun assistantQuery(
        @RequestBody body: AssistantQueryRequest,
        request: HttpServletRequest
    ): AssistantQueryResponse {
        val query = body.text.trim().takeIf { it.isNotBlank() }
            ?: throw InvalidWordException("text must not be blank.")
        if (query.length > 100) throw InvalidWordException("text must be 100 characters or fewer.")
        val feedback = vocabularyService.feedbackFor(query)
        return AssistantQueryResponse(
            query = query,
            word = feedback.word,
            meaningKo = feedback.meaningKo,
            exampleEn = feedback.exampleEn,
            exampleKo = feedback.exampleKo,
            audioUrl = absoluteUrl(request, feedback.audioUrl),
            matchedBy = if (query.lowercase() == feedback.word) "exact" else "alias"
        )
    }

    private fun absoluteUrl(request: HttpServletRequest, path: String?): String? {
        if (path == null || path.startsWith("http://") || path.startsWith("https://")) return path
        val port = request.serverPort.takeUnless {
            (request.scheme == "http" && it == 80) || (request.scheme == "https" && it == 443)
        }?.let { ":$it" } ?: ""
        return "${request.scheme}://${request.serverName}$port${if (path.startsWith('/')) path else "/$path"}"
    }
}
