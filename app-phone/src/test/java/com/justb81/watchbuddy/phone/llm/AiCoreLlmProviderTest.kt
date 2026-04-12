package com.justb81.watchbuddy.phone.llm

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("AiCoreLlmProvider")
class AiCoreLlmProviderTest {

    private val context: Context = mockk(relaxed = true)
    private val provider = AiCoreLlmProvider(context)

    @Test
    fun `displayName is AICore (Gemini Nano)`() {
        assertEquals("AICore (Gemini Nano)", provider.displayName)
    }

    @Test
    fun `generate throws UnsupportedOperationException`() {
        val exception = assertThrows<UnsupportedOperationException> {
            kotlinx.coroutines.test.runTest {
                provider.generate("test prompt")
            }
        }
        assertTrue(exception.message!!.contains("play-services-aicore"))
    }
}
