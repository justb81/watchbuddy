package com.justb81.watchbuddy.core.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DiagnosticLog — PII redaction applied on write (#563)")
class DiagnosticLogRedactionTest {

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        DiagnosticLog.resetRedactorForTest()
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
        DiagnosticLog.resetRedactorForTest()
    }

    // ── Bearer token ─────────────────────────────────────────────────────────

    @Test
    fun `Bearer token in event message is redacted in snapshot`() {
        DiagnosticLog.event("Auth", "response: Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig")
        val entry = DiagnosticLog.snapshot().last()
        assertFalse(entry.message.contains("eyJhbGciOiJSUzI1NiJ9"), "Raw token must not appear in snapshot")
        assertTrue(entry.message.contains("[REDACTED]"))
    }

    // ── access_token ─────────────────────────────────────────────────────────

    @Test
    fun `access_token in error message is redacted in snapshot`() {
        DiagnosticLog.error("Network", "failed: https://api.trakt.tv/auth?access_token=supersecret123")
        val entry = DiagnosticLog.snapshot().last()
        assertFalse(entry.message.contains("supersecret123"), "Token value must not appear in snapshot")
        assertTrue(entry.message.contains("access_token=[REDACTED]"))
    }

    // ── MAC / BSSID ───────────────────────────────────────────────────────────

    @Test
    fun `BSSID in warn message is redacted in snapshot`() {
        DiagnosticLog.warn("BLE", "connected to AA:BB:CC:DD:EE:FF")
        val entry = DiagnosticLog.snapshot().last()
        assertFalse(entry.message.contains("AA:BB:CC:DD:EE:FF"), "BSSID must not appear in snapshot")
        assertTrue(entry.message.contains("[MAC_REDACTED]"))
    }

    // ── IP in URL ─────────────────────────────────────────────────────────────

    @Test
    fun `IP address in URL is redacted in debug message`() {
        DiagnosticLog.debug("HTTP", "connecting to http://192.168.1.100:8765/capability")
        val entry = DiagnosticLog.snapshot().last()
        assertFalse(entry.message.contains("192.168.1.100"), "IP address must not appear in snapshot")
        assertTrue(entry.message.contains("[IP_REDACTED]"))
    }

    // ── Throwable summarisation ───────────────────────────────────────────────

    @Test
    fun `throwable summary contains no stack trace frames`() {
        val exception = RuntimeException("connection refused to http://10.0.0.1:8765")
        DiagnosticLog.error("HTTP", "request failed", exception)
        val entry = DiagnosticLog.snapshot().last()
        val summary = entry.throwableSummary
        assertNotNull(summary)
        assertFalse(summary!!.contains("at com."), "Stack frame must not appear in throwable summary")
        assertFalse(summary.contains("at java."), "Stack frame must not appear in throwable summary")
        assertFalse(summary.contains(".kt:"), "Stack frame line number must not appear")
    }

    @Test
    fun `throwable summary contains only class name and redacted truncated message`() {
        val exception = RuntimeException("Bearer tok_secret_abc123 caused the error")
        DiagnosticLog.error("tag", "failed", exception)
        val entry = DiagnosticLog.snapshot().last()
        val summary = entry.throwableSummary!!
        assertTrue(summary.contains("RuntimeException"), "Class name must appear")
        assertFalse(summary.contains("tok_secret_abc123"), "Raw Bearer payload must not appear")
        assertTrue(summary.contains("[REDACTED]"), "Redacted placeholder must appear")
    }

    @Test
    fun `throwable message longer than 200 chars is truncated`() {
        val longMessage = "x".repeat(300)
        val exception = RuntimeException(longMessage)
        DiagnosticLog.error("tag", "failed", exception)
        val summary = entry().throwableSummary!!
        val messageInSummary = summary.substringAfter(": ")
        assertTrue(messageInSummary.length <= 200, "Throwable message must be capped at 200 characters")
    }

    @Test
    fun `throwable with no message produces summary with only class name`() {
        val exception = RuntimeException()
        DiagnosticLog.error("tag", "failed", exception)
        val summary = entry().throwableSummary!!
        assertTrue(summary.contains("RuntimeException"))
        assertFalse(summary.contains(":"), "No colon separator when there is no message")
    }

    // ── formatForShare ────────────────────────────────────────────────────────

    @Test
    fun `formatForShare output contains no raw PII from messages`() {
        DiagnosticLog.event("Auth", "token: Bearer secret_abc")
        DiagnosticLog.event("BLE", "bssid: AA:BB:CC:DD:EE:FF")
        val output = DiagnosticLog.formatForShare()
        assertFalse(output.contains("secret_abc"), "Raw Bearer value must not appear in share output")
        assertFalse(output.contains("AA:BB:CC:DD:EE:FF"), "BSSID must not appear in share output")
    }

    // ── Custom redactor ───────────────────────────────────────────────────────

    @Test
    fun `custom redactor is applied when set`() {
        DiagnosticLog.setRedactorForTest { input -> input.replace("password", "***") }
        DiagnosticLog.event("tag", "user entered password correctly")
        val entry = DiagnosticLog.snapshot().last()
        assertFalse(entry.message.contains("password"), "Custom redactor must have replaced the word")
        assertTrue(entry.message.contains("***"))
    }

    @Test
    fun `resetRedactorForTest restores default redactor`() {
        DiagnosticLog.setRedactorForTest { "static" }
        DiagnosticLog.resetRedactorForTest()
        DiagnosticLog.event("tag", "Bearer secret123 present")
        val entry = DiagnosticLog.snapshot().last()
        assertFalse(entry.message.contains("secret123"), "Default redactor must be active after reset")
        assertTrue(entry.message.contains("[REDACTED]"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun entry(): DiagnosticLog.Entry = DiagnosticLog.snapshot().last()
}
