@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.maximums.retry

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private sealed interface TestError

private class Temporary(override val retryAfter: Duration? = null) : TestError, Retryable

private class Permanent : TestError

/** Returns [errors] in order on successive calls, then "ok". Records when each call happened. */
private class Flaky(private vararg val errors: TestError) {
    val callTimes = mutableListOf<Duration>()

    fun call(now: Duration): Either<TestError, String> {
        callTimes += now
        return errors.getOrNull(callTimes.size - 1)?.left() ?: "ok".right()
    }
}

/** Runs on a virtual clock, so retry waits take no real time and `currentTime` shows when each call happened. */
val RetryTest by testSuite(testConfig = TestConfig.testScope(isEnabled = true)) {

    test("returns a success from the first call") {
        val flaky = Flaky()

        assertEquals("ok".right(), withRetry { flaky.call(testScope.currentTime.milliseconds) })
        assertEquals(listOf(Duration.ZERO), flaky.callTimes)
    }

    test("retries retryable errors with exponential backoff until the call succeeds") {
        val flaky = Flaky(Temporary(), Temporary())
        val policy = RetryPolicy(maxAttempts = 3, initialDelay = 1.seconds, factor = 2.0)

        assertEquals("ok".right(), withRetry(policy) { flaky.call(testScope.currentTime.milliseconds) })
        assertEquals(listOf(0.seconds, 1.seconds, 3.seconds), flaky.callTimes)
    }

    test("waits as long as the error asks instead of the backoff") {
        val flaky = Flaky(Temporary(retryAfter = 5.seconds))

        withRetry { flaky.call(testScope.currentTime.milliseconds) }

        assertEquals(listOf(0.seconds, 5.seconds), flaky.callTimes)
    }

    test("returns the last error once the attempts run out") {
        val last = Temporary()
        val flaky = Flaky(Temporary(), Temporary(), last)

        val outcome = withRetry(RetryPolicy(maxAttempts = 3)) { flaky.call(testScope.currentTime.milliseconds) }

        assertSame(last, outcome.leftOrNull())
        assertEquals(3, flaky.callTimes.size)
    }

    test("returns an error that isn't retryable at once") {
        val permanent = Permanent()
        val flaky = Flaky(permanent)

        assertSame(permanent, withRetry { flaky.call(testScope.currentTime.milliseconds) }.leftOrNull())
        assertEquals(1, flaky.callTimes.size)
    }

    test("returns the error instead of waiting longer than the maximum delay") {
        val tooLong = Temporary(retryAfter = 60.seconds)
        val flaky = Flaky(tooLong)

        val outcome = withRetry(RetryPolicy(maxDelay = 30.seconds)) { flaky.call(testScope.currentTime.milliseconds) }

        assertSame(tooLong, outcome.leftOrNull())
        assertEquals(listOf(Duration.ZERO), flaky.callTimes)
    }

    test("lets exceptions through without retrying, since they mean bugs") {
        var calls = 0

        assertFailsWith<IllegalStateException> {
            withRetry<TestError, String> {
                calls++
                error("bug")
            }
        }
        assertEquals(1, calls)
    }

    test("caps the backoff at the maximum delay") {
        val policy = RetryPolicy(initialDelay = 10.seconds, factor = 2.0, maxDelay = 15.seconds)

        assertEquals(10.seconds, policy.backoff(1))
        assertEquals(15.seconds, policy.backoff(2))
        assertEquals(15.seconds, policy.backoff(5))
    }

    test("rejects policies that can't work") {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(factor = 0.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelay = 10.seconds, maxDelay = 5.seconds) }
    }
}
