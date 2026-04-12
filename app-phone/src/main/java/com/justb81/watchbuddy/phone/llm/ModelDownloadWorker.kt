package com.justb81.watchbuddy.phone.llm

import android.content.Context
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

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelUrl = inputData.getString(KEY_MODEL_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val targetPath = File(applicationContext.filesDir, "llm_models/$fileName")
        targetPath.parentFile?.mkdirs()

        return try {
            downloadWithProgress(modelUrl, targetPath) { progress ->
                setProgress(workDataOf(KEY_PROGRESS to progress))
            }
            settingsRepository.setModelReady(true)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun downloadWithProgress(
        url: String,
        target: File,
        onProgress: suspend (Int) -> Unit
    ) {
        val client = OkHttpClient()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val contentLength = response.body?.contentLength() ?: -1L
        var bytesRead = 0L
        response.body?.byteStream()?.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8 * 1024)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    bytesRead += bytes
                    if (contentLength > 0) {
                        onProgress((bytesRead * 100 / contentLength).toInt())
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_MODEL_URL = "model_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_PROGRESS = "progress"
        const val WORK_TAG = "model_download"
    }
}
