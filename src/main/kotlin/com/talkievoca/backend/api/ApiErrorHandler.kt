package com.talkievoca.backend.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorBody(val error: ErrorDetail)
data class ErrorDetail(val code: String, val message: String)

class InvalidWordException(message: String) : RuntimeException(message)
class WordNotFoundException(word: String) : RuntimeException("No feedback found for '$word'.")

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(InvalidWordException::class)
    fun invalidWord(exception: InvalidWordException) = error(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_word", exception.message!!)

    @ExceptionHandler(WordNotFoundException::class)
    fun wordNotFound(exception: WordNotFoundException) = error(HttpStatus.NOT_FOUND, "word_not_found", exception.message!!)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingWord() = error(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_word", "word must not be blank.")

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ErrorBody(ErrorDetail(code, message)))
}
