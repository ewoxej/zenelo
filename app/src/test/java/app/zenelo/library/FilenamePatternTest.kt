package app.zenelo.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenamePatternTest {

    @Test
    fun artistDashTitle() {
        val g = FilenamePattern.parse("%artist% - %title%", "/storage/emulated/0/Music/Radiohead - Airbag.flac")
        assertEquals(PathGuess("Radiohead", null, "Airbag"), g)
    }

    @Test
    fun toleratesSpacingAroundSeparators() {
        val g = FilenamePattern.parse("%artist% - %title%", "/m/Daft Punk-One More Time.mp3")
        assertEquals(PathGuess("Daft Punk", null, "One More Time"), g)
    }

    @Test
    fun numberedWithFolders() {
        val g = FilenamePattern.parse("%artist%/%album%/%number% %title%", "/sd/Music/Radiohead/OK Computer/01 Airbag.flac")
        assertEquals(PathGuess("Radiohead", "OK Computer", "Airbag", 1), g)
    }

    @Test
    fun trackIsAliasOfTitle() {
        val g = FilenamePattern.parse("%Artist% - %Track%", "/m/Aphex Twin - Avril 14th.mp3")
        assertEquals(PathGuess("Aphex Twin", null, "Avril 14th"), g)
    }

    @Test
    fun numberMustBeDigits() {
        assertNull(FilenamePattern.parse("%number% - %artist% - %title%", "/m/Intro - Artist - Song.mp3"))
        assertEquals(
            PathGuess("Artist", null, "Song", 3),
            FilenamePattern.parse("%number% - %artist% - %title%", "/m/03 - Artist - Song.mp3"),
        )
    }

    @Test
    fun noMatchWithoutSeparator() {
        assertNull(FilenamePattern.parse("%artist% - %title%", "/m/JustATitle.mp3"))
    }

    @Test
    fun firstMatchingPatternWins() {
        val g = FilenamePattern.guess(listOf("%number%. %artist% - %title%", "%artist% - %title%"), "/m/Brian Eno - An Ending.flac")
        assertEquals(PathGuess("Brian Eno", null, "An Ending"), g)
    }
}

class FilenamePatternOptionalTest {

    @Test
    fun optionalNumberPresent() {
        val g = FilenamePattern.parse("[%number%. ]%artist% - %title%", "/m/03. Brian Eno - An Ending.flac")
        assertEquals(PathGuess("Brian Eno", null, "An Ending", 3), g)
    }

    @Test
    fun optionalNumberAbsent() {
        val g = FilenamePattern.parse("[%number%. ]%artist% - %title%", "/m/Brian Eno - An Ending.flac")
        assertEquals(PathGuess("Brian Eno", null, "An Ending", null), g)
    }

    @Test
    fun nestedOptional() {
        val p = "[%number%[.] - ]%artist% - %title%"
        assertEquals(PathGuess("A", null, "B", 7), FilenamePattern.parse(p, "/m/07. - A - B.mp3"))
        assertEquals(PathGuess("A", null, "B", 7), FilenamePattern.parse(p, "/m/07 - A - B.mp3"))
        assertEquals(PathGuess("A", null, "B", null), FilenamePattern.parse(p, "/m/A - B.mp3"))
    }

    @Test
    fun unbalancedBracketsNeverMatch() {
        assertNull(FilenamePattern.parse("[%number% %artist% - %title%", "/m/1 A - B.mp3"))
    }
}
