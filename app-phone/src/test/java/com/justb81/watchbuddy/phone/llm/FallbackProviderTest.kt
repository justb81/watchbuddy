package com.justb81.watchbuddy.phone.llm

import com.justb81.watchbuddy.core.model.TmdbEpisode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("FallbackProvider")
class FallbackProviderTest {

    private fun episodes(count: Int): List<TmdbEpisode> = (1..count).map { i ->
        TmdbEpisode(
            id = i, name = "Episode $i", overview = "Overview $i",
            still_path = "/still$i.jpg", season_number = 1, episode_number = i
        )
    }

    @Test
    fun `displayName is TMDB Synopsis Fallback`() {
        assertEquals("TMDB Synopsis Fallback", FallbackProvider(emptyList()).displayName)
    }

    @Test
    fun `generate returns HTML with slides`() = runTest {
        val html = FallbackProvider(episodes(3)).generate("unused prompt")
        assertTrue(html.contains("class=\"slide\""))
        assertTrue(html.contains("Episode 1"))
        assertTrue(html.contains("Episode 2"))
        assertTrue(html.contains("Episode 3"))
    }

    @Test
    fun `generate uses last 6 episodes when more than 6 provided`() = runTest {
        val html = FallbackProvider(episodes(10)).generate("unused")
        // takeLast(6) means episodes 5-10 should appear
        // "Episode 10" contains "Episode 1" as substring, so check for exact episode labels
        assertTrue(html.contains("S01E05"))
        assertTrue(html.contains("S01E10"))
        // Episodes 1-4 should not have their labels
        assertFalse(html.contains("S01E01"))
        assertFalse(html.contains("S01E04"))
    }

    @Test
    fun `generate uses all episodes when fewer than 6`() = runTest {
        val html = FallbackProvider(episodes(3)).generate("unused")
        assertTrue(html.contains("Episode 1"))
        assertTrue(html.contains("Episode 3"))
    }

    @Test
    fun `generate formats season and episode numbers with zero-padding`() = runTest {
        val html = FallbackProvider(episodes(1)).generate("unused")
        assertTrue(html.contains("S01E01"))
    }

    @Test
    fun `generate uses em-dash for null overview`() = runTest {
        val ep = TmdbEpisode(1, "Test", null, null, 1, 1)
        val html = FallbackProvider(listOf(ep)).generate("unused")
        assertTrue(html.contains("\u2014")) // em-dash
    }

    @Test
    fun `generate includes CSS animation styles`() = runTest {
        val html = FallbackProvider(episodes(1)).generate("unused")
        assertTrue(html.contains("@keyframes fadeSlide"))
        assertTrue(html.contains("animation"))
    }

    @Test
    fun `generate includes data-tmdb-still placeholder`() = runTest {
        val html = FallbackProvider(episodes(1)).generate("unused")
        assertTrue(html.contains("data-tmdb-still=\"S01E01\""))
    }

    @Test
    fun `generate with empty episode list returns HTML with no slides`() = runTest {
        val html = FallbackProvider(emptyList()).generate("unused")
        assertTrue(html.contains("font-family"))
        assertFalse(html.contains("class=\"slide\""))
    }

    @Test
    fun `animation delay increments by 4s per slide`() = runTest {
        val html = FallbackProvider(episodes(3)).generate("unused")
        assertTrue(html.contains("animation-delay:0s"))
        assertTrue(html.contains("animation-delay:4s"))
        assertTrue(html.contains("animation-delay:8s"))
    }

    @Test
    fun `generate escapes HTML special characters in episode name`() = runTest {
        val ep = TmdbEpisode(1, "The <Choice> & \"Consequences\"", "Normal overview", null, 1, 1)
        val html = FallbackProvider(listOf(ep)).generate("unused")
        assertTrue(html.contains("The &lt;Choice&gt; &amp; &quot;Consequences&quot;"))
        assertFalse(html.contains("<Choice>"))
        assertFalse(html.contains("\"Consequences\""))
    }

    @Test
    fun `generate escapes HTML special characters in episode overview`() = runTest {
        val ep = TmdbEpisode(1, "Normal", "<script>alert('xss')</script> & more", null, 1, 1)
        val html = FallbackProvider(listOf(ep)).generate("unused")
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp; more"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `generate escapes ampersand in episode name`() = runTest {
        val ep = TmdbEpisode(1, "Fire & Ice", null, null, 1, 1)
        val html = FallbackProvider(listOf(ep)).generate("unused")
        assertTrue(html.contains("Fire &amp; Ice"))
        assertFalse(html.contains("Fire & Ice"))
    }

    @Test
    fun `generate escapes less-than and greater-than signs in overview`() = runTest {
        val ep = TmdbEpisode(1, "Normal", "Rating: 8 > 7 and <10", null, 1, 1)
        val html = FallbackProvider(listOf(ep)).generate("unused")
        assertTrue(html.contains("8 &gt; 7"))
        assertTrue(html.contains("&lt;10"))
    }

    @Test
    fun `generate escapes double quotes in episode name`() = runTest {
        val ep = TmdbEpisode(1, "The \"One\"", null, null, 1, 1)
        val html = FallbackProvider(listOf(ep)).generate("unused")
        assertTrue(html.contains("The &quot;One&quot;"))
        assertFalse(html.contains("The \"One\""))
    }
}
