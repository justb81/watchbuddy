package com.justb81.watchbuddy.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("WatchBuddyJson")
class SharedJsonTest {

    @Serializable
    private data class Sample(val name: String, val value: Int = 0)

    @Nested
    @DisplayName("ignoreUnknownKeys")
    inner class IgnoreUnknownKeysTest {
        @Test
        fun `ignores extra fields in JSON`() {
            val json = """{"name":"test","unknown":"extra","value":1}"""
            val result = WatchBuddyJson.decodeFromString<Sample>(json)
            assertEquals(Sample("test", 1), result)
        }
    }

    @Nested
    @DisplayName("isLenient")
    inner class LenientTest {
        @Test
        fun `accepts unquoted string values`() {
            val json = """{"name":unquoted,"value":42}"""
            val result = WatchBuddyJson.decodeFromString<Sample>(json)
            assertEquals(Sample("unquoted", 42), result)
        }
    }

    @Nested
    @DisplayName("coerceInputValues")
    inner class CoerceInputValuesTest {
        @Test
        fun `coerces null to default for non-nullable field`() {
            val json = """{"name":"test","value":null}"""
            val result = WatchBuddyJson.decodeFromString<Sample>(json)
            assertEquals(Sample("test", 0), result)
        }
    }

    @Nested
    @DisplayName("singleton identity")
    inner class SingletonTest {
        @Test
        fun `WatchBuddyJson is the same instance on repeated access`() {
            assertSame(WatchBuddyJson, WatchBuddyJson)
        }

        @Test
        fun `WatchBuddyJson is not the default Json instance`() {
            assertNotSame(Json.Default, WatchBuddyJson)
        }
    }

    @Nested
    @DisplayName("round-trip encoding")
    inner class RoundTripTest {
        @Test
        fun `encodes and decodes correctly`() {
            val original = Sample("hello", 99)
            val encoded = WatchBuddyJson.encodeToString(Sample.serializer(), original)
            val decoded = WatchBuddyJson.decodeFromString<Sample>(encoded)
            assertEquals(original, decoded)
        }
    }
}

@DisplayName("WatchBuddyStrictJson")
class StrictJsonTest {

    @Serializable
    private data class Sample(val name: String, val value: Int = 0)

    @Nested
    @DisplayName("unknown keys")
    inner class UnknownKeysTest {
        @Test
        fun `throws on extra fields in JSON`() {
            val json = """{"name":"test","unknown":"extra","value":1}"""
            assertThrows<SerializationException> {
                WatchBuddyStrictJson.decodeFromString<Sample>(json)
            }
        }
    }

    @Nested
    @DisplayName("strict parsing")
    inner class StrictParsingTest {
        @Test
        fun `rejects unquoted string values`() {
            val json = """{"name":unquoted,"value":42}"""
            assertThrows<SerializationException> {
                WatchBuddyStrictJson.decodeFromString<Sample>(json)
            }
        }

        @Test
        fun `rejects null for non-nullable field`() {
            val json = """{"name":"test","value":null}"""
            assertThrows<SerializationException> {
                WatchBuddyStrictJson.decodeFromString<Sample>(json)
            }
        }
    }

    @Nested
    @DisplayName("valid JSON")
    inner class ValidJsonTest {
        @Test
        fun `decodes well-formed JSON correctly`() {
            val json = """{"name":"hello","value":42}"""
            val result = WatchBuddyStrictJson.decodeFromString<Sample>(json)
            assertEquals(Sample("hello", 42), result)
        }

        @Test
        fun `missing optional field uses Kotlin default`() {
            val json = """{"name":"hello"}"""
            val result = WatchBuddyStrictJson.decodeFromString<Sample>(json)
            assertEquals(Sample("hello", 0), result)
        }
    }

    @Nested
    @DisplayName("distinct from WatchBuddyJson")
    inner class DistinctInstanceTest {
        @Test
        fun `is not the same instance as WatchBuddyJson`() {
            assertNotSame(WatchBuddyJson, WatchBuddyStrictJson)
        }
    }
}
