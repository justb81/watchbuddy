package com.justb81.watchbuddy.phone.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InitialsAvatar#initialsFor")
class InitialsAvatarTest {

    @Test
    fun `picks up to two leading letters`() {
        assertEquals("BR", initialsFor("Bastian Rang"))
    }

    @Test
    fun `uppercases single-word names`() {
        assertEquals("C", initialsFor("couchguy"))
    }

    @Test
    fun `handles extra whitespace`() {
        assertEquals("AB", initialsFor("  alice   bob  "))
    }

    @Test
    fun `falls back to question mark on empty input`() {
        assertEquals("?", initialsFor(""))
        assertEquals("?", initialsFor("   "))
    }
}
