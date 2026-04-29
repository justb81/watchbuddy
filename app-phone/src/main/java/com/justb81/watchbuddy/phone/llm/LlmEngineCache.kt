package com.justb81.watchbuddy.phone.llm

import com.google.ai.edge.aicore.GenerativeModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide cache of initialized on-device LLM engines.
 *
 * `LlmProviderFactory.buildProviderCascade(...)` constructs fresh
 * [LiteRtLlmProvider] / [AiCoreLlmProvider] instances on every recap and
 * title-extract call. Without a shared cache each call would pay the full
 * cold-start cost (LiteRT-LM: ~2.4–3.4 GB model load + JNI engine init;
 * AICore: Play Services bind). Holding the engine handle here lets the second
 * call onwards reuse a warm engine, which is what keeps the recap inside the
 * TV-side 90 s `readTimeout` (`PhoneApiClientFactory.kt`).
 *
 * The cache is also the warm-up target: `CompanionService` calls
 * [LlmProviderFactory.warmUp] after `startForeground` so the first user-facing
 * recap is already warm.
 *
 * A single [Mutex] serialises engine creation across recap + title-extraction
 * coroutines so we never double-load the model.
 */
@Singleton
class LlmEngineCache @Inject constructor() {

    private val mutex = Mutex()

    private var liteRtHandle: LiteRtLlmProvider.EngineHandle? = null
    private var liteRtVariant: LlmOrchestrator.ModelVariant? = null
    private var liteRtIsGpu: Boolean = false

    private var aiCoreModel: GenerativeModel? = null

    /**
     * Returns a cached LiteRT-LM handle for [variant]+[useGpu] or creates one
     * via [factory]. If a handle is cached for a different variant or backend
     * (GPU vs. CPU) it is closed before the new one is built.
     *
     * `internal` because [LiteRtLlmProvider.EngineFactory] / [LiteRtLlmProvider.EngineHandle]
     * are themselves `internal` (the JNI-backed engine types must not leak to
     * other modules). Same-module callers — `LiteRtLlmProvider` and the unit
     * tests — can still reach this from the LLM package.
     */
    internal suspend fun getOrCreateLiteRtHandle(
        variant: LlmOrchestrator.ModelVariant,
        useGpu: Boolean,
        modelPath: String,
        factory: LiteRtLlmProvider.EngineFactory,
    ): LiteRtLlmProvider.EngineHandle = mutex.withLock {
        val cached = liteRtHandle
        if (cached != null && liteRtVariant == variant && liteRtIsGpu == useGpu) {
            return@withLock cached
        }
        runCatching { cached?.close() }
        val newHandle = factory.create(modelPath, useGpu)
        liteRtHandle = newHandle
        liteRtVariant = variant
        liteRtIsGpu = useGpu
        newHandle
    }

    /** True when the currently cached LiteRT-LM handle is using the GPU backend. */
    suspend fun isLiteRtGpu(): Boolean = mutex.withLock { liteRtIsGpu && liteRtHandle != null }

    /**
     * Drops the cached LiteRT-LM handle. Used on GPU init/inference failure so
     * the next call can build a fresh CPU handle.
     */
    suspend fun invalidateLiteRtHandle() = mutex.withLock {
        runCatching { liteRtHandle?.close() }
        liteRtHandle = null
        liteRtVariant = null
        liteRtIsGpu = false
    }

    /**
     * Returns the cached AICore [GenerativeModel] or creates one via [factory].
     * AICore's own underlying connection to Play Services is reused so subsequent
     * `generateContent` calls skip the bind cost.
     */
    suspend fun getOrCreateAiCoreModel(factory: () -> GenerativeModel): GenerativeModel =
        mutex.withLock {
            aiCoreModel ?: factory().also { aiCoreModel = it }
        }

    /** Test seam: drop both caches so each test sees a cold engine. */
    internal suspend fun clearForTesting() = mutex.withLock {
        runCatching { liteRtHandle?.close() }
        liteRtHandle = null
        liteRtVariant = null
        liteRtIsGpu = false
        aiCoreModel = null
    }
}
