package com.justb81.watchbuddy.core.logging

/**
 * Strips known PII patterns from a string before it is stored in the
 * [DiagnosticLog] ring buffer.
 *
 * The default implementation ([DefaultRedactor]) covers the four most likely
 * leak vectors; callers can supply their own instance via
 * [DiagnosticLog.setRedactorForTest] if test fixtures need deterministic
 * sanitisation.
 */
fun interface Redactor {
    fun redact(input: String): String
}

/**
 * Default [Redactor] applied to every [DiagnosticLog] entry.
 *
 * Rules applied in order:
 * - `Bearer <token>` → `Bearer [REDACTED]`
 * - `access_token=<value>` → `access_token=[REDACTED]`
 * - MAC / BSSID patterns (six colon-separated hex octets) → `[MAC_REDACTED]`
 * - IP address inside a URL → `[IP_REDACTED]`
 */
object DefaultRedactor : Redactor {

    private val BEARER_TOKEN = Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE)
    private val ACCESS_TOKEN = Regex("""(access_token=)[^&\s"']+""", RegexOption.IGNORE_CASE)
    private val MAC_ADDRESS = Regex("""[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}""")
    private val IP_IN_URL = Regex("""(https?://)\d{1,3}(?:\.\d{1,3}){3}""", RegexOption.IGNORE_CASE)

    override fun redact(input: String): String = input
        .replace(BEARER_TOKEN, "Bearer [REDACTED]")
        .replace(ACCESS_TOKEN) { match -> "${match.groupValues[1]}[REDACTED]" }
        .replace(MAC_ADDRESS, "[MAC_REDACTED]")
        .replace(IP_IN_URL) { match -> "${match.groupValues[1]}[IP_REDACTED]" }
}
