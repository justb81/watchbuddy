package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.trakt.ProxyTokenRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TokenProxyServiceFactory")
class TokenProxyServiceFactoryTest {

    private lateinit var server: MockWebServer
    private lateinit var sharedClient: OkHttpClient
    private lateinit var factory: TokenProxyServiceFactory

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sharedClient = NetworkModule.provideOkHttpClient(traktClientId = "test-id")
        factory = TokenProxyServiceFactory(sharedClient)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creates non-null service from valid URL`() {
        val service = factory.create("https://proxy.example.com")
        assertNotNull(service)
    }

    @Test
    fun `creates service for URL without trailing slash`() {
        val service = factory.create("https://proxy.example.com")
        assertNotNull(service)
    }

    @Test
    fun `creates service for URL with trailing slash`() {
        val service = factory.create("https://proxy.example.com/")
        assertNotNull(service)
    }

    @Test
    fun `creates independent instances for different URLs`() {
        val service1 = factory.create("https://proxy1.example.com")
        val service2 = factory.create("https://proxy2.example.com")
        assertNotSame(service1, service2)
    }

    @Test
    fun `uses shared client timeouts`() {
        assertEquals(10_000, sharedClient.connectTimeoutMillis)
        assertEquals(30_000, sharedClient.readTimeoutMillis)
        assertEquals(45_000, sharedClient.callTimeoutMillis)
    }

    @Test
    fun `inherits retryOnConnectionFailure false from shared client`() {
        assertFalse(sharedClient.retryOnConnectionFailure)
    }

    @Test
    fun `forwards interceptors from the shared client to outgoing requests`() {
        val headerName = "X-Test-Marker"
        val headerValue = "shared-client-active"
        val customClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header(headerName, headerValue).build())
            }
            .build()
        val tokenResponse = """
            {"access_token":"t","refresh_token":"r","expires_in":7776000,
             "token_type":"Bearer","scope":"public"}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponse))
        val service = TokenProxyServiceFactory(customClient).create(server.url("/").toString())
        runBlocking {
            try { service.exchangeDeviceCode(ProxyTokenRequest("code")) } catch (_: Exception) {}
        }
        val recorded = server.takeRequest()
        assertEquals(headerValue, recorded.getHeader(headerName))
    }
}
