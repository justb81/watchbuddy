package com.justb81.watchbuddy.phone.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LifecycleOwner
import io.mockk.any
import io.mockk.anyConstructed
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WifiStateProvider")
class WifiStateProviderTest {

    private val connectivityManager: ConnectivityManager = mockk(relaxed = true)
    private val context: Context = mockk()

    @BeforeEach
    fun setUp() {
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivityManager

        // NetworkRequest.Builder is an Android stub whose addTransportType() returns null
        // in unit-test stubs, making the chained .build() call NPE. Mock the constructor
        // so the chain returns a proper NetworkRequest mock.
        mockkConstructor(NetworkRequest.Builder::class)
        val mockRequest = mockk<NetworkRequest>()
        every { anyConstructed<NetworkRequest.Builder>().addTransportType(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockRequest

        every {
            connectivityManager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(NetworkRequest.Builder::class)
    }

    private fun buildProvider(): WifiStateProvider = WifiStateProvider(context)

    @Nested
    @DisplayName("initial state")
    inner class InitialState {
        @Test
        fun `starts false when no active network`() {
            every { connectivityManager.activeNetwork } returns null
            val provider = buildProvider()
            assertFalse(provider.isOnWifi.value)
        }

        @Test
        fun `starts true when active network has TRANSPORT_WIFI`() {
            val network = mockk<Network>()
            val caps = mockk<NetworkCapabilities>()
            every { connectivityManager.activeNetwork } returns network
            every { connectivityManager.getNetworkCapabilities(network) } returns caps
            every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
            val provider = buildProvider()
            assertTrue(provider.isOnWifi.value)
        }

        @Test
        fun `registers a NetworkCallback on construction`() {
            buildProvider()
            verify(exactly = 1) {
                connectivityManager.registerNetworkCallback(
                    any<NetworkRequest>(),
                    any<ConnectivityManager.NetworkCallback>()
                )
            }
        }
    }

    @Nested
    @DisplayName("shutdown")
    inner class Shutdown {
        @Test
        fun `unregisters the stored callback`() {
            every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just runs
            val provider = buildProvider()

            provider.shutdown()

            verify(exactly = 1) {
                connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
            }
        }

        @Test
        fun `is idempotent — second call is a no-op`() {
            every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just runs
            val provider = buildProvider()

            provider.shutdown()
            provider.shutdown()

            verify(exactly = 1) {
                connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
            }
        }

        @Test
        fun `does not throw when ConnectivityManager is null`() {
            every { context.getSystemService(ConnectivityManager::class.java) } returns null
            val provider = WifiStateProvider(context)

            provider.shutdown() // must not throw
        }

        @Test
        fun `does not throw when unregisterNetworkCallback fails`() {
            every {
                connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
            } throws IllegalArgumentException("already unregistered")
            val provider = buildProvider()

            provider.shutdown() // runCatching must swallow the exception
        }
    }

    @Nested
    @DisplayName("lifecycle observer")
    inner class LifecycleObserver {
        @Test
        fun `onDestroy delegates to shutdown`() {
            every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just runs
            val provider = buildProvider()
            val owner = mockk<LifecycleOwner>()

            provider.onDestroy(owner)

            verify(exactly = 1) {
                connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
            }
        }
    }
}
