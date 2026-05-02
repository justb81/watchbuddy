package com.justb81.watchbuddy.tv.discovery

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PhoneApiClientFactory")
class PhoneApiClientFactoryTest {

    private val httpClient: OkHttpClient = OkHttpClient.Builder().build()
    private lateinit var factory: PhoneApiClientFactory

    @BeforeEach
    fun setUp() {
        factory = PhoneApiClientFactory(httpClient)
    }

    @Test
    fun `createClient returns PhoneApiService`() {
        val client = factory.createClient("http://192.168.1.1:8765/")
        assertNotNull(client)
    }

    @Test
    fun `createClient returns same instance for same URL`() {
        val client1 = factory.createClient("http://192.168.1.1:8765/")
        val client2 = factory.createClient("http://192.168.1.1:8765/")
        assertSame(client1, client2)
    }

    @Test
    fun `createClient returns different instance for different URL`() {
        val client1 = factory.createClient("http://192.168.1.1:8765/")
        val client2 = factory.createClient("http://192.168.1.2:8765/")
        assertNotSame(client1, client2)
    }

    @Test
    fun `createClient caches by URL`() {
        factory.createClient("http://host1:8765/")
        factory.createClient("http://host2:8765/")
        factory.createClient("http://host1:8765/")
        assertEquals(2, factory.cacheSize())
    }

    @Test
    fun `createClient with bearer token returns different instance than without`() {
        val noToken = factory.createClient("http://192.168.1.1:8765/")
        val withToken = factory.createClient("http://192.168.1.1:8765/", "my-token")
        assertNotSame(noToken, withToken)
    }

    @Test
    fun `createClient caches by URL and token pair`() {
        factory.createClient("http://host1:8765/", "token-a")
        factory.createClient("http://host1:8765/", "token-b")
        factory.createClient("http://host1:8765/", "token-a")
        assertEquals(2, factory.cacheSize())
    }

    @Test
    fun `createClient with null token caches separately from no-token call`() {
        factory.createClient("http://host1:8765/")
        factory.createClient("http://host1:8765/", null)
        assertEquals(1, factory.cacheSize())
    }
}
