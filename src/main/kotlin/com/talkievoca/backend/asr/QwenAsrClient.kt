package com.talkievoca.backend.asr

import com.talkievoca.backend.api.AsrUnavailableException
import com.talkievoca.backend.api.InvalidAudioException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import tools.jackson.databind.ObjectMapper

data class AsrTranscript(val text: String, val language: String?)

@Component
class QwenAsrClient(
    @param:Value("\${talkievoca.asr.enabled:false}") val enabled: Boolean,
    @param:Value("\${talkievoca.asr.url:http://localhost:8000}") private val baseUrl: String,
    @param:Value("\${talkievoca.asr.model:Qwen/Qwen3-ASR-0.6B}") private val model: String,
    @param:Value("\${talkievoca.asr.api-key:}") private val apiKey: String,
    @Value("\${talkievoca.asr.timeout-seconds:20}") timeoutSeconds: Long,
    @param:Value("\${talkievoca.asr.max-bytes:2097152}") private val maxBytes: Int,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun status(): String {
        if (!enabled) return "disabled"
        return if (listOf("/health", "/v1/models").any(::isHealthy)) "ready" else "unavailable"
    }

    fun transcribe(fileName: String?, contentType: String?, audio: ByteArray): AsrTranscript {
        if (!enabled) throw AsrUnavailableException("High-accuracy voice recognition is not configured.")
        if (audio.isEmpty()) throw InvalidAudioException("The recording is empty.")
        if (audio.size > maxBytes) throw InvalidAudioException("The recording is too large.")
        if (contentType != null && !contentType.startsWith("audio/") && contentType != "application/octet-stream") {
            throw InvalidAudioException("Only audio recordings are accepted.")
        }

        val boundary = "TalkieVoca-${UUID.randomUUID()}"
        val body = multipart(boundary, safeFileName(fileName), contentType ?: "audio/mp4", audio)
        val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/v1/audio/transcriptions"))
            .timeout(timeout)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (exception: Exception) {
            log.warn("Qwen ASR request failed: {}", exception.message)
            throw AsrUnavailableException("Voice recognition did not respond.", exception)
        }
        when (response.statusCode()) {
            400, 413, 415, 422 -> {
                log.info("Qwen ASR rejected audio with HTTP {}", response.statusCode())
                throw InvalidAudioException("The recording could not be decoded. Please record it again.")
            }
        }
        if (response.statusCode() !in 200..299) {
            log.warn("Qwen ASR returned HTTP {}: {}", response.statusCode(), response.body().take(500))
            throw AsrUnavailableException("Voice recognition returned HTTP ${response.statusCode()}.")
        }
        val json = try {
            objectMapper.readTree(response.body())
        } catch (exception: Exception) {
            log.warn("Qwen ASR returned invalid JSON: {}", exception.message)
            throw AsrUnavailableException("Voice recognition returned an invalid response.", exception)
        }
        val text = json.path("text").asString().trim()
        if (text.isBlank()) throw InvalidAudioException("No word was recognized. Please speak once more.")
        return AsrTranscript(text, json.path("language").asString().takeIf { it.isNotBlank() })
    }

    private fun multipart(
        boundary: String,
        fileName: String,
        contentType: String,
        audio: ByteArray
    ): ByteArray = ByteArrayOutputStream().use { output ->
        fun write(value: String) = output.write(value.toByteArray(StandardCharsets.UTF_8))
        write("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n")
        write("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
        write("--$boundary\r\nContent-Disposition: form-data; name=\"enable_speaker_diarization\"\r\n\r\nfalse\r\n")
        write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
        write("Content-Type: $contentType\r\n\r\n")
        output.write(audio)
        write("\r\n--$boundary--\r\n")
        output.toByteArray()
    }

    private fun isHealthy(path: String): Boolean = try {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(1))
            .GET()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
    } catch (_: Exception) {
        false
    }

    private fun safeFileName(fileName: String?) = fileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.takeIf { it.isNotBlank() }
        ?: "recording.m4a"
}
