package com.justb81.watchbuddy.phone.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LLM provider that sends prompts to a remote Ollama server via HTTP POST.
 *
 * Expected Ollama endpoint: POST /api/generate
 * The server URL is user-configurable (e.g. http://192.168.1.100:11434).
 *
 * Uses the shared [OkHttpClient] for connection pooling, interceptors, and logging.
 * Ollama-specific timeouts are applied via [OkHttpClient.newBuilder] without mutating
 * the shared instance.
 */
class RemoteOllamaProvider(
    private val serverUrl: String,
    private val httpClient: OkHttpClient,
    private val model: String = "gemma3:4b"
) : LlmProvider {

    override val displayName: String = "Ollama ($model @ $serverUrl)"

    @Serializable
    private data class OllamaRequest(
        val model: String,
        val prompt: String,
        val stream: Boolean = false
    )

    @Serializable
    private data class OllamaResponse(
        val response: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client = httpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // LLM generation can be slow
        .build()

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/api/generate"
        val body = json.encodeToString(OllamaRequest(model, prompt))

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                val error = it.body?.string() ?: "unknown error"
                throw IllegalStateException("Ollama returned HTTP ${it.code}: $error")
            }

            val responseBody = it.body?.string()
                ?: throw IllegalStateException("Ollama returned empty body")
            val parsed = json.decodeFromString<OllamaResponse>(responseBody)
            if (parsed.response.isBlank()) {
                throw IllegalStateException("Ollama returned empty response")
            }
            parsed.response
        }
    }
}
