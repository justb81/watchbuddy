package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RateLimitInterceptor")
class RateLimitInterceptorTest {

    private lateinit var server: MockWebServer
    private val sleepDelays = mutableListOf<Long>()
    private val interceptor = RateLimitInterceptor(sleepFn = { ms -> sleepDelays.add(ms) })
    private val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        DiagnosticLog.clear()
        sleepDelays.clear()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Nested
    @DisplayName("non-429 responses")
    inner class NonRateLimited {

        @Test
        fun `passes through 200 without retry`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(200, response.code)
            assertEquals(1, server.requestCount)
            assertTrue(sleepDelays.isEmpty())
        }

        @Test
        fun `passes through 404 without retry`() {
            server.enqueue(MockResponse().setResponseCode(404))
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(404, response.code)
            assertEquals(1, server.requestCount)
        }

        @Test
        fun `passes through 500 without retry`() {
            server.enqueue(MockResponse().setResponseCode(500))
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(500, response.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Nested
    @DisplayName("GET 429 retry behaviour")
    inner class GetRetry {

        @Test
        fun `retries GET once and returns second response`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(200, response.code)
            assertEquals(2, server.requestCount)
        }

        @Test
        fun `sleeps for Retry-After seconds on GET 429`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "10"))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(listOf(10_000L), sleepDelays)
        }

        @Test
        fun `caps delay at 60 seconds`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(listOf(60_000L), sleepDelays)
        }

        @Test
        fun `uses default 5s delay when Retry-After header is absent`() {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(listOf(5_000L), sleepDelays)
        }

        @Test
        fun `uses default 5s delay when Retry-After is non-numeric`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "Wed, 21 Oct 2025 07:28:00 GMT"))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(listOf(5_000L), sleepDelays)
        }

        @Test
        fun `uses default 5s delay when Retry-After is zero`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(listOf(5_000L), sleepDelays)
        }

        @Test
        fun `uses default 5s delay when Retry-After is negative`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "-5"))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(listOf(5_000L), sleepDelays)
        }

        @Test
        fun `does not retry a second time when retried request also returns 429`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(429, response.code)
            assertEquals(2, server.requestCount)
            // Only one sleep — we only retry once
            assertEquals(1, sleepDelays.size)
        }
    }

    @Nested
    @DisplayName("non-idempotent methods are not retried")
    inner class NonIdempotentMethods {

        @Test
        fun `POST 429 is not retried`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "5"))
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/"))
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
            ).execute()
            assertEquals(429, response.code)
            assertEquals(1, server.requestCount)
            assertTrue(sleepDelays.isEmpty())
        }

        @Test
        fun `PUT 429 is not retried`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "5"))
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/"))
                    .put(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
            ).execute()
            assertEquals(429, response.code)
            assertEquals(1, server.requestCount)
            assertTrue(sleepDelays.isEmpty())
        }

        @Test
        fun `DELETE 429 is not retried`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "5"))
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/"))
                    .delete()
                    .build()
            ).execute()
            assertEquals(429, response.code)
            assertEquals(1, server.requestCount)
            assertTrue(sleepDelays.isEmpty())
        }
    }

    @Nested
    @DisplayName("DiagnosticLog surfacing")
    inner class DiagnosticLogTests {

        @Test
        fun `logs WARN entry on GET 429`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "3"))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/path")).build()).execute()
            val entries = DiagnosticLog.snapshot()
            val warn429 = entries.filter {
                it.level == DiagnosticLog.Level.WARN && it.message.contains("429")
            }
            assertTrue(warn429.isNotEmpty(), "Expected a WARN entry for 429 in DiagnosticLog")
        }

        @Test
        fun `logs WARN entry on POST 429 even though not retried`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "3"))
            client.newCall(
                Request.Builder()
                    .url(server.url("/scrobble/start"))
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
            ).execute()
            val entries = DiagnosticLog.snapshot()
            val warn429 = entries.filter {
                it.level == DiagnosticLog.Level.WARN && it.message.contains("429")
            }
            assertTrue(warn429.isNotEmpty(), "Expected a WARN entry for POST 429 in DiagnosticLog")
        }

        @Test
        fun `log entry includes retry-after delay seconds`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "42"))
            server.enqueue(MockResponse().setResponseCode(200))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            val warn = DiagnosticLog.snapshot().first {
                it.level == DiagnosticLog.Level.WARN && it.message.contains("429")
            }
            assertTrue(warn.message.contains("42s"), "Log should include delay: ${warn.message}")
        }
    }

    @Nested
    @DisplayName("HEAD method")
    inner class HeadMethod {

        @Test
        fun `HEAD 429 is retried like GET`() {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setResponseCode(200))
            val response = client.newCall(
                Request.Builder().url(server.url("/")).head().build()
            ).execute()
            assertEquals(200, response.code)
            assertEquals(2, server.requestCount)
        }
    }
}
