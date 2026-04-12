package com.justb81.watchbuddy.core.locale

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale
import java.util.stream.Stream

@DisplayName("LocaleHelper")
class LocaleHelperTest {

    companion object {
        @JvmStatic
        fun localeProvider(): Stream<Arguments> = Stream.of(
            Arguments.of(Locale.ENGLISH, "English"),
            Arguments.of(Locale.GERMAN, "German"),
            Arguments.of(Locale.FRENCH, "French"),
            Arguments.of(Locale.CHINESE, "Chinese"),
            Arguments.of(Locale.JAPANESE, "Japanese"),
            Arguments.of(Locale("es"), "Spanish"),
            Arguments.of(Locale("ar"), "Arabic"),
            Arguments.of(Locale("ko"), "Korean"),
            Arguments.of(Locale("pt"), "Portuguese"),
            Arguments.of(Locale("it"), "Italian")
        )
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("localeProvider")
    fun `returns correct English language name for locale`(locale: Locale, expectedName: String) {
        assertEquals(expectedName, LocaleHelper.getLlmResponseLanguage(locale))
    }

    @Test
    fun `returns non-empty string for any locale`() {
        val result = LocaleHelper.getLlmResponseLanguage(Locale("xx"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }
}
