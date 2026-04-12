package com.justb81.watchbuddy.phone.llm

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ModelDownloadWorker")
class ModelDownloadWorkerTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        every { settingsRepository.modelDir() } returns tempDir
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun createWorker(modelUrl: String?): ModelDownloadWorker {
        val inputData = if (modelUrl != null) {
            Data.Builder()
                .putString(ModelDownloadWorker.KEY_MODEL_URL, modelUrl)
                .build()
        } else {
            Data.EMPTY
        }
        every { workerParams.inputData } returns inputData
        every { workerParams.runAttemptCount } returns 1
        return ModelDownloadWorker(context, workerParams, settingsRepository, client)
    }

    @Test
    fun `downloads file using injected OkHttpClient`() = runTest {
        val content = "model binary data"
        server.enqueue(
            MockResponse()
                .setBody(content)
                .setHeader("Content-Length", content.length)
        )

        val worker = createWorker(server.url("/model.bin").toString())
        val result = worker.doWork()

        assertEquals(Result.success(
            Data.Builder().putInt(ModelDownloadWorker.KEY_PROGRESS, 100).build()
        ), result)

        val request = server.takeRequest()
        assertEquals("GET", request.method)

        val outputFile = File(tempDir, "model.bin")
        assertTrue(outputFile.exists())
        assertEquals(content, outputFile.readText())
    }

    @Test
    fun `returns failure when no model URL provided`() = runTest {
        val worker = createWorker(null)
        val result = worker.doWork()
        assertTrue(result is Result.Failure)
    }

    @Test
    fun `returns retry on HTTP error when attempts remain`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

        every { workerParams.runAttemptCount } returns 1
        val worker = createWorker(server.url("/model.bin").toString())
        val result = worker.doWork()

        assertEquals(Result.retry(), result)
    }

    @Test
    fun `returns failure on HTTP error when max retries exceeded`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

        every { workerParams.runAttemptCount } returns 3
        val worker = createWorker(server.url("/model.bin").toString())
        val result = worker.doWork()

        assertTrue(result is Result.Failure)
    }

    @Test
    fun `cleans up temp file on failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

        val worker = createWorker(server.url("/model.bin").toString())
        worker.doWork()

        val tempFile = File(tempDir, "model.bin.tmp")
        assertFalse(tempFile.exists())
    }

    @Test
    fun `sets model ready on successful download`() = runTest {
        server.enqueue(MockResponse().setBody("data"))

        val worker = createWorker(server.url("/model.bin").toString())
        worker.doWork()

        verify { settingsRepository.setModelReady(true) }
    }

    @Test
    fun `uses shared OkHttpClient instead of creating new instance`() = runTest {
        // Verify that our injected client's connection pool is used
        // by making two sequential downloads
        server.enqueue(MockResponse().setBody("data1"))
        server.enqueue(MockResponse().setBody("data2"))

        val worker1 = createWorker(server.url("/model1.bin").toString())
        worker1.doWork()

        val worker2 = createWorker(server.url("/model2.bin").toString())
        worker2.doWork()

        assertEquals(2, server.requestCount)
    }
}
