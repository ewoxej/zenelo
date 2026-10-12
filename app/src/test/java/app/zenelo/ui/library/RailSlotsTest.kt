package app.zenelo.ui.library

import app.zenelo.ui.library.RailSlots.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailSlotsTest {
    @Test
    fun everyLetterWhenThereIsRoom() {
        assertEquals((0 until 5).map { Slot(it, it) }, RailSlots.layout(5, 26))
    }

    @Test
    fun lettersAndDotsWhenNot() {
        // 60 letters (Latin + Cyrillic + #) in 25 places.
        val slots = RailSlots.layout(60, 25)
        assertTrue(slots.size <= 25)
        assertEquals(Slot(0, 0), slots.first())
        assertEquals(Slot(59, 59), slots.last())
        // Letters and dots alternate, and together they cover every letter once, in order.
        assertEquals((0 until 60).toList(), slots.flatMap { it.first..it.last })
        slots.zipWithNext().forEach { (a, b) -> assertTrue(!(a.isDot && b.isDot)) }
    }

    @Test
    fun aDotPicksItsLettersByWhereTheFingerIs() {
        val dot = Slot(3, 6)
        assertEquals(3, RailSlots.letterAt(dot, 0f))
        assertEquals(4, RailSlots.letterAt(dot, 0.3f))
        assertEquals(6, RailSlots.letterAt(dot, 0.99f))
        assertEquals(6, RailSlots.letterAt(dot, 1f))
        assertEquals(2, RailSlots.letterAt(Slot(2, 2), 0.7f))
    }
}
