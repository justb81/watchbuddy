package com.justb81.watchbuddy.phone.settings

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import io.mockk.answers
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream

@DisplayName("AvatarImageStore")
class AvatarImageStoreTest {

    @TempDir
    lateinit var filesDir: File

    private val context: Context = mockk()
    private val contentResolver: ContentResolver = mockk()
    private val uri: Uri = mockk()
    private lateinit var store: AvatarImageStore

    @BeforeEach
    fun setUp() {
        every { context.contentResolver } returns contentResolver
        every { context.filesDir } returns filesDir
        // Default: size unknown (provider returns null descriptor) — always allowed.
        every { contentResolver.openAssetFileDescriptor(uri, "r") } returns null
        mockkStatic(BitmapFactory::class)
        store = AvatarImageStore(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(BitmapFactory::class)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stubs both BitmapFactory.decodeStream calls: the first (bounds-only)
     * writes [mimeType], [width], [height] to Options and returns null; the
     * second (full decode) returns [decodedBitmap].
     */
    private fun stubBoundsAndDecode(
        mimeType: String?,
        width: Int = 100,
        height: Int = 100,
        decodedBitmap: Bitmap? = null,
    ) {
        var callCount = 0
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(ByteArray(64)) }
        every { BitmapFactory.decodeStream(any(), null, any<BitmapFactory.Options>()) } answers {
            val opts = arg<BitmapFactory.Options>(2)
            callCount++
            if (callCount == 1) {
                opts.outWidth = width
                opts.outHeight = height
                opts.outMimeType = mimeType
                null
            } else {
                opts.outWidth = width
                opts.outHeight = height
                decodedBitmap
            }
        }
    }

    /** Returns a mock Bitmap that writes [outputBytes] for every compress call. */
    private fun mockBitmapWithOutput(outputBytes: Int, width: Int = 100, height: Int = 100): Bitmap {
        val bmp = mockk<Bitmap>(relaxed = true)
        every { bmp.width } returns width
        every { bmp.height } returns height
        every { bmp.compress(any(), any(), any()) } answers {
            val out = arg<OutputStream>(2)
            out.write(ByteArray(outputBytes))
            true
        }
        return bmp
    }

    // ── Input size guard ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("input size guard")
    inner class InputSizeGuard {

        @Test
        fun `rejects input larger than 10 MB`() = runTest {
            val fd = mockk<AssetFileDescriptor>(relaxed = true)
            every { fd.length } returns 11L * 1024 * 1024
            every { fd.close() } just runs
            every { contentResolver.openAssetFileDescriptor(uri, "r") } returns fd

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("too_large"), result)
        }

        @Test
        fun `allows input of exactly 10 MB`() = runTest {
            val fd = mockk<AssetFileDescriptor>(relaxed = true)
            every { fd.length } returns 10L * 1024 * 1024
            every { fd.close() } just runs
            every { contentResolver.openAssetFileDescriptor(uri, "r") } returns fd
            val bmp = mockBitmapWithOutput(512)
            stubBoundsAndDecode("image/jpeg", decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
        }
    }

    // ── MIME type validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("MIME type validation")
    inner class MimeTypeValidation {

        @Test
        fun `rejects application-pdf content`() = runTest {
            stubBoundsAndDecode(mimeType = "application/pdf")

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("invalid_mime"), result)
        }

        @Test
        fun `rejects application-octet-stream content`() = runTest {
            stubBoundsAndDecode(mimeType = "application/octet-stream")

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("invalid_mime"), result)
        }

        @Test
        fun `accepts image-jpeg`() = runTest {
            val bmp = mockBitmapWithOutput(1024)
            stubBoundsAndDecode("image/jpeg", decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
        }

        @Test
        fun `accepts image-png`() = runTest {
            val bmp = mockBitmapWithOutput(1024)
            stubBoundsAndDecode("image/png", decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
        }

        @Test
        fun `accepts image-webp`() = runTest {
            val bmp = mockBitmapWithOutput(512)
            stubBoundsAndDecode("image/webp", decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
        }

        @Test
        fun `allows null MIME type (unknown format, proceed to full decode)`() = runTest {
            val bmp = mockBitmapWithOutput(1024)
            stubBoundsAndDecode(mimeType = null, decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
        }

        @Test
        fun `allows empty MIME type (proceed to full decode)`() = runTest {
            val bmp = mockBitmapWithOutput(512)
            stubBoundsAndDecode(mimeType = "", decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
        }
    }

    // ── Dimension validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("dimension validation")
    inner class DimensionValidation {

        @Test
        fun `rejects when outWidth is zero`() = runTest {
            stubBoundsAndDecode(mimeType = "image/jpeg", width = 0, height = 100)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("unreadable"), result)
        }

        @Test
        fun `rejects when outHeight is zero`() = runTest {
            stubBoundsAndDecode(mimeType = "image/jpeg", width = 100, height = 0)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("unreadable"), result)
        }

        @Test
        fun `rejects when both dimensions are negative`() = runTest {
            stubBoundsAndDecode(mimeType = "image/jpeg", width = -1, height = -1)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("unreadable"), result)
        }
    }

    // ── Output size cap ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("output size cap")
    inner class OutputSizeCap {

        @Test
        fun `succeeds when first compress is within 200 KB`() = runTest {
            val bmp = mockBitmapWithOutput(100 * 1024) // 100 KB
            stubBoundsAndDecode("image/jpeg", decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
        }

        @Test
        fun `retries with lower quality when first compress exceeds 200 KB`() = runTest {
            var compressCallCount = 0
            val bmp = mockk<Bitmap>(relaxed = true)
            every { bmp.width } returns 256
            every { bmp.height } returns 256
            every { bmp.compress(any(), any(), any()) } answers {
                val out = arg<OutputStream>(2)
                compressCallCount++
                // First compress returns 250 KB (too large), subsequent return 80 KB (ok).
                // The second quality step (70) should succeed so scale is never reached.
                if (compressCallCount == 1) out.write(ByteArray(250 * 1024))
                else out.write(ByteArray(80 * 1024))
                true
            }
            stubBoundsAndDecode("image/jpeg", width = 256, height = 256, decodedBitmap = bmp)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Ok, result)
            assertTrue(compressCallCount > 1, "Expected retry after oversized first compress")
        }

        @Test
        fun `output file does not exceed 200 KB`() = runTest {
            val bmp = mockBitmapWithOutput(150 * 1024) // 150 KB — within cap
            stubBoundsAndDecode("image/jpeg", decodedBitmap = bmp)

            store.writeFromUri(uri)

            val avatarFile = File(filesDir, "avatar.jpg")
            assertTrue(avatarFile.exists())
            assertTrue(avatarFile.length() <= AvatarImageStore.MAX_OUTPUT_BYTES)
        }

        @Test
        fun `returns Failed when all compress retries exceed 200 KB`() = runTest {
            // Bitmap.scale is a Kotlin extension function (BitmapKt static) — must use
            // mockkStatic to intercept it; instance mocking silently ignores it.
            mockkStatic("androidx.core.graphics.BitmapKt")
            try {
                val bmp = mockk<Bitmap>(relaxed = true)
                every { bmp.width } returns 256
                every { bmp.height } returns 256
                every { bmp.compress(any(), any(), any()) } answers {
                    val out = arg<OutputStream>(2)
                    out.write(ByteArray(300 * 1024)) // 300 KB — always over the 200 KB cap
                    true
                }

                // Mock the scale extension function to return a smaller bitmap that also
                // compresses to 300 KB, so even the half-dimension retry path fails.
                val smaller = mockk<Bitmap>(relaxed = true)
                every { smaller.width } returns 128
                every { smaller.height } returns 128
                every { smaller.compress(any(), any(), any()) } answers {
                    val out = arg<OutputStream>(2)
                    out.write(ByteArray(300 * 1024))
                    true
                }
                every { bmp.scale(any<Int>(), any<Int>()) } returns smaller

                stubBoundsAndDecode("image/jpeg", width = 256, height = 256, decodedBitmap = bmp)

                val result = store.writeFromUri(uri)

                assertEquals(AvatarImageStore.Result.Failed("write"), result)
            } finally {
                unmockkStatic("androidx.core.graphics.BitmapKt")
            }
        }
    }

    // ── Decode failure ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("decode failure")
    inner class DecodeFailure {

        @Test
        fun `returns Failed decode_bounds when bounds decode throws`() = runTest {
            every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(ByteArray(64)) }
            every { BitmapFactory.decodeStream(any(), null, any<BitmapFactory.Options>()) } throws
                RuntimeException("corrupt image")

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("decode_bounds"), result)
        }

        @Test
        fun `returns Failed decode when full bitmap decode returns null`() = runTest {
            stubBoundsAndDecode("image/jpeg", decodedBitmap = null)

            val result = store.writeFromUri(uri)

            assertEquals(AvatarImageStore.Result.Failed("decode"), result)
        }
    }

    // ── File management ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("file management")
    inner class FileManagement {

        @Test
        fun `exists returns false when no file present`() {
            assertFalse(store.exists())
        }

        @Test
        fun `exists returns true after successful write`() = runTest {
            val bmp = mockBitmapWithOutput(1024)
            stubBoundsAndDecode("image/jpeg", decodedBitmap = bmp)

            store.writeFromUri(uri)

            assertTrue(store.exists())
        }

        @Test
        fun `clear removes the stored file`() = runTest {
            val bmp = mockBitmapWithOutput(1024)
            stubBoundsAndDecode("image/jpeg", decodedBitmap = bmp)
            store.writeFromUri(uri)
            assertTrue(store.exists())

            store.clear()

            assertFalse(store.exists())
        }
    }
}
