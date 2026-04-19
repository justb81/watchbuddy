package com.justb81.watchbuddy.phone.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user-supplied custom avatar on disk as a downscaled JPEG.
 *
 * The picker hands us an arbitrary content URI; we decode once, downscale to
 * a max of 256×256 while preserving aspect ratio, and write to an internal
 * file via a temp-file + rename so half-written bytes can never be served
 * by the `/avatar` route running in the companion HTTP server.
 */
@Singleton
class AvatarImageStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AvatarImageStore"
        private const val FILENAME = "avatar.jpg"
        private const val MAX_DIMENSION_PX = 256
        private const val JPEG_QUALITY = 85
        private const val MAX_INPUT_BYTES = 10L * 1024 * 1024 // 10 MB
    }

    sealed interface Result {
        data object Ok : Result
        data class Failed(val reason: String) : Result
    }

    /** Final on-disk location of the custom avatar (may not exist). */
    fun file(): File = File(context.filesDir, FILENAME)

    /** True when [file] exists and is non-empty. */
    fun exists(): Boolean = file().let { it.exists() && it.length() > 0 }

    /**
     * Decodes [uri], downscales in-sample to ≤ [MAX_DIMENSION_PX] on the long
     * edge, and atomically writes the JPEG to [file]. Rejects inputs larger
     * than [MAX_INPUT_BYTES] before decoding to avoid OOM on 50-MP pictures.
     */
    suspend fun writeFromUri(uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (size in 1..MAX_INPUT_BYTES || size == -1L) {
            // proceed — size == -1 means the provider did not report one
        } else {
            DiagnosticLog.warn(TAG, "writeFromUri: rejected ${size}B input > ${MAX_INPUT_BYTES}B")
            return@withContext Result.Failed("too_large")
        }

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        }.onFailure { return@withContext Result.Failed("decode_bounds") }
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return@withContext Result.Failed("unreadable")

        val sampleSize = computeInSampleSize(srcW, srcH, MAX_DIMENSION_PX)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        }.getOrNull() ?: return@withContext Result.Failed("decode")

        val scaled = scaleToFit(decoded, MAX_DIMENSION_PX)
        if (scaled !== decoded) decoded.recycle()

        val tmp = File(context.filesDir, "$FILENAME.tmp")
        val writeOk = runCatching {
            FileOutputStream(tmp).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }
        }.isSuccess
        scaled.recycle()
        if (!writeOk) {
            tmp.delete()
            return@withContext Result.Failed("write")
        }

        val target = file()
        if (!tmp.renameTo(target)) {
            // renameTo can fail if target exists on some FSes; fall back to copy-then-delete.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext Result.Failed("rename")
            }
        }
        Result.Ok
    }

    /** Deletes the stored file. Safe to call when nothing is stored. */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { file().delete() }
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = maxOf(srcW, srcH)
        while (longest / sample > maxEdge * 2) sample *= 2
        return sample
    }

    private fun scaleToFit(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
