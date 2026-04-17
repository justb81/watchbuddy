package com.justb81.watchbuddy.core.network

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
            val client = NetworkModule.provideOkHttpClient(isDebug = false, traktClientId = "test-id")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("application/json", recorded.getHeader("Content-Type"))
        }

        @Test
        fun `adds trakt-api-version header`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = NetworkModule.provideOkHttpClient(isDebug = false, traktClientId = "test-id")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("2", recorded.getHeader("trakt-api-version"))
        }

        @Test
        fun `adds trakt-api-key header when client id is provided`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = NetworkModule.provideOkHttpClient(isDebug = false, traktClientId = "abc-123")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("abc-123", recorded.getHeader("trakt-api-key"))
        }

        @Test
        fun `omits trakt-api-key header when client id is blank`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = NetworkModule.provideOkHttpClient(isDebug = false, traktClientId = "")
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("trakt-api-key"))
        }

        @Test
        fun `does not apply certificate pinning`() {
            val client = NetworkModule.provideOkHttpClient(isDebug = false, traktClientId = "test-id")
            assertTrue(client.certificatePinner.pins.isEmpty())
        }
    }

    @Nested
    @DisplayName("provideTokenProxyRetrofit")
    inner class TokenProxyRetrofitTest {
        @Test
        fun `returns null for blank URL`() {
            val result = NetworkModule.provideTokenProxyRetrofit("", OkHttpClient())
            assertNull(result)
        }

        @Test
        fun `returns null for whitespace-only URL`() {
            val result = NetworkModule.provideTokenProxyRetrofit("   ", OkHttpClient())
            assertNull(result)
        }

        @Test
        fun `returns non-null for valid URL`() {
            val result = NetworkModule.provideTokenProxyRetrofit("https://example.com", OkHttpClient())
            assertNotNull(result)
        }

        @Test
        fun `appends trailing slash if missing`() {
            val result = NetworkModule.provideTokenProxyRetrofit("https://example.com", OkHttpClient())
            assertEquals("https://example.com/", result!!.baseUrl().toString())
        }

        @Test
        fun `preserves existing trailing slash`() {
            val result = NetworkModule.provideTokenProxyRetrofit("https://example.com/", OkHttpClient())
            assertEquals("https://example.com/", result!!.baseUrl().toString())
        }
    }

    @Nested
    @DisplayName("provideTokenProxyService")
    inner class TokenProxyServiceTest {
        @Test
        fun `returns null when retrofit is null`() {
            val result = NetworkModule.provideTokenProxyService(null)
            assertNull(result)
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
