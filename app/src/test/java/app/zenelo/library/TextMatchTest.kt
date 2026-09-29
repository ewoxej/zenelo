package app.zenelo.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {
    @Test
    fun sameNames() {
        assertTrue(Text.matches("Альбина Сексова", "альбина сексова"))
        assertTrue(Text.matches("The Dark Side of the Moon (2011 Remaster)", "The Dark Side of the Moon"))
        assertTrue(Text.matches("The Beatles", "Beatles"))
        assertTrue(Text.matches("AC/DC", "AC DC"))
    }

    @Test
    fun editionsAndCredits() {
        assertTrue(Text.matches("Discovery Deluxe Edition", "Discovery"))
        assertTrue(Text.matches("Daft Punk feat. Pharrell", "Daft Punk"))
    }

    @Test
    fun aWordInsideAnotherNameIsNotAMatch() {
        // Folder names read as artist / album used to pick unrelated covers.
        assertFalse(Text.matches("VedicDhvani Music", "Music"))
        assertFalse(Text.matches("Shree Hanuman Chalisa Lyrics", "Lyrics"))
        assertFalse(Text.matches("Greatest Hits of Queen", "Queen"))
        assertFalse(Text.matches("", "Queen"))
    }
}
