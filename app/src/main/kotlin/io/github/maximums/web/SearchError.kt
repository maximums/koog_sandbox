package io.github.maximums.web

import io.github.maximums.retry.Retryable
import kotlin.time.Duration


sealed interface SearchError {
    val message: String

    data class Unauthorized(override val message: String) : SearchError

    /** The account's credit, plan or spending limit is used up. Later searches will fail the same way. */
    data class QuotaExceeded(override val message: String) : SearchError

    /** Too many requests. [retryAfter] is how long the provider asked us to wait, when it said. */
    data class RateLimited(override val message: String, override val retryAfter: Duration?) : SearchError, Retryable

    data class Unavailable(override val message: String, val cause: Throwable? = null) : SearchError, Retryable

    data class InvalidRequest(override val message: String) : SearchError

    data class Unexpected(override val message: String, val cause: Throwable? = null) : SearchError
}
