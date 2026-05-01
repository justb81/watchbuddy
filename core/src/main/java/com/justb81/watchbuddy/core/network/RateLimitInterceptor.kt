package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import okhttp3.Interceptor
import okhttp3.Response

private const val TAG = "RateLimit"
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val MAX_RETRY_DELAY_SECONDS = 60L
private const val DEFAULT_RETRY_DELAY_SECONDS = 5L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * OkHttp interceptor that handles 429 Too Many Requests responses.
 *
 * On a 429, it reads the `Retry-After` header (seconds, capped at 60 s) and
 * retries the request once. Non-idempotent methods (POST, PUT, PATCH, DELETE)
 * are not retried — the 429 is propagated so callers can decide how to proceed.
 * All 429 hits are surfaced in [DiagnosticLog].
 */
internal class RateLimitInterceptor(
    private val sleepFn: (Long) -> Unit = { ms -> Thread.sleep(ms) }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != HTTP_TOO_MANY_REQUESTS) return response

        val method = request.method
        val url = request.url.toString()
        val retryAfterSeconds = parseRetryAfter(response.header("Retry-After"))
        val delaySeconds = retryAfterSeconds.coerceAtMost(MAX_RETRY_DELAY_SECONDS)

        DiagnosticLog.warn(TAG, "429 on $method $url — retry-after: ${delaySeconds}s")

        // Do not retry non-idempotent methods; caller must handle the 429.
        if (!isSafeMethod(method)) return response

        response.close()
        sleepFn(delaySeconds * MILLIS_PER_SECOND)

        return chain.proceed(request)
    }

    private fun parseRetryAfter(header: String?): Long {
        if (header == null) return DEFAULT_RETRY_DELAY_SECONDS
        return header.trim().toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_RETRY_DELAY_SECONDS
    }

    private fun isSafeMethod(method: String) = method.equals("GET", ignoreCase = true) ||
        method.equals("HEAD", ignoreCase = true)
}
