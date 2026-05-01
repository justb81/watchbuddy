package com.justb81.watchbuddy.tv.data

import android.content.Context
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LastUsedProviderRepository — encode/decode")
class LastUsedProviderRepositoryTest {

    // encode/decode do not access DataStore; a relaxed mock Context is sufficient.
    private val repo = LastUsedProviderRepository(mockk<Context>(relaxed = true))

    @BeforeEach
    fun clearLog() {
        DiagnosticLog.clear()
    }

    @AfterEach
    fun cleanLog() {
        DiagnosticLog.clear()
    }

    @Nested
    @DisplayName("encode")
    inner class EncodeTests {

        @Test
        fun `empty map produces v1 prefix`() {
            assertTrue(repo.encode(emptyMap()).startsWith("v1:"))
        }

        @Test
        fun `non-empty map round-trips through decode`() {
            val original = mapOf(100 to 8, 200 to 350, 999 to 1)
            val encoded = repo.encode(original)
            assertEquals(original, repo.decode(encoded))
        }

        @Test
        fun `single-entry map round-trips`() {
            val original = mapOf(42 to 7)
            assertEquals(original, repo.decode(repo.encode(original)))
        }

        @Test
        fun `overwrite of existing key survives round-trip`() {
            val map = mapOf(1 to 10, 2 to 20)
            val updated = map.toMutableMap().also { it[1] = 99 }
            assertEquals(99, repo.decode(repo.encode(updated))[1])
        }
    }

    @Nested
    @DisplayName("decode — blank / empty input")
    inner class BlankInputTests {

        @Test
        fun `blank string returns empty map`() {
            assertTrue(repo.decode("").isEmpty())
        }

        @Test
        fun `whitespace-only string returns empty map`() {
            assertTrue(repo.decode("   ").isEmpty())
        }
    }

    @Nested
    @DisplayName("decode — v1 JSON format")
    inner class V1DecodeTests {

        @Test
        fun `valid v1 JSON decodes correctly`() {
            val encoded = repo.encode(mapOf(1 to 2, 3 to 4))
            val result = repo.decode(encoded)
            assertEquals(mapOf(1 to 2, 3 to 4), result)
        }

        @Test
        fun `v1 JSON with unknown extra field is tolerated`() {
            val raw = """v1:{"providers":{"100":8},"future_field":true}"""
            val result = repo.decode(raw)
            assertEquals(mapOf(100 to 8), result)
        }

        @Test
        fun `corrupt v1 JSON returns empty map`() {
            val result = repo.decode("v1:{not valid json!}")
            assertTrue(result.isEmpty())
        }

        @Test
        fun `corrupt v1 JSON logs a WARN entry`() {
            repo.decode("v1:{not valid json!}")
            val warns = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.WARN }
            assertTrue(warns.isNotEmpty(), "Expected at least one WARN log entry")
        }

        @Test
        fun `v1 JSON with non-integer string key is silently dropped`() {
            val raw = """v1:{"providers":{"abc":8,"100":9}}"""
            val result = repo.decode(raw)
            assertEquals(mapOf(100 to 9), result)
        }
    }

    @Nested
    @DisplayName("decode — legacy pipe-delimited format (migration)")
    inner class LegacyMigrationTests {

        @Test
        fun `legacy format decodes all valid entries`() {
            val result = repo.decode("100|8;200|350;999|1")
            assertEquals(mapOf(100 to 8, 200 to 350, 999 to 1), result)
        }

        @Test
        fun `legacy format logs a WARN migration entry`() {
            repo.decode("100|8;200|350")
            val warns = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.WARN }
            assertTrue(warns.any { it.message.contains("Migrated legacy") })
        }

        @Test
        fun `malformed legacy entry without pipe is skipped and logged`() {
            val result = repo.decode("100|8;BADENTRY;200|350")
            assertEquals(mapOf(100 to 8, 200 to 350), result)
            val warns = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.WARN }
            assertTrue(warns.any { it.message.contains("malformed") })
        }

        @Test
        fun `legacy entry with non-integer value is silently dropped`() {
            val result = repo.decode("100|abc;200|350")
            assertEquals(mapOf(200 to 350), result)
        }

        @Test
        fun `legacy entry with non-integer key is silently dropped`() {
            val result = repo.decode("abc|8;200|350")
            assertEquals(mapOf(200 to 350), result)
        }

        @Test
        fun `empty string between delimiters does not produce spurious WARN`() {
            // A trailing semicolon leaves a blank entry; it should be dropped silently.
            repo.decode("100|8;")
            val warns = DiagnosticLog.snapshot()
                .filter { it.level == DiagnosticLog.Level.WARN && it.message.contains("malformed") }
            assertTrue(warns.isEmpty(), "Blank trailing entries must not generate malformed warnings")
        }

        @Test
        fun `re-encoding migrated legacy data produces v1 format`() {
            val migrated = repo.decode("100|8;200|350")
            val reencoded = repo.encode(migrated)
            assertTrue(reencoded.startsWith("v1:"))
            // Verify the re-encoded value is stable through another decode cycle
            assertEquals(migrated, repo.decode(reencoded))
        }
    }
}
