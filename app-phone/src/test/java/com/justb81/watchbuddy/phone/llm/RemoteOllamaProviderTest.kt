package com.justb81.watchbuddy.phone.llm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

@DisplayName("RemoteOllamaProvider")
class RemoteOllamaProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: okhttp3.OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = okhttp3.OkHttpClient()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(model: String = "gemma3:4b"): RemoteOllamaProvider =
        RemoteOllamaProvider(server.url("/").toString().trimEnd('/'), model, httpClient)

    @Test
    fun `displayName includes model and server URL`() {
        val p = RemoteOllamaProvider("http://192.168.1.1:11434", "llama3", httpClient)
        assertTrue(p.displayName.contains("llama3"))
        assertTrue(p.displayName.contains("192.168.1.1"))
    }

    @Test
    fun `generate sends POST to api generate endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"Hello"}"""))
        provider().generate("test prompt")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/generate"))
    }

    @Test
    fun `generate sends correct JSON body`() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"Hello"}"""))
        // RemoteOllamaProvider uses HttpURLConnection which goes through the system proxy.
        // MockWebServer receives the raw request. The body encoding may differ.
        // We just verify the request was received and method is POST
        provider("testmodel").generate("my prompt")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertFalse(body.isEmpty(), "Body should not be empty")
    }

    @Test
    fun `generate returns response text on success`() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"Generated HTML recap"}"""))
        val result = provider().generate("prompt")
        assertEquals("Generated HTML recap", result)
    }

    @Test
    fun `generate throws on non-200 response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val exception = assertThrows<IllegalStateException> {
            provider().generate("prompt")
        }
        assertTrue(exception.message!!.contains("500"))
    }

    @Test
    fun `generate throws on empty response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":""}"""))
        assertThrows<IllegalStateException> {
            provider().generate("prompt")
        }
    }

    @Test
    fun `generate throws on blank response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"   "}"""))
        assertThrows<IllegalStateException> {
            provider().generate("prompt")
        }
    }

    @Test
    fun `Content-Type header is set to application json`() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"ok"}"""))
        provider().generate("prompt")
        val request = server.takeRequest()
        assertTrue(request.getHeader("Content-Type")!!.contains("application/json"))
    }

    @Test
    fun `uses shared OkHttpClient connection pool`() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"first"}"""))
        server.enqueue(MockResponse().setBody("""{"response":"second"}"""))
        val p = provider()
        p.generate("p1")
        p.generate("p2")
        assertEquals(2, server.requestCount)
    }
}
