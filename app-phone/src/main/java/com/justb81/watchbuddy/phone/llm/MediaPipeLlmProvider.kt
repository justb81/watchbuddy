package com.justb81.watchbuddy.phone.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM provider backed by MediaPipe LLM Inference API with a local Gemma model.
 *
 * The model file (e.g. gemma-4-e2b-it-int4.task) must be present in the app's
 * internal files directory before calling [generate]. Model download is handled
 * separately by WorkManager (see SettingsViewModel.downloadModel).
 */
class MediaPipeLlmProvider(
    private val context: Context,
    private val modelVariant: LlmOrchestrator.ModelVariant
) : LlmProvider {

    override val displayName: String = "MediaPipe (${modelVariant.fileName})"

    private var inference: LlmInference? = null

    private fun getOrCreateInference(): LlmInference {
        inference?.let { return it }

        val modelPath = File(context.filesDir, modelVariant.fileName).absolutePath
        if (!File(modelPath).exists()) {
            throw IllegalStateException(
                "Model file not found: ${modelVariant.fileName}. Download it first via Settings."
            )
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .build()

        return LlmInference.createFromOptions(context, options).also { inference = it }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val llm = getOrCreateInference()
        val result = llm.generateResponse(prompt)
        if (result.isNullOrBlank()) {
            throw IllegalStateException("MediaPipe returned empty response")
        }
        result
    }
}
