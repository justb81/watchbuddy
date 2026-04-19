package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.trakt.NoOpTokenProxyService
import com.justb81.watchbuddy.core.trakt.ProxyRefreshRequest
import com.justb81.watchbuddy.core.trakt.ProxyTokenRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NetworkModule")
class NetworkModuleTest {

    @Nested
    @DisplayName("provideOkHttpClient")
    inner class OkHttpClientTest {

        private lateinit var server: MockWebServer

        @BeforeEach
        fun setUp() {
            server = MockWebServer()
            server.start()
        }

        @AfterEach
        fun tearDown() {
            server.shutdown()
        }

        @Test
        fun `adds Content-Type header`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = NetworkModule.provideOkHttpClient(traktClientId = "test-id")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("application/json", recorded.getHeader("Content-Type"))
        }

        @Test
        fun `adds trakt-api-version header`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = NetworkModule.provideOkHttpClient(traktClientId = "test-id")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("2", recorded.getHeader("trakt-api-version"))
        }

        @Test
        fun `adds trakt-api-key header when client id is provided`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = NetworkModule.provideOkHttpClient(traktClientId = "abc-123")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("abc-123", recorded.getHeader("trakt-api-key"))
        }

        @Test
        fun `omits trakt-api-key header when client id is blank`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = NetworkModule.provideOkHttpClient(traktClientId = "")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("trakt-api-key"))
        }

        @Test
        fun `does not apply certificate pinning`() {
            val client = NetworkModule.provideOkHttpClient(traktClientId = "test-id")
            assertTrue(client.certificatePinner.pins.isEmpty())
        }

        @Test
        fun `logging interceptor level matches BuildConfig DEBUG`() {
            val client = NetworkModule.provideOkHttpClient(traktClientId = "test-id")
            val loggingInterceptor = client.interceptors
                .filterIsInstance<HttpLoggingInterceptor>()
                .firstOrNull()
            assertNotNull(loggingInterceptor, "HttpLoggingInterceptor should be present")
            val expectedLevel = if (com.justb81.watchbuddy.core.BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
            assertEquals(expectedLevel, loggingInterceptor!!.level)
        }
    }

    @Nested
    @DisplayName("provideTokenProxyService")
    inner class TokenProxyServiceTest {

        @Test
        fun `returns NoOpTokenProxyService for blank URL`() {
            val result = NetworkModule.provideTokenProxyService("", OkHttpClient())
            assertInstanceOf(NoOpTokenProxyService::class.java, result)
        }

        @Test
        fun `returns NoOpTokenProxyService for whitespace-only URL`() {
            val result = NetworkModule.provideTokenProxyService("   ", OkHttpClient())
            assertInstanceOf(NoOpTokenProxyService::class.java, result)
        }

        @Test
        fun `returns non-null real service for valid URL`() {
            val result = NetworkModule.provideTokenProxyService("https://example.com", OkHttpClient())
            assertNotNull(result)
            assertFalse(result is NoOpTokenProxyService)
        }

        @Test
        fun `NoOpTokenProxyService throws UnsupportedOperationException on exchangeDeviceCode`() {
            val noop = NoOpTokenProxyService()
            assertThrows(UnsupportedOperationException::class.java) {
                runBlocking { noop.exchangeDeviceCode(ProxyTokenRequest("code")) }
            }
        }

        @Test
        fun `NoOpTokenProxyService throws UnsupportedOperationException on refreshToken`() {
            val noop = NoOpTokenProxyService()
            assertThrows(UnsupportedOperationException::class.java) {
                runBlocking { noop.refreshToken(ProxyRefreshRequest("token")) }
            }
        }
    }

    @Nested
    @DisplayName("provideDownloadClient")
    inner class DownloadClientTest {

        private lateinit var server: MockWebServer

        @BeforeEach
        fun setUp() {
            server = MockWebServer()
            server.start()
        }

        @AfterEach
        fun tearDown() {
            server.shutdown()
        }

        @Test
        fun `does not add Trakt headers`() {
            server.enqueue(MockResponse().setBody("data"))
            val client = NetworkModule.provideDownloadClient()
            client.newCall(Request.Builder().url(server.url("/download")).build()).execute()
            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("trakt-api-version"))
            assertNull(recorded.getHeader("Content-Type"))
        }

        @Test
        fun `does not include logging interceptor`() {
            val client = NetworkModule.provideDownloadClient()
            assertTrue(client.interceptors.isEmpty())
        }

        @Test
        fun `has appropriate timeouts`() {
            val client = NetworkModule.provideDownloadClient()
            assertEquals(30_000, client.connectTimeoutMillis)
            assertEquals(60_000, client.readTimeoutMillis)
        }
    }

    @Nested
    @DisplayName("Retrofit base URLs")
    inner class RetrofitBaseUrlTest {
        @Test
        fun `Trakt retrofit uses correct base URL`() {
            val retrofit = NetworkModule.provideTraktRetrofit(OkHttpClient())
            assertEquals("https://api.trakt.tv/", retrofit.baseUrl().toString())
        }

        @Test
        fun `TMDB retrofit uses correct base URL`() {
            val retrofit = NetworkModule.provideTmdbRetrofit(OkHttpClient())
            assertEquals("https://api.themoviedb.org/3/", retrofit.baseUrl().toString())
        }
    }
}
