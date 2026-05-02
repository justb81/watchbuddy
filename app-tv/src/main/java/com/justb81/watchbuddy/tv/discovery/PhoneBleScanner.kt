package com.justb81.watchbuddy.tv.discovery

import android.Manifest
import android.annotation.SuppressLint
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
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for WatchBuddy BLE advertisements emitted by `CompanionBleAdvertiser`
 * on the phone side. Each decoded advertisement surfaces a
 * `(ipv4, port, modelQuality, llmBackend, rssi)` tuple via [listener];
 * [PhoneDiscoveryManager] feeds these into the capability-fetch pipeline.
 *
 * BLE is the sole discovery channel — if BLE is unavailable (permission
 * denied, adapter off, unsupported hardware) the TV cannot discover phones
 * at all.
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
            rssi: Int,
            authCapable: Boolean = false,
            bearerTokenBytes: ByteArray? = null,
        )
    }

    private val bluetoothManager: BluetoothManager? =
        runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()

    private var scanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null

    @SuppressLint("MissingPermission") // Guarded by hasScanPermission() below.
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

        // Two filters: one for schema v1 (legacy phones without bearer auth)
        // and one for schema v2 (current phones with BLE-distributed bearer token).
        // Separate filters allow the OS to match either advertisement — a single
        // filter with mask 0x00 on the version byte would accept any schema version
        // including unknown future ones and garbled adverts from other apps.
        val filterV1 = ScanFilter.Builder()
            .setServiceData(
                ParcelUuid(BleDiscoveryContract.SERVICE_UUID),
                byteArrayOf(BleDiscoveryContract.PAYLOAD_SCHEMA_VERSION_LEGACY),
                byteArrayOf(0xFF.toByte()),
            )
            .build()
        val filterV2 = ScanFilter.Builder()
            .setServiceData(
                ParcelUuid(BleDiscoveryContract.SERVICE_UUID),
                byteArrayOf(BleDiscoveryContract.PAYLOAD_SCHEMA_VERSION),
                byteArrayOf(0xFF.toByte()),
            )
            .build()

        val settings = ScanSettings.Builder()
            // BALANCED (~5 s interval, ~2 s window) catches a phone
            // advertising in BALANCED mode promptly without the battery cost
            // of continuous scanning. Runs for the entire lifetime of
            // discovery — BLE is the sole channel, so the TV must keep
            // listening while "Phone discovery" is enabled.
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
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
            leScanner.startScan(listOf(filterV1, filterV2), settings, callback)
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

    @SuppressLint("MissingPermission") // Guarded by hasScanPermission() below.
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
        val scanRecord = result.scanRecord
        val data = scanRecord?.serviceData?.get(ParcelUuid(BleDiscoveryContract.SERVICE_UUID))
        when (val decoded = BleDiscoveryContract.decode(data)) {
            is BleDiscoveryContract.DecodeResult.Ok -> {
                val payload = decoded.payload
                val bearerTokenBytes = if (decoded.authCapable) {
                    BleDiscoveryContract.decodeTokenPayload(
                        scanRecord?.serviceData?.get(ParcelUuid(BleDiscoveryContract.TOKEN_SERVICE_UUID))
                    )
                } else null
                Log.d(
                    TAG,
                    "advertisement: ip=${payload.ipv4.hostAddress} port=${payload.port} " +
                        "quality=${payload.modelQuality} backend=${payload.llmBackendOrdinal} " +
                        "rssi=${result.rssi} auth=${decoded.authCapable}"
                )
                listener.onAdvertisement(
                    ipv4 = payload.ipv4,
                    port = payload.port,
                    modelQuality = payload.modelQuality,
                    llmBackendOrdinal = payload.llmBackendOrdinal,
                    rssi = result.rssi,
                    authCapable = decoded.authCapable,
                    bearerTokenBytes = bearerTokenBytes,
                )
            }
            is BleDiscoveryContract.DecodeResult.WrongVersion -> DiagnosticLog.debug(
                TAG,
                "BLE schema mismatch from ${result.device.address}: " +
                    "v${decoded.found} (expected v${decoded.expected})",
            )
            BleDiscoveryContract.DecodeResult.Truncated -> DiagnosticLog.warn(
                TAG,
                "Truncated BLE payload from ${result.device.address}",
            )
            BleDiscoveryContract.DecodeResult.MalformedIpv4 -> DiagnosticLog.warn(
                TAG,
                "Malformed IPv4 in BLE payload from ${result.device.address}",
            )
        }
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
