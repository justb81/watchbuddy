package com.justb81.watchbuddy.core.network

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TokenProxyServiceFactory")
class TokenProxyServiceFactoryTest {

    private val factory = TokenProxyServiceFactory()

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
}
