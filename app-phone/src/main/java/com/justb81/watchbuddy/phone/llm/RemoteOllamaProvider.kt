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

/**
 * LLM provider that sends prompts to a remote Ollama server via HTTP POST.
 *
 * Expected Ollama endpoint: POST /api/generate
 * The server URL is user-configurable (e.g. http://192.168.1.100:11434).
 */
class RemoteOllamaProvider(
    private val serverUrl: String,
    private val model: String = "gemma3:4b",
    private val httpClient: OkHttpClient
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

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/api/generate"
        val body = json.encodeToString(OllamaRequest(model, prompt))

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val ollamaClient = httpClient.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = ollamaClient.newCall(request).execute()
        val code = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()

        if (code != 200) {
            throw IllegalStateException("Ollama returned HTTP $code: $responseBody")
        }
        if (responseBody.isEmpty()) {
            throw IllegalStateException("Empty response body")
        }

        val parsed = json.decodeFromString<OllamaResponse>(responseBody)
        if (parsed.response.isBlank()) {
            throw IllegalStateException("Ollama returned empty response")
        }
        parsed.response
    }
}
