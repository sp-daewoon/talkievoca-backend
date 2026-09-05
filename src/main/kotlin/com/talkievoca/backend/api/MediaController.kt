package com.talkievoca.backend.api

import com.talkievoca.backend.media.AudioStorageService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

data class AudioUploadResponse(val mediaId: UUID, val word: String, val audioUrl: String)

@RestController
class MediaController(private val audioStorageService: AudioStorageService) {
    @PostMapping("/v1/media/audio", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAudio(
        @RequestParam word: String,
        @RequestParam file: MultipartFile,
        request: HttpServletRequest
    ): AudioUploadResponse {
        val stored = audioStorageService.store(word, file)
        return AudioUploadResponse(stored.id, stored.word, absoluteUrl(request, "/v1/media/${stored.id}"))
    }

    @GetMapping("/v1/media/{mediaId}")
    fun media(@PathVariable mediaId: UUID): ResponseEntity<Resource> {
        val stored = audioStorageService.load(mediaId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(stored.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(stored.filename).build().toString())
            .body(stored.resource)
    }
}

private fun absoluteUrl(request: HttpServletRequest, path: String): String {
    val port = request.serverPort.takeUnless {
        (request.scheme == "http" && it == 80) || (request.scheme == "https" && it == 443)
    }?.let { ":$it" } ?: ""
    return "${request.scheme}://${request.serverName}$port$path"
}
