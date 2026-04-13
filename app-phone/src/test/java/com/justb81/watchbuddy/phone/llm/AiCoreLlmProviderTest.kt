package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.ai.edge.aicore.GenerateContentResponse
import com.google.ai.edge.aicore.GenerativeModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("AiCoreLlmProvider")
class AiCoreLlmProviderTest {

    private val context: Context = mockk(relaxed = true)
    private val provider = AiCoreLlmProvider(context)

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `displayName is AICore (Gemini Nano)`() {
        assertEquals("AICore (Gemini Nano)", provider.displayName)
    }

    @Nested
    @DisplayName("generate")
    inner class GenerateTest {

        @BeforeEach
        fun setUp() {
            mockkConstructor(GenerativeModel::class)
        }

        @Test
        fun `returns text from GenerativeModel response`() = runTest {
            val mockResponse = mockk<GenerateContentResponse>()
            every { mockResponse.text } returns "Generated recap HTML"
            coEvery {
                anyConstructed<GenerativeModel>().generateContent(any<String>())
            } returns mockResponse

            val result = provider.generate("test prompt")
            assertEquals("Generated recap HTML", result)
        }

        @Test
        fun `throws IllegalStateException on null response text`() = runTest {
            val mockResponse = mockk<GenerateContentResponse>()
            every { mockResponse.text } returns null
            coEvery {
                anyConstructed<GenerativeModel>().generateContent(any<String>())
            } returns mockResponse

            assertThrows<IllegalStateException> {
                provider.generate("test prompt")
            }
        }

        @Test
        fun `throws IllegalStateException on blank response text`() = runTest {
            val mockResponse = mockk<GenerateContentResponse>()
            every { mockResponse.text } returns "   "
            coEvery {
                anyConstructed<GenerativeModel>().generateContent(any<String>())
            } returns mockResponse

            assertThrows<IllegalStateException> {
                provider.generate("test prompt")
            }
        }

        @Test
        fun `propagates AICore exceptions for cascade fallback`() = runTest {
            coEvery {
                anyConstructed<GenerativeModel>().generateContent(any<String>())
            } throws RuntimeException("AICore service unavailable")

            assertThrows<RuntimeException> {
                provider.generate("test prompt")
            }
        }

        @Test
        fun `reuses cached model on subsequent calls`() = runTest {
            val mockResponse = mockk<GenerateContentResponse>()
            every { mockResponse.text } returns "response"
            coEvery {
                anyConstructed<GenerativeModel>().generateContent(any<String>())
            } returns mockResponse

            // Both calls should succeed using the same cached model
            assertEquals("response", provider.generate("first prompt"))
            assertEquals("response", provider.generate("second prompt"))
        }
    }
}
