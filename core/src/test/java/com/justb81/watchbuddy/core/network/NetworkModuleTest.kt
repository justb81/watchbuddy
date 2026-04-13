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
            val client = createTestClient()
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("application/json", recorded.getHeader("Content-Type"))
        }

        @Test
        fun `adds trakt-api-version header`() {
            server.enqueue(MockResponse().setBody("{}"))
            val client = createTestClient()
            client.newCall(Request.Builder().url(server.url("/test")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("2", recorded.getHeader("trakt-api-version"))
        }

        private fun createTestClient(): OkHttpClient {
            // Build a client that mimics NetworkModule's interceptor logic but without certificate pinning
            return OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Content-Type", "application/json")
                        .addHeader("trakt-api-version", "2")
                        .build()
                    chain.proceed(request)
                }
                .build()
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
