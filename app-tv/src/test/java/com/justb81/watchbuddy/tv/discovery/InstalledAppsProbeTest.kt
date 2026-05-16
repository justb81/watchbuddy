package com.justb81.watchbuddy.tv.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("InstalledAppsProbe")
class InstalledAppsProbeTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val lifecycle: Lifecycle = mockk(relaxed = true)
    private val lifecycleOwner: LifecycleOwner = mockk(relaxed = true)

    private val receiverSlot = slot<BroadcastReceiver>()
    private val observerSlot = slot<LifecycleObserver>()

    private lateinit var probe: InstalledAppsProbe

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class)
        every { context.packageManager } returns packageManager
        every { lifecycleOwner.lifecycle } returns lifecycle
        every { ContextCompat.registerReceiver(any(), capture(receiverSlot), any(), any()) } returns null
        justRun { lifecycle.addObserver(capture(observerSlot)) }
        justRun { lifecycle.removeObserver(any()) }
        justRun { context.unregisterReceiver(any()) }

        every { packageManager.getPackageInfo(any<String>(), 0) } throws PackageManager.NameNotFoundException()

        probe = InstalledAppsProbe(context, lifecycleOwner)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    @Nested
    @DisplayName("initialisation")
    inner class InitialisationTests {

        @Test
        fun `registers BroadcastReceiver with RECEIVER_NOT_EXPORTED on construction`() {
            verify(exactly = 1) {
                ContextCompat.registerReceiver(context, any(), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }

        @Test
        fun `adds lifecycle observer on construction`() {
            verify(exactly = 1) { lifecycle.addObserver(any()) }
        }
    }

    @Nested
    @DisplayName("isInstalled")
    inner class IsInstalledTests {

        @Test
        fun `returns true for an installed package`() {
            every { packageManager.getPackageInfo(eq("com.netflix.ninja"), 0) } returns mockk()

            assertTrue(probe.isInstalled("com.netflix.ninja"))
        }

        @Test
        fun `returns false for a package not installed`() {
            assertFalse(probe.isInstalled("com.netflix.ninja"))
        }

        @Test
        fun `returns false for a package not in ProviderCatalogRegistry`() {
            assertFalse(probe.isInstalled("com.unknown.app"))
        }

        @Test
        fun `returns true for ARD dedicated TV package when installed`() {
            every { packageManager.getPackageInfo(eq("de.swr.avp.ard.tv"), 0) } returns mockk()

            assertTrue(probe.isInstalled("de.swr.avp.ard.tv"))
        }

        @Test
        fun `returns true for ARD universal package when installed on TV`() {
            every { packageManager.getPackageInfo(eq("de.swr.avp.ard"), 0) } returns mockk()

            assertTrue(probe.isInstalled("de.swr.avp.ard"))
        }

        @Test
        fun `returns true for ZDF Mediathek when installed`() {
            every { packageManager.getPackageInfo(eq("com.zdf.android.mediathek"), 0) } returns mockk()

            assertTrue(probe.isInstalled("com.zdf.android.mediathek"))
        }

        @Test
        fun `returns false for ARD dedicated TV package when not installed`() {
            assertFalse(probe.isInstalled("de.swr.avp.ard.tv"))
        }

        @Test
        fun `returns false for ARD universal package when not installed`() {
            assertFalse(probe.isInstalled("de.swr.avp.ard"))
        }

        @Test
        fun `returns false for ZDF Mediathek when not installed`() {
            assertFalse(probe.isInstalled("com.zdf.android.mediathek"))
        }
    }

    @Nested
    @DisplayName("caching")
    inner class CachingTests {

        @Test
        fun `returns the same Set instance on repeated calls (cache hit)`() {
            every { packageManager.getPackageInfo(eq("com.netflix.ninja"), 0) } returns mockk()

            val first = probe.getInstalledPackages()
            val second = probe.getInstalledPackages()

            assertSame(first, second)
        }

        @Test
        fun `reloads packages after cache is cleared by package change`() {
            every { packageManager.getPackageInfo(eq("com.netflix.ninja"), 0) } returns mockk()

            val before = probe.getInstalledPackages()

            val intent = mockk<Intent>(relaxed = true)
            receiverSlot.captured.onReceive(context, intent)

            val after = probe.getInstalledPackages()

            assertNotSame(before, after)
        }

        @Test
        fun `PackageManager is queried only once before cache is invalidated`() {
            every { packageManager.getPackageInfo(eq("com.netflix.ninja"), 0) } returns mockk()

            probe.getInstalledPackages()
            probe.getInstalledPackages()
            probe.getInstalledPackages()

            verify(exactly = 1) { packageManager.getPackageInfo(eq("com.netflix.ninja"), 0) }
        }

        @Test
        fun `PackageManager is queried again after cache is invalidated`() {
            every { packageManager.getPackageInfo(eq("com.netflix.ninja"), 0) } returns mockk()

            probe.getInstalledPackages()

            val intent = mockk<Intent>(relaxed = true)
            receiverSlot.captured.onReceive(context, intent)

            probe.getInstalledPackages()

            verify(exactly = 2) { packageManager.getPackageInfo(eq("com.netflix.ninja"), 0) }
        }
    }

    @Nested
    @DisplayName("lifecycle — onDestroy")
    inner class OnDestroyTests {

        @Test
        fun `unregisters BroadcastReceiver on process destroy`() {
            probe.onDestroy(lifecycleOwner)

            verify(exactly = 1) { context.unregisterReceiver(receiverSlot.captured) }
        }

        @Test
        fun `removes lifecycle observer on process destroy`() {
            probe.onDestroy(lifecycleOwner)

            verify(exactly = 1) { lifecycle.removeObserver(any()) }
        }
    }
}
