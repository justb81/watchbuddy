package com.justb81.watchbuddy.core.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefaultRedactor — PII pattern scrubbing (#563)")
class DiagnosticRedactorTest {

    // ── Bearer tokens ────────────────────────────────────────────────────────

    @Test
    fun `Bearer token is replaced`() {
        val result = DefaultRedactor.redact("auth: Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig")
        assertEquals("auth: Bearer [REDACTED]", result)
    }

    @Test
    fun `Bearer token is case-insensitive`() {
        val result = DefaultRedactor.redact("BEARER abc123token")
        assertEquals("Bearer [REDACTED]", result)
    }

    @Test
    fun `Bearer token at end of string is replaced`() {
        val result = DefaultRedactor.redact("header value: Bearer tok3n_with-special.chars")
        assertFalse(result.contains("tok3n_with-special.chars"))
        assertTrue(result.contains("[REDACTED]"))
    }

    // ── access_token ─────────────────────────────────────────────────────────

    @Test
    fun `access_token query param is replaced`() {
        val result = DefaultRedactor.redact("https://api.example.com/data?access_token=s3cr3t&foo=bar")
        assertEquals("https://api.example.com/data?access_token=[REDACTED]&foo=bar", result)
    }

    @Test
    fun `access_token is case-insensitive`() {
        val result = DefaultRedactor.redact("ACCESS_TOKEN=mytoken123")
        assertEquals("ACCESS_TOKEN=[REDACTED]", result)
    }

    @Test
    fun `access_token value at end of string is replaced`() {
        val result = DefaultRedactor.redact("request: access_token=end_value")
        assertFalse(result.contains("end_value"))
        assertTrue(result.contains("access_token=[REDACTED]"))
    }

    // ── MAC / BSSID addresses ─────────────────────────────────────────────────

    @Test
    fun `BSSID MAC address is replaced`() {
        val result = DefaultRedactor.redact("connected to BSSID: AA:BB:CC:DD:EE:FF")
        assertEquals("connected to BSSID: [MAC_REDACTED]", result)
    }

    @Test
    fun `lowercase MAC address is replaced`() {
        val result = DefaultRedactor.redact("bssid=aa:bb:cc:dd:ee:ff")
        assertEquals("bssid=[MAC_REDACTED]", result)
    }

    @Test
    fun `mixed-case MAC address is replaced`() {
        val result = DefaultRedactor.redact("mac: 0A:1b:2C:3d:4E:5f")
        assertEquals("mac: [MAC_REDACTED]", result)
    }

    @Test
    fun `short hex sequence that is not a full MAC is not replaced`() {
        val result = DefaultRedactor.redact("partial: AA:BB:CC:DD:EE")
        assertEquals("partial: AA:BB:CC:DD:EE", result)
    }

    // ── IP address in URL ─────────────────────────────────────────────────────

    @Test
    fun `IP address in http URL is replaced`() {
        val result = DefaultRedactor.redact("connecting to http://192.168.1.100:8765/capability")
        assertEquals("connecting to http://[IP_REDACTED]:8765/capability", result)
    }

    @Test
    fun `IP address in https URL is replaced`() {
        val result = DefaultRedactor.redact("endpoint: https://10.0.0.1/api/v1")
        assertEquals("endpoint: https://[IP_REDACTED]/api/v1", result)
    }

    @Test
    fun `IP address not in a URL is not replaced`() {
        val result = DefaultRedactor.redact("address: 192.168.1.100")
        assertEquals("address: 192.168.1.100", result)
    }

    // ── Multiple patterns in one string ───────────────────────────────────────

    @Test
    fun `multiple PII patterns in one string are all replaced`() {
        val input = "token=Bearer abc123 bssid=AA:BB:CC:DD:EE:FF url=http://10.0.0.1/api"
        val result = DefaultRedactor.redact(input)
        assertFalse(result.contains("abc123"))
        assertFalse(result.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(result.contains("10.0.0.1"))
        assertTrue(result.contains("[REDACTED]"))
        assertTrue(result.contains("[MAC_REDACTED]"))
        assertTrue(result.contains("[IP_REDACTED]"))
    }

    // ── Clean strings pass through unchanged ──────────────────────────────────

    @Test
    fun `clean string passes through unchanged`() {
        val input = "HTTP server started on port 8765"
        assertEquals(input, DefaultRedactor.redact(input))
    }

    @Test
    fun `empty string passes through unchanged`() {
        assertEquals("", DefaultRedactor.redact(""))
    }
}
