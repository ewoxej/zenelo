package app.zenelo.library

import app.zenelo.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GenresTest {
    @Test
    fun splitsTagsIntoGenres() {
        assertEquals(listOf("Rock", "Indie"), Genres.split("Rock; Indie"))
        assertEquals(listOf("Rock", "Pop"), Genres.split("Rock/Pop"))
        assertEquals(listOf("Jazz", "Soul"), Genres.split("Jazz, Soul"))
        // Same genre twice in other case: once.
        assertEquals(listOf("Rock"), Genres.split("Rock; rock"))
        assertEquals(emptyList<String>(), Genres.split("  "))
        assertEquals(emptyList<String>(), Genres.split(null))
    }

    @Test
    fun id3NumbersBecomeNames() {
        assertEquals(listOf("Rock"), Genres.split("(17)"))
        assertEquals(listOf("Rock"), Genres.split("17"))
        assertEquals(listOf("Hard Rock"), Genres.split("(79)Hard Rock"))
    }

    private fun track(path: String, genre: String?, artist: String) = TrackEntity(
        path, "/m", 0, 1, "t", artist, null, null, null, 60_000, 0, 0, 0, false, false, null, null, null, genre = genre,
    )

    @Test
    fun genresCountEachTagOfATrack() {
        val rows = Library.genresOf(
            listOf(track("/m/1", "Rock; Indie", "A"), track("/m/2", "rock", "B"), track("/m/3", null, "C"), track("/m/4", "Indie", "A")),
        ).associateBy { it.key }
        assertEquals(setOf("rock", "indie"), rows.keys)
        assertEquals(2, rows.getValue("rock").tracks)
        assertEquals(2, rows.getValue("rock").artists)
        assertEquals(2, rows.getValue("indie").tracks)
        assertEquals(1, rows.getValue("indie").artists)
        assertEquals(120_000L, rows.getValue("rock").durationMs)
    }
}
