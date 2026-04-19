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
    fun `nsd state transitions emit and error code is carried`() {
        val mgr = CompanionStateManager()
        assertEquals(CompanionStateManager.NsdRegistrationState.IDLE, mgr.nsdRegistrationState.value)
        assertNull(mgr.nsdErrorCode.value)

        mgr.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.REGISTERING)
        assertEquals(CompanionStateManager.NsdRegistrationState.REGISTERING, mgr.nsdRegistrationState.value)

        mgr.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.REGISTERED)
        assertEquals(CompanionStateManager.NsdRegistrationState.REGISTERED, mgr.nsdRegistrationState.value)
        assertNull(mgr.nsdErrorCode.value)

        mgr.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.FAILED, errorCode = 3)
        assertEquals(CompanionStateManager.NsdRegistrationState.FAILED, mgr.nsdRegistrationState.value)
        assertEquals(3, mgr.nsdErrorCode.value)

        // Clearing to IDLE resets the error code.
        mgr.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.IDLE)
        assertNull(mgr.nsdErrorCode.value)
    }

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
    fun `setServiceRunning false resets transient state`() {
        val mgr = CompanionStateManager()
        mgr.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.REGISTERED)
        mgr.setHttpServerBinding("0.0.0.0:8765")
        mgr.setMulticastLockHeld(true)
        mgr.setBleAdvertiseState(CompanionStateManager.BleAdvertiseState.ADVERTISING)
        mgr.setWifiIpv4("192.168.1.2")
        mgr.setServiceRunning(true)

        assertTrue(mgr.isServiceRunning.value)
        assertEquals("0.0.0.0:8765", mgr.httpServerBinding.value)
        assertTrue(mgr.multicastLockHeld.value)

        mgr.setServiceRunning(false)

        assertFalse(mgr.isServiceRunning.value)
        assertNull(mgr.httpServerBinding.value)
        assertFalse(mgr.multicastLockHeld.value)
        assertEquals(CompanionStateManager.NsdRegistrationState.IDLE, mgr.nsdRegistrationState.value)
        assertEquals(CompanionStateManager.BleAdvertiseState.IDLE, mgr.bleAdvertiseState.value)
        assertNull(mgr.wifiIpv4.value)
    }
}
