package com.justb81.watchbuddy.phone.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user-supplied custom avatar on disk as a downscaled JPEG.
 *
 * The picker hands us an arbitrary content URI; we decode once, downscale to
 * a max of 256×256 while preserving aspect ratio, and write to an internal
 * file via a temp-file + rename so half-written bytes can never be served
 * by the `/avatar` route running in the companion HTTP server.
 *
 * Output is capped at [MAX_OUTPUT_BYTES]. If the first JPEG encode overshoots,
 * the compressor retries with progressively lower qualities, and as a last
 * resort, halves the pixel dimensions before retrying again.
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
        private const val JPEG_QUALITY_MED = 70
        private const val JPEG_QUALITY_LOW = 55
        private const val JPEG_QUALITY_MIN = 40
        private const val MAX_INPUT_BYTES = 10L * 1024 * 1024 // 10 MB
        internal const val MAX_OUTPUT_BYTES = 200 * 1024 // 200 KB
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
     * Rejects inputs whose reported MIME type is not an image type.
     * Caps the output file at [MAX_OUTPUT_BYTES], retrying with lower quality
     * and smaller dimensions before failing.
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

        // Reject obviously non-image content (PDF, APK, etc.) identified by bounds decode.
        val mimeType = boundsOpts.outMimeType
        if (!mimeType.isNullOrEmpty() && !mimeType.startsWith("image/")) {
            DiagnosticLog.warn(TAG, "writeFromUri: rejected mime=$mimeType")
            return@withContext Result.Failed("invalid_mime")
        }

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
        val writeOk = compressWithSizeCap(scaled, tmp)
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

    /**
     * Compresses [bitmap] as JPEG into [dest], retrying at progressively lower
     * qualities until the output fits within [MAX_OUTPUT_BYTES]. Falls back to
     * half-pixel dimensions if quality reduction alone is not enough.
     * Returns true on success, false if all attempts exceed the cap or if
     * encoding or writing fails.
     */
    private fun compressWithSizeCap(bitmap: Bitmap, dest: File): Boolean {
        val qualitySteps = intArrayOf(JPEG_QUALITY, JPEG_QUALITY_MED, JPEG_QUALITY_LOW, JPEG_QUALITY_MIN)
        for (quality in qualitySteps) {
            val bytes = tryCompressToBytes(bitmap, quality) ?: continue
            if (bytes.size <= MAX_OUTPUT_BYTES) {
                return runCatching { dest.writeBytes(bytes) }.isSuccess
            }
            DiagnosticLog.warn(TAG, "compressWithSizeCap: quality=$quality → ${bytes.size}B, retrying")
        }

        // Still too large after quality reduction — scale to half dimensions and retry.
        val halfW = (bitmap.width / 2).coerceAtLeast(1)
        val halfH = (bitmap.height / 2).coerceAtLeast(1)
        val smaller = bitmap.scale(halfW, halfH)
        for (quality in intArrayOf(JPEG_QUALITY_LOW, JPEG_QUALITY_MIN)) {
            val bytes = tryCompressToBytes(smaller, quality)
            if (bytes != null && bytes.size <= MAX_OUTPUT_BYTES) {
                smaller.recycle()
                return runCatching { dest.writeBytes(bytes) }.isSuccess
            }
        }
        smaller.recycle()
        DiagnosticLog.warn(TAG, "compressWithSizeCap: all retries exhausted for ${bitmap.width}×${bitmap.height}")
        return false
    }

    /** Returns the JPEG-encoded bytes, or null if [Bitmap.compress] reports failure. */
    private fun tryCompressToBytes(bitmap: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
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
        return src.scale(w, h)
    }
}
