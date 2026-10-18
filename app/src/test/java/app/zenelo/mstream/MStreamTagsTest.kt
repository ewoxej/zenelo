package app.zenelo.mstream

import app.zenelo.library.TagReader.EditableTags
import org.junit.Assert.assertEquals
import org.junit.Test

class MStreamTagsTest {
    private val file = ServerTags(title = "Song", artist = "A", album = "Al", track = "3/12", year = "1999", genre = "Rock")
    private fun edit(t: ServerTags) = EditableTags(t.title, t.artist, t.album, t.albumArtist, t.track.substringBefore('/'), t.year, t.genre)

    @Test
    fun nothingChanged() = assertEquals(emptyMap<String, String>(), MStreamTags.changes(file, edit(file)))

    @Test
    fun onlyWhatChanged_trimmed_emptyRemoves() {
        val changed = edit(file).copy(title = " New ", genre = "", albumArtist = "Band")
        assertEquals(mapOf("title" to "New", "albumArtist" to "Band", "genre" to ""), MStreamTags.changes(file, changed))
    }

    @Test
    fun aTrackNumberKeepsItsTotal() {
        assertEquals(mapOf("track" to "4/12"), MStreamTags.changes(file, edit(file).copy(trackNumber = "4")))
        // Given whole, or cleared: as written.
        assertEquals(mapOf("track" to "4/10"), MStreamTags.changes(file, edit(file).copy(trackNumber = "4/10")))
        assertEquals(mapOf("track" to ""), MStreamTags.changes(file, edit(file).copy(trackNumber = "")))
        // No total in the file: just the number.
        assertEquals(mapOf("track" to "5"), MStreamTags.changes(file.copy(track = "3"), edit(file).copy(trackNumber = "5")))
    }
}
