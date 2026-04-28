package com.justb81.watchbuddy.phone.llm

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM provider backed by LiteRT-LM with a local Gemma model (.litertlm format).
 *
 * The model file (e.g. gemma-4-E2B-it.litertlm) must be present in the app's
 * internal files directory before calling [generate]. Model download is handled
 * separately by WorkManager (see SettingsViewModel.downloadModel).
 *
 * LiteRT-LM's GPU backend needs an OpenCL runtime that not every Android device
 * ships. Rather than probing upfront, we try GPU first and fall back to CPU on
 * any failure — including failures that only surface inside the JNI call for
 * `sendMessage` (#464: "Can not find OpenCL library on this device"). The
 * outcome is latched in [gpuKnownBad] for the remainder of the process so
 * subsequent recaps and scrobble extractions skip the GPU path immediately.
 */
class LiteRtLlmProvider internal constructor(
    private val context: Context,
    private val modelVariant: LlmOrchestrator.ModelVariant,
    private val engineFactory: EngineFactory,
) : LlmProvider {

    // Public production constructor — wires the real Engine factory. The
    // engineFactory-taking primary constructor is `internal` so it can expose
    // the `internal` [EngineFactory] type (Kotlin forbids that on public API).
    constructor(
        context: Context,
        modelVariant: LlmOrchestrator.ModelVariant,
    ) : this(context, modelVariant, DefaultEngineFactory)

    /**
     * Test seam: production wires [DefaultEngineFactory] which creates a real
     * LiteRT-LM [Engine] plus a per-call [com.google.ai.edge.litertlm.Conversation].
     * Unit tests supply a pure-Kotlin fake so the JNI-backed LiteRT-LM classes
     * never have to load in the JVM test classpath.
     *
     * Parameters are plain Kotlin types (no LiteRT-LM imports) so the seam is
     * safe to reference from JVM-only unit tests without risking native-library
     * class-load failures during JUnit discovery.
     */
    internal fun interface EngineFactory {
        fun create(modelPath: String, useGpu: Boolean): EngineHandle
    }

    /** Opaque handle over an initialized LiteRT-LM engine. */
    internal interface EngineHandle {
        fun sendMessage(prompt: String): String
        fun close() {}
    }

    override val displayName: String = "LiteRT-LM (${modelVariant.fileName})"

    private var handle: EngineHandle? = null
    private var currentBackendIsGpu: Boolean = false

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val text = try {
            getOrCreateHandle().sendMessage(prompt)
        } catch (e: Exception) {
            if (!currentBackendIsGpu) throw e
            markGpuBadAndLog("inference", e)
            runCatching { handle?.close() }
            handle = null
            currentBackendIsGpu = false
            getOrCreateHandle().sendMessage(prompt)
        }
        if (text.isBlank()) {
            throw IllegalStateException("LiteRT-LM returned empty response")
        }
        text
    }

    private fun getOrCreateHandle(): EngineHandle {
        handle?.let { return it }
        val modelPath = resolveModelPath()
        val newHandle = if (gpuKnownBad.get()) {
            createHandle(modelPath, useGpu = false).also { currentBackendIsGpu = false }
        } else {
            try {
                createHandle(modelPath, useGpu = true).also { currentBackendIsGpu = true }
            } catch (e: Exception) {
                markGpuBadAndLog("init", e)
                createHandle(modelPath, useGpu = false).also { currentBackendIsGpu = false }
            }
        }
        handle = newHandle
        return newHandle
    }

    private fun createHandle(modelPath: String, useGpu: Boolean): EngineHandle =
        engineFactory.create(modelPath, useGpu)

    private fun resolveModelPath(): String {
        val modelDir = File(context.filesDir, "llm_models")
        val modelPath = File(modelDir, modelVariant.fileName).absolutePath
        if (!File(modelPath).exists()) {
            throw IllegalStateException(
                "Model file not found: ${modelVariant.fileName}. Download it first via Settings."
            )
        }
        return modelPath
    }

    private fun markGpuBadAndLog(phase: String, e: Throwable) {
        if (gpuKnownBad.compareAndSet(false, true)) {
            Log.w(TAG, "GPU backend unavailable ($phase), falling back to CPU", e)
            DiagnosticLog.warn(TAG, "gpu-unavailable phase=$phase: ${e.javaClass.simpleName}", e)
        } else {
            Log.d(TAG, "GPU backend unavailable ($phase), using CPU (already known)")
        }
    }

    private object DefaultEngineFactory : EngineFactory {
        override fun create(modelPath: String, useGpu: Boolean): EngineHandle {
            val backend = if (useGpu) Backend.GPU() else Backend.CPU()
            val config = EngineConfig(modelPath = modelPath, backend = backend)
            val engine = Engine(config).also { it.initialize() }
            return RealEngineHandle(engine)
        }
    }

    private class RealEngineHandle(private val engine: Engine) : EngineHandle {
        override fun sendMessage(prompt: String): String {
            val conversation = engine.createConversation(ConversationConfig())
            return try {
                conversation.sendMessage(prompt).toString()
            } finally {
                conversation.close()
            }
        }
    }

    companion object {
        private const val TAG = "LiteRtLlmProvider"

        // Process-wide latch: once GPU inference fails on this device we skip
        // the GPU path for all subsequent [LiteRtLlmProvider] instances in
        // this process. The cascade builds a fresh provider per call, so
        // per-instance state can't carry the knowledge.
        private val gpuKnownBad = AtomicBoolean(false)

        @VisibleForTesting
        internal fun resetGpuKnownBadForTesting() {
            gpuKnownBad.set(false)
        }

        @VisibleForTesting
        internal fun isGpuKnownBadForTesting(): Boolean = gpuKnownBad.get()
    }
}
