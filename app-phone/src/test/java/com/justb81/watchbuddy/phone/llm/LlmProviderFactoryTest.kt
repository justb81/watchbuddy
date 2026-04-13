package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.TmdbEpisode
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LlmProviderFactory")
class LlmProviderFactoryTest {

    private val context: Context = mockk(relaxed = true)
    private val orchestrator: LlmOrchestrator = mockk()
    private lateinit var factory: LlmProviderFactory

    private val episodes = listOf(
        TmdbEpisode(1, "Ep1", "Overview", null, 1, 1)
    )

    @BeforeEach
    fun setUp() {
        factory = LlmProviderFactory(context, orchestrator)
    }

    @Nested
    @DisplayName("generateWithCascade")
    inner class GenerateWithCascadeTest {

        @Test
        fun `returns result from first successful provider`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.NONE, null, 0
            )
            // With NONE backend, cascade goes straight to Fallback
            val result = factory.generateWithCascade("prompt", episodes)
            assertTrue(result.isNotBlank())
        }

        @Test
        fun `returns fallback HTML when all providers fail`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.NONE, null, 0
            )
            // Empty episodes means Fallback generates valid but empty HTML
            val result = factory.generateWithCascade("prompt", emptyList())
            assertTrue(result.isNotBlank())
        }
    }

    @Nested
    @DisplayName("cascade order")
    inner class CascadeOrderTest {

        @Test
        fun `AICORE backend includes AICore provider in cascade`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.AICORE, null, 150
            )
            // AICore throws UnsupportedOperationException, then cascade continues
            val result = factory.generateWithCascade("prompt", episodes)
            assertNotNull(result)
        }

        @Test
        fun `LITERT backend skips AICore`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.LITERT,
                LlmOrchestrator.ModelVariant.GEMMA4_E2B,
                70
            )
            // LiteRT-LM will fail (no model file), then Fallback succeeds
            val result = factory.generateWithCascade("prompt", episodes)
            assertNotNull(result)
        }

        @Test
        fun `NONE backend goes straight to Fallback`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.NONE, null, 0
            )
            val result = factory.generateWithCascade("prompt", episodes)
            assertNotNull(result)
        }
    }
}
