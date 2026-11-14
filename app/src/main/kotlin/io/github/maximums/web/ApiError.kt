package io.github.maximums.web

import io.github.maximums.retry.Retryable
import kotlin.time.Duration

/** A failed call to a web API. Searches can only fail this way; page fetches can also fail per page. */
sealed interface ApiError : FetchError {

    data class Unauthorized(override val message: String) : ApiError

    /** The account's credit, plan or spending limit is used up. Later calls will fail the same way. */
    data class QuotaExceeded(override val message: String) : ApiError

    /** Too many requests. [retryAfter] is how long the provider asked us to wait, when it said. */
    data class RateLimited(override val message: String, override val retryAfter: Duration?) : ApiError, Retryable

    data class Unavailable(override val message: String, val cause: Throwable? = null) : ApiError, Retryable

    data class InvalidRequest(override val message: String) : ApiError

    data class Unexpected(override val message: String, val cause: Throwable? = null) : ApiError
}
