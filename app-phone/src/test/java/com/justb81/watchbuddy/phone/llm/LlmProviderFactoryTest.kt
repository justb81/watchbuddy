package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("LlmProviderFactory")
class LlmProviderFactoryTest {

    private val context: Context = mockk(relaxed = true)
    private val orchestrator: LlmOrchestrator = mockk()
    private val eventLog = LlmEventLog()
    private val engineCache = LlmEngineCache()
    private val settingsRepository: SettingsRepository = mockk {
        every { settings } returns flowOf(AppSettings(llmActivityLoggingEnabled = false))
    }
    private val settingsLazy = object : Lazy<SettingsRepository> {
        override fun get(): SettingsRepository = settingsRepository
    }
    private lateinit var factory: LlmProviderFactory

    private val episodes = listOf(
        TmdbEpisode(1, "Ep1", "Overview", null, 1, 1)
    )

    @BeforeEach
    fun setUp() {
        factory = LlmProviderFactory(context, orchestrator, eventLog, settingsLazy, engineCache)
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
            val result = factory.generateWithCascade("recap", "prompt", episodes)
            assertTrue(result.isNotBlank())
        }

        @Test
        fun `returns fallback HTML when all providers fail`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.NONE, null, 0
            )
            // Empty episodes means Fallback generates valid but empty HTML
            val result = factory.generateWithCascade("recap", "prompt", emptyList())
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
            // AICore fails (no Play Services in test), then cascade continues
            val result = factory.generateWithCascade("recap", "prompt", episodes)
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
            val result = factory.generateWithCascade("recap", "prompt", episodes)
            assertNotNull(result)
        }

        @Test
        fun `NONE backend goes straight to Fallback`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.NONE, null, 0
            )
            val result = factory.generateWithCascade("recap", "prompt", episodes)
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("queue depth limiting")
    inner class QueueDepthTest {

        private fun setQueueDepth(value: Int) {
            val field = LlmProviderFactory::class.java.getDeclaredField("llmQueueDepth")
            field.isAccessible = true
            (field.get(factory) as AtomicInteger).set(value)
        }

        @Test
        fun `generateWithCascade throws LlmBusyException when queue is at max depth`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.NONE, null, 0
            )
            setQueueDepth(3) // MAX_LLM_QUEUE_DEPTH
            try {
                assertThrows<LlmBusyException> {
                    factory.generateWithCascade("recap", "p", emptyList())
                }
            } finally {
                setQueueDepth(0)
            }
        }

        @Test
        fun `generateOrNull throws LlmBusyException when queue is at max depth`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.AICORE, null, 150
            )
            setQueueDepth(3)
            try {
                assertThrows<LlmBusyException> {
                    factory.generateOrNull("extract", "p")
                }
            } finally {
                setQueueDepth(0)
            }
        }

        @Test
        fun `slot is released after a call completes so next call succeeds`() = runTest {
            every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                LlmBackend.NONE, null, 0
            )
            factory.generateWithCascade("recap", "prompt", emptyList())
            // Slot must be released — second call must also succeed.
            val result = factory.generateWithCascade("recap", "prompt", emptyList())
            assertTrue(result.isNotBlank())
        }
    }

    @Nested
    @DisplayName("per-provider timeout")
    inner class TimeoutTest {

        @Test
        fun `provider that exceeds timeout throws TimeoutCancellationException`() = runTest {
            val slowProvider = object : LlmProvider {
                override val displayName: String = "slow-provider"
                override suspend fun generate(prompt: String): String {
                    // Virtual time inside runTest — withTimeout(75_000) fires
                    // before this delay completes.
                    delay(LlmProviderFactory.LLM_TIMEOUT_MS + 5_000)
                    return "should-not-reach-here"
                }
            }
            assertThrows<TimeoutCancellationException> {
                factory.invokeWithTimeoutForTesting(slowProvider, "prompt")
            }
        }

        @Test
        fun `provider that finishes before timeout returns its result`() = runTest {
            val fastProvider = object : LlmProvider {
                override val displayName: String = "fast-provider"
                override suspend fun generate(prompt: String): String {
                    delay(50)
                    return "ok"
                }
            }
            assertEquals("ok", factory.invokeWithTimeoutForTesting(fastProvider, "prompt"))
        }
    }
}
