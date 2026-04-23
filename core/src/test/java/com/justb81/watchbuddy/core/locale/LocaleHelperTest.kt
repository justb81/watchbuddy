package com.justb81.watchbuddy.core.locale

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale
import java.util.stream.Stream

@DisplayName("LocaleHelper")
class LocaleHelperTest {

    companion object {
        // `Locale.of(...)` is JDK 19+; CI runs tests on JDK 17, so the direct
        // call throws NoSuchMethodError at runtime. `Locale.Builder()` works on
        // JDK 17 and isn't flagged as deprecated by newer compilers.
        private fun locale(language: String, country: String = ""): Locale =
            Locale.Builder().setLanguage(language).setRegion(country).build()

        @JvmStatic
        fun localeProvider(): Stream<Arguments> = Stream.of(
            Arguments.of(Locale.ENGLISH, "English"),
            Arguments.of(Locale.GERMAN, "German"),
            Arguments.of(Locale.FRENCH, "French"),
            Arguments.of(Locale.CHINESE, "Chinese"),
            Arguments.of(Locale.JAPANESE, "Japanese"),
            Arguments.of(locale("es"), "Spanish"),
            Arguments.of(locale("ar"), "Arabic"),
            Arguments.of(locale("ko"), "Korean"),
            Arguments.of(locale("pt"), "Portuguese"),
            Arguments.of(locale("it"), "Italian")
        )

        @JvmStatic
        fun tmdbLanguageProvider(): Stream<Arguments> = Stream.of(
            Arguments.of(Locale.US, "en-US"),
            Arguments.of(Locale.UK, "en-GB"),
            Arguments.of(Locale.GERMANY, "de-DE"),
            Arguments.of(Locale.FRANCE, "fr-FR"),
            Arguments.of(locale("es", "ES"), "es-ES"),
            Arguments.of(locale("ja", "JP"), "ja-JP"),
            Arguments.of(Locale.ENGLISH, "en"),
            Arguments.of(Locale.GERMAN, "de"),
            Arguments.of(locale("fr"), "fr")
        )
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("localeProvider")
    fun `returns correct English language name for locale`(locale: Locale, expectedName: String) {
        assertEquals(expectedName, LocaleHelper.getLlmResponseLanguage(locale))
    }

    @Test
    fun `returns non-empty string for any locale`() {
        val result = LocaleHelper.getLlmResponseLanguage(locale("xx"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Nested
    @DisplayName("getTmdbLanguage")
    inner class GetTmdbLanguageTest {

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("com.justb81.watchbuddy.core.locale.LocaleHelperTest#tmdbLanguageProvider")
        fun `returns correct BCP 47 tag for locale`(locale: Locale, expected: String) {
            assertEquals(expected, LocaleHelper.getTmdbLanguage(locale))
        }

        @Test
        fun `returns en-US for empty locale`() {
            val emptyLocale = locale("", "")
            assertEquals("en-US", LocaleHelper.getTmdbLanguage(emptyLocale))
        }

        @Test
        fun `result is never blank`() {
            val result = LocaleHelper.getTmdbLanguage(Locale.US)
            assertTrue(result.isNotBlank())
        }

        @Test
        fun `language and country are separated by hyphen`() {
            val result = LocaleHelper.getTmdbLanguage(Locale.GERMANY)
            assertTrue(result.contains("-"))
            val parts = result.split("-")
            assertEquals(2, parts.size)
        }

        @Test
        fun `language-only locale returns language without hyphen`() {
            val result = LocaleHelper.getTmdbLanguage(Locale.ENGLISH)
            assertFalse(result.contains("-"))
        }
    }
}
