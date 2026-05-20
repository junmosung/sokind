package me.victor.demo.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class NotFoundException(message: String) : RuntimeException(message)
class ConflictException(message: String) : RuntimeException(message)
class BadRequestException(message: String) : RuntimeException(message)

/** 4xx 공통 에러 본문. error는 분류 코드 (bad_request | not_found | conflict). */
data class ApiError(val error: String, val message: String?)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = ResponseEntity
        .status(HttpStatus.NOT_FOUND).body(ApiError("not_found", e.message))

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException) = ResponseEntity
        .status(HttpStatus.CONFLICT).body(ApiError("conflict", e.message))

    @ExceptionHandler(BadRequestException::class)
    fun badRequest(e: BadRequestException) = ResponseEntity
        .status(HttpStatus.BAD_REQUEST).body(ApiError("bad_request", e.message))
}
