package com.justb81.watchbuddy.phone.llm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val httpClient: OkHttpClient
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelUrl = inputData.getString(KEY_MODEL_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No model URL provided"))

        val outputDir = settingsRepository.modelDir()
        val outputFile = File(outputDir, "model.bin")
        val tempFile = File(outputDir, "model.bin.tmp")

        try {
            downloadFile(modelUrl, tempFile)
            tempFile.renameTo(outputFile)
            settingsRepository.setModelReady(true)
            setProgress(workDataOf(KEY_PROGRESS to 100))
            return Result.success(workDataOf(KEY_PROGRESS to 100))
        } catch (e: Exception) {
            Log.e(TAG, "Download failed (attempt $runAttemptCount)", e)
            tempFile.delete()
            return if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
            }
        }
    }

    private suspend fun downloadFile(url: String, target: File) {
        val client = httpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw RuntimeException("Empty response body")
        val contentLength = body.contentLength()

        body.byteStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Long = 0
                var lastReportedProgress = -1

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesRead += read

                    if (contentLength > 0) {
                        val progress = ((bytesRead * 100) / contentLength).toInt()
                            .coerceIn(0, 99)
                        if (progress != lastReportedProgress) {
                            setProgress(workDataOf(KEY_PROGRESS to progress))
                            lastReportedProgress = progress
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_MODEL_URL = "model_url"
        const val KEY_PROGRESS = "download_progress"
        const val KEY_ERROR = "download_error"
        const val UNIQUE_WORK_NAME = "llm_model_download"
        private const val TAG = "ModelDownloadWorker"
        private const val MAX_RETRIES = 3
        private const val BUFFER_SIZE = 8 * 1024
    }
}
