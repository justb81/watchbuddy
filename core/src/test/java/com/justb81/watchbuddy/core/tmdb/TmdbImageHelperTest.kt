package com.justb81.watchbuddy.core.tmdb

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TmdbImageHelper")
class TmdbImageHelperTest {

    @Nested
    @DisplayName("still()")
    inner class StillTest {
        @Test
        fun `returns correct URL with default width 300`() {
            assertEquals("https://image.tmdb.org/t/p/w300/abc.jpg", TmdbImageHelper.still("/abc.jpg"))
        }

        @Test
        fun `returns correct URL with custom width`() {
            assertEquals("https://image.tmdb.org/t/p/w780/abc.jpg", TmdbImageHelper.still("/abc.jpg", 780))
        }

        @Test
        fun `returns null when path is null`() {
            assertNull(TmdbImageHelper.still(null))
        }

        @Test
        fun `returns null when path is null with custom width`() {
            assertNull(TmdbImageHelper.still(null, 500))
        }
    }

    @Nested
    @DisplayName("poster()")
    inner class PosterTest {
        @Test
        fun `returns correct URL with default width 500`() {
            assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", TmdbImageHelper.poster("/poster.jpg"))
        }

        @Test
        fun `returns correct URL with custom width`() {
            assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", TmdbImageHelper.poster("/poster.jpg", 342))
        }

        @Test
        fun `returns null when path is null`() {
            assertNull(TmdbImageHelper.poster(null))
        }
    }

    @Nested
    @DisplayName("backdrop()")
    inner class BackdropTest {
        @Test
        fun `returns correct URL with default width 1280`() {
            assertEquals("https://image.tmdb.org/t/p/w1280/back.jpg", TmdbImageHelper.backdrop("/back.jpg"))
        }

        @Test
        fun `returns correct URL with custom width`() {
            assertEquals("https://image.tmdb.org/t/p/w780/back.jpg", TmdbImageHelper.backdrop("/back.jpg", 780))
        }

        @Test
        fun `returns null when path is null`() {
            assertNull(TmdbImageHelper.backdrop(null))
        }
    }

    @Test
    @DisplayName("all methods use correct base URL prefix")
    fun `base URL is consistent across methods`() {
        val base = "https://image.tmdb.org/t/p/"
        assertTrue(TmdbImageHelper.still("/x.jpg")!!.startsWith(base))
        assertTrue(TmdbImageHelper.poster("/x.jpg")!!.startsWith(base))
        assertTrue(TmdbImageHelper.backdrop("/x.jpg")!!.startsWith(base))
    }
}
