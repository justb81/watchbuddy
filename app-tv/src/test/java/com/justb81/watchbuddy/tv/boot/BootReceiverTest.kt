package com.justb81.watchbuddy.tv.boot

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
 * Unit tests for [BootReceiver].
 *
 * Constructs the receiver directly (bypassing Hilt) so the preference guard
 * logic can be tested without a full Android environment.
 */
@DisplayName("BootReceiver")
class BootReceiverTest {

    private val repository: StreamingPreferencesRepository = mockk()
    private lateinit var receiver: BootReceiver

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        receiver = BootReceiver()
        receiver.preferencesRepository = repository
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
        unmockkStatic(androidx.core.content.ContextCompat::class)
    }

    @Test
    fun `does nothing when autostart is disabled`() {
        every { repository.isAutostartEnabled } returns flowOf(false)
        mockkStatic(androidx.core.content.ContextCompat::class)
        val context = mockk<android.content.Context>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 0) {
            androidx.core.content.ContextCompat.startForegroundService(any(), any())
        }
    }

    @Test
    fun `ignores intents that are not BOOT_COMPLETED`() {
        every { repository.isAutostartEnabled } returns flowOf(true)
        mockkStatic(androidx.core.content.ContextCompat::class)
        val context = mockk<android.content.Context>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED))

        verify(exactly = 0) {
            androidx.core.content.ContextCompat.startForegroundService(any(), any())
        }
    }

    @Test
    fun `starts TvDiscoveryService when autostart is enabled`() {
        every { repository.isAutostartEnabled } returns flowOf(true)
        mockkStatic(androidx.core.content.ContextCompat::class)
        every { androidx.core.content.ContextCompat.startForegroundService(any(), any()) } returns mockk()
        val context = mockk<android.content.Context>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) {
            androidx.core.content.ContextCompat.startForegroundService(context, any())
        }
    }

    @Test
    fun `emits DiagnosticLog breadcrumb when service start fails`() {
        every { repository.isAutostartEnabled } returns flowOf(true)
        mockkStatic(androidx.core.content.ContextCompat::class)
        every {
            androidx.core.content.ContextCompat.startForegroundService(any(), any())
        } throws SecurityException("background start restricted")
        val context = mockk<android.content.Context>(relaxed = true)

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val events = DiagnosticLog.snapshot()
        assertTrue(
            events.any { it.tag == "tv.boot.autostart.failed" },
            "Expected tv.boot.autostart.failed breadcrumb in DiagnosticLog"
        )
    }
}
