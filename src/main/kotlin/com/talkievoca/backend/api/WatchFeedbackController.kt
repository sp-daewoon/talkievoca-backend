package com.talkievoca.backend.api

import com.talkievoca.backend.vocabulary.VocabularyService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(val status: String = "ok")

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

    private fun absoluteUrl(request: HttpServletRequest, path: String?): String? {
        if (path == null || path.startsWith("http://") || path.startsWith("https://")) return path
        val port = request.serverPort.takeUnless {
            (request.scheme == "http" && it == 80) || (request.scheme == "https" && it == 443)
        }?.let { ":$it" } ?: ""
        return "${request.scheme}://${request.serverName}$port${if (path.startsWith('/')) path else "/$path"}"
    }
}
