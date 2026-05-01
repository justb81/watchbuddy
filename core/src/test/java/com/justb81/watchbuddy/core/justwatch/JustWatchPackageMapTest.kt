package com.justb81.watchbuddy.core.justwatch

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("JustWatchPackageMap")
class JustWatchPackageMapTest {

    @BeforeEach
    fun clearLog() {
        DiagnosticLog.clear()
    }

    @AfterEach
    fun resetLog() {
        DiagnosticLog.clear()
    }

    @ParameterizedTest(name = "{0} → TMDB {1}")
    @CsvSource(
        "nfx, 8",
        "prv, 119",
        "dnp, 337",
        "atp, 350",
        "pmp, 531",
        "hbm, 1899",
        "max, 1899",
        "jyn, 2184",
        "wpu, 2187",
        "ard, 195",
        "zdf, 231",
        "yti, 192",
        "yot, 192",
        "ytv, 192",
    )
    fun `resolves known technical names to TMDB provider ids`(technicalName: String, expectedId: Int) {
        assertEquals(expectedId, JustWatchPackageMap.resolveProviderId(technicalName))
    }

    @Test
    fun `youtubeAliasesResolveToProvider192`() {
        assertEquals(192, JustWatchPackageMap.resolveProviderId("yti"))
        assertEquals(192, JustWatchPackageMap.resolveProviderId("yot"))
        assertEquals(192, JustWatchPackageMap.resolveProviderId("ytv"))
    }

    @Test
    fun `resolveProviderId is case-insensitive`() {
        assertEquals(8, JustWatchPackageMap.resolveProviderId("NFX"))
        assertEquals(8, JustWatchPackageMap.resolveProviderId("Nfx"))
    }

    @Test
    fun `resolveProviderId returns null for unknown technical name`() {
        assertNull(JustWatchPackageMap.resolveProviderId("unknown_service"))
        assertNull(JustWatchPackageMap.resolveProviderId(""))
        assertNull(JustWatchPackageMap.resolveProviderId("abc"))
    }

    @Test
    fun `resolveProviderId emits a WARN diagnostic for unknown technical names`() {
        JustWatchPackageMap.resolveProviderId("new_streaming_service")

        val warnings = DiagnosticLog.snapshot().filter {
            it.level == DiagnosticLog.Level.WARN && it.message.contains("new_streaming_service")
        }
        assertTrue(warnings.isNotEmpty(), "Expected a WARN entry for unmapped technicalName")
    }

    @Test
    fun `resolveProviderId does not log a warning for known technical names`() {
        JustWatchPackageMap.resolveProviderId("nfx")

        val warnings = DiagnosticLog.snapshot().filter {
            it.level == DiagnosticLog.Level.WARN
        }
        assertTrue(warnings.isEmpty(), "Expected no WARN entries when technicalName resolves successfully")
    }

    @Test
    fun `resolveProviderId warning includes the lowercased technical name`() {
        JustWatchPackageMap.resolveProviderId("UNKNOWN_PROVIDER")

        val entry = DiagnosticLog.snapshot().firstOrNull {
            it.level == DiagnosticLog.Level.WARN
        }
        assertTrue(
            entry?.message?.contains("unknown_provider") == true,
            "Warning message should contain the lowercased technicalName"
        )
    }

    @Test
    fun `map does not contain duplicates for known entries`() {
        // hbm and max both map to 1899 — that is intentional, not a bug
        val uniqueEntries = JustWatchPackageMap.technicalNameToProviderId.keys
        assertEquals(uniqueEntries.size, uniqueEntries.toSet().size)
    }
}
