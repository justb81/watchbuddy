package com.justb81.watchbuddy.phone.llm

import com.justb81.watchbuddy.core.model.LibraryHint
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LlmTitleExtractor — JSON validation & hallucination guards")
class LlmTitleExtractorTest {

    private val factory: LlmProviderFactory = mockk()
    private lateinit var extractor: LlmTitleExtractor

    private val hints = listOf(
        LibraryHint(traktId = 1, tmdbId = 1396, title = "Breaking Bad", year = 2008),
        LibraryHint(traktId = 2, tmdbId = 136315, title = "The Bear", year = 2022),
    )

    @BeforeEach
    fun setUp() {
        extractor = LlmTitleExtractor(factory)
    }

    @Test
    fun `well-formed JSON with library traktId is parsed and accepted`() = runTest {
        coEvery { factory.generateOrNull(any()) } returns """
            {"showTitle":"Breaking Bad","season":3,"episode":7,"libraryTraktId":1,"confidence":0.88}
        """.trimIndent()

        val result = extractor.extract(sampleSnapshot(), hints)

        assertNotNull(result)
        assertEquals("Breaking Bad", result!!.showTitle)
        assertEquals(3, result.season)
        assertEquals(7, result.episode)
        assertEquals(1, result.libraryTraktId)
        assertEquals(0.88f, result.confidence)
    }

    @Test
    fun `LLM wrapping output in prose + markdown still parses via brace extraction`() = runTest {
        coEvery { factory.generateOrNull(any()) } returns """
            Sure! Here's the extracted metadata:

            ```json
            {"showTitle":"Breaking Bad","season":1,"episode":1,"libraryTraktId":1,"confidence":0.9}
            ```

            Hope that helps!
        """.trimIndent()

        val result = extractor.extract(sampleSnapshot(), hints)

        assertEquals("Breaking Bad", result?.showTitle)
        assertEquals(0.9f, result?.confidence)
    }

    @Test
    fun `no LLM available returns null (not a fabricated response)`() = runTest {
        coEvery { factory.generateOrNull(any()) } returns null

        val result = extractor.extract(sampleSnapshot(), hints)

        assertNull(result)
    }

    @Test
    fun `malformed JSON is rejected — no fuzzy salvage`() = runTest {
        val result = extractor.parseAndValidate(
            raw = "not json at all",
            libraryHints = hints,
        )
        assertNull(result)
    }

    @Test
    fun `truncated JSON is rejected cleanly`() = runTest {
        val result = extractor.parseAndValidate(
            raw = """{"showTitle":"Breaking Bad","season":1""",
            libraryHints = hints,
        )
        assertNull(result)
    }

    @Test
    fun `hallucinated libraryTraktId is stripped — not in hint list`() {
        val result = extractor.parseAndValidate(
            raw = """{"showTitle":"Breaking Bad","season":1,"episode":1,"libraryTraktId":9999,"confidence":0.9}""",
            libraryHints = hints,
        )

        assertNotNull(result)
        assertEquals("Breaking Bad", result!!.showTitle)
        // The hallucinated ID is cleared; other fields survive.
        assertNull(result.libraryTraktId)
    }

    @Test
    fun `confidence is clamped to 0_0 to 1_0`() {
        val over = extractor.parseAndValidate(
            raw = """{"showTitle":"Breaking Bad","confidence":3.5}""",
            libraryHints = hints,
        )
        val under = extractor.parseAndValidate(
            raw = """{"showTitle":"Breaking Bad","confidence":-1.0}""",
            libraryHints = hints,
        )

        assertEquals(1.0f, over?.confidence)
        assertEquals(0.0f, under?.confidence)
    }

    @Test
    fun `out-of-range season and episode are discarded`() {
        val result = extractor.parseAndValidate(
            raw = """{"showTitle":"Breaking Bad","season":-1,"episode":99999,"confidence":0.5}""",
            libraryHints = hints,
        )

        assertNotNull(result)
        assertNull(result!!.season)
        assertNull(result.episode)
    }

    @Test
    fun `blank showTitle is collapsed to null`() {
        val result = extractor.parseAndValidate(
            raw = """{"showTitle":"   ","confidence":0.5}""",
            libraryHints = hints,
        )
        assertNotNull(result)
        assertNull(result!!.showTitle)
    }

    private fun sampleSnapshot() = MediaMetadataSnapshot(
        packageName = "com.netflix.ninja",
        title = "Pilot",
    )
}
