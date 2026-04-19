package com.justb81.watchbuddy.tv.boot

import android.content.Context
import android.content.Intent
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BootReceiver.handleBootIntent].
 *
 * Tests call the companion-object function directly to avoid triggering Hilt's
 * injection machinery (which requires an Application context) and to work around
 * Android stub limitations (Intent constructors don't store fields under
 * isReturnDefaultValues=true).
 */
@DisplayName("BootReceiver")
class BootReceiverTest {

    private val repository: StreamingPreferencesRepository = mockk()
    private val context: Context = mockk(relaxed = true)

    private val bootIntent: Intent = mockk<Intent>().also {
        every { it.action } returns Intent.ACTION_BOOT_COMPLETED
    }
    private val otherIntent: Intent = mockk<Intent>().also {
        every { it.action } returns Intent.ACTION_PACKAGE_ADDED
    }

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        mockkStatic(androidx.core.content.ContextCompat::class)
        every { androidx.core.content.ContextCompat.startForegroundService(any(), any()) } returns mockk()
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
        unmockkStatic(androidx.core.content.ContextCompat::class)
    }

    @Test
    fun `does nothing when autostart is disabled`() {
        every { repository.isAutostartEnabled } returns flowOf(false)

        BootReceiver.handleBootIntent(context, bootIntent, repository)

        verify(exactly = 0) {
            androidx.core.content.ContextCompat.startForegroundService(any(), any())
        }
    }

    @Test
    fun `ignores intents that are not BOOT_COMPLETED`() {
        every { repository.isAutostartEnabled } returns flowOf(true)

        BootReceiver.handleBootIntent(context, otherIntent, repository)

        verify(exactly = 0) {
            androidx.core.content.ContextCompat.startForegroundService(any(), any())
        }
    }

    @Test
    fun `starts TvDiscoveryService when autostart is enabled`() {
        every { repository.isAutostartEnabled } returns flowOf(true)

        BootReceiver.handleBootIntent(context, bootIntent, repository)

        verify(exactly = 1) {
            androidx.core.content.ContextCompat.startForegroundService(context, any())
        }
    }

    @Test
    fun `emits DiagnosticLog breadcrumb when service start fails`() {
        every { repository.isAutostartEnabled } returns flowOf(true)
        every {
            androidx.core.content.ContextCompat.startForegroundService(any(), any())
        } throws SecurityException("background start restricted")

        BootReceiver.handleBootIntent(context, bootIntent, repository)

        val events = DiagnosticLog.snapshot()
        assertTrue(
            events.any { it.tag == "tv.boot.autostart.failed" },
            "Expected tv.boot.autostart.failed breadcrumb in DiagnosticLog"
        )
    }
}
