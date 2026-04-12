package com.justb81.watchbuddy.tv.scrobbler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {

    // ── normalize() ──────────────────────────────────────────────────────────

    @Test
    fun `normalize lowercases input`() {
        assertEquals("breaking bad", FuzzyMatcher.normalize("Breaking Bad"))
    }

    @Test
    fun `normalize removes special characters`() {
        assertEquals("marvels agentsofshield", FuzzyMatcher.normalize("Marvel's Agents.of" + "S.H.I.E.L.D."))
        // Note: dots and apostrophes removed, spaces collapsed
    }

    @Test
    fun `normalize strips leading the`() {
        assertEquals("boys", FuzzyMatcher.normalize("The Boys"))
        assertEquals("mandalorian", FuzzyMatcher.normalize("the mandalorian"))
    }

    @Test
    fun `normalize does not strip the in the middle`() {
        assertEquals("over the garden wall", FuzzyMatcher.normalize("Over the Garden Wall"))
    }

    @Test
    fun `normalize collapses whitespace`() {
        assertEquals("stranger things", FuzzyMatcher.normalize("  Stranger   Things  "))
    }

    @Test
    fun `normalize handles empty string`() {
        assertEquals("", FuzzyMatcher.normalize(""))
    }

    @Test
    fun `normalize handles only special characters`() {
        assertEquals("", FuzzyMatcher.normalize("!@#\$%"))
    }

    // ── levenshteinDistance() ─────────────────────────────────────────────────

    @Test
    fun `levenshtein identical strings`() {
        assertEquals(0, FuzzyMatcher.levenshteinDistance("kitten", "kitten"))
    }

    @Test
    fun `levenshtein empty strings`() {
        assertEquals(0, FuzzyMatcher.levenshteinDistance("", ""))
        assertEquals(5, FuzzyMatcher.levenshteinDistance("hello", ""))
        assertEquals(5, FuzzyMatcher.levenshteinDistance("", "hello"))
    }

    @Test
    fun `levenshtein classic example`() {
        assertEquals(3, FuzzyMatcher.levenshteinDistance("kitten", "sitting"))
    }

    @Test
    fun `levenshtein single character difference`() {
        assertEquals(1, FuzzyMatcher.levenshteinDistance("cat", "bat"))
    }

    @Test
    fun `levenshtein completely different strings`() {
        assertEquals(3, FuzzyMatcher.levenshteinDistance("abc", "xyz"))
    }

    // ── fuzzyScore() ─────────────────────────────────────────────────────────

    @Test
    fun `fuzzyScore exact match returns 1_0`() {
        assertEquals(1.0f, FuzzyMatcher.fuzzyScore("Breaking Bad", "Breaking Bad"), 0.001f)
    }

    @Test
    fun `fuzzyScore exact match ignoring case returns 1_0`() {
        assertEquals(1.0f, FuzzyMatcher.fuzzyScore("breaking bad", "BREAKING BAD"), 0.001f)
    }

    @Test
    fun `fuzzyScore exact match ignoring the returns 1_0`() {
        assertEquals(1.0f, FuzzyMatcher.fuzzyScore("The Boys", "boys"), 0.001f)
    }

    @Test
    fun `fuzzyScore prefix match returns 0_95`() {
        assertEquals(0.95f, FuzzyMatcher.fuzzyScore("Stranger Things", "Stranger Things Season 3"), 0.001f)
    }

    @Test
    fun `fuzzyScore similar titles returns high score`() {
        val score = FuzzyMatcher.fuzzyScore("Breaking Bad", "Braking Bad")
        assertTrue("Expected score > 0.8 but was $score", score > 0.8f)
    }

    @Test
    fun `fuzzyScore completely different titles returns low score`() {
        val score = FuzzyMatcher.fuzzyScore("Breaking Bad", "Game of Thrones")
        assertTrue("Expected score < 0.5 but was $score", score < 0.5f)
    }

    @Test
    fun `fuzzyScore empty string returns 0`() {
        assertEquals(0.0f, FuzzyMatcher.fuzzyScore("", "anything"), 0.001f)
        assertEquals(0.0f, FuzzyMatcher.fuzzyScore("anything", ""), 0.001f)
    }

    @Test
    fun `fuzzyScore special characters are ignored`() {
        assertEquals(1.0f, FuzzyMatcher.fuzzyScore("Marvel's Agents of S.H.I.E.L.D.", "marvels agents of shield"), 0.001f)
    }

    @Test
    fun `fuzzyScore handles minor typos gracefully`() {
        val score = FuzzyMatcher.fuzzyScore("Stranger Things", "Stranjer Things")
        assertTrue("Expected score > 0.85 but was $score", score > 0.85f)
    }
}
