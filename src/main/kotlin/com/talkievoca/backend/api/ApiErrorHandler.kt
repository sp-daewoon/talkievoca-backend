package com.talkievoca.backend.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.support.MissingServletRequestPartException

data class ErrorBody(val error: ErrorDetail)
data class ErrorDetail(val code: String, val message: String)

class InvalidWordException(message: String) : RuntimeException(message)
class WordNotFoundException(word: String) : RuntimeException("No feedback found for '$word'.")
class InvalidUploadException(message: String) : RuntimeException(message)
class StoredFileNotFoundException(message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(InvalidWordException::class)
    fun invalidWord(exception: InvalidWordException) = error(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_word", exception.message!!)

    @ExceptionHandler(WordNotFoundException::class)
    fun wordNotFound(exception: WordNotFoundException) = error(HttpStatus.NOT_FOUND, "word_not_found", exception.message!!)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingWord() = error(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_word", "word must not be blank.")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidBody() = error(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_word", "text is required.")

    @ExceptionHandler(InvalidUploadException::class, MissingServletRequestPartException::class)
    fun invalidUpload(exception: Exception) = error(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_upload", exception.message ?: "file is required.")

    @ExceptionHandler(StoredFileNotFoundException::class)
    fun storedFileNotFound(exception: StoredFileNotFoundException) = error(HttpStatus.NOT_FOUND, "media_not_found", exception.message!!)

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ErrorBody(ErrorDetail(code, message)))
}
