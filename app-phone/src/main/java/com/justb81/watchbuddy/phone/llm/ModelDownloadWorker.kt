package com.justb81.watchbuddy.phone.llm

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.WatchBuddyPhoneApp
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Named

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    @Named("download") private val downloadClient: OkHttpClient
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelUrl = inputData.getString(KEY_MODEL_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No model URL provided"))
        val modelFileName = inputData.getString(KEY_MODEL_FILENAME) ?: "model.bin"

        setForeground(createForegroundInfo())

        val outputDir = settingsRepository.modelDir()
        val outputFile = File(outputDir, modelFileName)
        val tempFile = File(outputDir, "$modelFileName.tmp")

        try {
            downloadFile(modelUrl, tempFile)
            if (!tempFile.renameTo(outputFile)) {
                throw RuntimeException("Failed to rename downloaded model to final path")
            }
            // Sanity-check: a real .litertlm model is hundreds of MB; anything smaller
            // is almost certainly an HTML error page or a corrupt response.
            if (outputFile.length() < MIN_MODEL_SIZE_BYTES) {
                val size = outputFile.length()
                outputFile.delete()
                return Result.failure(
                    workDataOf(KEY_ERROR to "Validation: model file too small ($size bytes)")
                )
            }
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

    internal fun createForegroundInfo(): ForegroundInfo {
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(applicationContext, WatchBuddyPhoneApp.MODEL_DOWNLOAD_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.model_download_notification_title))
            .setContentText(applicationContext.getString(R.string.model_download_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    private suspend fun downloadFile(url: String, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        downloadClient.newCall(request).execute().use { response ->
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
                        if (isStopped) throw CancellationException("Download cancelled")
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

                    if (contentLength > 0 && bytesRead != contentLength) {
                        throw RuntimeException(
                            "Incomplete download: expected $contentLength bytes, got $bytesRead"
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_MODEL_URL = "model_url"
        const val KEY_MODEL_FILENAME = "model_filename"
        const val KEY_PROGRESS = "download_progress"
        const val KEY_ERROR = "download_error"
        const val UNIQUE_WORK_NAME = "llm_model_download"
        private const val TAG = "ModelDownloadWorker"
        private const val MAX_RETRIES = 3
        private const val BUFFER_SIZE = 8 * 1024
        private const val NOTIFICATION_ID = 2
        private const val MIN_MODEL_SIZE_BYTES = 1_048_576L // 1 MB
    }
}
