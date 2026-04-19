package com.justb81.watchbuddy.tv.discovery

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.core.discovery.BleDiscoveryContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-mDNS discovery fallback: listens for WatchBuddy BLE advertisements
 * emitted by `CompanionBleAdvertiser` on the phone side. Each decoded
 * advertisement surfaces a `(ipv4, port, modelQuality, llmBackend)` tuple
 * via [listener]; [PhoneDiscoveryManager] feeds these into the existing
 * capability-fetch pipeline so BLE-discovered phones are ranked and
 * heartbeat-checked identically to NSD-discovered ones.
 *
 * Fails softly on every branch: permission denied, adapter off, BLE
 * unsupported — all log and no-op so the NSD path keeps running.
 */
@Singleton
class PhoneBleScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "PhoneBleScanner"
    }

    fun interface Listener {
        fun onAdvertisement(
            ipv4: Inet4Address,
            port: Int,
            modelQuality: Int,
            llmBackendOrdinal: Int,
        )
    }

    private val bluetoothManager: BluetoothManager? =
        runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()

    private var scanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null

    fun start(
        listener: Listener,
        onFailure: (Int) -> Unit = {},
    ): Boolean {
        if (!hasScanPermission()) {
            Log.i(TAG, "start skipped: BLUETOOTH_SCAN permission not granted")
            return false
        }
        val adapter = bluetoothManager?.adapter ?: run {
            Log.i(TAG, "start skipped: no Bluetooth adapter")
            return false
        }
        if (!adapter.isEnabled) {
            Log.i(TAG, "start skipped: Bluetooth adapter disabled")
            return false
        }
        val leScanner = adapter.bluetoothLeScanner ?: run {
            Log.i(TAG, "start skipped: BluetoothLeScanner unavailable")
            return false
        }

        // Stop any prior scan — e.g. after permission grant or Wi-Fi reconnect.
        stopInternal(leScanner)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleDiscoveryContract.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            // LOW_POWER (≈5 s window every 5 s) is plenty for discovery: a
            // phone advertising in BALANCED mode emits every ~250 ms, so
            // we'll see it within one window. BALANCED and LOW_LATENCY are
            // only worth the battery cost during active pairing UI flows.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(result, listener)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleResult(it, listener) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed: ${errorName(errorCode)}")
                synchronized(this@PhoneBleScanner) {
                    if (activeCallback === this) {
                        activeCallback = null
                        scanner = null
                    }
                }
                onFailure(errorCode)
            }
        }

        return runCatching {
            leScanner.startScan(listOf(filter), settings, callback)
            synchronized(this) {
                scanner = leScanner
                activeCallback = callback
            }
            Log.i(TAG, "scanning for WatchBuddy BLE advertisements")
            true
        }.onFailure { Log.e(TAG, "startScan threw", it) }
            .getOrDefault(false)
    }

    fun stop() {
        val (sc, cb) = synchronized(this) { scanner to activeCallback }
        stopInternal(sc, cb)
    }

    private fun stopInternal(
        sc: BluetoothLeScanner? = scanner,
        cb: ScanCallback? = activeCallback,
    ) {
        if (sc == null || cb == null) return
        if (!hasScanPermission()) {
            synchronized(this) {
                scanner = null
                activeCallback = null
            }
            return
        }
        runCatching { sc.stopScan(cb) }
            .onFailure { Log.w(TAG, "stopScan threw", it) }
        synchronized(this) {
            scanner = null
            activeCallback = null
        }
        Log.i(TAG, "scanning stopped")
    }

    private fun handleResult(result: ScanResult, listener: Listener) {
        val data = result.scanRecord
            ?.serviceData
            ?.get(ParcelUuid(BleDiscoveryContract.SERVICE_UUID))
        val payload = BleDiscoveryContract.decode(data) ?: run {
            Log.v(TAG, "scan result without decodable WatchBuddy payload: ${result.device.address}")
            return
        }
        Log.d(
            TAG,
            "advertisement: ip=${payload.ipv4.hostAddress} port=${payload.port} " +
                "quality=${payload.modelQuality} backend=${payload.llmBackendOrdinal}"
        )
        listener.onAdvertisement(
            ipv4 = payload.ipv4,
            port = payload.port,
            modelQuality = payload.modelQuality,
            llmBackendOrdinal = payload.llmBackendOrdinal,
        )
    }

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

    private fun errorName(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED($code)"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED($code)"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED($code)"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR($code)"
        else -> "UNKNOWN($code)"
    }
}
