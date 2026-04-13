package com.justb81.watchbuddy.phone.llm

import android.content.Context
import android.util.Log
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.TmdbEpisode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates [LlmProvider] instances based on [LlmOrchestrator.selectConfig] and
 * implements a cascade fallback:  AICore -> LiteRT-LM -> TMDB Fallback.
 *
 * Each provider is attempted in order. If a provider throws, the next one is tried.
 */
@Singleton
class LlmProviderFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val llmOrchestrator: LlmOrchestrator
) {
    companion object {
        private const val TAG = "LlmProviderFactory"
    }

    /**
     * Runs inference with cascade fallback.
     *
     * @param prompt       The LLM prompt
     * @param episodes     Watched episodes (used by FallbackProvider)
     * @return Generated text from the first provider that succeeds
     */
    suspend fun generateWithCascade(
        prompt: String,
        episodes: List<TmdbEpisode>
    ): String {
        val config = llmOrchestrator.selectConfig()
        val providers = buildProviderCascade(config, episodes)

        for (provider in providers) {
            try {
                Log.d(TAG, "Trying provider: ${provider.displayName}")
                val result = provider.generate(prompt)
                Log.d(TAG, "Success with provider: ${provider.displayName}")
                return result
            } catch (e: Exception) {
                Log.w(TAG, "Provider ${provider.displayName} failed: ${e.message}")
            }
        }

        // All providers failed — return minimal fallback
        Log.e(TAG, "All LLM providers failed, returning empty fallback")
        return buildMinimalFallback()
    }

    private fun buildProviderCascade(
        config: LlmOrchestrator.LlmConfig,
        episodes: List<TmdbEpisode>
    ): List<LlmProvider> {
        val providers = mutableListOf<LlmProvider>()

        when (config.backend) {
            LlmBackend.AICORE -> {
                providers += AiCoreLlmProvider(context)
                // Fall through to LiteRT-LM if AICore fails
                config.modelVariant?.let {
                    providers += LiteRtLlmProvider(context, it)
                }
            }
            LlmBackend.LITERT -> {
                config.modelVariant?.let {
                    providers += LiteRtLlmProvider(context, it)
                }
            }
            LlmBackend.NONE -> { /* skip on-device, go straight to fallback */ }
        }

        // TMDB synopsis fallback is always last
        providers += FallbackProvider(episodes)

        return providers
    }

    private fun buildMinimalFallback(): String = """
        <div style="display:flex;align-items:center;justify-content:center;height:100%;color:white;font-family:sans-serif;">
          <p>Could not generate recap.</p>
        </div>
    """.trimIndent()
}
