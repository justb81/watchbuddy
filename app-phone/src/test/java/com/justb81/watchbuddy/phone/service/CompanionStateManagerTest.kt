package com.justb81.watchbuddy.phone.service

import com.justb81.watchbuddy.service.CompanionStateManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CompanionStateManager")
class CompanionStateManagerTest {

    @Test
    fun `ble advertise state transitions emit and error code is carried`() {
        val mgr = CompanionStateManager()
        assertEquals(CompanionStateManager.BleAdvertiseState.IDLE, mgr.bleAdvertiseState.value)

        mgr.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.ADVERTISING)
        assertEquals(CompanionStateManager.BleAdvertiseState.ADVERTISING, mgr.bleAdvertiseState.value)
        assertNull(mgr.bleAdvertiseErrorCode.value)

        mgr.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.FAILED, errorCode = 2)
        assertEquals(CompanionStateManager.BleAdvertiseState.FAILED, mgr.bleAdvertiseState.value)
        assertEquals(2, mgr.bleAdvertiseErrorCode.value)

        mgr.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.IDLE)
        assertNull(mgr.bleAdvertiseErrorCode.value)
    }

    @Test
    fun `onCapabilityChecked updates lastCapabilityCheck`() {
        val mgr = CompanionStateManager()
        assertEquals(0L, mgr.lastCapabilityCheck.value)

        mgr.onCapabilityCheckedAt(1_000_000_000L)
        assertEquals(1_000_000_000L, mgr.lastCapabilityCheck.value)

        mgr.onCapabilityCheckedAt(1_000_060_000L)
        assertEquals(1_000_060_000L, mgr.lastCapabilityCheck.value)
    }

    @Test
    fun `onCapabilityChecked sets isConnectedToTv true immediately`() {
        val mgr = CompanionStateManager()
        assertFalse(mgr.isConnectedToTv.value)

        mgr.onCapabilityCheckedAt(1_000_000_000L)
        assertTrue(mgr.isConnectedToTv.value)
    }

    @Test
    fun `setConnectedToTv false clears isConnectedToTv`() {
        val mgr = CompanionStateManager()
        mgr.onCapabilityCheckedAt(1_000_000_000L)
        assertTrue(mgr.isConnectedToTv.value)

        mgr.setConnectedToTv(false)
        assertFalse(mgr.isConnectedToTv.value)
    }

    @Test
    fun `setServiceRunning false resets transient state including isConnectedToTv`() {
        val mgr = CompanionStateManager()
        val t0 = 1_000_000_000L
        mgr.setHttpServerBinding("0.0.0.0:8765")
        mgr.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.ADVERTISING)
        mgr.setWifiIpv4("192.168.1.2")
        mgr.onCapabilityCheckedAt(t0)
        mgr.setServiceRunning(true)

        assertTrue(mgr.isServiceRunning.value)
        assertEquals("0.0.0.0:8765", mgr.httpServerBinding.value)
        assertEquals(CompanionStateManager.BleAdvertiseState.ADVERTISING, mgr.bleAdvertiseState.value)
        assertTrue(mgr.isConnectedToTv.value)

        mgr.setServiceRunning(false)

        assertFalse(mgr.isServiceRunning.value)
        assertNull(mgr.httpServerBinding.value)
        assertEquals(CompanionStateManager.BleAdvertiseState.IDLE, mgr.bleAdvertiseState.value)
        assertNull(mgr.wifiIpv4.value)
        assertEquals(0L, mgr.lastCapabilityCheck.value)
        assertFalse(mgr.isConnectedToTv.value)
    }
}
