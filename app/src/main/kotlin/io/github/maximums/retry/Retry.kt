package io.github.maximums.retry

import arrow.core.Either
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Marks an error whose cause is temporary (a rate limit, an outage), so the same call may succeed later. */
interface Retryable {
    /** How long the error itself asks to wait before trying again, when it says. */
    val retryAfter: Duration? get() = null
}

/**
 * How often and how long to retry. [maxAttempts] counts the first call, so 3 means up to 2 retries.
 * Without a wait asked for by the error itself, waits start at [initialDelay] and grow by [factor] per retry,
 * never beyond [maxDelay].
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 1.seconds,
    val factor: Double = 2.0,
    val maxDelay: Duration = 30.seconds,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
        require(!initialDelay.isNegative()) { "initialDelay must not be negative, was $initialDelay" }
        require(factor >= 1.0) { "factor must be at least 1, was $factor" }
        require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must not be shorter than initialDelay ($initialDelay)" }
    }

    /** The wait before retry number [retry] (1-based) when the error doesn't say how long to wait. */
    fun backoff(retry: Int): Duration = (initialDelay * factor.pow(retry - 1)).coerceAtMost(maxDelay)
}

/**
 * Runs [block], and runs it again while it returns a [Retryable] error, waiting as long as the error asks or,
 * when it doesn't say, the policy's backoff.
 *
 * Returns the first success, or the last error once it isn't [Retryable], [RetryPolicy.maxAttempts] calls have
 * been made, or the error asks to wait longer than [RetryPolicy.maxDelay]. Exceptions are not caught: they
 * mean bugs, not expected failures, and pass straight through (cancellation included).
 */
suspend fun <E, T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    block: suspend () -> Either<E, T>,
): Either<E, T> {
    var attempt = 1

    while (true) {
        when (val outcome = block()) {
            is Either.Right -> return outcome
            is Either.Left -> {
                val error = outcome.value as? Retryable ?: return outcome
                val wait = error.retryAfter ?: policy.backoff(attempt)

                if (attempt >= policy.maxAttempts || wait > policy.maxDelay) return outcome

                delay(wait)
                attempt++
            }
        }
    }
}
