package app.zenelo.library

import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SortField
import app.zenelo.data.settings.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySortTest {

    private fun track(path: String, title: String?, artist: String? = null, album: String? = null, modified: Long = 0, duration: Long = 0) =
        TrackEntity(path, "/m", modified, 0, title, artist, album, null, null, duration, 44100, 16, 0, true, false, null, null, null)

    private fun album(name: String, tracks: Int, added: Long, artist: String? = null) =
        AlbumRow(name.lowercase(), name, artist, tracks, 0, added, "/m/$name")

    @Test
    fun namesIgnoreCaseAndFallBackToFileName() {
        val sorted = LibrarySort.tracks(
            listOf(track("/m/b.flac", "banana"), track("/m/Apple.flac", null), track("/m/c.flac", "Cherry")),
            SortOrder(SortField.NAME),
        )
        assertEquals(listOf("Apple", "banana", "Cherry"), sorted.map(LibrarySort::trackName))
    }

    @Test
    fun unknownArtistsGoLastInBothDirections() {
        val tracks = listOf(track("/m/1", "x", null), track("/m/2", "y", "Air"), track("/m/3", "z", "Björk"))
        assertEquals(listOf("Air", "Björk", null), LibrarySort.tracks(tracks, SortOrder(SortField.ARTIST)).map { it.artist })
        assertEquals(listOf("Björk", "Air", null), LibrarySort.tracks(tracks, SortOrder(SortField.ARTIST, descending = true)).map { it.artist })
    }

    @Test
    fun albumsByDateAddedNewestFirst() {
        val albums = listOf(album("Old", 3, 100), album("New", 5, 300), album("Mid", 1, 200))
        assertEquals(listOf("New", "Mid", "Old"), LibrarySort.albums(albums, SortOrder(SortField.DATE_ADDED, descending = true)).map { it.album })
        assertEquals(listOf("Mid", "Old", "New"), LibrarySort.albums(albums, SortOrder(SortField.TRACKS)).map { it.album })
    }

    @Test
    fun headersFollowTheSort() {
        val t = track("/m/1", "échos", "123 Band", "Zoo")
        assertEquals("E", LibrarySort.trackHeader(t, SortOrder(SortField.NAME)))
        assertEquals("#", LibrarySort.trackHeader(t, SortOrder(SortField.ARTIST)))
        assertEquals("Z", LibrarySort.trackHeader(t, SortOrder(SortField.ALBUM)))
        assertNull(LibrarySort.trackHeader(t, SortOrder(SortField.DURATION)))
    }

    @Test
    fun playCountsAfterThirtySecondsOrHalfOfShortTracks() {
        assertEquals(30_000L, Library.playThresholdMs(240_000))
        assertEquals(20_000L, Library.playThresholdMs(40_000))
        assertEquals(30_000L, Library.playThresholdMs(0))
    }

    @Test
    fun letterHeadersIgnoreAccentsLikeTheSort() {
        // The collator sorts "Élan" among the E's: its own "É" header would split them.
        assertEquals("E", LibrarySort.letter("Élan"))
        assertEquals("Е", LibrarySort.letter("ёлка"))
        assertEquals("A", LibrarySort.letter(" ångström"))
        assertEquals("#", LibrarySort.letter("!L!VE!"))
        assertEquals("#", LibrarySort.letter(null))
    }
}
