package com.justb81.watchbuddy.core.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("KNOWN_STREAMING_SERVICES")
class KnownStreamingServicesTest {

    @Test
    fun `has 7 streaming services`() {
        assertEquals(7, KNOWN_STREAMING_SERVICES.size)
    }

    @Test
    fun `all services have unique IDs`() {
        val ids = KNOWN_STREAMING_SERVICES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all services have unique package names`() {
        val packageNames = KNOWN_STREAMING_SERVICES.map { it.packageName }
        assertEquals(packageNames.size, packageNames.toSet().size)
    }

    @Test
    fun `Netflix is first`() {
        assertEquals("netflix", KNOWN_STREAMING_SERVICES.first().id)
        assertEquals("Netflix", KNOWN_STREAMING_SERVICES.first().name)
    }

    @Test
    fun `all deep link templates are non-blank`() {
        KNOWN_STREAMING_SERVICES.forEach { service ->
            assertTrue(service.deepLinkTemplate.isNotBlank(), "${service.id} has blank deepLinkTemplate")
        }
    }

    @Test
    fun `expected service IDs are present`() {
        val ids = KNOWN_STREAMING_SERVICES.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("netflix", "prime", "disney", "waipu", "joyn", "ard", "zdf")))
    }

    @Test
    fun `Netflix deep link uses tmdb_id placeholder`() {
        val netflix = KNOWN_STREAMING_SERVICES.first { it.id == "netflix" }
        assertTrue(netflix.deepLinkTemplate.contains("{tmdb_id}"))
    }

    @Test
    fun `Prime deep link uses slug placeholder for search`() {
        val prime = KNOWN_STREAMING_SERVICES.first { it.id == "prime" }
        assertTrue(prime.deepLinkTemplate.contains("{slug}"), "Prime Video should use a search URL with {slug}")
        assertFalse(prime.deepLinkTemplate.contains("{asin}"), "Prime Video must not use {asin} — TMDB IDs are not Amazon ASINs")
    }
}
