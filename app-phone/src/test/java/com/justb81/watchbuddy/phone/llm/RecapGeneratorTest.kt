package com.justb81.watchbuddy.phone.llm

import android.app.Application
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RecapGenerator")
class RecapGeneratorTest {

    private val application: Application = mockk(relaxed = true)
    private val llmProviderFactory: LlmProviderFactory = mockk()
    private lateinit var generator: RecapGenerator

    private val testShow = TmdbShow(100, "Breaking Bad", "A chemistry teacher turns to meth.")
    private val targetEpisode = TmdbEpisode(10, "Ozymandias", "Hank faces danger.", "/still10.jpg", 5, 14)

    private fun episodes(count: Int): List<TmdbEpisode> = (1..count).map { i ->
        TmdbEpisode(i, "Episode $i", "Overview $i", "/still$i.jpg", 1, i)
    }

    @BeforeEach
    fun setUp() {
        every { application.getString(any()) } returns "No description available"
        generator = RecapGenerator(application, llmProviderFactory)
    }

    @Nested
    @DisplayName("generateRecap integration")
    inner class GenerateRecapTest {

        @Test
        fun `sanitizes HTML from LLM output`() = runTest {
            val dangerousHtml = """<div>Safe</div><script>alert('xss')</script><div>Also safe</div>"""
            coEvery { llmProviderFactory.generateWithCascade(any(), any()) } returns dangerousHtml

            val result = generator.generateRecap(testShow, episodes(3), targetEpisode, "api-key")
            assertFalse(result.contains("<script>"))
            assertFalse(result.contains("alert"))
            assertTrue(result.contains("Safe"))
        }

        @Test
        fun `replaces TMDB still placeholders`() = runTest {
            val htmlWithPlaceholders = """<img data-tmdb-still="S01E01" alt="scene">"""
            coEvery { llmProviderFactory.generateWithCascade(any(), any()) } returns htmlWithPlaceholders

            val result = generator.generateRecap(testShow, episodes(3), targetEpisode, "api-key")
            assertTrue(result.contains("src=\"https://image.tmdb.org/t/p/w300/still1.jpg\""))
            assertFalse(result.contains("data-tmdb-still"))
        }

        @Test
        fun `passes prompt to LLM factory`() = runTest {
            val promptSlot = slot<String>()
            coEvery { llmProviderFactory.generateWithCascade(capture(promptSlot), any()) } returns "<div>Recap</div>"

            generator.generateRecap(testShow, episodes(3), targetEpisode, "api-key")
            val prompt = promptSlot.captured
            assertTrue(prompt.contains("Breaking Bad"))
            assertTrue(prompt.contains("S05E14"))
            assertTrue(prompt.contains("Ozymandias"))
        }
    }

    @Nested
    @DisplayName("HTML sanitization")
    inner class SanitizationTest {

        private suspend fun sanitize(html: String): String {
            coEvery { llmProviderFactory.generateWithCascade(any(), any()) } returns html
            return generator.generateRecap(testShow, episodes(1), targetEpisode, "key")
        }

        @Test
        fun `strips script tags and content`() = runTest {
            val result = sanitize("<div>ok</div><script>evil()</script><p>safe</p>")
            assertFalse(result.contains("script"))
            assertFalse(result.contains("evil"))
            assertTrue(result.contains("safe"))
        }

        @Test
        fun `strips iframe tags`() = runTest {
            val result = sanitize("""<div>ok</div><iframe src="evil.com"></iframe>""")
            assertFalse(result.contains("iframe"))
        }

        @Test
        fun `strips object and embed tags`() = runTest {
            val result = sanitize("""<object data="x"></object><embed src="y">""")
            assertFalse(result.contains("<object"))
            assertFalse(result.contains("<embed"))
        }

        @Test
        fun `strips form tags`() = runTest {
            val result = sanitize("""<form action="evil"><input></form>""")
            assertFalse(result.contains("<form"))
        }

        @Test
        fun `strips event handlers`() = runTest {
            val result = sanitize("""<div onclick="alert('xss')">click</div>""")
            assertFalse(result.contains("onclick"))
            assertFalse(result.contains("alert"))
            assertTrue(result.contains("click"))
        }

        @Test
        fun `strips onload event handler`() = runTest {
            val result = sanitize("""<img onload="steal()" src="img.jpg">""")
            assertFalse(result.contains("onload"))
        }

        @Test
        fun `strips javascript URLs`() = runTest {
            val result = sanitize("""<a href="javascript:alert(1)">link</a>""")
            assertFalse(result.contains("javascript:"))
            assertTrue(result.contains("about:blank"))
        }

        @Test
        fun `preserves safe HTML`() = runTest {
            val safeHtml = """<div class="slide"><h3>Title</h3><p>Description</p></div>"""
            val result = sanitize(safeHtml)
            assertTrue(result.contains("slide"))
            assertTrue(result.contains("Title"))
            assertTrue(result.contains("Description"))
        }
    }

    @Nested
    @DisplayName("prompt building")
    inner class PromptBuildingTest {

        @Test
        fun `prompt includes last 8 episodes only`() = runTest {
            val promptSlot = slot<String>()
            coEvery { llmProviderFactory.generateWithCascade(capture(promptSlot), any()) } returns "<div></div>"

            generator.generateRecap(testShow, episodes(12), targetEpisode, "key")
            val prompt = promptSlot.captured
            // Episodes 5-12 should be in prompt (last 8), episodes 1-4 should not
            assertFalse(prompt.contains("Episode 4"))
            assertTrue(prompt.contains("Episode 5"))
            assertTrue(prompt.contains("Episode 12"))
        }

        @Test
        fun `prompt includes show name`() = runTest {
            val promptSlot = slot<String>()
            coEvery { llmProviderFactory.generateWithCascade(capture(promptSlot), any()) } returns "<div></div>"

            generator.generateRecap(testShow, episodes(1), targetEpisode, "key")
            assertTrue(promptSlot.captured.contains("Breaking Bad"))
        }

        @Test
        fun `prompt includes zero-padded episode numbers`() = runTest {
            val promptSlot = slot<String>()
            coEvery { llmProviderFactory.generateWithCascade(capture(promptSlot), any()) } returns "<div></div>"

            generator.generateRecap(testShow, episodes(3), targetEpisode, "key")
            assertTrue(promptSlot.captured.contains("S01E01"))
            assertTrue(promptSlot.captured.contains("S01E02"))
        }
    }

    @Nested
    @DisplayName("TMDB placeholder replacement")
    inner class PlaceholderTest {

        @Test
        fun `replaces multiple placeholders`() = runTest {
            val html = """<img data-tmdb-still="S01E01"><img data-tmdb-still="S01E02">"""
            coEvery { llmProviderFactory.generateWithCascade(any(), any()) } returns html

            val eps = episodes(2)
            val result = generator.generateRecap(testShow, eps, targetEpisode, "key")
            assertTrue(result.contains("/still1.jpg"))
            assertTrue(result.contains("/still2.jpg"))
        }

        @Test
        fun `placeholder with no matching episode gets empty src`() = runTest {
            val html = """<img data-tmdb-still="S99E99">"""
            coEvery { llmProviderFactory.generateWithCascade(any(), any()) } returns html

            val result = generator.generateRecap(testShow, episodes(1), targetEpisode, "key")
            assertTrue(result.contains("src=\"\""))
        }

        @Test
        fun `episode with null still_path gets empty src`() = runTest {
            val html = """<img data-tmdb-still="S01E01">"""
            coEvery { llmProviderFactory.generateWithCascade(any(), any()) } returns html

            val eps = listOf(TmdbEpisode(1, "Ep1", "Ov", null, 1, 1))
            val result = generator.generateRecap(testShow, eps, targetEpisode, "key")
            assertTrue(result.contains("src=\"\""))
        }
    }
}
