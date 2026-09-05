package com.talkievoca.backend.api

import com.talkievoca.backend.asr.QwenAsrClient
import com.talkievoca.backend.vocabulary.AssistantResolution
import com.talkievoca.backend.vocabulary.AssistantService
import com.talkievoca.backend.vocabulary.VocabularyService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

data class HealthResponse(val status: String = "ok", val asr: String, val slm: String)
data class AssistantQueryRequest(val text: String)
data class AssistantQueryResponse(
    val query: String,
    val word: String,
    val meaningKo: String,
    val exampleEn: String,
    val exampleKo: String,
    val audioUrl: String?,
    val matchedBy: String,
    val source: String,
    val verified: Boolean,
    val transcriptLanguage: String? = null
)

@RestController
class WatchFeedbackController(
    private val vocabularyService: VocabularyService,
    private val assistantService: AssistantService,
    private val asr: QwenAsrClient
) {
    @GetMapping("/health")
    fun health() = HealthResponse(
        asr = asr.status(),
        slm = assistantService.slmStatus()
    )

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
        val query = body.text.trim()
        return response(query, assistantService.resolve(query), request)
    }

    @PostMapping("/v1/assistant/audio", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun assistantAudio(
        @RequestParam file: MultipartFile,
        request: HttpServletRequest
    ): AssistantQueryResponse {
        val transcript = asr.transcribe(file.originalFilename, file.contentType, file.bytes)
        val resolution = assistantService.resolve(transcript.text)
        return response(
            query = transcript.text,
            resolution = resolution.copy(matchedBy = "qwen_${resolution.matchedBy}"),
            request = request,
            transcriptLanguage = transcript.language
        )
    }

    private fun response(
        query: String,
        resolution: AssistantResolution,
        request: HttpServletRequest,
        transcriptLanguage: String? = null
    ): AssistantQueryResponse {
        val feedback = resolution.feedback
        return AssistantQueryResponse(
            query = query,
            word = feedback.word,
            meaningKo = feedback.meaningKo,
            exampleEn = feedback.exampleEn,
            exampleKo = feedback.exampleKo,
            audioUrl = absoluteUrl(request, feedback.audioUrl),
            matchedBy = resolution.matchedBy,
            source = feedback.origin,
            verified = feedback.verified,
            transcriptLanguage = transcriptLanguage
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
