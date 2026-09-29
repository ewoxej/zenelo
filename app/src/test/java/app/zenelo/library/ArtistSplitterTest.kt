package app.zenelo.library

import app.zenelo.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistSplitterTest {
    private val default = ArtistSplitter(ArtistSplitter.DEFAULT_SEPARATORS, ArtistSplitter.DEFAULT_EXCEPTIONS)

    @Test
    fun splitsOnDefaultSeparators() {
        assertEquals(listOf("A", "B", "C", "D"), default.split("A; B / C,D"))
    }

    @Test
    fun singleArtistAndBlank() {
        assertEquals(listOf("Radiohead"), default.split("  Radiohead "))
        assertEquals(emptyList<String>(), default.split(null))
        assertEquals(emptyList<String>(), default.split(" ; "))
    }

    @Test
    fun exceptionsStayWhole() {
        assertEquals(listOf("AC/DC", "Ozzy"), default.split("AC/DC; Ozzy"))
        assertEquals(listOf("earth, wind & fire"), default.split("earth, wind & fire"))
        assertEquals(listOf("Tyler, the Creator", "Frank Ocean"), default.split("Tyler, the Creator / Frank Ocean"))
    }

    @Test
    fun noSeparatorsNoSplit() {
        assertEquals(listOf("A; B"), ArtistSplitter(emptyList(), emptyList()).split("A; B"))
    }

    @Test
    fun wordSeparatorsMatchWholeWordsIgnoringCase() {
        val s = ArtistSplitter(listOf("feat.", "x", "&"), emptyList())
        assertEquals(listOf("A", "B"), s.split("A Feat. B"))
        assertEquals(listOf("Defeat. B"), s.split("Defeat. B"))
        assertEquals(listOf("Xzibit", "Dr. Dre"), s.split("Xzibit x Dr. Dre"))
        assertEquals(listOf("A", "B"), s.split("A&B"))
    }

    @Test
    fun longerSeparatorWins() {
        val s = ArtistSplitter(listOf("/", "//"), emptyList())
        assertEquals(listOf("A", "B"), s.split("A // B"))
    }

    @Test
    fun duplicatesIgnoringCase() {
        assertEquals(listOf("A", "B"), default.split("A; a; B"))
    }

    @Test
    fun artistsOfCountsEachName() {
        fun track(path: String, artist: String?, albumArtist: String? = null, album: String? = null) = TrackEntity(
            path, "/", modified = path.length.toLong(), size = 0, title = null, artist = artist, album = album,
            albumArtist = albumArtist, trackNumber = null, durationMs = 0, sampleRate = 0, bitsPerSample = 0,
            bitrateKbps = 0, lossless = false, hasArtwork = false, trackGainDb = null, albumGainDb = null, albumKey = album,
        )
        val rows = Library.artistsOf(
            listOf(
                track("/1", "A; B", album = "x"),
                track("/2", "b", album = "y"),
                track("/3", "ignored", albumArtist = "C", album = "z"),
            ),
            default,
        ).associateBy { it.key }
        assertEquals(setOf("a", "b", "c"), rows.keys)
        assertEquals(2, rows.getValue("b").tracks)
        assertEquals(2, rows.getValue("b").albums)
        assertEquals("b", rows.getValue("b").name)
        assertEquals("/1", rows.getValue("a").coverPath)
    }
}
