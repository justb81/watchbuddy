package com.justb81.watchbuddy.phone.llm

import android.content.Context
import android.util.Log
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates [LlmProvider] instances based on [LlmOrchestrator.selectConfig] and
 * implements a cascade fallback:  AICore -> LiteRT-LM -> TMDB Fallback.
 *
 * Each provider is attempted in order. If a provider throws, the next one is tried.
 *
 * Every invocation is also recorded into [LlmEventLog] (prompt + response,
 * backend, duration, status) so the Diagnostics → LLM Activity screen can
 * surface it. Recording is gated on the user-facing
 * `llmActivityLoggingEnabled` toggle in [com.justb81.watchbuddy.phone.settings.AppSettings].
 * A terse [DiagnosticLog] breadcrumb is also emitted alongside so the shared
 * share-diagnostics snapshot carries an LLM trail without the multi-KB
 * prompts bloating the breadcrumb ring.
 */
@Singleton
class LlmProviderFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val llmOrchestrator: LlmOrchestrator,
    private val llmEventLog: LlmEventLog,
    private val settingsRepository: Lazy<SettingsRepository>
) {
    companion object {
        private const val TAG = "LlmProviderFactory"
        const val CALLER_RECAP = "recap"
        const val CALLER_EXTRACT = "extract"
        private const val BACKEND_FALLBACK = "fallback"
        private const val BACKEND_NONE = "none"
        private const val MAX_ERROR_MESSAGE = 200
    }

    /**
     * Runs inference with cascade fallback.
     *
     * @param caller       Short label used in diagnostics (e.g. [CALLER_RECAP])
     * @param prompt       The LLM prompt
     * @param episodes     Watched episodes (used by FallbackProvider)
     * @return Generated text from the first provider that succeeds
     */
    suspend fun generateWithCascade(
        caller: String,
        prompt: String,
        episodes: List<TmdbEpisode>
    ): String {
        val startedAt = System.currentTimeMillis()
        val loggingEnabled = isLoggingEnabled()
        val config = llmOrchestrator.selectConfig()
        val providers = buildProviderCascade(config, episodes)
        val errors = mutableListOf<String>()

        for (provider in providers) {
            try {
                Log.d(TAG, "Trying provider: ${provider.displayName}")
                val result = provider.generate(prompt)
                Log.d(TAG, "Success with provider: ${provider.displayName}")
                recordEvent(
                    enabled = loggingEnabled,
                    caller = caller,
                    backend = provider.displayName,
                    startedAt = startedAt,
                    prompt = prompt,
                    response = result,
                    status = LlmEventLog.Status.SUCCESS,
                )
                return result
            } catch (e: Exception) {
                Log.w(TAG, "Provider ${provider.displayName} failed: ${e.message}")
                errors += "${provider.displayName}: ${summarize(e)}"
            }
        }

        // All providers failed — return minimal fallback
        Log.e(TAG, "All LLM providers failed, returning empty fallback")
        val fallback = buildMinimalFallback()
        recordEvent(
            enabled = loggingEnabled,
            caller = caller,
            backend = BACKEND_FALLBACK,
            startedAt = startedAt,
            prompt = prompt,
            response = fallback,
            status = LlmEventLog.Status.ERROR,
            errorSummary = errors.joinToString(" | ").ifEmpty { "no providers available" },
        )
        return fallback
    }

    /**
     * On-device cascade for non-recap use-cases (e.g. title extraction). Tries
     * AICore → LiteRT-LM and returns `null` when both fail or no on-device LLM
     * is configured. Intentionally skips [FallbackProvider] (recap-HTML only)
     * so callers can cleanly distinguish "no LLM available" from "LLM
     * succeeded".
     */
    suspend fun generateOrNull(caller: String, prompt: String): String? {
        val startedAt = System.currentTimeMillis()
        val loggingEnabled = isLoggingEnabled()
        val config = llmOrchestrator.selectConfig()
        val providers = buildOnDeviceProviders(config)
        if (providers.isEmpty()) {
            Log.d(TAG, "No on-device LLM available")
            recordEvent(
                enabled = loggingEnabled,
                caller = caller,
                backend = BACKEND_NONE,
                startedAt = startedAt,
                prompt = prompt,
                response = null,
                status = LlmEventLog.Status.EMPTY,
                errorSummary = "no on-device LLM available",
            )
            return null
        }
        val errors = mutableListOf<String>()
        for (provider in providers) {
            try {
                Log.d(TAG, "Trying provider: ${provider.displayName}")
                val result = provider.generate(prompt)
                Log.d(TAG, "Success with provider: ${provider.displayName}")
                recordEvent(
                    enabled = loggingEnabled,
                    caller = caller,
                    backend = provider.displayName,
                    startedAt = startedAt,
                    prompt = prompt,
                    response = result,
                    status = LlmEventLog.Status.SUCCESS,
                )
                return result
            } catch (e: Exception) {
                Log.w(TAG, "Provider ${provider.displayName} failed: ${e.message}")
                errors += "${provider.displayName}: ${summarize(e)}"
            }
        }
        recordEvent(
            enabled = loggingEnabled,
            caller = caller,
            backend = providers.last().displayName,
            startedAt = startedAt,
            prompt = prompt,
            response = null,
            status = LlmEventLog.Status.ERROR,
            errorSummary = errors.joinToString(" | "),
        )
        return null
    }

    private suspend fun isLoggingEnabled(): Boolean = try {
        settingsRepository.get().settings.first().llmActivityLoggingEnabled
    } catch (e: Exception) {
        // DataStore IO failure must never block the LLM call — default to off
        // so we don't accidentally capture prompts the user asked us not to.
        Log.w(TAG, "Failed to read llmActivityLoggingEnabled; defaulting to false", e)
        false
    }

    @Suppress("LongParameterList")
    private fun recordEvent(
        enabled: Boolean,
        caller: String,
        backend: String,
        startedAt: Long,
        prompt: String,
        response: String?,
        status: LlmEventLog.Status,
        errorSummary: String? = null,
    ) {
        if (!enabled) return
        try {
            val duration = System.currentTimeMillis() - startedAt
            val event = llmEventLog.record(
                caller = caller,
                backend = backend,
                startedAtMs = startedAt,
                durationMs = duration,
                prompt = prompt,
                response = response,
                status = status,
                errorSummary = errorSummary,
            )
            DiagnosticLog.event(
                TAG,
                "llm id=${event.id} caller=$caller backend=$backend " +
                    "duration=${duration}ms status=${status.name.lowercase()}",
            )
        } catch (e: Exception) {
            // Diagnostics must never take the app down.
            Log.w(TAG, "Failed to record LLM event", e)
        }
    }

    private fun summarize(t: Throwable): String {
        val msg = t.message?.take(MAX_ERROR_MESSAGE) ?: ""
        return "${t.javaClass.simpleName}: $msg"
    }

    private fun buildOnDeviceProviders(config: LlmOrchestrator.LlmConfig): List<LlmProvider> {
        val providers = mutableListOf<LlmProvider>()
        when (config.backend) {
            LlmBackend.AICORE -> {
                providers += AiCoreLlmProvider(context)
                config.modelVariant?.let { providers += LiteRtLlmProvider(context, it) }
            }
            LlmBackend.LITERT -> {
                config.modelVariant?.let { providers += LiteRtLlmProvider(context, it) }
            }
            LlmBackend.NONE -> { /* no on-device backend */ }
        }
        return providers
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
