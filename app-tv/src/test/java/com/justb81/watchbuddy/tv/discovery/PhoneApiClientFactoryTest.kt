package com.justb81.watchbuddy.tv.discovery

import io.mockk.mockk
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
        // Verify cache via accessing the private field
        val cacheField = PhoneApiClientFactory::class.java.getDeclaredField("cache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(factory) as Map<String, PhoneApiService>
        assertEquals(2, cache.size)
    }
}
