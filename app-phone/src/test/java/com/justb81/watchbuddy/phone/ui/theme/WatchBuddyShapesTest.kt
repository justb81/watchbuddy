package com.justb81.watchbuddy.phone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WatchBuddyShapes")
class WatchBuddyShapesTest {

    private val shapes = WatchBuddyShapes(
        card = RoundedCornerShape(16.dp),
        banner = RoundedCornerShape(12.dp),
        pill = RoundedCornerShape(12.dp),
        thumbnail = RoundedCornerShape(8.dp),
    )

    @Test
    fun `data class equality holds for identical instances`() {
        val copy = shapes.copy()
        assertEquals(shapes, copy)
    }

    @Test
    fun `copy with different card shape produces distinct instance`() {
        val modified = shapes.copy(card = RoundedCornerShape(8.dp))
        assertNotEquals(shapes, modified)
    }

    @Test
    fun `banner and pill use the same shape`() {
        assertEquals(shapes.banner, shapes.pill)
    }

    @Test
    fun `card shape differs from banner shape`() {
        assertNotEquals(shapes.card, shapes.banner)
    }

    @Test
    fun `thumbnail shape differs from banner shape`() {
        assertNotEquals(shapes.thumbnail, shapes.banner)
    }

    @Test
    fun `card shape is RoundedCornerShape 16dp`() {
        assertEquals(RoundedCornerShape(16.dp), shapes.card)
    }

    @Test
    fun `banner shape is RoundedCornerShape 12dp`() {
        assertEquals(RoundedCornerShape(12.dp), shapes.banner)
    }

    @Test
    fun `thumbnail shape is RoundedCornerShape 8dp`() {
        assertEquals(RoundedCornerShape(8.dp), shapes.thumbnail)
    }
}
