package com.justb81.watchbuddy.service

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.core.discovery.BleDiscoveryContract
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advertises the companion's reachable LAN endpoint over BLE so the TV can
 * discover us without mDNS/multicast. This is the fallback path for networks
 * that block peer-to-peer traffic (AP/client isolation, VLAN-segmented mesh
 * Wi-Fi, aggressive IGMP snooping). See [BleDiscoveryContract] for the wire
 * format.
 *
 * Fails softly on every branch: no BLE adapter, adapter off, permission
 * denied, advertiser unavailable — all log and no-op so NSD keeps working.
 */
@Singleton
class CompanionBleAdvertiser @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stateManager: CompanionStateManager,
) {
    companion object {
        private const val TAG = "CompanionBleAdvertiser"
    }

    private val bluetoothManager: BluetoothManager? =
        runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeCallback: AdvertiseCallback? = null

    /**
     * Starts BLE advertising with the given endpoint. Idempotent: repeat calls
     * with the same arguments are a no-op; calls with different arguments
     * replace the active advertisement.
     *
     * @return true if the advertisement was handed off to the system BLE stack
     *   (the system may still reject it asynchronously via `onStartFailure`).
     */
    fun start(
        ipv4: Inet4Address,
        port: Int,
        modelQuality: Int,
        llmBackendOrdinal: Int,
    ): Boolean {
        if (!hasAdvertisePermission()) {
            DiagnosticLog.event(TAG, "start skipped: BLUETOOTH_ADVERTISE permission not granted")
            return false
        }
        val adapter = bluetoothManager?.adapter ?: run {
            DiagnosticLog.event(TAG, "start skipped: no Bluetooth adapter")
            return false
        }
        if (!adapter.isEnabled) {
            DiagnosticLog.event(TAG, "start skipped: Bluetooth adapter disabled")
            return false
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
            DiagnosticLog.event(TAG, "start skipped: BluetoothLeAdvertiser unavailable (BLE peripheral role unsupported)")
            return false
        }

        // Stop any prior advertisement so we don't stack duplicates when the
        // endpoint changes (Wi-Fi IP flip on SSID handoff).
        stopInternal(leAdvertiser)

        val payload = BleDiscoveryContract.Payload(
            ipv4 = ipv4,
            port = port,
            modelQuality = modelQuality.coerceIn(0, 255),
            llmBackendOrdinal = llmBackendOrdinal.coerceIn(0, 255),
        )
        val payloadBytes = runCatching { BleDiscoveryContract.encode(payload) }
            .onFailure { DiagnosticLog.warn(TAG, "payload encode failed", it) }
            .getOrNull() ?: return false

        // Pick the advertise mode based on whether a TV is already polling us
        // steadily over HTTP. BALANCED (~250 ms interval) is only useful while
        // the TV doesn't yet have a Wi-Fi route to us; once a TV is polling
        // `/capability` reliably, LOW_POWER (~1 s interval) carries the same
        // fallback signal at a fraction of the radio cost (#345 Opt B).
        val steady = stateManager.pairedSteadyState.value
        val advertiseMode = if (steady) {
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        } else {
            AdvertiseSettings.ADVERTISE_MODE_BALANCED
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            // We only need the advertisement itself; we never accept inbound
            // BLE connections. Making the advert non-connectable also keeps it
            // inside the 31-byte legacy envelope on all chipsets.
            .setConnectable(false)
            .setTimeout(0) // 0 = advertise until explicitly stopped
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // would blow the 31-byte envelope
            .setIncludeTxPowerLevel(false)
            // Intentionally no addServiceUuid(): that AD field plus the
            // addServiceData() AD field below would both carry the same
            // 16-byte UUID, and the legacy envelope overflows at 48 bytes.
            // Strict BLE stacks (Android 16 / Nothing) then reject the
            // whole advertisement with DATA_TOO_LARGE and the TV can never
            // discover us via BLE. The UUID is still surfaced inside
            // addServiceData(), which scanners match via setServiceData()
            // filters — see PhoneBleScanner (#345).
            .addServiceData(ParcelUuid(BleDiscoveryContract.SERVICE_UUID), payloadBytes)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                DiagnosticLog.event(
                    TAG,
                    "advertising started ip=${ipv4.hostAddress} port=$port " +
                        "quality=$modelQuality backend=$llmBackendOrdinal"
                )
                stateManager.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.ADVERTISING)
            }

            override fun onStartFailure(errorCode: Int) {
                DiagnosticLog.error(TAG, "advertising failed: ${errorName(errorCode)}")
                stateManager.setBleAdvertiseState(
                    CompanionStateManager.BleAdvertiseState.FAILED,
                    errorCode = errorCode,
                )
                // Drop our reference so a later start() attempt can retry
                // cleanly instead of tripping over a stale callback.
                synchronized(this@CompanionBleAdvertiser) {
                    if (activeCallback === this) {
                        activeCallback = null
                        advertiser = null
                    }
                }
            }
        }

        return runCatching {
            leAdvertiser.startAdvertising(settings, data, callback)
            synchronized(this) {
                advertiser = leAdvertiser
                activeCallback = callback
            }
            true
        }.onFailure { DiagnosticLog.error(TAG, "startAdvertising threw", it) }
            .getOrDefault(false)
    }

    fun stop() {
        val (adv, cb) = synchronized(this) { advertiser to activeCallback }
        stopInternal(adv, cb)
    }

    private fun stopInternal(
        adv: BluetoothLeAdvertiser? = advertiser,
        cb: AdvertiseCallback? = activeCallback,
    ) {
        if (adv == null || cb == null) return
        if (!hasAdvertisePermission()) {
            // Can't legally call stopAdvertising, but we still clear our
            // references so future start() calls work.
            synchronized(this) {
                advertiser = null
                activeCallback = null
            }
            stateManager.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.IDLE)
            return
        }
        runCatching { adv.stopAdvertising(cb) }
            .onFailure { DiagnosticLog.warn(TAG, "stopAdvertising threw", it) }
        synchronized(this) {
            advertiser = null
            activeCallback = null
        }
        stateManager.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.IDLE)
        DiagnosticLog.event(TAG, "advertising stopped")
    }

    private fun hasAdvertisePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED

    private fun errorName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED($code)"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE($code)"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED($code)"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR($code)"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS($code)"
        else -> "UNKNOWN($code)"
    }
}
