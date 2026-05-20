package com.justb81.watchbuddy.tv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.tv.discovery.TvDiscoveryService
import com.justb81.watchbuddy.tv.ui.navigation.TvNavGraph
import com.justb81.watchbuddy.tv.ui.theme.WatchBuddyTvTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {

    private val requestBluetoothScanPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "BLUETOOTH_SCAN granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestBluetoothScanPermission()
        setContent {
            WatchBuddyTvTheme {
                TvNavGraph()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.startForegroundService(
            this,
            Intent(this, TvDiscoveryService::class.java),
        )
    }

    /**
     * Request BLUETOOTH_SCAN so [com.justb81.watchbuddy.tv.discovery.PhoneBleScanner]
     * can find phones on networks that block mDNS (guest Wi-Fi, mesh VLANs).
     * Fires once per cold start; the system suppresses the dialog if the user
     * has already answered.
     */
    private fun maybeRequestBluetoothScanPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothScanPermission.launch(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

    companion object {
        private const val TAG = "TvMainActivity"
    }
}
