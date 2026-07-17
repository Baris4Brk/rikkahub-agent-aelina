package me.rerere.search

import kotlinx.serialization.Serializable

@Serializable
enum class SearchFailureCode {
    InvalidRequest,
    NetworkError,
    HttpError,
    InvalidResponse,
    ResponseTooLarge,
}

@Serializable
data class SearchServiceFailure(
    val code: SearchFailureCode,
    val retryable: Boolean,
    val httpStatus: Int? = null,
)

class SearchServiceFailureException(
    val failure: SearchServiceFailure,
    cause: Throwable? = null,
) : Exception("search_service_failure:${failure.code.name}", cause)
