package com.justb81.watchbuddy.core.logging

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("CrashReporter — idempotent install and handler chaining (#575)")
class CrashReporterTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var fakeContext: Context

    @BeforeEach
    fun setUp() {
        CrashReporter.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler(null)

        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.filesDir } returns tempDir
        every { ctx.packageName } returns "com.test"
        fakeContext = ctx
    }

    @AfterEach
    fun tearDown() {
        CrashReporter.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    @Test
    fun `install registers an uncaught exception handler`() {
        assertNull(Thread.getDefaultUncaughtExceptionHandler())

        CrashReporter.install(fakeContext)

        assertNotNull(Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `install is idempotent — second call does not replace the registered handler`() {
        CrashReporter.install(fakeContext)
        val firstHandler = Thread.getDefaultUncaughtExceptionHandler()

        CrashReporter.install(fakeContext)
        val secondHandler = Thread.getDefaultUncaughtExceptionHandler()

        assertSame(firstHandler, secondHandler)
    }

    @Test
    fun `handler chains to previously installed handler`() {
        val previousCalled = AtomicBoolean(false)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousCalled.set(true) }

        CrashReporter.install(fakeContext)

        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        handler.uncaughtException(Thread.currentThread(), RuntimeException("test crash"))

        assertTrue(previousCalled.get())
    }

    @Test
    fun `double install does not duplicate handler chain — previous called exactly once`() {
        val callCount = AtomicInteger(0)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> callCount.incrementAndGet() }

        CrashReporter.install(fakeContext)
        CrashReporter.install(fakeContext)

        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        handler.uncaughtException(Thread.currentThread(), RuntimeException("test crash"))

        assertEquals(1, callCount.get())
    }

    @Test
    fun `handler completes gracefully when no previous handler is installed`() {
        CrashReporter.install(fakeContext)

        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        var threw = false
        try {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("test crash"))
        } catch (_: Throwable) {
            threw = true
        }

        assertTrue(!threw, "Handler should not throw when no previous handler is set")
    }

    @Test
    fun `second install after reset re-registers a new handler`() {
        CrashReporter.install(fakeContext)
        val firstHandler = Thread.getDefaultUncaughtExceptionHandler()

        CrashReporter.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler(null)

        CrashReporter.install(fakeContext)
        val secondHandler = Thread.getDefaultUncaughtExceptionHandler()

        assertNotNull(firstHandler)
        assertNotNull(secondHandler)
    }
}
