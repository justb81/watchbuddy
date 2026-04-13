package com.justb81.watchbuddy.phone.llm

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ModelDownloadWorker")
class ModelDownloadWorkerTest {

    companion object {
        private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    }

    private lateinit var server: MockWebServer
    private val downloadClient = OkHttpClient()
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

    private fun createWorker(
        modelUrl: String?,
        modelFileName: String = MODEL_FILENAME,
        attemptCount: Int = 1
    ): ModelDownloadWorker {
        val inputData = if (modelUrl != null) {
            Data.Builder()
                .putString(ModelDownloadWorker.KEY_MODEL_URL, modelUrl)
                .putString(ModelDownloadWorker.KEY_MODEL_FILENAME, modelFileName)
                .build()
        } else {
            Data.EMPTY
        }
        every { workerParams.inputData } returns inputData
        every { workerParams.runAttemptCount } returns attemptCount
        val worker = spyk(
            ModelDownloadWorker(context, workerParams, settingsRepository, downloadClient)
        )
        coEvery { worker.setProgress(any()) } just Runs
        return worker
    }

    @Nested
    @DisplayName("Successful downloads")
    inner class SuccessfulDownloads {

        @Test
        fun `downloads file using dedicated download client`() = runTest {
            val content = "model binary data"
            server.enqueue(
                MockResponse()
                    .setBody(content)
                    .setHeader("Content-Length", content.length)
            )

            val worker = createWorker(server.url("/model.bin").toString())
            val result = worker.doWork()

            assertTrue(result is Result.Success)

            val request = server.takeRequest()
            assertEquals("GET", request.method)

            val outputFile = File(tempDir, MODEL_FILENAME)
            assertTrue(outputFile.exists())
            assertEquals(content, outputFile.readText())
        }

        @Test
        fun `sets model ready on successful download`() = runTest {
            server.enqueue(MockResponse().setBody("data"))

            val worker = createWorker(server.url("/model.bin").toString())
            worker.doWork()

            verify { settingsRepository.setModelReady(true) }
        }

        @Test
        fun `does not send Trakt headers on download requests`() = runTest {
            server.enqueue(MockResponse().setBody("data"))

            val worker = createWorker(server.url("/model.bin").toString())
            worker.doWork()

            val request = server.takeRequest()
            assertNull(request.getHeader("trakt-api-version"))
        }

        @Test
        fun `removes temp file after successful rename`() = runTest {
            server.enqueue(MockResponse().setBody("data"))

            val worker = createWorker(server.url("/model.bin").toString())
            worker.doWork()

            val tempFile = File(tempDir, "$MODEL_FILENAME.tmp")
            assertFalse(tempFile.exists())
        }
    }

    @Nested
    @DisplayName("Failure handling")
    inner class FailureHandling {

        @Test
        fun `returns failure when no model URL provided`() = runTest {
            val worker = createWorker(null)
            val result = worker.doWork()
            assertTrue(result is Result.Failure)
        }

        @Test
        fun `returns retry on HTTP error when attempts remain`() = runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

            val worker = createWorker(server.url("/model.bin").toString(), attemptCount = 1)
            val result = worker.doWork()

            assertTrue(result is Result.Retry)
        }

        @Test
        fun `returns failure on HTTP error when max retries exceeded`() = runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

            val worker = createWorker(server.url("/model.bin").toString(), attemptCount = 3)
            val result = worker.doWork()

            assertTrue(result is Result.Failure)
        }

        @Test
        fun `cleans up temp file on failure`() = runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

            val worker = createWorker(server.url("/model.bin").toString())
            worker.doWork()

            val tempFile = File(tempDir, "$MODEL_FILENAME.tmp")
            assertFalse(tempFile.exists())
        }
    }

    @Nested
    @DisplayName("Download integrity")
    inner class DownloadIntegrity {

        @Test
        fun `detects incomplete download when content-length mismatches`() = runTest {
            val fullContent = "complete model data here"
            // Advertise full content-length but disconnect early
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Length", fullContent.length)
                    .setBody("partial")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
            )

            val worker = createWorker(server.url("/model.bin").toString(), attemptCount = 3)
            val result = worker.doWork()

            // Should fail because bytesRead ("partial".length) != contentLength (fullContent.length)
            assertTrue(result is Result.Failure)
        }

        @Test
        fun `succeeds when content-length matches downloaded bytes`() = runTest {
            val content = "exact content"
            server.enqueue(
                MockResponse()
                    .setBody(content)
                    .setHeader("Content-Length", content.length)
            )

            val worker = createWorker(server.url("/model.bin").toString())
            val result = worker.doWork()

            assertTrue(result is Result.Success)
            assertEquals(content, File(tempDir, MODEL_FILENAME).readText())
        }
    }

    @Nested
    @DisplayName("Client isolation")
    inner class ClientIsolation {

        @Test
        fun `uses dedicated download client for multiple workers`() = runTest {
            server.enqueue(MockResponse().setBody("data1"))
            server.enqueue(MockResponse().setBody("data2"))

            val worker1 = createWorker(server.url("/model1.bin").toString())
            worker1.doWork()

            val worker2 = createWorker(server.url("/model2.bin").toString())
            worker2.doWork()

            assertEquals(2, server.requestCount)
        }
    }
}
