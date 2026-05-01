package com.justb81.watchbuddy.tv.boot

import android.content.BroadcastReceiver
import android.content.Context
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("BootReceiver — handleBootCompleted")
class BootReceiverTest {

    private val context: Context = mockk(relaxed = true)
    private val repo: StreamingPreferencesRepository = mockk(relaxed = true)
    private val pendingResult: BroadcastReceiver.PendingResult = mockk(relaxed = true)

    private val receiver = BootReceiver()

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        mockkStatic(ContextCompat::class)
        justRun { ContextCompat.startForegroundService(any(), any()) }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
        DiagnosticLog.clear()
    }

    @Nested
    @DisplayName("autostart disabled")
    inner class AutostartDisabledTests {

        @Test
        fun `does not start service when autostart is disabled`() = runTest {
            every { repo.isAutostartEnabled } returns flowOf(false)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
        }

        @Test
        fun `logs event when autostart is disabled`() = runTest {
            every { repo.isAutostartEnabled } returns flowOf(false)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            val events = DiagnosticLog.snapshot()
            assertTrue(
                events.any { it.message.contains("autostart disabled") },
                "Expected an 'autostart disabled' log entry"
            )
        }

        @Test
        fun `calls pendingResult finish when autostart is disabled`() = runTest {
            every { repo.isAutostartEnabled } returns flowOf(false)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            verify(exactly = 1) { pendingResult.finish() }
        }
    }

    @Nested
    @DisplayName("autostart enabled")
    inner class AutostartEnabledTests {

        @Test
        fun `starts foreground service when autostart is enabled`() = runTest {
            every { repo.isAutostartEnabled } returns flowOf(true)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            verify(exactly = 1) { ContextCompat.startForegroundService(eq(context), any()) }
        }

        @Test
        fun `logs success event when service is started`() = runTest {
            every { repo.isAutostartEnabled } returns flowOf(true)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            val events = DiagnosticLog.snapshot()
            assertTrue(
                events.any { it.message.contains("TvDiscoveryService start requested") },
                "Expected a service-start log entry"
            )
        }

        @Test
        fun `calls pendingResult finish when autostart is enabled`() = runTest {
            every { repo.isAutostartEnabled } returns flowOf(true)
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            verify(exactly = 1) { pendingResult.finish() }
        }

        @Test
        fun `logs error and still calls finish when startForegroundService throws`() = runTest {
            every { repo.isAutostartEnabled } returns flowOf(true)
            every { ContextCompat.startForegroundService(any(), any()) } throws
                IllegalStateException("ForegroundServiceStartNotAllowedException")
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            val errors = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.ERROR }
            assertTrue(
                errors.any { it.message.contains("tv.boot.autostart.failed") },
                "Expected an error log entry for service start failure"
            )
            verify(exactly = 1) { pendingResult.finish() }
        }
    }

    @Nested
    @DisplayName("DataStore read failure")
    inner class DataStoreFailureTests {

        @Test
        fun `logs error when DataStore read throws`() = runTest {
            every { repo.isAutostartEnabled } returns flow { throw RuntimeException("DataStore error") }
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            val errors = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.ERROR }
            assertTrue(
                errors.any { it.message.contains("read autostart preference failed") },
                "Expected an error log for DataStore failure"
            )
        }

        @Test
        fun `calls pendingResult finish even when DataStore read throws`() = runTest {
            every { repo.isAutostartEnabled } returns flow { throw RuntimeException("DataStore error") }
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            verify(exactly = 1) { pendingResult.finish() }
        }

        @Test
        fun `does not start service when DataStore read throws`() = runTest {
            every { repo.isAutostartEnabled } returns flow { throw RuntimeException("DataStore error") }
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            receiver.handleBootCompleted(context, repo, scope, pendingResult)

            verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
        }
    }
}
