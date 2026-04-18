package com.justb81.watchbuddy.phone.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat

/**
 * Helpers for the runtime `BLUETOOTH_ADVERTISE` prompt (API 31+).
 *
 * BLE advertising is our non-mDNS fallback discovery channel used when the
 * local Wi-Fi router blocks peer-to-peer traffic (AP/client isolation,
 * VLAN-segmented mesh Wi-Fi, aggressive multicast filtering). If the user
 * denies this permission, the companion service still starts — NSD continues
 * to work on most networks; only the BLE fallback is unavailable. Denial is
 * therefore non-fatal and we never show a rationale dialog.
 */
object BluetoothAdvertisePermission {

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Remembers a launcher that requests `BLUETOOTH_ADVERTISE` and invokes
 * [onResult] with the grant outcome. [onResult] is called regardless of
 * whether the user accepted or denied so the caller can always proceed with
 * starting the companion service — a denied grant only disables the BLE
 * fallback, not the core companion functionality.
 */
@Composable
fun rememberBluetoothAdvertisePermissionRequest(
    onResult: (granted: Boolean) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> onResult(granted) }
    )
    return remember(launcher) {
        { launcher.launch(Manifest.permission.BLUETOOTH_ADVERTISE) }
    }
}
