package com.justb81.watchbuddy.phone.llm

/**
 * Abstraction for on-device and remote LLM inference backends.
 *
 * Implementations are tried in cascade order (AICore -> LiteRT-LM -> Remote -> Fallback)
 * by [LlmProviderFactory].
 */
interface LlmProvider {
    /** Run inference and return the generated text. Throws on failure. */
    suspend fun generate(prompt: String): String

    /** Human-readable name shown in settings / logs. */
    val displayName: String
}
