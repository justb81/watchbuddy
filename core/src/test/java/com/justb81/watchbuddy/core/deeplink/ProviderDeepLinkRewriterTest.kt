package com.justb81.watchbuddy.core.deeplink

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ProviderDeepLinkRewriter")
class ProviderDeepLinkRewriterTest {

    private val netflixProviderId = 8
    private val primeVideoProviderId = 9
    private val titleId = "80057281"

    @Nested
    @DisplayName("Netflix (provider 8)")
    inner class NetflixRewrite {

        @Test
        @DisplayName("title URL → nflx scheme first, then canonical https title URL")
        fun titleUrl() {
            val url = "https://www.netflix.com/title/$titleId"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertEquals("nflx://www.netflix.com/title/$titleId", result[0])
            assertEquals("https://www.netflix.com/title/$titleId", result[1])
        }

        @Test
        @DisplayName("watch URL → nflx scheme first, then canonical title URL, then original watch URL")
        fun watchUrl() {
            val url = "https://www.netflix.com/watch/$titleId"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertEquals("nflx://www.netflix.com/title/$titleId", result[0])
            assertEquals("https://www.netflix.com/title/$titleId", result[1])
            assertEquals("https://www.netflix.com/watch/$titleId", result[2])
        }

        @Test
        @DisplayName("locale-prefixed URL is handled correctly")
        fun localePrefixedUrl() {
            val url = "https://www.netflix.com/de/title/$titleId"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertEquals("nflx://www.netflix.com/title/$titleId", result[0])
            assertEquals("https://www.netflix.com/title/$titleId", result[1])
        }

        @Test
        @DisplayName("unrecognised URL is returned verbatim")
        fun unrecognisedUrl() {
            val url = "https://example.com/garbage"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertEquals(listOf(url), result)
        }

        @Test
        @DisplayName("title URL produces only two distinct candidates (no duplicate)")
        fun titleUrlDistinct() {
            val url = "https://www.netflix.com/title/$titleId"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertEquals(result.size, result.distinct().size)
            assertEquals(2, result.size)
        }

        @Test
        @DisplayName("watch URL produces three distinct candidates")
        fun watchUrlDistinct() {
            val url = "https://www.netflix.com/watch/$titleId"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertEquals(result.size, result.distinct().size)
            assertEquals(3, result.size)
        }
    }

    @Nested
    @DisplayName("Non-Netflix providers")
    inner class NonNetflixPassthrough {

        @Test
        @DisplayName("Prime Video URL is returned unchanged")
        fun primeVideoPassthrough() {
            val url = "https://www.primevideo.com/detail/amzn1.dv.gti.abc123"
            val result = ProviderDeepLinkRewriter.rewrite(primeVideoProviderId, url)

            assertEquals(listOf(url), result)
        }

        @Test
        @DisplayName("Disney+ URL is returned unchanged")
        fun disneyPlusPassthrough() {
            val url = "https://www.disneyplus.com/series/the-show/abc123"
            val result = ProviderDeepLinkRewriter.rewrite(337, url)

            assertEquals(listOf(url), result)
        }
    }

    @Nested
    @DisplayName("Contract: result is always non-empty and contains the original URL")
    inner class AlwaysNonEmpty {

        @Test
        @DisplayName("Netflix with recognisable URL contains original")
        fun netflixContainsOriginal() {
            val url = "https://www.netflix.com/title/$titleId"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertFalse(result.isEmpty())
            assertTrue(result.contains(url))
        }

        @Test
        @DisplayName("Netflix with unrecognisable URL contains original")
        fun netflixUnknownContainsOriginal() {
            val url = "https://example.com/garbage"
            val result = ProviderDeepLinkRewriter.rewrite(netflixProviderId, url)

            assertFalse(result.isEmpty())
            assertTrue(result.contains(url))
        }

        @Test
        @DisplayName("unknown provider contains original URL")
        fun unknownProviderContainsOriginal() {
            val url = "https://www.someprovider.com/show/123"
            val result = ProviderDeepLinkRewriter.rewrite(9999, url)

            assertFalse(result.isEmpty())
            assertTrue(result.contains(url))
        }
    }
}
